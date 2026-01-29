-- hot_key_detector_v7.lua
-- V7增强版热点Key检测脚本
-- 功能：多维度热点检测、自适应阈值、趋势分析、热度衰减
-- 
-- KEYS[1]: 统计Key，如 hotkey:stats:{key}
-- KEYS[2]: 热度分数Key，如 hotkey:score:{key}
-- KEYS[3]: 趋势数据Key，如 hotkey:trend:{key}
-- KEYS[4]: 全局热点集合Key，如 hotkey:global:hot
-- 
-- ARGV[1]: 当前时间戳（秒）
-- ARGV[2]: 窗口大小（秒），默认 10
-- ARGV[3]: 热点阈值（QPS），默认 5000
-- ARGV[4]: 衰减因子，默认 0.9
-- ARGV[5]: 趋势窗口数，默认 6
-- ARGV[6]: Key名称（用于全局热点集合）

local stats_key = KEYS[1]
local score_key = KEYS[2]
local trend_key = KEYS[3]
local global_hot_key = KEYS[4]

local now = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2]) or 10
local threshold = tonumber(ARGV[3]) or 5000
local decay_factor = tonumber(ARGV[4]) or 0.9
local trend_windows = tonumber(ARGV[5]) or 6
local key_name = ARGV[6] or 'unknown'

-- ========== 滑动窗口统计 ==========

local window_start = now - window_size

-- 移除过期的访问记录
redis.call('ZREMRANGEBYSCORE', stats_key, '-inf', window_start)

-- 添加当前访问记录（使用时间戳+随机数作为member，确保唯一性）
local member = now .. ':' .. math.random(1000000)
redis.call('ZADD', stats_key, now, member)

-- 设置过期时间（窗口大小 * 3，防止内存泄漏）
redis.call('EXPIRE', stats_key, window_size * 3)

-- 统计窗口内访问次数
local count = redis.call('ZCOUNT', stats_key, window_start, '+inf')

-- 计算当前QPS
local current_qps = count / window_size

-- ========== 热度分数计算（带衰减） ==========

-- 获取上一次的热度分数
local prev_score = tonumber(redis.call('GET', score_key)) or 0

-- 计算新的热度分数（指数加权移动平均）
local new_score = prev_score * decay_factor + current_qps * (1 - decay_factor)

-- 保存新的热度分数
redis.call('SET', score_key, new_score)
redis.call('EXPIRE', score_key, window_size * 10)

-- ========== 趋势分析 ==========

-- 获取当前时间窗口索引
local window_idx = math.floor(now / window_size) % trend_windows

-- 保存当前窗口的QPS到趋势数据
redis.call('HSET', trend_key, window_idx, current_qps)
redis.call('EXPIRE', trend_key, window_size * trend_windows * 2)

-- 计算趋势（简单线性回归斜率）
local trend_slope = 0
local valid_windows = 0
local sum_x = 0
local sum_y = 0
local sum_xy = 0
local sum_x2 = 0

for i = 0, trend_windows - 1 do
    local val = tonumber(redis.call('HGET', trend_key, i))
    if val then
        valid_windows = valid_windows + 1
        sum_x = sum_x + i
        sum_y = sum_y + val
        sum_xy = sum_xy + i * val
        sum_x2 = sum_x2 + i * i
    end
end

if valid_windows >= 3 then
    local n = valid_windows
    local denominator = n * sum_x2 - sum_x * sum_x
    if denominator ~= 0 then
        trend_slope = (n * sum_xy - sum_x * sum_y) / denominator
    end
end

-- ========== 热点判定 ==========

-- 综合判定：当前QPS + 热度分数 + 趋势
local is_hot = 0
local hot_reason = 'normal'

-- 条件1：当前QPS超过阈值
if current_qps >= threshold then
    is_hot = 1
    hot_reason = 'qps_exceeded'
-- 条件2：热度分数超过阈值的80%，且趋势上升
elseif new_score >= threshold * 0.8 and trend_slope > 0 then
    is_hot = 1
    hot_reason = 'trending_hot'
-- 条件3：热度分数超过阈值的60%，且趋势快速上升
elseif new_score >= threshold * 0.6 and trend_slope > threshold * 0.1 then
    is_hot = 1
    hot_reason = 'rapid_rise'
end

-- ========== 更新全局热点集合 ==========

if is_hot == 1 then
    -- 添加到全局热点集合，分数为热度分数
    redis.call('ZADD', global_hot_key, new_score, key_name)
    redis.call('EXPIRE', global_hot_key, window_size * 60)
else
    -- 如果不再是热点，降低其分数
    local existing_score = redis.call('ZSCORE', global_hot_key, key_name)
    if existing_score then
        local degraded_score = tonumber(existing_score) * decay_factor
        if degraded_score < threshold * 0.1 then
            -- 分数过低，移除
            redis.call('ZREM', global_hot_key, key_name)
        else
            redis.call('ZADD', global_hot_key, degraded_score, key_name)
        end
    end
end

-- 限制全局热点集合大小（保留Top 1000）
local hot_count = redis.call('ZCARD', global_hot_key)
if hot_count > 1000 then
    redis.call('ZREMRANGEBYRANK', global_hot_key, 0, hot_count - 1001)
end

-- ========== 返回结果 ==========

-- 返回：当前QPS、热度分数、趋势斜率、是否热点、热点原因
return {
    math.floor(current_qps * 100) / 100,      -- 当前QPS（保留2位小数）
    math.floor(new_score * 100) / 100,        -- 热度分数
    math.floor(trend_slope * 1000) / 1000,    -- 趋势斜率（保留3位小数）
    is_hot,                                    -- 是否热点（0或1）
    hot_reason                                 -- 热点原因
}
