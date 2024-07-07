package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

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
