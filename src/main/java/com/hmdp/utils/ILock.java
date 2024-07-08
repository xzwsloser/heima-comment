package com.hmdp.utils;

/**
 * @author xzw
 * @version 1.0
 * @Description
 * @Date 2024/7/8 10:01
 */
public interface ILock {
    /**
     * 非阻塞的尝试获取互斥锁
     * @param expireTime  过期实现 sec为单位
     * @return
     */
    boolean tryLock(Long expireTime);

    /**
     *  释放互斥锁
     */
    void unlock();
}
