# 秒杀业务的优化
## 异步秒杀思路
- 由于操作数据库的操作过多,所以对于并发的处理比较差,就是所有的操作都是由一个线程执行的
- 利用一个线程判断是否由秒杀资格,开启另外一个线程操作数据库
- 但是如果把两个操作串联执行，那么还是会导致操作的耗时长,耗时其实就是用户发送请求到返回数据的这一段事件,
所以可以使用一个线程提前判断秒杀库存,校验一人一单,之后把各种 id 存储到阻塞队列中,另外的线程来读取阻塞队列中的信息就可以了,有一点像生产者消费者机制
![Screenshot_20240709_093740_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_093740_tv.danmaku.bilibilihd.jpg)
- 首先可以把库存存入到Redis中,每一次下单就可以把库存 - 1 , 同时用户秒杀成功之后就可以把用户的 ID 放入到一个 set 集合中,之后只用判断 set集合中是否有用户 ID 
- 同时注意保证扣除库存和把用户ID加入库存的原子性(可以使用 Lua 脚本)
![Screenshot_20240709_094424_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_094424_tv.danmaku.bilibilihd.jpg)
### 异步秒杀优化实现
需要实现的功能:
    - 新增秒杀优化券的同时,把优惠券信息保存到 Redis 中
    -  基于 Lua 脚本,判断秒杀库存,一人一单,决定用户是否抢购成功
    - 如果抢购成功,将优惠券 id 和用户 id 封装之后存入到阻塞队列中
    - 开启线程任务,不断从阻塞队列中获取信息,实现异步下单功能
- 第一个步骤的实现方式:
```java
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀业务到 Redis 中,后面其实就是库存
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId() , voucher.getStock().toString());

    }
```
- 第二个步骤的实现方式,使用 Lua 脚本
```lua
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
```
```java
    @Override
    @Transactional  // 注意涉及了两张表的操作,所以需要使用事务控制
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 首先执行 Lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2. 判断是否为 0
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不可以重复下单");
        }
        // 不为 0 表示没有购买资格否则就是有购买资格
        long orderId = redisWorker.nextId("order");
        // 保存到阻塞队列
        // 为 0 就表示有购买资格
        return Result.ok(orderId);
    }
```
- 第三步实现方式:
[VoucherOrderServiceImpl.java](..%2Fsrc%2Fmain%2Fjava%2Fcom%2Fhmdp%2Fservice%2Fimpl%2FVoucherOrderServiceImpl.java)
- 注意最后为什么代理对象需要重新传入,原因还是代理对象底层还是通过 ThreadLocal 获取信息的,所以需要重新传入,一定需要注意ThreadLocal 到底是哪一个的 ThreadLocal
