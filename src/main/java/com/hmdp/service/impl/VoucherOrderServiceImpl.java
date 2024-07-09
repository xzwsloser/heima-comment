package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource RedisWorker redisWorker;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);  // 1M
    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private class VoucherOrderHandler implements  Runnable {
        @Override
        public void run() {
            // 不断从队列中取出信息
            while(true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucher) {
        // 获取用户
        Long userId = voucher.getUserId();  // 这里不是一个线程,所以不可以用 ThreadLocal
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean flag = lock.tryLock();  //可以指定重试的最大等待时间,超时释放时间,没有参数就表示不尝试并且 30 s 就会过期
        if(!flag) {
            log.error("不可以重复下单");
            return ;
        }

//        synchronized (userId.toString().intern()) {
        try {
           proxy.createVoucherOrder(voucher);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
//        }
    }

    @PostConstruct  // 表示初始化之后就可以进行初始化了
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//    @Override
//    @Transactional  // 注意涉及了两张表的操作,所以需要使用事务控制
//    public Result seckillVoucher(Long voucherId) {
//        // 实现订单的秒杀
//
//        // 1. 查询判断秒杀是否开始
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        // 判断库存是否充足
//        if(voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean flag = lock.tryLock();  //可以指定重试的最大等待时间,超时释放时间,没有参数就表示不尝试并且 30 s 就会过期
//        if(!flag) {
//            return Result.fail("不允许重复下单");
//        }
//
////        synchronized (userId.toString().intern()) {
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
////        }
//    }

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
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        // 把这一个对象放入到阻塞队列中
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 为 0 就表示有购买资格
        return Result.ok(orderId);
    }
    @Transactional  // 但是如果此时 锁就是 this,所以需要使用用户 id作为
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // 实现一人一单的功能
          Long userId = voucherOrder.getUserId(); // 注意此时还是需要注意锁的独特性，如果每一个对象
        // 不同的用户就不会被锁定

            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
            if (count > 0) {
                log.error("用户已经下过单了");
            }
            // 开始扣除库存 ,这就是利用乐观锁来,注意一定需要在数据库中剪掉订单
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder)
                    .gt("stock", 0).update();
            if (!success) {
               log.error("秒杀失败");
            }
            // 写入数据库
            save(voucherOrder);

        }
}
