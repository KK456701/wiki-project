package com.hospital.wikiagent.bizdb;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import com.hospital.wikiagent.runtime.RuntimeDatabaseConfig;
import com.hospital.wikiagent.runtime.RuntimeSettingsStore;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * 业务库动态路由数据源。
 *
 * <p>应用内所有 {@code bizDbJdbcTemplate} 调用都持有本对象，而不是固定的物理连接池。
 * 实施人员切换业务库时先创建并验证新连接池，再原子替换后续连接的来源；已经借出的旧连接
 * 不会被中断，旧连接池在查询超时窗口之后关闭。</p>
 */
public final class HotSwappableBusinessDataSource extends AbstractDataSource {
    private static final long RETIRE_DELAY_SECONDS = 45;

    private final RuntimeSettingsStore settings;
    private final BizDbProperties business;
    private final AtomicReference<ActivePool> active = new AtomicReference<>();
    private final ScheduledExecutorService retiredPoolCloser = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "bizdb-retired-pool-closer");
        thread.setDaemon(true);
        return thread;
    });

    public HotSwappableBusinessDataSource(
            RuntimeSettingsStore settings,
            BizDbProperties business) {
        this.settings = Objects.requireNonNull(settings);
        this.business = Objects.requireNonNull(business);
        RuntimeDatabaseConfig initial = settings.business(business);
        active.set(new ActivePool("business", initial, createPool(initial)));
    }

    public String activeConnectionId() {
        return active.get().connectionId();
    }

    /** 当前正式业务查询实际使用的连接快照，供方言、Schema 和元数据判断共用。 */
    public RuntimeDatabaseConfig activeConfig() {
        return active.get().config();
    }

    /** 测试并切换正式业务查询连接；失败时继续使用原连接池。 */
    public synchronized String activate(String connectionId) {
        String normalized = normalizeConnectionId(connectionId);
        RuntimeDatabaseConfig candidate = settings.business(business);
        if (!candidate.enabled()) throw new IllegalArgumentException("请先启用并保存该业务库连接。");
        if (!candidate.configured()) throw new IllegalArgumentException("该业务库连接配置不完整。");

        HikariDataSource next = createPool(candidate);
        try {
            validate(next, candidate.validationQuery());
        } catch (RuntimeException exception) {
            next.close();
            throw exception;
        }

        ActivePool previous = active.getAndSet(new ActivePool(normalized, candidate, next));
        retire(previous.pool());
        return normalized;
    }

    /** 当前生效连接被编辑后，立即用新配置重建连接池。 */
    public synchronized boolean reloadIfActive(String connectionId) {
        String normalized = normalizeConnectionId(connectionId);
        if (!normalized.equals(activeConnectionId())) return false;
        activate(normalized);
        return true;
    }

    /** 用设置页刚保存且已测试通过的唯一业务库配置热更新连接池。 */
    public synchronized boolean reload(RuntimeDatabaseConfig candidate) {
        if (candidate == null || !"business".equals(candidate.id())) {
            throw new IllegalArgumentException("当前只允许热更新业务库连接。");
        }
        HikariDataSource next = createPool(candidate);
        try {
            validate(next, candidate.validationQuery());
        } catch (RuntimeException exception) {
            next.close();
            throw exception;
        }
        ActivePool previous = active.getAndSet(new ActivePool("business", candidate, next));
        retire(previous.pool());
        return true;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return active.get().pool().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return active.get().pool().getConnection(username, password);
    }

    private HikariDataSource createPool(RuntimeDatabaseConfig config) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("bizDb-" + config.id() + "-" + System.nanoTime());
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
        dataSource.setReadOnly(true);
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    private static void validate(DataSource dataSource, String validationQuery) {
        String query = validationQuery == null || validationQuery.isBlank() ? "SELECT 1" : validationQuery;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(query);
        } catch (SQLException exception) {
            throw new IllegalArgumentException("业务库连接测试失败: " + safeMessage(exception), exception);
        }
    }

    private void retire(HikariDataSource pool) {
        retiredPoolCloser.schedule(pool::close, RETIRE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static String normalizeConnectionId(String connectionId) {
        String value = connectionId == null ? "" : connectionId.strip().toLowerCase();
        if (!value.equals("business")) {
            throw new IllegalArgumentException("当前只保留一个业务库连接。");
        }
        return value;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "请检查地址、账号、密码和 VPN。" : message;
    }

    @PreDestroy
    void close() {
        ActivePool current = active.getAndSet(null);
        if (current != null) current.pool().close();
        retiredPoolCloser.shutdownNow();
    }

    private record ActivePool(String connectionId, RuntimeDatabaseConfig config, HikariDataSource pool) {}
}
