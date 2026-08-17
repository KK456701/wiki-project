package com.hospital.wikiagent.agent.extraction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HospitalExecutionLockTest {

    @Test
    void retriesOnceWithFreshConnectionWhenPooledConnectionIsBroken() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection broken = mock(Connection.class);
        Connection healthy = mock(Connection.class);
        CallableStatement brokenAcquire = mock(CallableStatement.class);
        CallableStatement healthyAcquire = mock(CallableStatement.class);
        CallableStatement release = mock(CallableStatement.class);

        when(dataSource.getConnection()).thenReturn(broken, healthy);
        when(broken.prepareCall(anyString())).thenReturn(brokenAcquire);
        when(brokenAcquire.execute()).thenThrow(
                new SQLException("The connection is broken and recovery is not possible", "08S01"));
        when(healthy.prepareCall(anyString())).thenReturn(healthyAcquire, release);
        when(healthyAcquire.getInt(1)).thenReturn(0);

        try (HospitalExecutionLock.Lease ignored = lockFor(dataSource).acquire("994253")) {
            verify(dataSource, times(2)).getConnection();
            verify(broken).close();
        }
        verify(healthy).close();
    }

    @Test
    void doesNotRetryApplicationLockTimeout() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        CallableStatement statement = mock(CallableStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareCall(anyString())).thenReturn(statement);
        when(statement.getInt(1)).thenReturn(-1);

        assertThatThrownBy(() -> lockFor(dataSource).acquire("994253"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法取得医院级数据库执行锁。")
                .hasRootCauseMessage("sp_getapplock 返回失败码 -1");
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void reportsConnectionFailureAfterRetryAlsoFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection first = brokenConnection();
        Connection second = brokenConnection();
        when(dataSource.getConnection()).thenReturn(first, second);

        assertThatThrownBy(() -> lockFor(dataSource).acquire("994253"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动重连后仍无法取得")
                .hasRootCauseMessage("connection is closed");
        verify(dataSource, times(2)).getConnection();
    }

    private static Connection brokenConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        CallableStatement statement = mock(CallableStatement.class);
        when(connection.prepareCall(anyString())).thenReturn(statement);
        when(statement.execute()).thenThrow(new SQLException("connection is closed", "08003"));
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static HospitalExecutionLock lockFor(DataSource dataSource) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return new HospitalExecutionLock(provider);
    }
}
