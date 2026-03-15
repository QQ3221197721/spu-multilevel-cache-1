#!/usr/bin/env bash
# ============================================================
# SPU 多级缓存服务 — 压力测试自动化脚本
# ============================================================
# 用法:
#   ./scripts/stress-test.sh [BASE_URL] [DURATION] [CONCURRENCY]
#
# 参数:
#   BASE_URL     服务地址 (默认 http://localhost:8080)
#   DURATION     测试持续时间/秒 (默认 60)
#   CONCURRENCY  并发数 (默认 100)
#
# 前置条件:
#   - curl 已安装
#   - wrk 或 ab 已安装（可选，curl 回退方案始终可用）
# ============================================================

set -euo pipefail

# 参数
BASE_URL="${1:-http://localhost:8080}"
DURATION="${2:-60}"
CONCURRENCY="${3:-100}"
REPORT_DIR="./stress-test-reports"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
REPORT_FILE="${REPORT_DIR}/stress_test_${TIMESTAMP}.txt"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[INFO]${NC}  $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $1"; }

mkdir -p "$REPORT_DIR"

echo "============================================================" | tee "$REPORT_FILE"
echo " SPU Cache Service Stress Test" | tee -a "$REPORT_FILE"
echo " URL:         $BASE_URL" | tee -a "$REPORT_FILE"
echo " Duration:    ${DURATION}s" | tee -a "$REPORT_FILE"
echo " Concurrency: $CONCURRENCY" | tee -a "$REPORT_FILE"
echo " Timestamp:   $TIMESTAMP" | tee -a "$REPORT_FILE"
echo "============================================================" | tee -a "$REPORT_FILE"

# ============================================================
# Phase 0: 健康检查
# ============================================================
log_info "Phase 0: Health check..."
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" 2>/dev/null || echo "000")
if [ "$HEALTH" = "200" ]; then
    log_ok "Service is healthy (HTTP $HEALTH)"
else
    log_fail "Service is NOT healthy (HTTP $HEALTH). Aborting."
    exit 1
fi

# ============================================================
# Phase 1: 单个请求基准
# ============================================================
log_info "Phase 1: Single request baseline..."
echo "" >> "$REPORT_FILE"
echo "--- Phase 1: Single Request Baseline ---" >> "$REPORT_FILE"

for SPU_ID in 10001 10002 10003 99999; do
    RESPONSE=$(curl -s -o /dev/null -w "HTTP/%{http_code} time_total=%{time_total}s time_connect=%{time_connect}s size=%{size_download}B" \
        "${BASE_URL}/api/spu/detail/${SPU_ID}" 2>/dev/null)
    echo "  SPU $SPU_ID: $RESPONSE" | tee -a "$REPORT_FILE"
done

# ============================================================
# Phase 2: 缓存预热
# ============================================================
log_info "Phase 2: Cache warmup (10 sequential requests)..."
echo "" >> "$REPORT_FILE"
echo "--- Phase 2: Cache Warmup ---" >> "$REPORT_FILE"

for i in $(seq 1 10); do
    SPU_ID=$((10000 + i))
    curl -s -o /dev/null "${BASE_URL}/api/spu/detail/${SPU_ID}" 2>/dev/null
done
log_ok "Warmup complete"

# ============================================================
# Phase 3: 并发压力测试 (curl + xargs)
# ============================================================
log_info "Phase 3: Concurrent stress test (${CONCURRENCY} parallel, ${DURATION}s)..."
echo "" >> "$REPORT_FILE"
echo "--- Phase 3: Concurrent Stress Test ---" >> "$REPORT_FILE"

TOTAL_REQUESTS=0
SUCCESS_REQUESTS=0
FAILED_REQUESTS=0
START_TIME=$(date +%s)
END_TIME=$((START_TIME + DURATION))

# 使用临时文件收集结果
RESULT_FILE=$(mktemp)
trap "rm -f $RESULT_FILE" EXIT

run_requests() {
    local end_time=$1
    local base_url=$2
    local result_file=$3
    local count=0
    local success=0
    local fail=0

    while [ "$(date +%s)" -lt "$end_time" ]; do
        SPU_ID=$((10001 + RANDOM % 100))
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            --connect-timeout 5 --max-time 10 \
            "${base_url}/api/spu/detail/${SPU_ID}" 2>/dev/null || echo "000")
        count=$((count + 1))
        if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "404" ]; then
            success=$((success + 1))
        else
            fail=$((fail + 1))
        fi
    done

    echo "${count} ${success} ${fail}" >> "$result_file"
}

