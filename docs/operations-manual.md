# SPU 多级缓存服务 — 运维手册

> 版本: 1.0.0 | 最后更新: 2026-03

---

## 1. 系统架构概览

```
Client → CDN → Nginx Ingress → Spring Boot App
                                   ├── L1 Caffeine (本地JVM)
                                   ├── L2 Redis Cluster
                                   ├── L3 Memcached
                                   └── MySQL (主从 + ShardingSphere)
```

### 依赖组件

| 组件 | 版本 | 端口 | 用途 |
|------|------|------|------|
| MySQL | 8.0+ | 3306/3307 | 主从数据库 |
| Redis | 7.0+ | 6379 | L2 缓存 + 分布式锁 |
| Memcached | 1.6+ | 11211 | L3 缓存 |
| RocketMQ | 5.0+ | 9876/10911 | 缓存失效消息 |
| Canal | 1.1.7 | 11111 | Binlog 监听 |
| Nacos | 2.3+ | 8848 | 配置/注册中心 |
| SkyWalking | 9.x | 11800/12800 | 链路追踪 |
| Elasticsearch | 8.x | 9200 | 日志存储 |
| Logstash | 8.x | 5044 | 日志采集 |
| Kibana | 8.x | 5601 | 日志可视化 |
| Seata | 1.7+ | 8091 | 分布式事务 |
| Prometheus | 2.x | 9090 | 指标采集 |
| Grafana | 10.x | 3000 | 监控面板 |

---

## 2. 日常运维操作

### 2.1 服务启停

```bash
# 本地启动
mvn spring-boot:run -Dspring.profiles.active=dev

# Docker Compose 启动全栈
docker-compose up -d

# K8s 部署
helm install spu-cache ./helm/spu-multilevel-cache -f helm/spu-multilevel-cache/values-prod.yaml

# 滚动更新
kubectl rollout restart deployment/spu-cache -n spu-cache

# 回滚
kubectl rollout undo deployment/spu-cache -n spu-cache
# 或使用自动回滚脚本
bash scripts/rollback.sh <namespace> <deployment> <revision>
```

### 2.2 健康检查

```bash
# Actuator 健康端点
curl http://localhost:8080/actuator/health

# 就绪探针
curl http://localhost:8080/actuator/health/readiness

# 存活探针
curl http://localhost:8080/actuator/health/liveness

# 缓存健康
curl http://localhost:8080/api/spu/health
```

### 2.3 缓存管理

```bash
# 查看缓存统计
curl http://localhost:8080/api/cache/admin/stats

# 清除单个 SPU 缓存
curl -X DELETE http://localhost:8080/api/cache/admin/invalidate/100001

# 批量清除
curl -X POST http://localhost:8080/api/cache/admin/invalidate/batch \
  -H "Content-Type: application/json" \
  -d '[100001, 100002, 100003]'

# 清空 L1 本地缓存
curl -X DELETE http://localhost:8080/api/cache/admin/l1/clear

# 手动预热
curl -X POST http://localhost:8080/api/cache/admin/preload/execute \
  -H "Content-Type: application/json" \
  -d '[100001, 100002]'
```

### 2.4 降级管理

```bash
# 查看降级状态
curl http://localhost:8080/api/ops/degradation

# 快捷开启降级 (CACHE_ONLY)
curl -X POST "http://localhost:8080/api/ops/degradation/enable?reason=DB+maintenance"

# 设置降级级别
curl -X POST "http://localhost:8080/api/ops/degradation/level?level=READ_ONLY&reason=high+load"

# 关闭降级
curl -X POST "http://localhost:8080/api/ops/degradation/disable?reason=recovery"

# 查看降级审计日志
curl "http://localhost:8080/api/ops/degradation/audit?limit=20"
```

**降级级别说明:**

| 级别 | 行为 |
|------|------|
| NORMAL | 全部功能正常 |
| READ_ONLY | 禁止写操作，读取正常 |
| CACHE_ONLY | 仅从 L1/L2 缓存返回，禁止 DB 查询 |
| REJECT | 拒绝所有非健康检查请求 |

