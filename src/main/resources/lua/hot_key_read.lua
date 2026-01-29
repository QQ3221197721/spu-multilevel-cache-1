-- hot_key_read.lua
-- 功能：读取热点 Key，自动路由到随机分片
-- KEYS[1]: 原始 key，如 spu:detail:10086
-- ARGV[1]: 分片数量，默认 10

local key = KEYS[1]
local shard_count = tonumber(ARGV[1]) or 10
local random_suffix = math.random(0, shard_count - 1)
local shard_key = key .. ":shard:" .. random_suffix

-- 尝试从分片 key 读取
local value = redis.call('GET', shard_key)
if value then
    return value
end

-- 分片 key 不存在，从原始 key 读取
value = redis.call('GET', key)
if value then
    -- 异步分发到所有分片（设置 5 分钟过期）
    for i = 0, shard_count - 1 do
        local sk = key .. ":shard:" .. i
        redis.call('SETEX', sk, 300, value)
    end
end

return value
