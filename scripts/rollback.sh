#!/usr/bin/env bash
# ============================================================
# SPU 多级缓存服务 — Kubernetes 自动回滚脚本
# ============================================================
# 用法:
#   ./rollback.sh                        # 回滚到上一个版本
#   ./rollback.sh --revision 3           # 回滚到指定版本号
#   ./rollback.sh --canary-only          # 仅清理金丝雀部署
#   ./rollback.sh --dry-run              # 干运行，只显示将要执行的操作
#   ./rollback.sh --namespace my-ns      # 指定命名空间
# ============================================================

set -euo pipefail

# ----------------------------------------------------------
# 默认配置
# ----------------------------------------------------------
NAMESPACE="${K8S_NAMESPACE:-spu-cache}"
DEPLOYMENT_NAME="${DEPLOYMENT_NAME:-spu-cache-service}"
CANARY_DEPLOYMENT="${DEPLOYMENT_NAME}-canary"
TIMEOUT="300s"
REVISION=""
CANARY_ONLY=false
DRY_RUN=false

# ----------------------------------------------------------
# 参数解析
# ----------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case $1 in
    --revision)
      REVISION="$2"
      shift 2
      ;;
    --namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    --canary-only)
      CANARY_ONLY=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --timeout)
      TIMEOUT="$2"
      shift 2
      ;;
    --help|-h)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --revision N        Rollback to specific revision number"
      echo "  --namespace NS      Kubernetes namespace (default: spu-cache)"
      echo "  --canary-only       Only remove canary deployment"
      echo "  --dry-run           Show what would be done without executing"
      echo "  --timeout DURATION  Rollout timeout (default: 300s)"
      echo "  --help, -h          Show this help message"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# ----------------------------------------------------------
