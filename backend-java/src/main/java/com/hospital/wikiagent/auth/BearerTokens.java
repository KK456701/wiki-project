package com.hospital.wikiagent.auth;

import org.springframework.http.HttpStatus;

/**
 * 实现 {@code BearerTokens} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
public final class BearerTokens {
    private BearerTokens() {
    }

    /** 移除强制鉴权：未携带 token 时返回空字符串，由 authenticate 发放访客身份。 */
    public static String require(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        return authorization.substring(7).strip();
    }
}
