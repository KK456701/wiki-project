package com.hospital.wikiagent.sqlserver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import com.hospital.wikiagent.runtime.RuntimeDatabaseConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/** 真实库热更新代理；新连接立即进入新池，已经借出的旧连接不被中断。 */
public final class HotSwappableRealDataSource extends AbstractDataSource {
    private final AtomicReference<HikariDataSource> active = new AtomicReference<>();
    private final ScheduledExecutorService retiredPoolCloser = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "realdb-retired-pool-closer");
        thread.setDaemon(true);
        return thread;
    });

    public HotSwappableRealDataSource(RuntimeDatabaseConfig initial) {
        active.set(createPool(initial));
    }

    public synchronized void reload(RuntimeDatabaseConfig config) {
        if (!config.enabled()) throw new IllegalArgumentException("真实库是正式计算依赖，不能在运行中停用。");
        if (!config.configured()) throw new IllegalArgumentException("真实库连接配置不完整。");
        HikariDataSource next = createPool(config);
        try {
            validate(next, config.validationQuery());
        } catch (RuntimeException exception) {
            next.close();
            throw exception;
        }
        HikariDataSource previous = active.getAndSet(next);
        retiredPoolCloser.schedule(previous::close, 45, TimeUnit.SECONDS);
    }

    @Override
    public Connection getConnection() throws SQLException { return active.get().getConnection(); }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return active.get().getConnection(username, password);
    }

    private static HikariDataSource createPool(RuntimeDatabaseConfig config) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("realDb-" + System.nanoTime());
        dataSource.setJdbcUrl(config.url());
        dataSource.setUsername(config.username());
        dataSource.setPassword(config.password());
        dataSource.setDriverClassName(config.driverClassName());
        dataSource.setMaximumPoolSize(config.maximumPoolSize());
        dataSource.setMinimumIdle(config.minimumIdle());
        dataSource.setConnectionTimeout(config.connectionTimeoutMs());
        dataSource.setValidationTimeout(5_000);
        dataSource.setMaxLifetime(10 * 60_000L);
        dataSource.setKeepaliveTime(60_000L);
        dataSource.setInitializationFailTimeout(-1);
        dataSource.addDataSourceProperty("useBulkCopyForBatchInsert", "true");
        return dataSource;
    }

    private static void validate(DataSource dataSource, String validationQuery) {
        String query = validationQuery == null || validationQuery.isBlank() ? "SELECT 1" : validationQuery;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(query);
        } catch (SQLException exception) {
            String message = exception.getMessage();
            throw new IllegalArgumentException("真实库连接测试失败: "
                    + (message == null || message.isBlank() ? "请检查地址、账号、密码和 VPN。" : message), exception);
        }
    }

    @PreDestroy
    void close() {
        HikariDataSource current = active.getAndSet(null);
        if (current != null) current.close();
        retiredPoolCloser.shutdownNow();
    }
}
