package com.campusdeal.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param time 锁的过期时间
     * @return true代表获取锁成功
     */
    boolean tryLock(Long time);

    /**
     * 释放锁
     */
    void unLock();
}
