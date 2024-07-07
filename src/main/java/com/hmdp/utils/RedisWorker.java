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
