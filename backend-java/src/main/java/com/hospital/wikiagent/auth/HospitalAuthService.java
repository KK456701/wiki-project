package com.hospital.wikiagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HospitalAuthService {
    public static final int PBKDF2_ITERATIONS = 310_000;
    private static final int LOCK_AFTER_FAILURES = 5;
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private final HospitalAuthRepository repository;
    private final HospitalAuthProperties properties;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    public HospitalAuthService(HospitalAuthRepository repository, HospitalAuthProperties properties) {
        this(repository, properties, new SecureRandom(), Clock.systemUTC());
    }

    HospitalAuthService(
            HospitalAuthRepository repository,
            HospitalAuthProperties properties,
            SecureRandom random,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.random = random;
        this.clock = clock;
    }

    public LoginResult login(String accountId, String password) {
        LocalDateTime now = now();
        AuthRecords.User user = repository.findUserByAccount(accountId.strip()).orElse(null);
        if (user == null) {
            audit("AUTH_LOGIN_FAILED", "denied", null, null, "AUTH_BAD_CREDENTIALS", now);
            throw auth("账号或密码错误", "AUTH_BAD_CREDENTIALS", HttpStatus.UNAUTHORIZED);
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            audit("AUTH_LOGIN_FAILED", "denied", user.userId(), user.hospitalId(), "AUTH_ACCOUNT_LOCKED", now);
            throw auth("账号已临时锁定，请15分钟后重试", "AUTH_ACCOUNT_LOCKED", HttpStatus.LOCKED);
        }
        if (!"active".equals(user.status())) {
            audit("AUTH_LOGIN_FAILED", "denied", user.userId(), user.hospitalId(), "AUTH_ACCOUNT_DISABLED", now);
            throw auth("账号已停用，请联系管理员", "AUTH_ACCOUNT_DISABLED", HttpStatus.FORBIDDEN);
        }
        if (!verifyPassword(user, password)) {
            int failures = user.failedAttempts() + 1;
            LocalDateTime lockedUntil = failures >= LOCK_AFTER_FAILURES ? now.plusMinutes(15) : null;
            repository.recordFailedLogin(user.userId(), failures, lockedUntil, now);
            audit("AUTH_LOGIN_FAILED", "denied", user.userId(), user.hospitalId(), "AUTH_BAD_CREDENTIALS", now);
            throw auth("账号或密码错误", "AUTH_BAD_CREDENTIALS", HttpStatus.UNAUTHORIZED);
        }
        repository.recordSuccessfulLogin(user.userId(), now);
        LoginResult result = issueSession(repository.findUser(user.userId()).orElse(user), now);
        audit("AUTH_LOGIN_SUCCESS", "success", user.userId(), user.hospitalId(), null, now);
        return result;
    }

    public HospitalPrincipal authenticate(String token) {
        LocalDateTime now = now();
        AuthRecords.Session session = repository.findSessionByTokenHash(hashToken(token)).orElse(null);
        if (session == null || session.revokedAt() != null) {
            throw auth("登录已失效，请重新登录", "AUTH_SESSION_INVALID", HttpStatus.UNAUTHORIZED);
        }
        if (!session.expiresAt().isAfter(now)) {
            throw auth("登录已过期，请重新登录", "AUTH_SESSION_EXPIRED", HttpStatus.UNAUTHORIZED);
        }
        if (!"active".equals(session.status())) {
            throw auth("账号已停用，请联系管理员", "AUTH_ACCOUNT_DISABLED", HttpStatus.FORBIDDEN);
        }
        Set<String> permissions = repository.permissions(session.userId());
        HospitalPrincipal principal = new HospitalPrincipal(
                session.userId(),
                session.accountId(),
                session.hospitalId(),
                permissions,
                session.mustChangePassword(),
                session.sessionId());
        repository.touchSession(session.sessionId(), now);
        return principal;
    }

    public LoginResult changePassword(HospitalPrincipal principal, String currentPassword, String newPassword) {
        AuthRecords.User user = repository.findUser(principal.userId()).orElse(null);
        if (user == null || !verifyPassword(user, currentPassword)) {
            throw auth("当前密码不正确", "AUTH_CURRENT_PASSWORD_INVALID", HttpStatus.BAD_REQUEST);
        }
        validateNewPassword(newPassword);
        LocalDateTime now = now();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        repository.updatePassword(
                principal.userId(),
                hashPassword(newPassword, salt, PBKDF2_ITERATIONS),
                Base64.getEncoder().encodeToString(salt),
                PBKDF2_ITERATIONS,
                now);
        repository.revokeUserSessions(principal.userId(), now);
        AuthRecords.User updated = repository.findUser(principal.userId())
                .orElseThrow(() -> new IllegalStateException("密码更新后账号不存在"));
        LoginResult result = issueSession(updated, now);
        audit("AUTH_PASSWORD_CHANGED", "success", updated.userId(), updated.hospitalId(), null, now);
        return result;
    }

    public void logout(HospitalPrincipal principal) {
        LocalDateTime now = now();
        repository.revokeSession(principal.sessionId(), now);
        audit("AUTH_LOGOUT", "success", principal.userId(), principal.hospitalId(), null, now);
    }

    static String hashPassword(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算密码摘要", exception);
        }
    }

    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private LoginResult issueSession(AuthRecords.User user, LocalDateTime now) {
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String sessionId = "SESSION_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime expiresAt = now.plusHours(properties.getSessionHours());
        repository.createSession(sessionId, user.userId(), hashToken(token), expiresAt, now);
        return new LoginResult(
                token,
                expiresAt,
                user.userId(),
                user.accountId(),
                user.hospitalId(),
                repository.permissions(user.userId()),
                user.mustChangePassword());
    }

    private boolean verifyPassword(AuthRecords.User user, String password) {
        try {
            byte[] salt = Base64.getDecoder().decode(user.passwordSalt());
            byte[] candidate = Base64.getDecoder().decode(
                    hashPassword(password, salt, user.passwordIterations()));
            byte[] expected = Base64.getDecoder().decode(user.passwordHash());
            return MessageDigest.isEqual(candidate, expected);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void validateNewPassword(String password) {
        if (password.length() < 8 || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw new IllegalArgumentException("新密码至少8位，并且必须同时包含字母和数字");
        }
    }

    private void audit(
            String action,
            String result,
            String userId,
            String hospitalId,
            String reason,
            LocalDateTime now) {
        repository.insertAudit(
                "AUD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                action,
                result,
                userId,
                hospitalId,
                reason,
                now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static HospitalAuthException auth(String message, String code, HttpStatus status) {
        return new HospitalAuthException(message, code, status);
    }

    public record LoginResult(
            String token,
            LocalDateTime expiresAt,
            String userId,
            String accountId,
            String hospitalId,
            Set<String> permissions,
            boolean mustChangePassword) {
    }
}
