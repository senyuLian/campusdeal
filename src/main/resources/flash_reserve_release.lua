-- Release only the reservation owned by the matching order/user pair.
-- KEYS[1] = reservation key, KEYS[2] = user reservation hash,
-- KEYS[3] = stock key; ARGV[1] = user id, ARGV[2] = order id
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('HDEL', KEYS[2], ARGV[1])
    redis.call('INCR', KEYS[3])
    return 1
end
return 0