# 启动并发工作者
for i in $(seq 1 "$CONCURRENCY"); do
    run_requests "$END_TIME" "$BASE_URL" "$RESULT_FILE" &
done

log_info "Waiting for stress test to complete..."
wait

# 汇总结果
while IFS=' ' read -r total ok fail; do
    TOTAL_REQUESTS=$((TOTAL_REQUESTS + total))
    SUCCESS_REQUESTS=$((SUCCESS_REQUESTS + ok))
    FAILED_REQUESTS=$((FAILED_REQUESTS + fail))
done < "$RESULT_FILE"

ACTUAL_DURATION=$(($(date +%s) - START_TIME))
RPS=$((TOTAL_REQUESTS / (ACTUAL_DURATION > 0 ? ACTUAL_DURATION : 1)))
SUCCESS_RATE=$(echo "scale=2; $SUCCESS_REQUESTS * 100 / ($TOTAL_REQUESTS > 0 ? $TOTAL_REQUESTS : 1)" | bc 2>/dev/null || echo "N/A")

echo "  Total requests:   $TOTAL_REQUESTS" | tee -a "$REPORT_FILE"
echo "  Success requests: $SUCCESS_REQUESTS" | tee -a "$REPORT_FILE"
echo "  Failed requests:  $FAILED_REQUESTS" | tee -a "$REPORT_FILE"
echo "  Duration:         ${ACTUAL_DURATION}s" | tee -a "$REPORT_FILE"
echo "  RPS:              ~$RPS req/s" | tee -a "$REPORT_FILE"
echo "  Success rate:     ${SUCCESS_RATE}%" | tee -a "$REPORT_FILE"

# ============================================================
# Phase 4: 缓存统计
# ============================================================
log_info "Phase 4: Cache statistics..."
echo "" >> "$REPORT_FILE"
echo "--- Phase 4: Cache Statistics ---" >> "$REPORT_FILE"

STATS=$(curl -s "${BASE_URL}/api/spu/cache/stats" 2>/dev/null || echo '{"error":"unavailable"}')
echo "  $STATS" | tee -a "$REPORT_FILE"

# ============================================================
# Phase 5: Prometheus 指标
# ============================================================
log_info "Phase 5: Key Prometheus metrics..."
echo "" >> "$REPORT_FILE"
echo "--- Phase 5: Prometheus Metrics ---" >> "$REPORT_FILE"

METRICS=$(curl -s "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "unavailable")
for PATTERN in "cache_l1_hits" "cache_l2_hits" "cache_l3_hits" "cache_misses" "jvm_memory_used_bytes"; do
    MATCH=$(echo "$METRICS" | grep "^${PATTERN}" | head -3)
    if [ -n "$MATCH" ]; then
        echo "  $MATCH" | tee -a "$REPORT_FILE"
    fi
done

# ============================================================
# Phase 6: wrk 高级压测（如果可用）
# ============================================================
if command -v wrk &>/dev/null; then
    log_info "Phase 6: wrk advanced benchmark..."
    echo "" >> "$REPORT_FILE"
    echo "--- Phase 6: wrk Benchmark ---" >> "$REPORT_FILE"

    wrk -t4 -c"$CONCURRENCY" -d"${DURATION}s" \
        -H "Content-Type: application/json" \
        "${BASE_URL}/api/spu/detail/10001" 2>&1 | tee -a "$REPORT_FILE"
else
    log_warn "Phase 6: wrk not installed, skipping advanced benchmark"
    echo "  wrk not available, skipped" >> "$REPORT_FILE"
fi

# ============================================================
# 结果汇总
# ============================================================
echo "" | tee -a "$REPORT_FILE"
echo "============================================================" | tee -a "$REPORT_FILE"
echo " STRESS TEST SUMMARY" | tee -a "$REPORT_FILE"
echo "============================================================" | tee -a "$REPORT_FILE"
echo "  Total Requests:   $TOTAL_REQUESTS" | tee -a "$REPORT_FILE"
echo "  Success Rate:     ${SUCCESS_RATE}%" | tee -a "$REPORT_FILE"
echo "  Throughput (RPS): ~$RPS" | tee -a "$REPORT_FILE"
echo "  Report saved to:  $REPORT_FILE" | tee -a "$REPORT_FILE"
echo "============================================================" | tee -a "$REPORT_FILE"

# 判断结果
if [ "$FAILED_REQUESTS" -gt "$((TOTAL_REQUESTS / 10))" ]; then
    log_fail "Stress test FAILED: error rate > 10%"
    exit 1
else
    log_ok "Stress test PASSED"
fi
