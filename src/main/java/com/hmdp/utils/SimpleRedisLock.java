package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author xzw
 * @version 1.0
 * @Description
 * @Date 2024/7/8 10:03
 */
public class SimpleRedisLock implements  ILock{

    private String name; // 表示业务的名称,也就表示锁的名称
    private StringRedisTemplate stringRedisTemplate;
    public SimpleRedisLock (StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    private static final String KEY_PREFIX = "lock:"; // 前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("redis_auto.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(Long expireTime) {
        // 获取锁
        String key = KEY_PREFIX + name;
        // 注意 value 需要加上线程
        String id  = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key,id ,expireTime, TimeUnit.SECONDS);
       return BooleanUtil.isTrue(success);  // 判断是否为 True
    }

    @Override
    public void unlock() {
        // 注意此时就可以满足原子性了
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 首先线程的判断
//        // 首先获取线程标识
//        String realID = ID_PREFIX + Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(realID.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }


}
