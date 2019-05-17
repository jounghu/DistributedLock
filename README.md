### 要弄清楚分布式锁，先来了解什么是分布式锁？

同一个进程内的锁: 让线程按照指定的顺序去执行方法或者代码块，保证线程原子的进行

不同进程: jvm层面的锁就不管用了，那么可以利用第三方的一个组件，来获取锁，未获取到锁，则阻塞当前想要运行的线程。


场景1:

```text
A: 银行存款200

读取到200，存入100，得到 200+100=300


B: 同一账号 

读取到200，取出100，得到 200-100 = 100

最终账号里面应该是200不变，A得到300，B得到200，A-B 数据都出现不一致

```

所以我们可以使用分布式锁，让A，B两个进程顺序进行，这样B读取账号的时候就读到300，然后顺序进行最终数据是一致的。

场景2：

```text
现在任务池里面有一堆任务，A，B都需要从任务池里面获取任务，当获取到任务的时候，把获取的任务从任务池里面删除，如果不作同步处理，那么A,B就可能获取到同一个任务
```

我们可以使用分布式锁，保证获取任务顺序进行。


### 为什么zk能实现分布式锁

`Zookeeper`是什么？

其实zookeeper可以理解为一个类似liunx的文件结构，里面的核心数据结构是node节点，然后可以存放少量数据。

如下图:

![zk文件结构](http://ww1.sinaimg.cn/large/005RZJcZgy1g343xrs0aej30f907aaaj.jpg)

zk的node节点有四种属性

node创建模式 | 中文解释|说明
---|---|---|
PERSISTENT |持久节点| znode不会自动删除，即使客户端断开连接
PERSISTENT_SEQUENTIAL|持久顺序节点 | znode不会自动删除，并且名字会自动递增
EPHEMERAL | 临时节点|znode自动删除，在客户端断开连接
EPHEMERAL_SEQUENTIAL|临时有序节点 | znode自动删除，并且名字会自动递增

可见zk的node特性，当我们创建节点的时候，可以指定创建模式，当指定`EPHEMERAL_SEQUENTIAL`的节点，会生成一个有序的节点，当我们获取锁的时候，可以判断，当前节点是否为最小节点，如果是，那么就认为获取锁成功，如果不是，那么我们监听比当前节点的前一个节点，当前一个节点放弃锁的时候，zk通过watcher机制，会通知客户端，这样当前节点就可以顺利获取锁了。当解锁的时候，delete掉当前节点就好了。


### 如何利用zk实现分布式锁

前面的理论知识，那么我们就可以用代码实现


```java
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
public class DistributedLock implements Lock {

    private static final String LOCK_PATH = "/LOCK";

    private static final String ZOOKEEPER_IP_PORT = "localhost:2181";

    private ZkClient client = new ZkClient(ZOOKEEPER_IP_PORT, 4000, 4000, new SerializableSerializer());

    private String currentPath;

    private String lastPath;

    private CountDownLatch countDownLatch;

    public DistributedLock() {
        // 新建锁的时候，判断父节点是否存在
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
                log.info("前置节点已经删除,path={}", s);
                if(countDownLatch!=null){
                    // 前置节点删除，通知唤醒去获取锁
                    countDownLatch.countDown();
                }
            }
        };
        // 监听前置节点
        client.subscribeDataChanges(lastPath, listener);
        // 判断前置节点是否存在
        if (this.client.exists(lastPath)) {
            countDownLatch = new CountDownLatch(1);
            try {
                // 前置节点存在，那么就阻塞等待
                countDownLatch.await();
            } catch (InterruptedException e) {
                log.error("CountDownLatch await Exception", e);
            }
        }
        // 前一个节点的listener解绑，因为已经删除完了
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
        // 获取所有子节点
        List<String> children = client.getChildren(LOCK_PATH);
        Collections.sort(children);
        // 当前最小就认为获取锁成功，直接返回true
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
```

分布式锁使用

```java

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

    private Lock lock = new DistributedLock();

    public void run() {
        try {
            COUNTDOWNLATCH.await();
        } catch (InterruptedException e) {
            log.error("CountDownLatch error", e);
        }
        String orderCode = null;
        lock.lock();
        try {
            // do biz
            orderCode = getOrderCode();
        } finally {
            lock.unlock();
        }
       

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
```

日志打印:

```log
2019-05-17 10:53:14,542  INFO Thread-1 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000150
2019-05-17 10:53:14,542  INFO Thread-3 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000153
2019-05-17 10:53:14,542  INFO Thread-5 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000151
2019-05-17 10:53:14,543  INFO Thread-9 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000159
2019-05-17 10:53:14,543  INFO Thread-15 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000154
2019-05-17 10:53:14,543  INFO Thread-13 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000156
2019-05-17 10:53:14,543  INFO Thread-7 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000155
2019-05-17 10:53:14,544  INFO Thread-17 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000157
2019-05-17 10:53:14,543  INFO Thread-11 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000152
2019-05-17 10:53:14,544  INFO Thread-19 (com.skrein.zk.DistributedLock.tryLock:87) - 当前锁路径/LOCK/0000000158
2019-05-17 10:53:14,547  INFO Thread-1 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000150
2019-05-17 10:53:14,560  INFO Thread-1 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000150
2019-05-17 10:53:14,609  INFO Thread-5 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000151
2019-05-17 10:53:14,609  INFO Thread-5 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000151
2019-05-17 10:53:14,643  INFO Thread-11 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000152
2019-05-17 10:53:14,644  INFO Thread-11 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000152
2019-05-17 10:53:14,673  INFO Thread-3 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000153
2019-05-17 10:53:14,673  INFO Thread-3 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000153
2019-05-17 10:53:14,724  INFO Thread-15 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000154
2019-05-17 10:53:14,724  INFO Thread-15 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000154
2019-05-17 10:53:14,759  INFO Thread-7 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000155
2019-05-17 10:53:14,759  INFO Thread-7 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000155
2019-05-17 10:53:14,800  INFO Thread-13 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000156
2019-05-17 10:53:14,800  INFO Thread-13 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000156
2019-05-17 10:53:14,833  INFO Thread-17 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000157
2019-05-17 10:53:14,833  INFO Thread-17 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000157
2019-05-17 10:53:14,872  INFO Thread-19 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000158
2019-05-17 10:53:14,872  INFO Thread-19 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000158
2019-05-17 10:53:14,906  INFO Thread-9 (com.skrein.zk.DistributedLock.lock:46) - 获取锁成功,当前锁节点/LOCK/0000000159
2019-05-17 10:53:14,906  INFO Thread-9 (com.skrein.zk.DistributedLock.unlock:111) - 释放锁/LOCK/0000000159
```
可以看到10个线程，依次获取锁，解绑锁。



### 为什么Redis能实现分布式锁

Redis的`SetNx`命令当key存在的时候,返回null

为了保证锁的可用性，也就是当一个获取锁的线程挂掉了，后续线程也能正确的获取锁，那么需要利用key的expired属性来保证

因为是分布式锁，所以需要一个requestId来保障是哪个请求获取的锁，所以value可以是标识客户端的id

所以思路是：获取锁的时候通过sexNx来尝试获取锁，如果设置成功,那么获取锁成功，如果设置失败，那么需要轮询该key是否存在或者ttl(time to live)该key的存活时间，来重新去获取锁，直到获取锁成功。

解锁过程：`get(key) == requestId ? del(key)`
先判断key返回的结果是否是当前客户端，如果是，那么删除该key

相信处理过高并发的同学，一定能发现问题，这个操作并不是原子性的。也就是说有可能执行完`get(key) == requestId`,由于当前线程执行业务时间过长，超过了该key的过期时间锁已经废弃掉了，其他线程就获取了锁，然后接下来执行`del(key)`，就会把其他线程的锁给删除掉。

好在Redis给我们提供了lua脚本，可以将多个操作合并为一个原子操作。所以解锁操作使用lua脚本的方式进行。


### Redis实现分布式锁过程

```java
@Slf4j
public class RedisDistributedLock {

    private Jedis client = new Jedis("localhost");

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
            // key不存在或者已经过期，然后break掉重新去获取锁
            if (!client.exists(lockKey) || ttl.intValue() <= 0) {
                log.info("当前锁已经过期过期时间为:{}", ttl);
                break;
            }
        }
    }

    // 获取锁操作很简单，直接setnx操作即可
    public boolean tryLock(String key) {
        String tryLock = client.set(REDIS_LOCK_KEY + key, reqLockId, SetParams.setParams().nx().px(LOCK_EXPIRED_TIME));
        return tryLock != null && tryLock.equals("OK");
    }


    // 解锁采用eval lua脚本方式执行
    public boolean unlock(String key) {
        // 判断key的value是否为RequestId,如果是，那么删除key
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Object eval = client.eval(script, Collections.singletonList(REDIS_LOCK_KEY + key), Collections.singletonList(reqLockId));
        return "OK".equals(eval);
    }


    public static void main(String[] args) throws InterruptedException {
        String orderId = "123";
        for (int i = 0; i < 10; i++) {
            // 开启10个线程模拟并发
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
```

```log
"C:\Program Files\Java\jdk1.8.0_161\bin\java.exe" -javaagent:C:\Users\hujiansong\AppData\Local\JetBrains\Toolbox\apps\IDEA-U\ch-0\191.7141.44\lib\idea_rt.jar=63968:C:\Users\hujiansong\AppData\Local\JetBrains\Toolbox\apps\IDEA-U\ch-0\191.7141.44\bin -Dfile.encoding=UTF-8 -classpath "C:\Program Files\Java\jdk1.8.0_161\jre\lib\charsets.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\deploy.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\access-bridge-64.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\cldrdata.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\dnsns.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\jaccess.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\jfxrt.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\localedata.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\nashorn.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\sunec.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\sunjce_provider.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\sunmscapi.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\sunpkcs11.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\ext\zipfs.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\javaws.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\jce.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\jfr.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\jfxswt.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\jsse.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\management-agent.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\plugin.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\resources.jar;C:\Program Files\Java\jdk1.8.0_161\jre\lib\rt.jar;C:\Users\hujiansong\Desktop\tmp\distributed-lock\target\classes;F:\DevTool\apache-maven-3.5.3\repo\org\projectlombok\lombok\1.18.6\lombok-1.18.6.jar;F:\DevTool\apache-maven-3.5.3\repo\org\slf4j\slf4j-api\1.7.26\slf4j-api-1.7.26.jar;F:\DevTool\apache-maven-3.5.3\repo\log4j\log4j\1.2.17\log4j-1.2.17.jar;F:\DevTool\apache-maven-3.5.3\repo\com\101tec\zkclient\0.10\zkclient-0.10.jar;F:\DevTool\apache-maven-3.5.3\repo\org\apache\zookeeper\zookeeper\3.4.8\zookeeper-3.4.8.jar;F:\DevTool\apache-maven-3.5.3\repo\org\slf4j\slf4j-log4j12\1.6.1\slf4j-log4j12-1.6.1.jar;F:\DevTool\apache-maven-3.5.3\repo\jline\jline\0.9.94\jline-0.9.94.jar;F:\DevTool\apache-maven-3.5.3\repo\io\netty\netty\3.7.0.Final\netty-3.7.0.Final.jar;F:\DevTool\apache-maven-3.5.3\repo\redis\clients\jedis\3.0.1\jedis-3.0.1.jar;F:\DevTool\apache-maven-3.5.3\repo\org\apache\commons\commons-pool2\2.4.3\commons-pool2-2.4.3.jar" com.skrein.redis.RedisDistributedLock
2019-05-17 17:11:14,007  INFO Thread-7 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:14,008  INFO Thread-7 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:19,009  INFO Thread-7 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:19,012  INFO Thread-8 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:19,012  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:19,013  INFO Thread-8 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:19,014  INFO Thread-8 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:19,012  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:19,012  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:19,012  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:19,012  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:19,012  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:19,012  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:19,012  INFO Thread-6 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:24,014  INFO Thread-8 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:24,015  INFO Thread-6 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:24,016  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:24,016  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:24,017  INFO Thread-6 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:24,017  INFO Thread-6 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:24,016  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:24,016  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:24,016  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:24,015  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:24,017  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:29,017  INFO Thread-6 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:29,017  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:29,018  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:29,017  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:29,018  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:29,017  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:29,018  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:29,018  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:29,018  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:29,018  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:34,018  INFO Thread-1 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:34,019  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:34,019  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:34,019  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:34,020  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:34,020  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:34,020  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:34,020  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:34,020  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:39,020  INFO Thread-4 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:39,020  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:39,020  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:39,021  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:39,021  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:39,021  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:39,020  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:39,021  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:44,021  INFO Thread-9 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:44,021  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:44,021  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:44,022  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:44,022  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:44,022  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:44,022  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:49,022  INFO Thread-3 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:49,023  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:49,023  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:49,023  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:49,023  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:49,023  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:54,023  INFO Thread-0 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:54,024  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:54,024  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:55
2019-05-17 17:11:54,024  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:54,024  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:11:59,024  INFO Thread-5 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!
2019-05-17 17:11:59,025  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.waitForLock:56) - 当前锁已经过期过期时间为:-2
2019-05-17 17:11:59,026  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.lock:45) - 获取锁成功!
2019-05-17 17:11:59,026  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.run:96) - do biz....
2019-05-17 17:12:04,026  INFO Thread-2 (com.skrein.redis.RedisDistributedLock.run:98) - do biz finished!

Process finished with exit code 0

```

可以看到锁依次进行获取，然后释放。

### 总结

ZK 实现分布式锁，主要利用zk的node创建模式以及通知机制。

Redis实现分布式，依赖SexNX命令，和解锁的lua脚本保证原子性

锁的可靠性主要包含如下4点：


可靠性特征 | 解释
---|---
锁是互斥 | 相同的时间，只有一个客户端可以获取到锁
NO死锁 | 当客户端获取到锁，当客户端突然挂掉，接下来的客户端也能正确获取到锁
容错性 | Redis要保证可靠，即当Redis集群大多数节点可用的时候，锁也是可用的
正确释放锁 | A客户端不会释放掉B客户端的锁，锁要被加锁的人释放


下面就来聊聊zk和Redis对可靠性的满足:

---|ZK | Redis
---|---|---
锁是互斥|满足，node唯一 | 满足，setNx
NO死锁|满足 session timeout机制 | 满足，expired
容错性|依靠zk集群 | 依靠Redis集群
正确释放锁|依赖wather机制 | 轮询，靠代码实现


**声明**：以上是我学习分布式锁的demo例子，没有上过生产。如果上生产，任何问题概不负责。

### 代码仓库


完整代码放入了我的github仓库，欢迎star 

代码地址如下：

[DistributedLock](https://github.com/jounghu/DistributedLock)





