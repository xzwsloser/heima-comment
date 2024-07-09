-- 1 参数列表
-- 优惠券 id
local voucherId = ARGV[1]
-- 用户 id
local userId = ARGV[2]
-- 数据 key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单 key
local orderKey = 'seckill:order' .. voucherId
-- 脚本业务
if(tonumber(redis.call('get',stockKey)) <= 0) then
    -- 库存不足返回 1
    return 1
end
-- 判断用户是否下单
-- 利用 set 集合
-- 利用 SISMEMBER orderKey userId
if(redis.call('sismember',orderKey,userId) == 1) then
    -- 存在就表示重复下单
    return 2
end
-- 扣减库存
redis.call('incrby',stockKey,-1)
-- 下单,就是保存用户到 Redis 中
redis.call('sadd',orderKey,userId)
return 0