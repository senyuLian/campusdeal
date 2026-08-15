-- Flash Deal Lua Script (atomic stock check + dedup + decrement)
-- Called from: FlashDealServiceImpl.executeLuaScript()
-- ARGV[1] = dealId, ARGV[2] = userId
-- Returns:
--   N >= 0  = 秒杀成功，N = 剩余库存（用于 L1 负缓存预置：剩余为 0 时快速失败）
--   -1      = 库存不足（out of stock）
--   -2      = 重复下单（该用户已购买此活动）

-- 1. Parameters
local dealId = ARGV[1];
local userId = ARGV[2];

-- 2. Redis keys
local stockKey = "flashdeal:stock:" .. dealId;
local orderKey = "flashdeal:order:" .. dealId;

-- 3. Check stock
local stock = redis.call("get", stockKey);
if not stock or tonumber(stock) <= 0 then
    return -1;
end

-- 4. Check duplicate order (one user, one deal)
if redis.call("sismember", orderKey, userId) == 1 then
    return -2;
end

-- 5. Deduct stock + record user
redis.call("incrby", stockKey, -1);
redis.call("sadd", orderKey, userId);

-- 6. Return remaining stock (>= 0): 秒杀成功后由服务端决定是否写 L1 负缓存
return tonumber(stock) - 1;
