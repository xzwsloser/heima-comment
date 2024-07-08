# 分布式锁
- 集群下 锁就会失效,由于 JVM 中只有一个 锁监视器,所以多个JVM 要想达到锁的效果,需要一个外部的锁监视器
- 重点就是这一个锁监视器如何实现?
- 这个锁,多线程可见,并且可以实现互斥(可以使用Redis,所有服务器都可以访问到)
- 分布式锁: 满足分布式系统或者集群模式下多进程课可见并且互斥的锁,需要满足如下特点:
  - 多线程可见
  - 互斥
  - 高可用
  - 高性能
  - 安全性
  - (其他功能性特性)
![Screenshot_20240708_094251_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_094251_tv.danmaku.bilibilihd.jpg)
- 分布式锁的实现方式: 
  - 使用数据库 Mysql
  - 使用 Redis (还是使用 setnx 命令)
  - 使用 Zookeeper
![Screenshot_20240708_095119_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_095119_tv.danmaku.bilibilihd.jpg)
## 利用 Redis 实现分布式锁的实现方式
- 互斥性: 利用 setnx 命令就可以实现分布式锁,获取锁就可以使用 setnx
- 释放锁: 手动释放,超时释放(防止服务器宕机导致死锁)
- 拿到互斥锁和过期时间同时成功需要是一个原子操作，相当于在一个事务中,可以使用  set lock thread nx ex ttl 就可以了，其实就是利用 set的参数
- 非阻塞性: 尝试一次获取锁,获取失败就可以返回 false,获取到锁就返回 false 
![Screenshot_20240708_095952_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_095952_tv.danmaku.bilibilihd.jpg)
### 基于 Redis 实现分布式锁初级版本
- 实现方式
```java
public class SimpleRedisLock implements  ILock{

    private String name; // 表示业务的名称,也就表示锁的名称
    private StringRedisTemplate stringRedisTemplate;
    public SimpleRedisLock (StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    private static final String KEY_PREFIX = "lock:"; // 前缀
    @Override
    public boolean tryLock(Long expireTime) {
        // 获取锁
        String key = KEY_PREFIX + name;
        // 注意 value 需要加上线程
        long id = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key,id + "",expireTime, TimeUnit.SECONDS);
       return BooleanUtil.isTrue(success);  // 判断是否为 True
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}

```
- 使用方式
```java
       // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
        boolean flag = lock.tryLock(1200L);
        if(!flag) {
            return Result.fail("不允许重复下单");
        }

//        synchronized (userId.toString().intern()) {
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
```
- 但是以上步骤还可能有其他的问题
- 极端情况就是,如果一个线程在执行业务的过程中,由于超时释放了锁,导致其他的线程拿到了锁,但是这一个线程业务执行完毕之后又释放了锁
这就导致它释放的是前面线程正在使用的锁,此时就可能由其他的获取互斥锁并且执行自己的业务(一人一单)
![Screenshot_20240708_102523_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_102523_tv.danmaku.bilibilihd.jpg)
- 解决方案就是释放锁时需要首先取出数据判断string中的value值是否是自己的线程号
- 新的时序图如下:
![Screenshot_20240708_102741_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_102741_tv.danmaku.bilibilihd.jpg)
- 但是如何直接使用线程 ID 作为线程的标识,那么就可能导致不同的线程拥有同一个线程 ID ,所以可以使用 UUID 进行判断
- 方式互斥锁误删的情况
```java
    @Override
    public void unlock() {
        // 首先线程的判断
        // 首先获取线程标识
        String realID = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(realID.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
```
- 原子性问题: 如何判断锁标识和释放锁的动作之间还有阻塞的行为那么还是可能会有释放其他线程的锁的问题,所以需要进一步改进
- 判断锁标识和释放锁必须是一个原子操作
![Screenshot_20240708_104017_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_104017_tv.danmaku.bilibilihd.jpg)
- 很容易想到Redis中的事务,但是无法保证一致性,所以可以想到使用脚本进行操作
- 所以可以使用 Lua脚本控制原子性,同时可以使用 redis.call 调用 Lua 脚本
- 参考资料: https://www.runoob.com/lua/lua-tutorial.html
- 可以使用 EVAL "return redis.call('set','name','jack')" 0 等方式执行脚本
- 但是如果不想写死,key类型的参数会放入到 KEYS 数组中,其他的参数可以放在 ARGV 数组中
- 编写的脚本如下
```lua
-- 获取锁中的线程标识
local id = redis.call('get',KEYS[1])
-- 比较线程标识和锁中的标识是否一致
if(id == ARGV[1]) then
    return redis.call('del',key)
end
return 0      
```
- 可以使用 RedisTemplate中的 execute函数 调用 Lua脚本
```java
    @Override
    public void unlock() {
        // 注意此时就可以满足原子性了
        stringRedisTemplate.execute(UNLOCK_SCRIPT, 
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()); 
    }
```
- 脚本对象的创建方式
```java
   private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("redis_auto.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
```
- 总结：
  - 使用 set nx ex 获取锁,并且设置过期时间,保存线程标识
  - 释放锁时手下判断线程标示是否和自己一致,一致就可以删除锁
