# 利用  Redis 消息队列实现异步秒杀
## 消息队列介绍
- 消息队列其实就是存放消息的队列,最简单的消息队列包含3个角色:
  - 消息队列: 存储和管理消息,也成为消息代理(Message Broker)
  - 生产者: 发送消息到消息队列
  - 消费者: 从消息队列中获取消息并且处理消息
- 由于消息队列独立于 JVM 虚拟机而存在,所以不会消耗  JVM 的资源
- Redis中可以利用三种不同的方式实现消息队列:
  - list 结构: 基于 list 结构模拟消息队列
  - PubSub: 脚本的点对点消息模型
  - Stream: 比较完善的消息队列模型
![Screenshot_20240709_114434_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_114434_tv.danmaku.bilibilihd.jpg)
### 基于List实现消息队列
- 队列和栈的区别不用多说了吧,主要看出口和入口是否是一个位置
- 之前学习过的命令 BLPUSH 和 BRPOP 就可实现阻塞队列的效果,其实就是生产者向队列中存入元素,消费者向队列中取出元素
- 好处:
  - 利用 Redis 存储,不会受限于 JVM 内存上限
  - 基于 Redis 的持久化机制,数据安全性有保证
  - 可以满足消息有序性
- 缺点:
  - 无法避免消息丢失
  - 只支持单消费者
### 基于 PubSub 实现消息队列
- PubSub是 Redis2.0 版本引入的消息传递模型,就是消费者可以订阅一个或者多个 channel , 生产者向对应的 channel 发送消息之后,所有订阅者就可以收到相关的消息
- SUBSCRIBE(subscribe) channel [channel] : 订阅一个或者多个频道
- PUBLISH channel msg : 向一个频道发送消息(返回的就是得到了消息的订阅者的数量)
- PSUBSCRIBE(psubscribe) :订阅和pattern 格式匹配的所有频道(可以支持通配符: ? , * [a-z]等都可以)
- 所以可以实现一条消息被一个消费者或者多个消费者拿到
- 优点:
  - 采用发布订阅模型,支持多生产,多消费
- 缺点:
  - 不支持数据持久化
  - 无法避免消息丢失
  - 消息堆积上限,超出时数据丢失
### 基于 Stream 的消息队列
- 发送消息 xadd 
- xadd 的使用方法和参数列表
  - xadd 键值 \[ 如果队列不存在,是否自动创建队列 \] \[设置消息队列的\] *(如何设置 ID) field key(可以设置为 Entry ,可以设置多组键值对)
- 使用方式如下:
![Screenshot_20240709_121328_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_121328_tv.danmaku.bilibilihd.jpg)
- 最简单的使用方式: xadd user * name jack 
- 读取消息 xread
- 命令的形式: xread \[每一次读取消息的最大数量\] \[没有消息时,是否阻塞,阻塞时长\] 从哪一个队列中读取消息 其实 ID(只会返回大于这一个 ID 的消息)
- xlen 可以查看队列长度
- 如果读取最新的消息可以使用 $ , 
![Screenshot_20240709_121945_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_121945_tv.danmaku.bilibilihd.jpg)
- 比如 xread的一条命令：
  - xread count 1 block 0 streams s1 $ 表示从 s1 通道中读取第一条消息,并且采用阻塞模式,其实就是读取id大于指定 id的几条数据
- 特点：
  - 消息可以回溯
  - 一个消息可以被多个消费者读取
  - 可以阻塞读取
  - 有消息漏读的风险(就是处理消息的过程中,没有收到消息,最后阻塞性的读取之后收到最后一条消息)
