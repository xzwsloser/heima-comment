# 商品缓存查询
## 了解缓存
- 缓存就是数据交换的缓冲区(称为Cache),是存储数据的临时地方,一般读写性能比较高
- 缓存的应用场景如下(应用层缓存就是java程序中利用数据结构存储的数据,比如 spring中的缓存):
![Screenshot_20240706_162100_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_162100_tv.danmaku.bilibilihd.jpg)
- 缓存的作用和成本:
  - 缓存的作用: 
    - 降低后端负载
    - 提高读写效率,降低响应时间
  - 缓存的成本:
    - 数据不一致
    - 代码维护成本(缓存雪崩,缓存穿透)
    - 运维成本(缓存的集群)
## 添加商品缓存
- 就是在数据库前面加一个缓冲,缓存模型如下(梳理程序执行流程时,一定需要花流程图理解):
![Screenshot_20240706_163141_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_163141_tv.danmaku.bilibilihd.jpg)
- 查询缓存并且添加缓存的代码逻辑如下:
```java
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        // 1. 从 Redis 中查询商品缓存
        String key = CACHE_SHOP_KEY + id;  // 注意 key一定需要时唯一的
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,转换为对象返回
            // 3. 存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4. 没有的话就需要在数据库中查询缓存
        Shop shop = getById(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 5. 存在就可以写入到 redis
        // 使用字符串作为 value 进行存储,写入到缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        // 6. 返回
        return Result.ok(shop);
    }
}
```
- 如何使用list结构构建缓存如下:
```java
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryForShopType() {
        // 首先查询缓存
        String  key = SHOP_TYPE_LIST;
        List<String> shopList = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 获取的就是所有商铺的信息
        if(shopList != null && !shopList.isEmpty()) {
            // 可以返回给前端
            // 转化为列表,之后返回给前端
            List<ShopType> shopTypes = new ArrayList<>();
            for (String shop : shopList) {
                // 序列化
                ShopType shopType = JSONUtil.toBean(shop, ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);  // 注意这里排序应该是之前的工作,而不是之后的工作
        }
        // 没有查询到数据
        List<ShopType> shops = query().orderByAsc("sort").list();
        // 之后放入到 Redis 缓存中
        // 首先还是序列化为字符串
        // 直接一个一个放入
        for (ShopType shop : shops) {
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForList().rightPush(key,jsonStr);
        }
        // 最后返回就可以了
        return Result.ok(shops);
    }
}
```
## 缓存更新策略
- 缓存的更新策略: 
  - 内存淘汰
  - 超时剔除
  - 主动更新
- 三种策略的一致性和维护成本也各不相同
![Screenshot_20240706_171756_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_171756_tv.danmaku.bilibilihd.jpg)
- 低一致性需求: 可以使用内存淘汰机制,例如商铺类型的查询缓存
- 高一致性需求: 主动更新,并且以超时的剔除作为兜底方案,例如店铺详情缓存的查询

- 主动更新策略
- 主要解释一下第三种解决方案: 就是调用者只是操作缓存,线程监视缓存的变化,最后把缓存中的数据持久化到数据库中
- 最好还是在更新数据库时重新更新缓存
- 方法1的三个问题:
  - 删除缓存还是更新缓存:
    - 更新缓存: 每一次更新都会时缓存更新,可能操作无效缓存
    - 删除缓存: 更新数据库使得缓存失效,查询时更新缓存 , 着一种方法有效的缓存此时更多
  - 如果保证缓存与数据库的操作的同时成功和失败：
    - 单体项目中,把缓存和数据库操作放在一个事务中
    - 分布式系统,使用 TCC等分布式事务方案
  - 先操作缓存还是先操作数据库
    - 先删除缓存,在操作数据库(可能发生线程安全问题,就会使得缓存中的数据没有更新)
    - 先更新数据库,之后删除缓存(线程安全问题发生的可能性不高)(一般使用这一个)
