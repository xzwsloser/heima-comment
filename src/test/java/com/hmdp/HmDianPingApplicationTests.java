package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Resource
    private RedisWorker redisWorker;
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

}
