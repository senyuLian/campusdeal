-- Atomic provisional reservation for the durable flash-order path.
-- KEYS[1] = flashdeal:stock:{dealId}
-- KEYS[2] = flashdeal:reservation:{orderId}
-- KEYS[3] = flashdeal:reserved:{dealId} (user -> order id hash)
-- ARGV[1] = order id, ARGV[2] = user id, ARGV[3] = lease seconds
-- 1 = reservation created, -1 = sold out, -2 = existing live reservation

local stock = redis.call('GET', KEYS[1])
local existing = redis.call('HGET', KEYS[3], ARGV[2])
if existing then
    local existingKey = 'flashdeal:reservation:' .. existing
    if redis.call('EXISTS', existingKey) == 1 then
        return -2
    end
    redis.call('HDEL', KEYS[3], ARGV[2])
end

-- A missing stock key is an unavailable mirror, not proof that the deal is
-- sold out. The Java boundary falls back to the authoritative MySQL decrement.
if not stock then
    return -3
end
if tonumber(stock) <= 0 then
    return -1
end
if redis.call('SETNX', KEYS[2], ARGV[2]) == 0 then
    return -2
end
redis.call('EXPIRE', KEYS[2], ARGV[3])
redis.call('HSET', KEYS[3], ARGV[2], ARGV[1])
redis.call('DECR', KEYS[1])
return 1
