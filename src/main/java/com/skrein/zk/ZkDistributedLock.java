package com.skrein.zk;

import lombok.extern.slf4j.Slf4j;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.serialize.SerializableSerializer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * @Author: hujiansong
 * @Date: 2019/5/16 17:29
 * @since: 1.8
 */
@Slf4j
public class ZkDistributedLock implements Lock {

    private static final String LOCK_PATH = "/LOCK";

    private static final String ZOOKEEPER_IP_PORT = "localhost:2181";

    private ZkClient client = new ZkClient(ZOOKEEPER_IP_PORT, 4000, 4000, new SerializableSerializer());

    private String currentPath;

    private String lastPath;

    private CountDownLatch countDownLatch;

    public ZkDistributedLock() {
        if (!client.exists(LOCK_PATH)) {
            client.createPersistent(LOCK_PATH);
        }
    }

    public void lock() {
        if (!tryLock()) {
            waitForLock();
            lock();
        } else {
            log.info("获取锁成功,当前锁节点{}", currentPath);
        }
    }

    private void waitForLock() {

        // 监听lastPath
        IZkDataListener listener = new IZkDataListener() {
            public void handleDataChange(String s, Object o) throws Exception {

            }

            public void handleDataDeleted(String s) throws Exception {
                if(countDownLatch!=null){
                    countDownLatch.countDown();
                }
            }
        };

        client.subscribeDataChanges(lastPath, listener);
        // 判断前置节点是否存在
        if (this.client.exists(lastPath)) {
            countDownLatch = new CountDownLatch(1);
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                log.error("CountDownLatch await Exception", e);
            }
        }
        client.unsubscribeDataChanges(lastPath, listener);

    }

    public void lockInterruptibly() throws InterruptedException {

    }

    public boolean tryLock() {
        // 先创建一个临时有序节点
        if (currentPath == null) {
            currentPath = client.createEphemeralSequential(LOCK_PATH + "/", "lock");
            log.info("当前锁路径{}", currentPath);
        }
        // 当前路径不等于空
        List<String> children = client.getChildren(LOCK_PATH);
        Collections.sort(children);
        if (currentPath.equals(LOCK_PATH + "/" + children.get(0))) {
            return true;
        }
        // 如果第一个不是,那么拿到currentPath 前一个 path
        int i = Collections.binarySearch(children, currentPath.substring(6));
        try {
            lastPath = LOCK_PATH + "/" + children.get(i - 1);
        } catch (Exception e) {
            System.out.println(currentPath + "" + children);
        }
        return false;
    }

    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return false;
    }

    public void unlock() {
        // 删除当前节点
        log.info("释放锁{}",currentPath);
        this.client.delete(currentPath);
    }

    public Condition newCondition() {
        return null;
    }
}