![Screenshot_20240706_172333_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_172333_tv.danmaku.bilibilihd.jpg)
![Screenshot_20240706_173325_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_173325_tv.danmaku.bilibilihd.jpg)
缓存更新策略的最佳实践方案:
    1. 低一致性需求: 使用 Redis 自带的内存淘汰机制
    2. 高一致性需求: 主动更新,并且使用超时剔除作为兜底方案
       - 读操作：
         - 缓存命中,直接返回
         - 缓存没有命中,从数据库中查询数据并且返回
       - 写数据(考虑线程安全问题):
         - 先写数据库,之后删除缓存
         - 要确保数据库和缓存操作的原子性(单体项目中,放在一个事务中)
缓存更新的最佳实践:
```java
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        // 1. 从 Redis 中查询商品缓存
        String key = CACHE_SHOP_KEY + id;  // 注意 key一定需要时唯一的
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,转换为对象返回
            // 3. 存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4. 没有的话就需要在数据库中查询缓存
        Shop shop = getById(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 5. 存在就可以写入到 redis
        // 使用字符串作为 value 进行存储,写入到缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6. 返回
        return Result.ok(shop);
    }

    @Override
    @Transactional  // 控制事务的原子性
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("商铺 ID 不存在");
        }
        // 首先更新数据库,删除缓存
        boolean res = updateById(shop);
        if (res) {
            // 更新成功删除缓存
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
            return Result.ok();
        }
        return Result.fail("更新缓存失败");
    }
}
```
## 缓存穿透
- 缓存穿透: 就是指的客户端请求的数据在缓存和数据库中都不存在,这样缓存永远不会生效,这些请求都会到达数据库中
- 解决缓存穿透: 
  - 缓存空对象(一般使用这一种方案)
  - 布隆过滤器(利用一种映射关系,可以近似访问所有数据)
![Screenshot_20240706_175951_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_175951_tv.danmaku.bilibilihd.jpg)
- 第一种方案的解决方式:
![Screenshot_20240706_180134_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_180134_tv.danmaku.bilibilihd.jpg)```java
- 缓存穿透的实现方式(第一种解决方式):
```java
    @Override
