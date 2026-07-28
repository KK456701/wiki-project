package com.hospital.wikiagent.sqlserver;

import java.util.HashSet;
import java.util.List;
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
                       IS_SRVROLEMEMBER('sysadmin') AS is_sysadmin,
                       IS_MEMBER('db_owner') AS is_db_owner,
                       HAS_PERMS_BY_NAME(NULL, 'SERVER', 'CONTROL SERVER')
                           AS control_server,
                       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CONTROL')
                           AS control_database,
                       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'ALTER')
                           AS alter_database,
                       HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE')
                           AS create_table
                """);
        String database = text(identity.get("database_name"));
        String login = text(identity.get("login_name"));
        int sysadmin = number(identity.get("is_sysadmin"));
        if (!RealDatabaseSafetyPolicy.DATABASE.equalsIgnoreCase(database)) {
            throw new IllegalStateException("REAL_DB_IDENTITY_MISMATCH");
        }
        if ("sa".equalsIgnoreCase(login)
                || sysadmin == 1
                || number(identity.get("is_db_owner")) == 1
                || number(identity.get("control_server")) == 1
                || number(identity.get("control_database")) == 1
                || number(identity.get("alter_database")) == 1
                || number(identity.get("create_table")) == 1) {
            throw new IllegalStateException("REAL_DB_PERMISSION_UNSAFE");
        }
        List<Map<String, Object>> tablePermissions = jdbc.queryForList("""
                SELECT TABLE_NAME AS table_name,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'SELECT') AS can_select,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'INSERT') AS can_insert,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'DELETE') AS can_delete,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'UPDATE') AS can_update,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'ALTER') AS can_alter,
                       HAS_PERMS_BY_NAME(
                           QUOTENAME(TABLE_SCHEMA) + '.' + QUOTENAME(TABLE_NAME),
                           'OBJECT', 'CONTROL') AS can_control
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'dbo' AND TABLE_TYPE = 'BASE TABLE'
                """);
        Map<String, Map<String, Object>> byTable = new java.util.HashMap<>();
        for (Map<String, Object> row : tablePermissions) {
            byTable.put(text(row.get("table_name")).toUpperCase(Locale.ROOT), row);
        }
        Set<String> actual = new HashSet<>(byTable.keySet());
        if (!actual.containsAll(RealDatabaseSafetyPolicy.TABLES)) {
            throw new IllegalStateException("REAL_DB_SCHEMA_INCOMPLETE");
        }
        for (String table : RealDatabaseSafetyPolicy.TABLES) {
            Map<String, Object> permission = byTable.get(table);
            if (number(permission.get("can_select")) != 1
                    || number(permission.get("can_insert")) != 1
                    || number(permission.get("can_delete")) != 1
                    || number(permission.get("can_update")) != 0
                    || number(permission.get("can_alter")) != 0
                    || number(permission.get("can_control")) != 0) {
                throw new IllegalStateException("REAL_DB_PERMISSION_UNSAFE");
            }
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
