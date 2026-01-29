package com.ecommerce.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * SPU 详情页多级缓存压测脚本
 * 目标：10 W QPS，TP99 < 50 ms，错误率 < 0.1%
 */
class SpuDetailSimulation extends Simulation {

  // HTTP 协议配置
  val httpProtocol = http
    .baseUrl("http://gateway.ecommerce.com")
    .acceptHeader("application/json")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("Gatling/3.9.5")
    .shareConnections

  // SPU ID 数据源（100 万 SPU）
  val spuFeeder = csv("spu_ids.csv").random

  // 用户 ID 数据源
  val userFeeder = csv("user_ids.csv").random

  // ========== 场景 1：正常流量（70%）==========
  val normalScenario = scenario("Normal Traffic")
    .feed(spuFeeder)
    .feed(userFeeder)
    .exec(
      http("Get SPU Detail")
        .get("/api/spu/detail/${spuId}")
        .header("X-User-Id", "${userId}")
        .header("X-Request-Id", "${spuId}-${randomUUID}")
        .check(status.is(200))
        .check(jsonPath("$.code").is("0"))
        .check(responseTimeInMillis.lte(50))
    )
    .pause(100.milliseconds, 500.milliseconds)

  // ========== 场景 2：灰度流量（10%）==========
  val canaryScenario = scenario("Canary Traffic")
    .feed(spuFeeder)
    .feed(userFeeder)
    .exec(
      http("Get SPU Detail (Canary)")
        .get("/api/spu/detail/${spuId}")
        .header("canary", "1")
        .header("X-User-Id", "${userId}")
        .header("X-Request-Id", "${spuId}-canary-${randomUUID}")
        .check(status.is(200))
        .check(jsonPath("$.code").is("0"))
    )
    .pause(100.milliseconds, 500.milliseconds)

  // ========== 场景 3：热点 Key 压测（20%）==========
  // 模拟大促期间热门商品的高并发访问
  val hotKeyScenario = scenario("Hot Key Traffic")
    .exec(
      http("Get Hot SPU Detail - 10086")
        .get("/api/spu/detail/10086")  // 固定热点 SPU
        .header("X-Request-Id", "hotkey-10086-${randomUUID}")
        .check(status.is(200))
    )
    .pause(10.milliseconds, 50.milliseconds)
    .exec(
      http("Get Hot SPU Detail - 10087")
        .get("/api/spu/detail/10087")  // 另一个热点 SPU
        .header("X-Request-Id", "hotkey-10087-${randomUUID}")
        .check(status.is(200))
    )
    .pause(10.milliseconds, 50.milliseconds)

  // ========== 场景 4：缓存穿透测试 ==========
  // 模拟恶意请求不存在的 SPU
  val penetrationScenario = scenario("Penetration Test")
    .exec(
      http("Get Non-exist SPU")
        .get("/api/spu/detail/999999999")  // 不存在的 SPU
        .header("X-Request-Id", "penetration-${randomUUID}")
        .check(status.is(404))  // 期望返回 404
    )
    .pause(500.milliseconds, 1.second)

  // ========== 场景 5：缓存刷新测试 ==========
  val refreshScenario = scenario("Cache Refresh")
    .feed(spuFeeder)
    .exec(
      http("Refresh SPU Cache")
        .post("/api/spu/refresh/${spuId}")
        .header("X-Request-Id", "refresh-${spuId}-${randomUUID}")
        .check(status.is(200))
    )
    .pause(1.second, 2.seconds)

  // ========== 10 W QPS 压测配置 ==========
  setUp(
    // 正常流量：70,000 用户
    normalScenario.inject(
      rampUsers(70000).during(60.seconds),    // 1 分钟内逐步加压
      constantUsersPerSec(70000).during(300.seconds)  // 持续 5 分钟
    ),
    
    // 灰度流量：10,000 用户
    canaryScenario.inject(
      rampUsers(10000).during(60.seconds),
      constantUsersPerSec(10000).during(300.seconds)
    ),
    
    // 热点 Key：20,000 用户
    hotKeyScenario.inject(
      rampUsers(20000).during(30.seconds),
      constantUsersPerSec(20000).during(300.seconds)
    ),
    
    // 穿透测试：100 用户（低并发模拟攻击）
    penetrationScenario.inject(
      rampUsers(100).during(60.seconds),
      constantUsersPerSec(100).during(300.seconds)
    )
    
  ).protocols(httpProtocol)
    .assertions(
      // TP99 < 50 ms
      global.responseTime.percentile(99).lt(50),
      // TP999 < 80 ms
      global.responseTime.percentile(99.9).lt(80),
      // 错误率 < 0.1%
      global.failedRequests.percent.lt(0.1),
      // 平均响应时间 < 20 ms
      global.responseTime.mean.lt(20),
      // 最大响应时间 < 200 ms
      global.responseTime.max.lt(200)
    )
}

/**
 * 阶梯压测场景
 * 用于测试系统极限
 */
class StepLoadSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://gateway.ecommerce.com")
    .acceptHeader("application/json")
    .shareConnections

  val spuFeeder = csv("spu_ids.csv").random

  val scenario1 = scenario("Step Load Test")
    .feed(spuFeeder)
    .exec(
      http("Get SPU Detail")
        .get("/api/spu/detail/${spuId}")
        .check(status.is(200))
    )
    .pause(50.milliseconds, 200.milliseconds)

  setUp(
    scenario1.inject(
      // 阶梯加压
      incrementUsersPerSec(10000)
        .times(10)                    // 10 个阶梯
        .eachLevelLasting(60.seconds) // 每阶梯持续 1 分钟
        .separatedByRampsLasting(10.seconds) // 阶梯间隔 10 秒
        .startingFrom(10000)          // 起始 1 万用户
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(99).lt(100),
      global.failedRequests.percent.lt(1)
    )
}

/**
 * 峰值冲击测试
 * 模拟大促瞬间流量暴涨
 */
class SpikePeakSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://gateway.ecommerce.com")
    .acceptHeader("application/json")
    .shareConnections

  val spuFeeder = csv("spu_ids.csv").random

  val spikeScenario = scenario("Spike Peak Test")
    .feed(spuFeeder)
    .exec(
      http("Get SPU Detail")
        .get("/api/spu/detail/${spuId}")
        .check(status.is(200))
    )
    .pause(20.milliseconds, 100.milliseconds)

  setUp(
    spikeScenario.inject(
      // 预热
      rampUsers(10000).during(30.seconds),
      // 稳定运行
      constantUsersPerSec(10000).during(60.seconds),
      // 峰值冲击（瞬间 10 倍流量）
      atOnceUsers(100000),
      // 持续高峰
      constantUsersPerSec(100000).during(60.seconds),
      // 回落
      rampUsers(10000).during(30.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(99).lt(100),
      global.failedRequests.percent.lt(5)  // 峰值允许 5% 错误率
    )
}
