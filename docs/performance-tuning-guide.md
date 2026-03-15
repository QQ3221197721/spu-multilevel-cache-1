# SPU 多级缓存服务 — 性能调优指南

> 版本: 1.0.0 | 最后更新: 2026-03

---

## 1. 性能目标

| 指标 | 目标值 | 测量方式 |
|------|--------|---------|
| QPS | ≥ 100,000 | wrk / JMeter 压测 |
| TP50 | < 5ms | Prometheus histogram |
| TP99 | < 50ms | Prometheus histogram |
| TP999 | < 200ms | Prometheus histogram |
| L1 命中率 | > 90% | Caffeine stats |
| L2 命中率 | > 95% (含 L1) | Redis stats |
| 错误率 | < 0.01% | 5xx / total |
| GC 暂停 | < 100ms (P99) | JVM GC logs |

---

## 2. JVM 调优

### 2.1 推荐 JVM 参数（生产环境）

```bash
JAVA_OPTS="\
  -server \
  -Xms4g -Xmx4g \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:MaxGCPauseMillis=10 \
  -XX:SoftMaxHeapSize=3g \
  -XX:+UseTransparentHugePages \
  -XX:+AlwaysPreTouch \
  -XX:+UseStringDeduplication \
  -XX:MetaspaceSize=256m \
  -XX:MaxMetaspaceSize=512m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heapdump.hprof \
  -XX:+PrintGCDetails -Xlog:gc*:file=/var/log/gc.log:time,tags:filecount=5,filesize=100m \
  --enable-preview \
  --add-modules jdk.incubator.vector"
```

### 2.2 GC 选择

| GC 类型 | 适用场景 | 优势 | 劣势 |
|---------|---------|------|------|
| **ZGC (推荐)** | 低延迟场景 | 暂停 < 10ms | 内存占用稍高 |
| G1GC | 通用场景 | 平衡吞吐/延迟 | P99 暂停可达 100ms |
| Shenandoah | 超低延迟 | 暂停 < 1ms | 吞吐量略低 |

### 2.3 内存分析

```bash
# 堆快照
jmap -dump:format=b,file=heap.hprof <pid>

# 实时内存统计
jstat -gc <pid> 1000

# 查看大对象
jcmd <pid> GC.class_histogram | head -20

# 在线 JFR 录制 (5分钟)
jcmd <pid> JFR.start duration=300s filename=/tmp/recording.jfr
```

---

## 3. L1 Caffeine 缓存调优

### 3.1 核心参数

```yaml
app:
  cache:
    l1:
      max-size: 50000          # 本地缓存最大条目数
      ttl-seconds: 30          # TTL (秒)
      initial-capacity: 10000  # 初始容量
```

### 3.2 调优策略

| 参数 | 低流量 | 中等流量 | 高流量 |
|------|--------|---------|--------|
| max-size | 10,000 | 50,000 | 200,000 |
| ttl-seconds | 60 | 30 | 10 |
| initial-capacity | 2,000 | 10,000 | 50,000 |

**调优原则:**
- **max-size**: 根据 JVM 堆大小估算。每条 SPU ≈ 5KB，50000 条 ≈ 250MB
- **ttl-seconds**: 越短一致性越好，但命中率越低。建议 10-60s
- **监控**: 关注 `cache_l1_hit_rate`，目标 > 85%
- **驱逐**: Caffeine 使用 W-TinyLFU 算法，自动优化驱逐策略

### 3.3 内存估算公式

```
L1 内存 ≈ max-size × 平均对象大小 × 1.5 (额外开销)
示例: 50,000 × 5KB × 1.5 = 375MB
建议: L1 内存 ≤ 堆内存的 30%
```

---

## 4. L2 Redis 调优

### 4.1 连接池参数

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 64     # 最大连接数
          max-idle: 32       # 最大空闲连接
          min-idle: 8        # 最小空闲连接
          max-wait: 3000ms   # 最大等待时间
```

### 4.2 推荐配置

| 场景 | max-active | max-idle | min-idle |
|------|-----------|----------|----------|
| 开发 | 16 | 8 | 2 |
| 预发布 | 32 | 16 | 4 |
| 生产 | 64 | 32 | 8 |
| 高峰期 | 128 | 64 | 16 |

### 4.3 Redis Server 调优

```conf
# 内存策略
maxmemory 8gb
maxmemory-policy allkeys-lfu

# 持久化 (缓存场景可关闭)
save ""
appendonly no

# 网络
tcp-backlog 511
timeout 300

# 线程 (Redis 7.0+)
io-threads 4
io-threads-do-reads yes

# 慢日志
slowlog-log-slower-than 10000  # 10ms
slowlog-max-len 128
```

### 4.4 序列化优化

系统使用 JSON 序列化。大对象可考虑:
- **Protobuf**: 体积减少 60-70%，但需要 schema
- **Kryo**: 体积减少 50-60%，Java 生态友好
- **压缩**: 对 > 1KB 的 value 启用 GZIP

---

## 5. L3 Memcached 调优

### 5.1 参数配置

```yaml
app:
  cache:
    l3:
      servers: memcached:11211
      ttl-seconds: 300        # L3 TTL 较长
      operation-timeout: 2500 # 操作超时 (ms)