#### 基于 Stream 的消息队列 - 消费者组
消费者组: 将多个消费者组分到一个分组中,监听同一个队列,消费者组的特点如下:
![Screenshot_20240709_152314_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_152314_tv.danmaku.bilibilihd.jpg)
- 消息分流,消息标示,消息确认
- 创建消费者组: xgroup create key groupName ID \[MKSTREAM\]
- 比如 xgroup create s1 g1 0  队列中的消息想要就可以从 0 开始否则就从 $ 开始
- key 队列名称 
- groupName: 消费者组名称
- ID: 起始ID标识,$表示队列中的最后一个消息，0表示队列中的第一个消息
- MKSTREAM: 队列不存在时自动创建队列
- 另外的对于消费者组的操作方法如下:
![Screenshot_20240709_152851_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_152851_tv.danmaku.bilibilihd.jpg)
- 从组里面读取参数:
- xreadgroup group group consumer \[COUNT count\] \[BLOCK milliseconds\] \[NOACK\] STREAMS key \[key ...\] ID \[ID ...\]
![Screenshot_20240709_153237_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_153237_tv.danmaku.bilibilihd.jpg)
- 比如 xreadgroup group g1 c1(没有的话会自动创建) count 1 block 2000 streams s1 
- xpending 就是指定pendinglist中的消息值就可以
- 一个消费者中只有一个标记,所以读取时就可以读取到标记之后的值 > 就是没有处理的消息
- 可以使用 xack key groupName ID 就可以确认了：
  - \> 表示从下一个没有消费的消息开始
  - 其他: 根据指定的 id 从 pending-list中获取已经消费但是没有确认的消息，比如 0 , 就是从 pending-list 中读取第一个消息开始
- 利用 > 就可以读取到没有处理的消息,但是如果消息处理出现了异常就会在 pendinglist中,就可以在 pendinglist中查看,最后改为 0 就表示pending list 中的消息
- 可以使用 xack 进行处理消息
- 消息处理方式还是自己总结一下:
  - 首先利用 xgroup add 提供消息队列并且创建消息组 0 表示消息队列中的消息都需要,> 表示从当前消息开始
  - 使用 xreadgroup group g1 c1 count 1 block time streams key >(0) : 如果使用 > 表示读取没有处理的第count个消息，如果使用 0 表示读取 pending-list 中的消息
  起始队列中的消息被储存在pending-list中了,利用 0 就可以查看
  - 处理完成之后可以使用 xack s1 g1 ID 处理消息，被处理的消息就会在 pending-list 中移除
  - 同时可以使用 xpending 读取 pending-list中的消息,甚至可以做消息的大小限定
- 最佳实践就是,利用 > 不断监听消息,如果监听到消息就可以利用 xack 处理，但是如果监听到异常消息那么使用 0 从 pending-list中读取异常的消息,
  进一步处理利用xack 确认，如此往复就可以了
- stream 类型消息队列的xreadgroup 命令特点：
  - 消息可以回溯
  - 可以多消费者争抢消息，加快消费速度
  - 可以阻塞读取
  - 没有消息漏读风险
  - 有消息确认机制,保证消息至少被消费一次
- 三种消息队列模式的对比
![Screenshot_20240709_154257_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240709_154257_tv.danmaku.bilibilihd.jpg)
#### Stream实现的消息队列实现的消息队列
- 任务内容:
  - 创建一个Stream类型的消息队列,名为 stream.orders
  - 修改之前的秒杀下单 Lua 脚本,在认定有抢购资格之后,直接向 Stream.orders中添加消息，内容包含 voucherId,userId,orderId
  - 项目启动时，开启一个线程任务，尝试读取stream.orders中的内容，完成下单
- 任务1: xgroup CREATE stream.orders g1 0 MKSTREAM
- 任务2:
```lua
-- 1 参数列表
-- 优惠券 id
local voucherId = ARGV[1]
-- 用户 id
local userId = ARGV[2]
-- 订单 id
local orderId = ARGV[3]

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
-- 发送消息到队列中
-- XADD stream.orders * k1 v1 k2 v2 k3 v3
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
```
- 任务3(还是重点看一下我上面的总结)
```java
private class VoucherOrderHandler implements  Runnable {
    String queueName = "stream.orders";
    @Override
    public void run() {
        // 不断从队列中取出信息
        while(true) {
            try {
                // 1. 获取消息队列中的消息
                // XREADGROUP
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed() ) // 相当于 >
                );

                // 2. 判断是否可以确认成功
                if(records == null || records.isEmpty()) {
                    continue;
                }
                // 3. 如果获取成功，就可以下单
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 4.ACK 确认
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                log.error("处理订单异常");
                handlePendinglist();
            }
        }
    }

    private void handlePendinglist() {
        // 不断从队列中取出信息
        while(true) {
            try {
                // 1. 获取消息队列中的消息
                // XREADGROUP
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0") ) // 相当于 0
                );

                // 2. 判断是否可以确认成功
                if(records == null || records.isEmpty()) {
                    break; // list中已经没有消息了
                }
                // 3. 如果获取成功，就可以下单
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 4.ACK 确认
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                log.error("处理订单异常");  // 这里就会直接结束
            }
        }
    }
}
```
