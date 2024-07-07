package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        // 首先还是查询缓存
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return Result.ok(shop);
        }
        // 没有的可以到数据库查询
        Shop shop = getById(id);
        if(shop == null) {
            return Result.fail("店铺信息不存在");
        }
        // 写入到缓存中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        // 返回数据给前端
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
}
