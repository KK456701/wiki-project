package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.auth.AdminSessionService;

@RestController
@RequestMapping("/api/admin")
/**
 * 提供 {@code AdminAuthController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 */
public class AdminAuthController {
    private final AdminSessionService sessions;

    public AdminAuthController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest request) {
        return Map.of("token", sessions.login(request.password()), "message", "登录成功");
    }

    @PostMapping("/logout")
    public Map<String, String> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        sessions.logout(authorization);
        return Map.of("message", "已登出");
    }

    public record LoginRequest(String password) { }
}