public Result queryById(Long id) {
  // 1. 从 Redis 中查询商品缓存
  String key = CACHE_SHOP_KEY + id;  // 注意 key一定需要时唯一的
  String shopJson = stringRedisTemplate.opsForValue().get(key);
  // 2. 判断是否存在
  if (StrUtil.isNotBlank(shopJson)) {
    // 存在,转换为对象返回
    // 3. 存在直接返回
    // 判断是否为空
    Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    return Result.ok(shop);
  }
  // 判断是否命中空值
  if (shopJson != null) {
    return Result.fail("店铺不存在");
  }
  // 4. 没有的话就需要在数据库中查询缓存
  Shop shop = getById(id);
  if (shop == null) {
    // 就存入空值
    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);  // 表示存储空值到 Redis 中
    return Result.fail("店铺不存在");
  }
  // 5. 存在就可以写入到 redis
  // 使用字符串作为 value 进行存储,写入到缓存中
  stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
  // 6. 返回
  return Result.ok(shop);
}
```
- 另外的方式:
  - 缓存 null
  - 布隆过滤
  - 增强 id的复杂度
  - 做好数据的基础格式校验
  - 加强用户的权限校验
  - 做好热点参数的限流
## 缓存雪崩
- 缓存雪崩就是指的同一个时间段的缓存key同时失效,或者 Redis服务器宕机,导致大量请求达到数据库,带来巨大的压力
- 解决方案:
  - 给不同的 key 添加 TTL 随机值
  - 利用 Redis 集群提高服务的高可用性
  - 给缓存业务添加降级限流策略
  - 给业务添加多级缓存
![Screenshot_20240706_181704_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240706_181704_tv.danmaku.bilibilihd.jpg)
## 缓存击穿
- 缓存击穿问题也叫做热点 key 问题,就是一个被高并发访问,并且缓存重建业务比较复杂的key突然失效了,无数的访问就会在瞬间给数据库带来巨大的压力
- 解决方案:
  - 互斥锁
  - 逻辑过期
![Screenshot_20240707_085706_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_085706_tv.danmaku.bilibilihd.jpg)
- 就是不要多个线程同时重建缓存,只有获取到锁的线程才可以缓存重建,但是最大的问题就是线程一起等待,使得性能下降
- 另外一种方法就是逻辑更新,就是利用一个字段指定过期时间,这一个过期时间不是真的过期时间,其实数据会长期储存,只是如果没有在有效期里面,逻辑过期实践就会更新
- 两种方法的时序图如下:
![Screenshot_20240707_090316_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_090316_tv.danmaku.bilibilihd.jpg)
- 两种方法比较:
  - 互斥锁:
    - 优点: 
      - 没有额外的内存消耗
      - 保证一致性
      - 实现简单
    - 缺点:
      - 线程需要等待,性能收到影响
      - 可能有死锁问题
  - 逻辑过期:
    - 优点:
      - 线程不需要等待,性能比较好
    - 缺点：
      - 不保证一致性
      - 有额外内存小号
      - 实现复杂
### 基于互斥锁方案解决缓存击穿问题
- 利用互斥锁解决缓存击穿问题的逻辑图: 
![Screenshot_20240707_091009_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_091009_tv.danmaku.bilibilihd.jpg)
- 注意这里的互斥锁如何实现? 锁的广义要求就是只有一个线程可以获取执行权,其他的线程不可以获取执行权,执行必定失败,如果使用 java
中的互斥锁 ,那么无法控制两面性,所以可以使用 redis 中string数据类型中的 setnx 方法,利用着一种方法使得只有第一个操作数据的线程可以成功,其他的线程就不会成功
- 利用互斥锁解决缓存击穿问题的代码实现:
```java
    public Shop queryWithPassThrough(Long id){
        // 1. 从 Redis 中查询商品缓存
        String key = CACHE_SHOP_KEY + id;  // 注意 key一定需要时唯一的
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,转换为对象返回
            // 3. 存在直接返回
            // 判断是否为空
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断是否命中空值
        if (shopJson != null) {
            return null;
        }
        // 实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY;
        Shop shop = null;
        // 注意 try 的含义
        try {
            boolean flag = tryLock(lockKey);
            // 4.2 判断是否成功
            if(!flag) {
                // 失败就可以休眠并且重试
                Thread.sleep(50);
                return queryWithPassThrough(id);
            }
            // 4.3 失败就可以休眠并且充实
            // 4.4 成功就可以写入数据
            // 4. 没有的话就需要在数据库中查询缓存
            shop = getById(id);
            if (shop == null) {
                // 就存入空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);  // 表示存储空值到 Redis 中
                return null;
            }
            // 5. 存在就可以写入到 redis
            // 使用字符串作为 value 进行存储,写入到缓存中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            clearLock(lockKey);
        }
        // 6. 返回
        return shop;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag); // 返回是否成功
    }
    private boolean clearLock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }
```
### 基于逻辑过期实现缓存击穿问题
- 注意使用场景就只是针对于热点数据,并且热点数据都是提前存储到Redis中的,所以如果没有查询到数据就说明不是热点数据,就直接返回就可以了
- 注意对象的封装思想
```java
    public Shop queryThroughLogicalExpire(Long id){
        // 1. 从 Redis 中查询商品缓存
        String key = CACHE_SHOP_KEY + id;  // 注意 key一定需要时唯一的
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在,没有命中就可以直接返回
        if (StrUtil.isBlank(shopJson)) {
            // 存在,转换为对象返回
            // 3. 存在直接返回
            // 判断是否为空
            return null;
        }

        // 4.1 命中首先进行反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        // 5. 是否过期
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.1 没有过期就可以直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 没有过期,就是在后面时间的后面
            return shop;
        }
        // 5.2 已过期,需要缓存重建
        String lockKey = LOCK_SHOP_KEY;
        // 6. 缓存重建
        // 判断锁是否拿到
        boolean flag = tryLock(key);
        // 6.2 判断获取锁是否成功
        if(flag) {
            // 6.1 获取互斥锁
            // 开启线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    this.clearLock(lockKey);
                }
            });
        }
        // 6.3 成功就可以开启独立线程,实现缓存重建
        // 6.4 返回过期的商品信息
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag); // 返回是否成功
    }
    private boolean clearLock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }
    public  void saveShop2Redis(Long id,Long expireSeconds) {
        // 1. 查询店铺
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 2. 写入到 Redis 中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY,JSONUtil.toJsonStr(redisData));
    }
