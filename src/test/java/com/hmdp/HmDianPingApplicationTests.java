package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Resource
    private RedisWorker redisWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSaveShop() {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    public void testRedisWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisWorker.nextId("order");
                System.out.println("id = " + id );
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0 ; i < 300 ; i ++) {
            executorService.submit(task);
        }
        latch.await(); //相当于 waitgroup
        long end = System.currentTimeMillis();
        System.out.println("end - start = " + (end - start) );
    }

    @Test
    void method1(){
        RLock lock = redissonClient.getLock("lock");
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("获取锁失败,1");
            return ;
        }
        try {
            log.info("获取锁成功 , 1");
            method2(lock);
        } finally {
            log.info("释放锁,2");
            lock.unlock();
        }

    }

    void method2(RLock lock){
        boolean isLock = lock.tryLock();
        if(!isLock) {
            log.error("获取锁失败,2");
            return ;
        }
        try {
            log.info("获取锁成功,2");
        } finally {
            log.info("释放锁,2");
            lock.unlock();
        }
    }

    @Test
    void loadGEO() {
        // 1.  首先查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 把店铺信息进行分组，id一致的放在一个集合中
        // 可以使用 Map集合
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 分好组之后就可以分批写入到 Redis中了
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }

    @Test
    public void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl4",values);
            }
        }
        // 统计数量
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hl4");
        System.out.println("统计得到的数量为:" + size);
    }
}
