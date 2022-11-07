package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate redisTemplate;
    private final static String KEY_PREFIX = "lock:";
    private final static String ID_PREFIX = UUID.randomUUID().toString(true);
    private final static DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static {

        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(Long time) {
        //以当前线程id为value
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁 设置有效期为time L 单位s
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, time, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {

      redisTemplate.execute(UNLOCK_SCRIPT,
              Collections.singletonList(KEY_PREFIX + name),
              ID_PREFIX+Thread.currentThread().getId());

    }
//    @Override
//    public void unLock() {
//        //获取线程id
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //获取redis中存储的线程id
//        String id = redisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断redis中锁是否为当前线程持有
//        if (threadId.equals(id)){
//            // 是 释放锁
//            redisTemplate.delete(KEY_PREFIX+name);
//        }
//
//    }
}
