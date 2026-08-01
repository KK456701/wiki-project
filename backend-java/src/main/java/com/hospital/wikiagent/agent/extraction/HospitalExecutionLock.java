package com.hospital.wikiagent.agent.extraction;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 将同一医院的抽取与双库查询串行化，降低大表抽取并发造成锁竞争的风险。
 *
 * <p>锁覆盖一次抽取以及随后的真实库查询。进程内使用公平可重入锁；生产环境存在
 * SQL Server 数据源时，同时持有 session 级 {@code sp_getapplock}，使多实例也不能
 * 并发重建同一医院的全局中间表。数据库锁不可用时失败关闭，不降级成仅 JVM 锁。</p>
 */
@Component
public class HospitalExecutionLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ObjectProvider<DataSource> sqlServerDataSourceProvider;

    /** 单元测试和未启用真实库的运行方式仅使用进程内锁。 */
    public HospitalExecutionLock() {
        this.sqlServerDataSourceProvider = null;
    }

    @Autowired
    public HospitalExecutionLock(
            @Qualifier("sqlServerDataSource")
            ObjectProvider<DataSource> sqlServerDataSourceProvider) {
        this.sqlServerDataSourceProvider = sqlServerDataSourceProvider;
    }

    public Lease acquire(String hospitalId) {
        String key = hospitalId == null || hospitalId.isBlank() ? "unknown" : hospitalId;
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        boolean reentrant = lock.isHeldByCurrentThread();
        lock.lock();
        if (reentrant) {
            return new Lease(lock, null, null);
        }
        DataSource dataSource = sqlServerDataSourceProvider == null
                ? null
                : sqlServerDataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return new Lease(lock, null, null);
        }
        String resource = "wiki-agent:mras:hospital:" + key;
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            int result;
            try (CallableStatement statement = connection.prepareCall(
                    "{? = call sp_getapplock(?, ?, ?, ?)}")) {
                statement.registerOutParameter(1, Types.INTEGER);
                statement.setString(2, resource);
                statement.setString(3, "Exclusive");
                statement.setString(4, "Session");
                statement.setInt(5, 60_000);
                statement.execute();
                result = statement.getInt(1);
            }
            if (result < 0) {
                throw new SQLException("sp_getapplock 返回失败码 " + result);
            }
            return new Lease(lock, connection, resource);
        } catch (SQLException exception) {
            closeQuietly(connection);
            lock.unlock();
            throw new IllegalStateException("无法取得医院级数据库执行锁。", exception);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ReentrantLock lock;
        private final Connection connection;
        private final String resource;
        private boolean closed;

        private Lease(ReentrantLock lock, Connection connection, String resource) {
            this.lock = lock;
            this.connection = connection;
            this.resource = resource;
        }

        @Override
        public void close() {
            if (!closed) {
                try {
                    if (connection != null && resource != null) {
                        try (CallableStatement statement = connection.prepareCall(
                                "{? = call sp_releaseapplock(?, ?)}")) {
                            statement.registerOutParameter(1, Types.INTEGER);
                            statement.setString(2, resource);
                            statement.setString(3, "Session");
                            statement.execute();
                        }
                    }
                } catch (SQLException ignored) {
                    // 关闭 session 会由 SQL Server 自动释放 session 级应用锁。
                } finally {
                    closeQuietly(connection);
                    lock.unlock();
                    closed = true;
                }
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 释放失败时连接池会负责回收失效连接。
            }
        }
    }
}
