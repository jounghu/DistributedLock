package com.skrein.zk;

import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * @Author: hujiansong
 * @Date: 2019/5/16 17:53
 * @since: 1.8
 */
@Slf4j
public class OrderService implements Runnable {

    private static AtomicLong ATOMICLONG = new AtomicLong(1);

    private static CountDownLatch COUNTDOWNLATCH = new CountDownLatch(10);

    private Lock lock = new ZkDistributedLock();

    public void run() {
        try {
            COUNTDOWNLATCH.await();
        } catch (InterruptedException e) {
            log.error("CountDownLatch error", e);
        }

        String orderCode = null;
        lock.lock();
        try {
            orderCode = getOrderCode();
        } finally {
            lock.unlock();
        }
        // do biz

    }

    public String getOrderCode() {
        Date now = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        return sdf.format(now) + ATOMICLONG.getAndIncrement();
    }

    public static void main(String[] args) {
        for (int i = 1; i <= 10; i++) {
            // 按照线程数迭代实例化线程
            new Thread(new OrderService()).start();
            // 创建一个线程，倒计数器减1
            COUNTDOWNLATCH.countDown();
        }
    }


}
