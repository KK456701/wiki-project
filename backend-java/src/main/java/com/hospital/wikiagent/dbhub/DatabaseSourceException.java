package com.hospital.wikiagent.dbhub;

import org.springframework.http.HttpStatus;

/**
 * 表示数据库角色或数据源违反固定双库边界。
 *
 * <p>该异常只携带稳定错误码和安全说明，不暴露连接串、账号或底层 DBHub 响应。
 * 控制器和 Agent 工具可以据此区分退役数据源、非法角色和配置缺失。</p>
 */
public class DatabaseSourceException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DatabaseSourceException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static DatabaseSourceException retired() {
        return new DatabaseSourceException(
                "DB_SOURCE_RETIRED",
                "该数据库已经退役；当前系统只允许访问业务库 winex_all_dev 和真实库 winex_aima。",
                HttpStatus.GONE);
    }

    public static DatabaseSourceException invalid() {
        return new DatabaseSourceException(
                "DB_SOURCE_ROLE_INVALID",
                "数据库参数无效；只允许业务库 winex_all_dev 或真实库 winex_aima。",
                HttpStatus.BAD_REQUEST);
    }
}
