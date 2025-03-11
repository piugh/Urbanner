package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.Data;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class RedisLock implements ILock {
    private String name;
    private static final String KET_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    private StringRedisTemplate stringRedisTemplate;

    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long expireTime) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KET_PREFIX + name, ""+threadId, expireTime,
                TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String lockId = stringRedisTemplate.opsForValue().get(KET_PREFIX + name);
        if(threadId.equals(lockId)){
            stringRedisTemplate.delete(KET_PREFIX + name);
        }
    }
}
