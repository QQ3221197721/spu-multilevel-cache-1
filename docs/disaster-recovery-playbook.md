# SPU 多级缓存服务 — 灾备演练手册

> 版本: 1.0.0 | 最后更新: 2026-03

---

## 1. 灾备体系概览

### 1.1 灾难级别定义

| 级别 | 描述 | 影响范围 | RTO | RPO |
|------|------|---------|-----|-----|
| P0 | 全站不可用 | 全部用户 | < 5min | 0 |
| P1 | 核心功能异常 | > 50% 用户 | < 15min | < 1min |
| P2 | 部分功能降级 | < 30% 用户 | < 30min | < 5min |
| P3 | 性能下降 | 体验受影响 | < 1h | < 30min |

### 1.2 依赖故障影响矩阵

| 故障组件 | 影响 | 降级策略 | 恢复方案 |
|---------|------|---------|---------|
| Redis Cluster 全挂 | L2 缓存不可用 | 降级到 L1+L3+DB | 恢复 Redis，自动回填 |
| Memcached 全挂 | L3 缓存不可用 | 降级到 L1+L2+DB | 恢复 Memcached |
| MySQL 主库宕机 | 写入不可用 | READ_ONLY 降级 | 从库提升为主库 |
| MySQL 主从全挂 | DB 不可用 | CACHE_ONLY 降级 | 恢复 DB，从备份还原 |
| Canal 中断 | 缓存一致性延迟 | 延迟双删兜底 | 重启 Canal |
| RocketMQ 不可用 | 消息投递失败 | Outbox 本地暂存 | 恢复后自动重投 |
| Nacos 不可用 | 配置/注册失效 | 本地缓存配置 | 恢复 Nacos |
| SkyWalking 不可用 | 链路追踪中断 | 无业务影响 | 恢复即可 |

---

## 2. 演练场景

### 场景 1: Redis 单节点故障

**目的:** 验证 Redis 故障自动熔断与降级

**前置条件:**
- 生产/预发布环境已部署
- 监控告警正常运行
- 有 Redis Cluster 多节点

**操作步骤:**
```bash
# 1. 记录当前指标基线
curl http://localhost:8080/api/cache/admin/stats > baseline.json

# 2. 模拟 Redis 节点故障 (K8s 环境)
kubectl delete pod redis-0 -n spu-cache

# 3. 观察 (等待 30s)
# - 监控: 观察 Grafana Redis 面板
# - 熔断器: curl /api/cache/admin/circuit-breaker/status
# - 缓存统计: curl /api/cache/admin/stats

# 4. 验证服务可用性
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/spu/detail/100001
done | sort | uniq -c

# 5. Redis 自动恢复后验证
# K8s StatefulSet 会自动重建 Pod
kubectl get pods -n spu-cache -w

# 6. 确认指标恢复
curl http://localhost:8080/api/cache/admin/stats > recovered.json
```

**预期结果:**
- 熔断器在 5s 内切换到 OPEN 状态
- 服务自动降级到 L1+L3+DB
- 请求成功率 > 99%
- Redis 恢复后熔断器自动关闭
- 缓存自动回填

**回滚方案:**
```bash
# 手动重置熔断器
curl -X POST http://localhost:8080/api/cache/admin/circuit-breaker/reset/redis
```

---

### 场景 2: MySQL 主库宕机

**目的:** 验证数据库故障时的降级与读写分离切换

**操作步骤:**
```bash
# 1. 开启降级为 READ_ONLY
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=READ_ONLY&reason=drill-mysql-failover"

# 2. 模拟主库故障
# Docker 环境
docker stop mysql-master

# K8s 环境
kubectl scale statefulset mysql-master --replicas=0 -n spu-cache

# 3. 验证读操作正常 (从缓存返回)
curl http://localhost:8080/api/spu/detail/100001
# 预期: 200 OK (从 L1/L2/L3 缓存返回)

# 4. 验证写操作被拒绝
curl -X POST http://localhost:8080/api/spu/refresh/100001
# 预期: 503 或降级响应

# 5. 恢复主库
docker start mysql-master
# 或 K8s
kubectl scale statefulset mysql-master --replicas=1 -n spu-cache

# 6. 恢复正常模式
curl -X POST "http://localhost:8080/api/ops/degradation/disable?reason=drill-mysql-recovered"

# 7. 验证全部功能恢复
curl http://localhost:8080/api/spu/detail/100001
curl -X POST http://localhost:8080/api/spu/refresh/100001
```

**预期结果:**
- READ_ONLY 模式下读操作正常
- 写操作被优雅拒绝
- 主库恢复后全部功能正常

---

### 场景 3: 缓存雪崩模拟

**目的:** 验证大量缓存同时过期时的系统表现

**操作步骤:**
```bash
# 1. 批量预热 1000 个 SPU (使用相同 TTL)
curl -X POST http://localhost:8080/api/spu/cache/preheat \
  -H "Content-Type: application/json" \
  -d '{"spuIds": [100001,100002,...,101000], "ttlSeconds": 10}'

# 2. 等待 TTL 过期 (10s)
sleep 12

# 3. 同时发起大量请求
wrk -t8 -c200 -d30s -s random_spu.lua http://localhost:8080/api/spu/detail/

# 4. 观察指标
# - DB QPS 是否暴增
# - 缓存回源并发数
# - 分布式锁等待时间
# - 响应延迟变化
```

