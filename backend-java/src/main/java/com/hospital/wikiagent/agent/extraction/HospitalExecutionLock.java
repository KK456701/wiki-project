package com.hospital.wikiagent.agent.extraction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

/**
 * 将同一医院的抽取与双库查询串行化，降低大表抽取并发造成锁竞争的风险。
 *
 * <p>锁覆盖一次抽取以及随后的业务库、真实库查询，保证同一医院不会在本进程内
 * 并发触发大范围数据搬运。锁只存在于当前 Java 进程，不替代抽取服务自身的幂等、
 * 超时和分布式并发控制，也不用于保存任何患者数据。</p>
 */
@Component
public class HospitalExecutionLock {
    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    public Lease acquire(String hospitalId) {
        // 当前试运行环境的所有医院共用同一个 winex_aima 快照库，必须全局串行。
        String key = "winex_aima-global-snapshot";
        Semaphore semaphore = locks.computeIfAbsent(key, ignored -> new Semaphore(1, true));
        semaphore.acquireUninterruptibly();
        return new Lease(semaphore);
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!closed) {
                semaphore.release();
                closed = true;
            }
        }
    }
}
