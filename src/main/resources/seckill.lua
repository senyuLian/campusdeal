-- Flash Deal Lua Script (atomic stock check + dedup + decrement)
-- Called from: FlashDealServiceImpl.executeLuaScript()
-- ARGV[1] = dealId, ARGV[2] = userId
-- Returns: 0 = success, 1 = out of stock, 2 = duplicate order

-- 1. Parameters
local dealId = ARGV[1];
local userId = ARGV[2];

-- 2. Redis keys
local stockKey = "flashdeal:stock:" .. dealId;
local orderKey = "flashdeal:order:" .. dealId;

-- 3. Check stock
local stock = redis.call("get", stockKey);
if not stock or tonumber(stock) <= 0 then
    return 1;
end

-- 4. Check duplicate order (one user, one deal)
if redis.call("sismember", orderKey, userId) == 1 then
    return 2;
end

-- 5. Deduct stock + record user
redis.call("incrby", stockKey, -1);
redis.call("sadd", orderKey, userId);
return 0;
