package com.hmdp.config;

import io.lettuce.core.RedisClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author xzw
 * @version 1.0
 * @Description 配置 Redisson 进行配置
 * @Date 2024/7/8 11:22
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.59.132:6379").setPassword("808453");
        return Redisson.create(config);
    }
}
