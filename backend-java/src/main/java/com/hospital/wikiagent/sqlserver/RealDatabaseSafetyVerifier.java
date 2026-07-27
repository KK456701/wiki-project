package com.hospital.wikiagent.sqlserver;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 在首次写入前验证数据库身份、登录权限和白名单表完整性。
 *
 * <p>验证失败时抽取必须停止在任何 DELETE 之前。该类型不输出连接串、用户名之外的
 * 凭据或数据库结构详情，只向上层返回稳定的安全错误代码。</p>
 */
@Component
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class RealDatabaseSafetyVerifier {
    private final JdbcTemplate jdbc;
    private volatile boolean verified;

    public RealDatabaseSafetyVerifier(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized void verify() {
        if (verified) {
            return;
        }
        Map<String, Object> identity = jdbc.queryForMap("""
                SELECT DB_NAME() AS database_name,
                       SUSER_SNAME() AS login_name,
                       IS_SRVROLEMEMBER('sysadmin') AS is_sysadmin
                """);
        String database = text(identity.get("database_name"));
        String login = text(identity.get("login_name"));
        int sysadmin = number(identity.get("is_sysadmin"));
        if (!RealDatabaseSafetyPolicy.DATABASE.equalsIgnoreCase(database)) {
            throw new IllegalStateException("REAL_DB_IDENTITY_MISMATCH");
        }
        if ("sa".equalsIgnoreCase(login) || sysadmin == 1) {
            throw new IllegalStateException("REAL_DB_PERMISSION_UNSAFE");
        }
        Set<String> actual = new HashSet<>(jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'dbo' AND TABLE_TYPE = 'BASE TABLE'",
                String.class).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList());
        if (!actual.containsAll(RealDatabaseSafetyPolicy.TABLES)) {
            throw new IllegalStateException("REAL_DB_SCHEMA_INCOMPLETE");
        }
        verified = true;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