- 特性:
  - 使用set nx 满足互斥性
  - 使用 set ex 保证故障时锁依然可以释放,避免死锁,提高安全性
  - 使用 Redis 集群保证高可用和高并发特性
## 基于 Redis 的分布式锁的优化
- 之前的方法中存在的问题如下:
![Screenshot_20240708_111317_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_111317_tv.danmaku.bilibilihd.jpg)
- 主从复制也就是说Redis集群中,一个主服务器宕机了就会有另外一台从服务器顶上,他们之间的数据的复制就叫做主从复制
- 可以使用 Redission 组件解决这些问题，其中包含各种分布式中需要的各种组件，比如分布式锁等
### Redission 的使用和配置方式
- 配置方式如下:
```java
@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.59.132:6379").setPassword("808453");
        return Redisson.create(config);
    }

}
```
- 使用方式
```java
  RLock lock = redissonClient.getLock("order:" + userId);
        boolean flag = lock.tryLock();  //可以指定重试的最大等待时间,超时释放时间,没有参数就表示不尝试并且 30 s 就会过期
        if(!flag) {
            return Result.fail("不允许重复下单");
        }

//        synchronized (userId.toString().intern()) {
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
```
#### Redisson 实现可重入锁的原理
- 底层其实就是给 Redis中利用了 hash的数据结构,同时记录锁的重入次数和线程标识,但是此时删除锁的方式就是把 value - 1就可以了
![Screenshot_20240708_114357_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_114357_tv.danmaku.bilibilihd.jpg)
#### Redisson 的锁重试问题和 WatchDog 机制
- 锁的重试其实利用了 Redis 中的订阅机制
![Screenshot_20240708_151748_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240708_151748_tv.danmaku.bilibilihd.jpg)
- Redission 分布式锁的原理：
  - 可以重入: 利用 hash 结构,记录线程 id 和 重入次数
  - 利用信号量和 pubSub功能实现等待,唤醒,获取锁失败的重试机制
  - 超时预约,利用 watchDog,每隔一段时间 (releaseTime / 3) , 重置超时时间
#### Redisson 分布式锁主从一致性问题
- 主节点 Redis Master 主要用于写,Redis Slave 主要用于读,同时主节点和统计点需要保证数据的一致性,所以需要主从复制
- Redisson中改变了这一种主从关系,而是所有节点独立的,每一次服务器都会向每一个节点获取锁,同时只有三个节点都获取锁成功才可以成功获取锁
- 一个小的知识点: 可以在 junit中使用 @BeforeEach 来完成初始化工作
- 实现方式其实就是利用连锁的原理                
- Redisson的multiLock:
  - 原理: 利用多个独立的 Redis 节点,比如在所有节点都获取冲入锁，才算获取锁成功
  - 缺陷: 运维成本高,实现复杂
