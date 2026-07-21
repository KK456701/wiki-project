package com.hospital.wikiagent.auth;

import org.springframework.http.HttpStatus;

public final class BearerTokens {
    private BearerTokens() {
    }

    public static String require(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new HospitalAuthException(
                    "请先登录后继续操作",
                    "AUTH_SESSION_REQUIRED",
                    HttpStatus.UNAUTHORIZED);
        }
        String token = authorization.substring(7).strip();
        if (token.isEmpty()) {
            throw new HospitalAuthException(
                    "请先登录后继续操作",
                    "AUTH_SESSION_REQUIRED",
                    HttpStatus.UNAUTHORIZED);
        }
        return token;
    }
}
