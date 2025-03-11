package com.hmdp.utils;


public interface ILock {
    public boolean tryLock(long expireTime);

    public void unlock();
}
