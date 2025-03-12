package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RedisLock implements ILock {
    private String name;
    private static final String KET_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    //构建lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("luas/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数
     * @param name
     * @param stringRedisTemplate
     */
    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取锁---基于redis getnx获取锁
     * @param expireTime
     * @return
     */
    @Override
    public boolean tryLock(long expireTime) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KET_PREFIX + name, ""+threadId, expireTime,
                TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁---基于lua脚本（先查询是否该线程的锁，后决定是否删除锁）
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KET_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

}
