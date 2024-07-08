package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
