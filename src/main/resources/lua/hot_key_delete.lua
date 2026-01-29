-- hot_key_delete.lua
-- 功能：删除热点 Key 及所有分片
-- KEYS[1]: 原始 key
-- ARGV[1]: 分片数量

local key = KEYS[1]
local shard_count = tonumber(ARGV[1]) or 10

-- 删除原始 key
redis.call('DEL', key)

-- 删除所有分片
local deleted = 0
for i = 0, shard_count - 1 do
    local shard_key = key .. ":shard:" .. i
    deleted = deleted + redis.call('DEL', shard_key)
end

return deleted + 1