---

## 3. 故障排查指南

### 3.1 缓存穿透

**现象:** 大量请求直接到达数据库，DB 负载激增

**排查步骤:**
1. 检查布隆过滤器状态: `curl /api/cache/admin/bloom/check/{spuId}`
2. 查看缓存统计中 miss 率: `curl /api/cache/admin/stats`
3. 检查是否有大量不存在的 SPU ID 请求（查看 Nginx access log）

**处理:**
1. 确认布隆过滤器已加载: 检查启动日志中 `BloomFilter initialized`
2. 手动添加缺失 ID: `curl -X POST /api/cache/admin/bloom/add/{spuId}`
3. 紧急情况开启降级: `curl -X POST /api/ops/degradation/enable`

### 3.2 缓存击穿

**现象:** 热点 Key 过期瞬间，大量并发请求同时回源 DB

**排查步骤:**
1. 查看热点 Key: `curl /api/cache/admin/hotkey/list`
2. 检查 L1 缓存统计: `curl /api/cache/admin/stats` → l1.evictionCount
3. 查看慢查询: `curl /api/cache/admin/insight/slow-queries`

**处理:**
1. 手动标记热点 Key: `curl -X POST /api/cache/admin/hotkey/mark/{spuId}`
2. 手动预热: `curl -X POST /api/cache/admin/preload/execute -d '[spuId]'`
3. 系统已内置 DCL + 分布式锁保护，正常情况自动防护

### 3.3 缓存雪崩

**现象:** 大量 Key 同时过期，请求全部回源

**排查步骤:**
1. 检查 Redis 内存: `redis-cli info memory`
2. 检查 Redis 连接数: `redis-cli info clients`
3. 查看 Prometheus 中 `cache_l2_redis_operation_seconds` 延迟突增

**处理:**
1. 系统已内置随机 TTL（±20%）防止集中过期
2. 紧急预热: 使用缓存预热接口批量加载
3. 临时降级: `curl -X POST /api/ops/degradation/enable`

### 3.4 Redis 连接池耗尽

**现象:** 日志出现 `Unable to acquire connection from pool`

**排查步骤:**
1. 检查连接池健康: `curl /actuator/health` → redisPool
2. 查看 Prometheus: `hikaricp_connections_active` / `lettuce_pool_*`
3. 检查是否有慢命令: `redis-cli slowlog get 10`

**处理:**
1. 调整连接池参数: `spring.data.redis.lettuce.pool.max-active`
2. 检查是否有连接泄漏（大 Key、长事务）
3. 熔断器保护: `curl -X POST /api/cache/admin/circuit-breaker/force-open/redis`

### 3.5 MySQL 慢查询

**现象:** SPU 回源查询延迟高，TP99 超标

**排查步骤:**
1. 查看慢查询监控: Grafana → 慢查询面板
2. 检查 Prometheus: `slow_query_total`, `slow_query_duration_seconds`
3. 检查 HikariCP 连接池: `curl /actuator/health` → databasePool

**处理:**
1. 检查 SQL 执行计划: `EXPLAIN SELECT ...`
2. 确认索引存在: `SHOW INDEX FROM spu_detail`
3. 调整连接池: `spring.datasource.hikari.maximum-pool-size`
4. 开启读写分离: 配置 ShardingSphere 从库

### 3.6 Canal Binlog 同步延迟

**现象:** 数据库更新后缓存未及时失效

**排查步骤:**
1. 检查 Canal 健康: `curl /actuator/health` → canal
2. 查看 Canal 日志: `docker logs canal-server`
3. 检查 binlog 位点: Canal Admin 或 `canal/meta.dat`

**处理:**
1. 重启 Canal: `docker-compose restart canal-server`
2. 手动清除受影响缓存: `curl -X DELETE /api/cache/admin/invalidate/{spuId}`
3. 检查 MySQL binlog 格式: 必须为 ROW 格式

### 3.7 RocketMQ 消息积压

**现象:** 缓存失效消息消费延迟

