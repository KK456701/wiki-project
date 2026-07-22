package com.hospital.wikiagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 编排 {@code AdminSessionService} 对应的业务流程，并集中维护事务与安全边界。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class AdminSessionService {
    private final byte[] configuredPassword;
    private final boolean configured;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    public AdminSessionService(@Value("${wiki.admin.password:CHANGE_ME}") String password) {
        configuredPassword = password.getBytes(StandardCharsets.UTF_8);
        configured = !password.isBlank() && !"CHANGE_ME".equals(password);
    }

    public String login(String password) {
        if (!configured) {
            throw auth("Java 管理员密码尚未配置。", HttpStatus.SERVICE_UNAVAILABLE);
        }
        byte[] provided = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(configuredPassword, provided)) {
            throw auth("管理员密码错误", HttpStatus.UNAUTHORIZED);
        }
        byte[] value = new byte[16];
        random.nextBytes(value);
        String token = HexFormat.of().formatHex(value);
        tokens.add(token);
        return token;
    }

    public void require(String authorization) {
        String token = BearerTokens.require(authorization);
        if (!tokens.contains(token)) {
            throw auth("管理员 token 无效或已过期", HttpStatus.FORBIDDEN);
        }
    }

    public void logout(String authorization) {
        String token = BearerTokens.require(authorization);
        tokens.remove(token);
    }

    private static HospitalAuthException auth(String message, HttpStatus status) {
        return new HospitalAuthException(message, "ADMIN_AUTH_FAILED", status);
    }
}