```

### 5.2 Server 调优

```bash
# 启动参数
memcached -m 2048 \    # 内存 2GB
  -c 1024 \            # 最大连接数
  -t 4 \               # 线程数
  -I 2m \              # 最大 item 大小
  -f 1.25 \            # 增长因子
  -n 48                # 最小 item 大小
```

---

## 6. 数据库调优

### 6.1 HikariCP 连接池

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 900000
      leak-detection-threshold: 60000
```

### 6.2 连接池大小估算

```
connections = (core_count × 2) + effective_spindle_count
示例: (8核 × 2) + 1 = 17，取整 20

经验值:
- 4C/8G:  15-20
- 8C/16G: 20-30
- 16C/32G: 30-50
```

### 6.3 SQL 优化清单

- 确保 `spu_detail.id` 有主键索引
- 确保 `spu_detail.spu_id` 有唯一索引
- 分页查询使用 `WHERE id > last_id LIMIT N` 而非 `OFFSET`
- 避免 `SELECT *`，仅查询必需字段
- 开启慢查询日志: `slow_query_log = ON`, `long_query_time = 1`

### 6.4 ShardingSphere 读写分离

```yaml
# 写操作走主库
spring.datasource.url=jdbc:mysql://master:3306/spu_db

# 读操作走从库 (ShardingSphere 自动路由)
# 从库延迟 > 1s 时自动降级到主库
```

---

## 7. 网络与线程池

### 7.1 Tomcat 线程池

```yaml
server:
  tomcat:
    threads:
      max: 400          # 最大线程数
      min-spare: 50     # 最小空闲线程
    max-connections: 10000
    accept-count: 200
```

### 7.2 线程池估算

```
线程数 = CPU核数 × (1 + I/O等待时间 / CPU处理时间)
缓存场景 I/O 密集: 线程数 ≈ CPU核数 × 10-20
示例: 8核 × 15 = 120，保守设 200-400
```

### 7.3 异步处理

- 缓存预热: 异步线程池处理
- 审计日志: AOP 异步写入
- Canal 事件: 异步消费
- RocketMQ: 并行消费配置

---

## 8. 热点 Key 优化

### 8.1 热点检测配置

```yaml
app:
  hot-key:
    window-seconds: 10      # 检测窗口
    threshold: 100           # 触发阈值 (窗口内访问次数)
    shard-count: 16          # 分片数量
```

### 8.2 优化策略

1. **L1 自动缓存**: 热点 Key 自动提升到 L1
2. **分片保护**: 热点 Key 自动分散到 16 个 Redis 分片
3. **本地副本**: 短 TTL 本地缓存 + 异步刷新
4. **监控**: `curl /api/cache/admin/hotkey/list` 定期巡检

---

## 9. 布隆过滤器调优

```yaml
app:
  bloom-filter:
    expected-insertions: 10000000  # 预估元素数
    false-positive-rate: 0.001     # 误判率 0.1%
```

**内存估算:**
```
bits = -n × ln(p) / (ln2)²
示例: -10M × ln(0.001) / 0.4804 ≈ 143M bits ≈ 18MB
```

---

## 10. 压测方法

### 10.1 wrk 基准测试

```bash
# 读取压测
wrk -t12 -c400 -d60s http://localhost:8080/api/spu/detail/100001

# Lua 脚本随机 SPU ID
wrk -t12 -c400 -d60s -s random_spu.lua http://localhost:8080/api/spu/detail/
```

### 10.2 JMeter 场景测试

1. **渐进式加压**: 50→200→500→1000 并发，观察拐点
2. **持续压测**: 目标 QPS × 1.2 持续 30 分钟
3. **混合场景**: 读:写 = 95:5
4. **故障注入**: 压测中模拟 Redis 宕机

### 10.3 关键观测指标

- QPS / TPS
- TP50 / TP99 / TP999
- 错误率
- CPU / 内存使用率
- GC 频率与暂停时间
- 连接池使用率
- 缓存命中率变化

---

## 11. 性能检查清单

### 上线前
- [ ] JVM 参数已按生产配置
- [ ] 连接池参数已调优
- [ ] 缓存预热已执行
- [ ] 布隆过滤器已初始化
- [ ] 压测通过 (目标 QPS × 1.5)
- [ ] GC 暂停 < 100ms (P99)
- [ ] 慢查询 < 100ms (P99)

### 大促前
- [ ] 热点商品已预热
- [ ] L1 max-size 已调大
- [ ] Redis 连接池已扩容
- [ ] DB 连接池已扩容
- [ ] 降级方案已就绪
- [ ] 回滚方案已验证
- [ ] 监控告警已开启
