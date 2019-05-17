package com.skrein.redis;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;

/**
 * @Author: hujiansong
 * @Date: 2019/5/17 16:00
 * @since: 1.8
 */
@Slf4j
public class RedisDistributedLock {

    private Jedis client = new Jedis("10.191.72.200");

    private static final String REDIS_LOCK_KEY = "DISTRIBUTE::LOCK::";

    /**
     * 锁的过期时间 ms
     */
    private static final Long LOCK_EXPIRED_TIME = 60 * 1000L;


    /**
     * 请求锁的id，标识是哪个客户端获取的锁
     */
    private String reqLockId;


    public RedisDistributedLock() {
        // 使用 uuid + 当前时间戳 来生成锁的ID
        this.reqLockId = UUID.randomUUID().toString() + System.currentTimeMillis();
    }

    public void lock(String key) throws InterruptedException {
        if (!tryLock(key)) {
            waitForLock(key);
            lock(key);
        } else {
            log.info("获取锁成功!");
        }

    }

    private void waitForLock(final String key) throws InterruptedException {
        // 开启一个线程去轮询redis的key过期时间
        for (; ; ) {
            String lockKey = REDIS_LOCK_KEY + key;
            Long ttl = client.ttl(lockKey);
            if (!client.exists(lockKey) || ttl.intValue() <= 0) {
                log.info("当前锁已经过期过期时间为:{}", ttl);
                break;
            }
        }
    }


    public boolean tryLock(String key) {
        String tryLock = client.set(REDIS_LOCK_KEY + key, reqLockId, SetParams.setParams().nx().px(LOCK_EXPIRED_TIME));
        return tryLock != null && tryLock.equals("OK");
    }


    public boolean unlock(String key) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Object eval = client.eval(script, Collections.singletonList(REDIS_LOCK_KEY + key), Collections.singletonList(reqLockId));
        return "OK".equals(eval);
    }


    public static void main(String[] args) throws InterruptedException {
        String orderId = "123";
        for (int i = 0; i < 10; i++) {
            new Thread(new OrderService(orderId)).start();
        }
    }

    static class OrderService implements Runnable {

        private String orderId;

        private RedisDistributedLock lock = new RedisDistributedLock();

        public OrderService(String orderId) {
            this.orderId = orderId;
        }

        public void run() {
            try {
                lock.lock(orderId);
                log.info("do biz....");
                Thread.sleep(5000);
                log.info("do biz finished!");
            } catch (InterruptedException e) {
                log.error("lock InterruptedException", e);
            } finally {
                lock.unlock(orderId);
            }
        }
    }


}
