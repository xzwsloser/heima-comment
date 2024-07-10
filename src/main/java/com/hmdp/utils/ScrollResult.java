package com.hmdp.utils;

import lombok.Data;

import java.util.List;

/**
 * @author xzw
 * @version 1.0
 * @Description
 * @Date 2024/7/10 16:15
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
