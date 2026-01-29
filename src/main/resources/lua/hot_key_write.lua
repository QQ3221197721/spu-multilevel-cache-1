-- hot_key_write.lua
-- 功能：写入热点 Key，同时更新所有分片
-- KEYS[1]: 原始 key
-- ARGV[1]: value
-- ARGV[2]: TTL 秒数
-- ARGV[3]: 分片数量

local key = KEYS[1]
local value = ARGV[1]
local ttl = tonumber(ARGV[2]) or 600
local shard_count = tonumber(ARGV[3]) or 10

-- 写入原始 key
redis.call('SETEX', key, ttl, value)

-- 写入所有分片
for i = 0, shard_count - 1 do
    local shard_key = key .. ":shard:" .. i
    redis.call('SETEX', shard_key, ttl, value)
end

return "OK"
