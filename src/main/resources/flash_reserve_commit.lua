-- Commit a matching reservation without returning its stock.  The durable
-- MySQL intent is authoritative; the purchaser set is only a Redis mirror.
-- KEYS[1] = reservation key, KEYS[2] = user reservation hash,
-- KEYS[3] = purchaser set; ARGV[1] = user id
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('HDEL', KEYS[2], ARGV[1])
    redis.call('SADD', KEYS[3], ARGV[1])
    return 1
end
return 0
