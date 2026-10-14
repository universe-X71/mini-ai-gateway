-- KEYS[1]: 限流 key，如 "rl:tpm:user123"
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 窗口大小（毫秒）
-- ARGV[3]: 限制 token 总数
-- ARGV[4]: 本次请求预估消耗的 token 数
-- 返回：1=允许，0=拒绝

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local tokens = tonumber(ARGV[4])

-- 1. 清理窗口外的旧数据
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 遍历窗口内所有 member，累加 token 数
-- member 格式：时间戳-随机数:tokenCount
local members = redis.call('ZRANGE', key, 0, -1)
local total = 0
for i = 1, #members do
    local member = members[i]
    local t = tonumber(string.match(member, ':(-?%d+)$'))
    if t then
        total = total + t
    end
end

-- 3. 判断是否超限
if total + tokens <= limit then
    local member = tostring(now) .. '-' .. tostring(math.random(1000000)) .. ':' .. tostring(tokens)
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
else
    return 0
end