**预期结果:**
- 随机 TTL 机制使过期分散（±20%）
- DCL + 分布式锁限制回源并发
- DB QPS 不超过正常值 3x
- TP99 < 200ms

---

### 场景 4: Canal 同步中断

**目的:** 验证 Canal 故障时的缓存一致性保障

**操作步骤:**
```bash
# 1. 停止 Canal
docker stop canal-server

# 2. 更新数据库中的 SPU 数据
mysql -e "UPDATE spu_detail SET title='Test Updated' WHERE spu_id=100001"

# 3. 检查缓存 (应该仍是旧数据)
curl http://localhost:8080/api/spu/detail/100001
# 预期: 返回旧数据 (Canal 停止，未触发失效)

# 4. 验证延迟双删兜底机制
# 通过 API 更新触发手动失效
curl -X DELETE http://localhost:8080/api/cache/admin/invalidate/100001

# 5. 再次查询 (应该返回新数据)
curl http://localhost:8080/api/spu/detail/100001

# 6. 恢复 Canal
docker start canal-server

# 7. 检查 Canal 健康
curl http://localhost:8080/actuator/health | jq '.components.canal'

# 8. 验证后续数据变更自动同步
mysql -e "UPDATE spu_detail SET title='Test Auto Sync' WHERE spu_id=100002"
sleep 2
curl http://localhost:8080/api/spu/detail/100002
```

**预期结果:**
- Canal 停止期间有延迟双删兜底
- 手动失效 API 可立即生效
- Canal 恢复后自动追赶 binlog

---

### 场景 5: 节点滚动更新

**目的:** 验证滚动更新期间零停机

**操作步骤:**
```bash
# 1. 启动持续压测
wrk -t4 -c100 -d300s http://localhost:8080/api/spu/detail/100001 &

# 2. 触发滚动更新
kubectl set image deployment/spu-cache \
  spu-cache=registry.example.com/spu-cache:new-version -n spu-cache

# 3. 观察滚动过程
kubectl rollout status deployment/spu-cache -n spu-cache

# 4. 检查 PDB 保护
kubectl get pdb -n spu-cache

# 5. 压测结束后检查结果
# 关注: 错误率、延迟毛刺
```

**预期结果:**
- 滚动更新期间请求成功率 > 99.9%
- PDB 保证至少 2 个 Pod 可用
- preStop hook 优雅关闭 (15s drain)
- 延迟毛刺 < 500ms

---

### 场景 6: 全站降级演练

**目的:** 验证完整降级链路

**操作步骤:**
```bash
# 1. NORMAL → READ_ONLY
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=READ_ONLY&reason=drill"
sleep 5
curl http://localhost:8080/api/ops/degradation  # 验证

# 2. READ_ONLY → CACHE_ONLY
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=CACHE_ONLY&reason=drill"
sleep 5
curl http://localhost:8080/api/spu/detail/100001  # 应该从缓存返回

# 3. CACHE_ONLY → REJECT
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=REJECT&reason=drill"
sleep 5
curl http://localhost:8080/api/spu/detail/100001  # 应该被拒绝
curl http://localhost:8080/api/spu/health  # 健康检查仍可用

# 4. 逐步恢复 REJECT → CACHE_ONLY → READ_ONLY → NORMAL
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=CACHE_ONLY&reason=drill-recovery"
sleep 5
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=NORMAL&reason=drill-complete"

# 5. 检查审计日志
curl "http://localhost:8080/api/ops/degradation/audit?limit=10"
```

**预期结果:**
- 每级降级切换 < 1s 生效
- 所有级别切换记录在审计日志
- REJECT 模式下健康检查仍可用
- 恢复后全部功能正常

---

## 3. 演练计划模板

### 3.1 演练前准备

| 项目 | 负责人 | 确认 |
|------|--------|------|
| 通知相关团队 | | [ ] |
| 备份当前配置 | | [ ] |
| 确认回滚方案 | | [ ] |
| 监控面板就绪 | | [ ] |
| 通信频道就绪 | | [ ] |
| 降级API已测试 | | [ ] |

### 3.2 演练执行记录

| 时间 | 操作 | 预期结果 | 实际结果 | 备注 |
|------|------|---------|---------|------|
| | | | | |

### 3.3 演练后总结

- **发现问题:** (列出演练中发现的问题)
- **改进措施:** (针对问题的改进方案)
- **后续计划:** (下次演练计划)

---

## 4. 演练频率

| 场景 | 频率 | 环境 |
|------|------|------|
| Redis 故障 | 每月 1 次 | 预发布 |
| MySQL 主从切换 | 每月 1 次 | 预发布 |
| 缓存雪崩 | 每季度 1 次 | 预发布 |
| Canal 中断 | 每季度 1 次 | 预发布 |
| 滚动更新 | 每次发版 | 预发布+生产 |
| 全站降级 | 每季度 1 次 | 预发布 |
| 全链路压测 | 大促前 | 预发布 |

---

## 5. 应急通讯录

| 角色 | 联系方式 | 备注 |
|------|---------|------|
| 值班 SRE | on-call | 一线响应 |
| 缓存服务 Owner | | 技术决策 |
| DBA | | 数据库问题 |
| 中间件 | | Redis/MQ/Canal |
| 安全 | | 安全事件 |
