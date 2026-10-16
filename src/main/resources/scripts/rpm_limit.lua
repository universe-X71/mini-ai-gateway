-- KEYS[1]: 限流 key，如 "rl:rpm:tenant123"
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 窗口大小（毫秒）
-- ARGV[3]: 限制次数
-- 返回：1=允许，0=拒绝

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- 1. 清理窗口外的旧数据
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 统计当前窗口内的请求数
local count = redis.call('ZCARD', key)

-- 3. 判断是否超限
if count < limit then
    -- 添加当前请求，member 用 "时间戳-随机数" 保证唯一
    local member = tostring(now) .. '-' .. tostring(math.random(1000000))
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
else
    return 0
end