**排查步骤:**
1. 查看消费者组延迟: RocketMQ Dashboard → Consumer
2. 检查死信队列: 查看 `%DLQ%` topic 消息量
3. 查看发件箱表: `SELECT COUNT(*) FROM message_outbox WHERE status='PENDING'`

**处理:**
1. 扩容消费者实例
2. 清理死信消息: 检查 `dead_letter_messages` 表
3. 手动重试发件箱: 系统每 30s 自动扫描重试

---

## 4. 监控告警

### 4.1 关键 Grafana 面板

| 面板 | 路径 | 关注指标 |
|------|------|---------|
| JVM 监控 | Grafana → JVM Overview | 堆内存使用率、GC 暂停时间 |
| 缓存概览 | Grafana → Cache Dashboard | 命中率、延迟、QPS |
| 慢查询 | Grafana → Slow Query | 慢查询数量、P99 延迟 |
| 业务指标 | Grafana → Business Metrics | SPU 查询量、错误率 |

### 4.2 告警规则

| 告警 | 触发条件 | 处理 |
|------|---------|------|
| 缓存命中率低 | L1 命中率 < 70% 持续 5min | 检查缓存配置、预热状态 |
| Redis 延迟高 | P99 > 50ms 持续 3min | 检查 Redis 集群、网络 |
| DB 连接池满 | active = max 持续 2min | 扩容连接池或降级 |
| GC 暂停长 | GC pause > 500ms | 检查堆配置、内存泄漏 |
| 错误率高 | 5xx > 1% 持续 3min | 查看日志排查根因 |
| 降级已开启 | level != NORMAL | 确认是否人为操作 |

### 4.3 日志查询 (Kibana)

```
# 查看错误日志
level: "ERROR" AND service: "spu-cache"

# 查看慢请求
tags.duration: >1000 AND service: "spu-cache"

# 查看特定 SPU 相关日志
message: "100001" AND service: "spu-cache"

# 查看缓存失效事件
logger_name: "CanalBinlogListener"
```

---

## 5. 日常维护检查清单

### 每日检查
- [ ] Grafana 面板无红色告警
- [ ] 缓存命中率 > 85%
- [ ] Redis 内存使用率 < 80%
- [ ] MySQL 慢查询数量无异常增长
- [ ] Canal 同步延迟 < 1s
- [ ] RocketMQ 无消息积压
- [ ] 审计日志无高风险异常

### 每周检查
- [ ] JVM GC 趋势分析
- [ ] 连接池使用率趋势
- [ ] 热点 Key 变化分析
- [ ] 布隆过滤器误判率评估
- [ ] 磁盘空间检查（日志/数据）

### 每月检查
- [ ] 依赖组件版本安全更新
- [ ] SSL 证书有效期
- [ ] 备份恢复验证
- [ ] 容量评估与扩容规划
- [ ] 灾备演练

---

## 6. 常用运维命令

```bash
# ===== K8s =====
# 查看 Pod 状态
kubectl get pods -n spu-cache -o wide

# 查看 Pod 日志
kubectl logs -f deployment/spu-cache -n spu-cache --tail=100

# 进入 Pod
kubectl exec -it <pod-name> -n spu-cache -- /bin/sh

# 查看 HPA 状态
kubectl get hpa -n spu-cache

# 查看 PDB 状态
kubectl get pdb -n spu-cache

# ===== Redis =====
# 查看内存
redis-cli info memory | grep used_memory_human

# 查看连接数
redis-cli info clients | grep connected_clients

# 查看慢日志
redis-cli slowlog get 20

# 查看 key 数量
redis-cli dbsize

# ===== MySQL =====
# 查看连接数
mysql -e "SHOW STATUS LIKE 'Threads_connected';"

# 查看慢查询
mysql -e "SHOW VARIABLES LIKE 'slow_query_log';"

# 查看进程列表
mysql -e "SHOW PROCESSLIST;"

# ===== RocketMQ =====
# 查看 topic 列表
mqadmin topicList -n localhost:9876

# 查看消费延迟
mqadmin consumerProgress -g spu-cache-consumer -n localhost:9876
```
