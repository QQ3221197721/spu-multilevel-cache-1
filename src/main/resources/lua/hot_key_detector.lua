-- hot_key_detector.lua
-- 功能：滑动窗口统计 Key 访问频率，超过阈值返回热点标识
-- KEYS[1]: 统计 Key，如 hotkey:stats:{key}
-- ARGV[1]: 当前时间戳（秒）
-- ARGV[2]: 窗口大小（秒），默认 10
-- ARGV[3]: 热点阈值（QPS），默认 5000

local stats_key = KEYS[1]
local now = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2]) or 10
local threshold = tonumber(ARGV[3]) or 5000

-- 滑动窗口起始时间
local window_start = now - window_size

-- 移除过期的访问记录
redis.call('ZREMRANGEBYSCORE', stats_key, '-inf', window_start)

-- 添加当前访问记录（使用时间戳+随机数作为 member，确保唯一性）
redis.call('ZADD', stats_key, now, now .. ':' .. math.random(1000000))

-- 设置过期时间（窗口大小 * 2，防止内存泄漏）
redis.call('EXPIRE', stats_key, window_size * 2)

-- 统计窗口内访问次数
local count = redis.call('ZCOUNT', stats_key, window_start, '+inf')

-- 计算 QPS
local qps = count / window_size

-- 返回 QPS 和是否超过阈值
if qps >= threshold then
    return {qps, 1}  -- 是热点 Key
else
    return {qps, 0}  -- 不是热点 Key
end
