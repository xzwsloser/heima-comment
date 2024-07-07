package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data   // 逻辑过期时间和存储的数据
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
