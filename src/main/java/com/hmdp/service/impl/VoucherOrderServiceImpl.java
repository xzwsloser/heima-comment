package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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
    @Override
    @Transactional  // 注意涉及了两张表的操作,所以需要使用事务控制
    public Result seckillVoucher(Long voucherId) {
        // 实现订单的秒杀

        // 1. 查询判断秒杀是否开始
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        // 判断库存是否充足
        if(voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
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
//        }
    }
    @Transactional  // 但是如果此时 锁就是 this,所以需要使用用户 id作为
    public  Result createVoucherOrder(Long voucherId) {

        // 实现一人一单的功能
        Long userId = UserHolder.getUser().getId(); // 注意此时还是需要注意锁的独特性，如果每一个对象
        // 不同的用户就不会被锁定

            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("该用户已经下过单了");
            }
            // 开始扣除库存 ,这就是利用乐观锁来
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0).update();
            if (!success) {
                return Result.fail("秒杀失败");
            }
            // 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 用户 ID
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            // 写入数据库
            save(voucherOrder);
            return Result.ok(orderId);
        }
}