```
## 缓存工具封装
- 缓存工具需要解决如下问题:
  - 可以把java对象反序列化成json存储到Redis中,并且可以设置过期时间
  - 可以将任意的 java对象序列化成json并且存储到 string 类型的key中,并且设置逻辑过期时间,用于解决缓存击穿的问题
  - 根据 key 查询缓存,并且反序列化成指定类型,并且利用缓存空值的方式解决缓存穿透问题
  - 根据指定的key查询缓存,并且反序列化成指定类型,利用逻辑过期时间解决缓存击穿问题
- 重点就是如何使用 java 中的泛型的使用,自己复习一下:
```java
package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import sun.text.resources.ext.JavaTimeSupplementary_es_US;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author xzw
 * @version 1.0
 * @Description
 * @Date 2024/7/7 10:09
 */
@Component
public class CacheClient {

    @Resource
    private static StringRedisTemplate stringRedisTemplate;

    private static ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *  设置过期时间,解决缓存问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 通过设置逻辑过期时间解决缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicExpire(String key , Object value,Long time ,TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        LocalDateTime localDateTime = LocalDateTime.now().plusSeconds(unit.toSeconds(time));
        redisData.setExpireTime(localDateTime);
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPerfix , ID id,Class<R> type , Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        String key = keyPerfix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json,type);
        }
        if(json != null) {
            return null;  // 错误信息
        }
        R r = dbFallback.apply(id);
        if(r == null) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回 null
            return null;
        }
        // 存在就可以写入 redis
        this.set(key,r,time,unit);
        return r ;
    }

    /**
     *
     *  利用逻辑过期的时间,利用key值取出逻辑过期时间,注意泛型的调用,调用时可以自动类型推导
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time ,TimeUnit unit) {
        // 首先查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)) {
            // 直接返回 null
            return null;
        }
        // 判断过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = BeanUtil.toBean(redisData.getData(),type);
        // 取出过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 没有过期直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 首先还是要拿出数据来
            Object data = redisData.getData();
            // 转换为 R 类型返回
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取到锁
        boolean flag = tryLock(lockKey);
        // 说明没有抢到互斥锁
        if(!flag) {
            return r;
        }
        try {
            // 过期了就可以在数据库中查询数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 首先在数据库中查询
                R obj = dbFallback.apply(id);
                // 之后进行数据的存储
                this.setWithLogicExpire(key, obj , time ,unit);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            clearLock(lockKey);
        }
        return r;
    }

    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    public void clearLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
```
## 缓存总结
- 注意每一种情况下各种解决方案的弊端和优点,可以根据不同的业务场景确定不同的应对策略
- 注意各种情况下的最佳实践方案 ! ! ! 

- 注意缓存的定义
- 如何利用添加 Redis 缓存的方式提高查询效率(key? , value? , 颗粒度?)
- 缓存更新策略如何实现(缓存一致性,三种策略,如何保证一致性)
- 什么是缓存穿透,如何解决(特别需要注意应对何种情况,高并发情况下热点 key 的重建问题)
- 什么是缓存雪崩,如何解决缓存雪崩
- 什么是缓存击穿,如何利用两种方法解决缓存击穿问题