# 工具函数
# ----------------------------------------------------------
log_info()  { echo "[INFO]  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_warn()  { echo "[WARN]  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_error() { echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

run_cmd() {
  if [ "$DRY_RUN" = true ]; then
    log_info "[DRY-RUN] $*"
  else
    log_info "Executing: $*"
    eval "$@"
  fi
}

check_prerequisites() {
  if ! command -v kubectl &>/dev/null; then
    log_error "kubectl not found in PATH"
    exit 1
  fi

  if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
    log_error "Namespace '$NAMESPACE' does not exist"
    exit 1
  fi
}

# ----------------------------------------------------------
# 记录当前状态（用于日志审计）
# ----------------------------------------------------------
record_current_state() {
  log_info "=== Current Deployment State ==="
  
  CURRENT_IMAGE=$(kubectl get deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "N/A")
  CURRENT_REPLICAS=$(kubectl get deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" \
    -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "N/A")
  READY_PODS=$(kubectl get pods -n "$NAMESPACE" \
    -l "app=$DEPLOYMENT_NAME,version!=canary" \
    --field-selector=status.phase=Running \
    -o name 2>/dev/null | wc -l || echo "0")

  log_info "  Image:    $CURRENT_IMAGE"
  log_info "  Replicas: $CURRENT_REPLICAS"
  log_info "  Ready:    $READY_PODS"

  # 显示最近 5 个版本历史
  log_info "=== Rollout History (recent) ==="
  kubectl rollout history deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" 2>/dev/null | tail -6 || true
  echo ""
}

# ----------------------------------------------------------
# 清理金丝雀部署
# ----------------------------------------------------------
cleanup_canary() {
  if kubectl get deployment/"$CANARY_DEPLOYMENT" -n "$NAMESPACE" &>/dev/null; then
    log_info "Removing canary deployment: $CANARY_DEPLOYMENT"
    run_cmd "kubectl delete deployment/$CANARY_DEPLOYMENT -n $NAMESPACE --grace-period=30"
    log_info "Canary deployment removed"
  else
    log_info "No canary deployment found — skipping"
  fi
}

# ----------------------------------------------------------
# 执行回滚
# ----------------------------------------------------------
perform_rollback() {
  if ! kubectl get deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" &>/dev/null; then
    log_error "Deployment '$DEPLOYMENT_NAME' not found in namespace '$NAMESPACE'"
    exit 1
  fi

  if [ -n "$REVISION" ]; then
    log_info "Rolling back to revision: $REVISION"
    run_cmd "kubectl rollout undo deployment/$DEPLOYMENT_NAME -n $NAMESPACE --to-revision=$REVISION"
  else
    log_info "Rolling back to previous revision"
    run_cmd "kubectl rollout undo deployment/$DEPLOYMENT_NAME -n $NAMESPACE"
  fi

  if [ "$DRY_RUN" = false ]; then
    log_info "Waiting for rollout to complete (timeout: $TIMEOUT)..."
    kubectl rollout status deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" --timeout="$TIMEOUT"
  fi
}

# ----------------------------------------------------------
# 回滚后验证
# ----------------------------------------------------------
verify_rollback() {
  if [ "$DRY_RUN" = true ]; then
    log_info "[DRY-RUN] Would verify rollback health"
    return
  fi

  log_info "=== Post-Rollback Verification ==="

  NEW_IMAGE=$(kubectl get deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "N/A")
  log_info "  Rolled back to image: $NEW_IMAGE"

  # 等待 Pod 就绪
  DESIRED=$(kubectl get deployment/"$DEPLOYMENT_NAME" -n "$NAMESPACE" \
    -o jsonpath='{.spec.replicas}')
  RETRIES=30
  for ((i=1; i<=RETRIES; i++)); do
    READY=$(kubectl get pods -n "$NAMESPACE" \
      -l "app=$DEPLOYMENT_NAME,version!=canary" \
      --field-selector=status.phase=Running \
      -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' \
      | grep -c "True" 2>/dev/null || echo "0")
    if [ "$READY" -ge "$DESIRED" ]; then
      log_info "  All pods ready: $READY/$DESIRED"
      break
    fi
    log_info "  Waiting for pods... ($READY/$DESIRED ready, attempt $i/$RETRIES)"
    sleep 5
  done

  if [ "$READY" -lt "$DESIRED" ]; then
    log_warn "Not all pods are ready after rollback: $READY/$DESIRED"
    log_warn "Manual intervention may be required"
    return 1
  fi

  # 健康检查（通过 port-forward）
  log_info "Running health check via port-forward..."
  FIRST_POD=$(kubectl get pod -n "$NAMESPACE" \
    -l "app=$DEPLOYMENT_NAME,version!=canary" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

  if [ -n "$FIRST_POD" ]; then
    kubectl port-forward -n "$NAMESPACE" pod/"$FIRST_POD" 18888:8080 &
    PF_PID=$!
    sleep 3

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:18888/actuator/health 2>/dev/null || echo "000")
    kill $PF_PID 2>/dev/null || true
    wait $PF_PID 2>/dev/null || true

    if [ "$HTTP_CODE" = "200" ]; then
      log_info "  Health check PASSED (HTTP $HTTP_CODE)"
    else
      log_warn "  Health check returned HTTP $HTTP_CODE"
    fi
  fi

  log_info "=== Rollback Complete ==="
}

# ----------------------------------------------------------
# 主流程
# ----------------------------------------------------------
main() {
  log_info "========================================="
  log_info "SPU Cache Service — Rollback"
  log_info "  Namespace:  $NAMESPACE"
  log_info "  Deployment: $DEPLOYMENT_NAME"
  log_info "  Canary:     $CANARY_DEPLOYMENT"
  log_info "  Revision:   ${REVISION:-<previous>}"
  log_info "  Dry-run:    $DRY_RUN"
  log_info "========================================="

  check_prerequisites
  record_current_state

  # 始终清理金丝雀
  cleanup_canary

  if [ "$CANARY_ONLY" = true ]; then
    log_info "Canary-only mode — skipping production rollback"
    exit 0
  fi

  perform_rollback
  verify_rollback

  log_info "Rollback operation completed successfully"
}

main "$@"
