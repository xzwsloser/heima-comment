# 优惠券秒杀业务
## 全局唯一 ID 
- 当用户抢购时,就会生成订单并且保存到 tb_voucher_order 这张表中,那么订单表中如何使用数据库自增 ID就会出现一些问题：
  - id的规律过于明显
  - 收到单表数据量的限制,分表分库时,id可能会重复
- 全局 ID 生成器,就是一种在分布式系统中用于生成全局唯一的ID 的工具,一般需要满足如下特性:
  - 唯一性
  - 高性能
  - 高可用
  - 递增性
  - 安全性
- 可以使用 redis 保证高可用性,和高性能,以及整体的自增性
- 全局 ID 生成器的ID 生成算法:
![Screenshot_20240707_113647_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_113647_tv.danmaku.bilibilihd.jpg)
### 全局唯一 ID 生成器
- 另外的实现方式 UUID , Redis 自增,或者雪花算法,或者使用数据库自增,就是重新建立一张表,这一张表就和 redis 自增差不多
```java
package com.hmdp.utils;
import cn.hutool.core.date.DateTime;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author xzw
 * @version 1.0
 * @Description 利用 Redis 生成全场唯一 ID
 * @Date 2024/7/7 11:38
 */
@Component
public class RedisWorker {
    private static final long BEGIN_TIMESTAMP = 1704067201L;
    private static final int COUNT_BITS = 32 ;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     *   用于生成不同业务的唯一 ID
     * @param keyPerfix 表示业务的前缀
     * @return
     */
    public long nextId(String keyPerfix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 注意redis的自增长还是有上限的，可以根据当前日期获取时间
        // 得到的就是序列号
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPerfix + ":" + format);
        // 2. 生成序列号
        // 注意需要通过位运算

        // 3. 并且并且返回 , 时间戳向左边移动 32 ,之后进行或运算
        return timeStamp << COUNT_BITS |  count ;
    }

}
```
## 优惠券秒杀下单功能
- 注意每一个店铺都可以发布优惠券,分为平价券和特价券
- voucher_seckill 表中就是秒杀券,并且 voucher_order 就是普通券
## 实现秒杀下单功能
- 首先需要检查秒杀是否开始,如果尚未开始或者已经结束,就无法下单
- 库存是否充足,不足就无法下单
- 流程图如下: 
![Screenshot_20240707_151834_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_151834_tv.danmaku.bilibilihd.jpg)
- 注意之后自己写业务时也一定需要画出每一个业务的流程图,根据流程图写代码,注意多张表的操作在在一起时需要加上事务控制
## 超卖问题
- 其实也是多个线程操作同一个数据时发生的线程安全问题
- 时序图如下:
![Screenshot_20240707_161647_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_161647_tv.danmaku.bilibilihd.jpg)
- 所以解决典型的多线程安全问题,针对于这一个为题的常见方式就是加锁
- 乐观锁和悲观锁的区别(其实就是一种思想,而不是真正的锁): 
![Screenshot_20240707_161857_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_161857_tv.danmaku.bilibilihd.jpg)
- 乐观锁的关键在于如何查看数据是否被修改过,常见的方式有两种:
  - 版本号法: 判断查询到的数据的version是否改变,如何被修改了就不会执行,没有改变就会执行,简化方案就是利用数据自身的改变判断版本号是否改变
  - CAS法: 直接利用数据本身的变化来判断数据是否被修改过,如果数据被修改过之后就不会执行相关的操作了
### 利用乐观锁解决问题
- 注意一下代码的弊端:
  - 虽然发生了并发修改,成功率过低,可以进行改进,可以放宽条件,注意乐观锁只是一种思想而已,不是真的锁
```java
       // 开始扣除库存 ,这就是利用乐观锁来
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id",voucherId)
                .eq("stock",voucher.getStock()).update();
```
- 改进方式
```java
        // 开始扣除库存 ,这就是利用乐观锁来
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id",voucherId)
                .gt("stock",0).update();
```
- 悲观锁:添加同步锁,让线程穿行执行
  - 优点: 简单粗暴
  - 缺点: 性能一般
- 乐观锁: 不加锁,在更新时自动判断是否有其他线程在修改
  - 优点: 性能好
  - 缺点: 存在成功率低的情况 
## 实现一人一单的功能
- 一人一单的实现思路如下:
![Screenshot_20240707_163754_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_163754_tv.danmaku.bilibilihd.jpg)
- 注意如果不加锁,还是会出现多个线程操作同一个数据时发生的并发安全问题,就是如果另外一个线程没有把库存生成订单,那么此时查出来的还是没有结果
所以这一段逻辑也需要加锁解决,但是由于乐观锁只适用于更新数据，所以这里需要使用悲观锁
- 明确需要封装哪一个逻辑 ctrl + alt + m 可以把代码抽成一个方法
- 代码实现如下:
```java
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource RedisWorker redisWorker;
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
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
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

```
- 这一个代码中有很多细节:
  - 首先锁的位置需要确定：
    - 如果锁加在方法上面就会倒是锁的对象就是 this,所以所有用户都会共用一个锁就不符合要求
    - 如果把锁加载方法里面就导致锁失效的时候还没有提交事务，可能有其他的线程此时会执行事务
    - 所以需要把锁加载外面的代码的那一部分
  - 但是如果把锁加载了外面代码的哪一个部分，此时由于 spring是通过动态代理的方式来管理事务，但是此时的this中没有相应的方法，所以需要在
  外部创建代理对象，利用 AopContext.currentProxy()创建代理对象解决问题
## 分布式情况下的并发安全问题
- 就是同时向两个服务器发送请求,由于两个服务器的 JVM 不同,所以监视器不同,所以就会发生线程安全问题
![Screenshot_20240707_173738_tv.danmaku.bilibilihd.jpg](..%2Fimg%2FScreenshot_20240707_173738_tv.danmaku.bilibilihd.jpg)


