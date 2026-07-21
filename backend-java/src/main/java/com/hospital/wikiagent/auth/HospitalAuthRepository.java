package com.hospital.wikiagent.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class HospitalAuthRepository {
    private final JdbcTemplate jdbc;

    public HospitalAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuthRecords.User> findUserByAccount(String accountId) {
        return first(jdbc.query(
                "SELECT * FROM med_hospital_user WHERE account_id=?",
                HospitalAuthRepository::mapUser,
                accountId));
    }

    public Optional<AuthRecords.User> findUser(String userId) {
        return first(jdbc.query(
                "SELECT * FROM med_hospital_user WHERE user_id=?",
                HospitalAuthRepository::mapUser,
                userId));
    }

    public Set<String> permissions(String userId) {
        List<String> values = jdbc.query(
                "SELECT permission_code FROM med_hospital_user_permission WHERE user_id=? ORDER BY permission_code",
                (rs, rowNum) -> rs.getString(1),
                userId);
        return Set.copyOf(new LinkedHashSet<>(values));
    }

    @Transactional
    public void recordFailedLogin(String userId, int failures, LocalDateTime lockedUntil, LocalDateTime now) {
        jdbc.update(
                "UPDATE med_hospital_user SET failed_attempts=?, locked_until=?, updated_at=? WHERE user_id=?",
                failures, lockedUntil, now, userId);
    }

    @Transactional
    public void recordSuccessfulLogin(String userId, LocalDateTime now) {
        jdbc.update(
                "UPDATE med_hospital_user SET failed_attempts=0, locked_until=NULL, updated_at=? WHERE user_id=?",
                now, userId);
    }

    @Transactional
    public void createSession(
            String sessionId,
            String userId,
            String tokenHash,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        jdbc.update(
                "INSERT INTO med_hospital_session "
                        + "(session_id,user_id,token_hash,expires_at,created_at,last_seen_at) VALUES (?,?,?,?,?,?)",
                sessionId, userId, tokenHash, expiresAt, now, now);
    }

    public Optional<AuthRecords.Session> findSessionByTokenHash(String tokenHash) {
        return first(jdbc.query(
                "SELECT s.session_id,s.user_id,s.expires_at,s.revoked_at,u.account_id,u.hospital_id,"
                        + "u.must_change_password,u.status FROM med_hospital_session s "
                        + "JOIN med_hospital_user u ON u.user_id=s.user_id WHERE s.token_hash=?",
                HospitalAuthRepository::mapSession,
                tokenHash));
    }

    @Transactional
    public void touchSession(String sessionId, LocalDateTime now) {
        jdbc.update("UPDATE med_hospital_session SET last_seen_at=? WHERE session_id=?", now, sessionId);
    }

    @Transactional
    public void revokeSession(String sessionId, LocalDateTime now) {
        jdbc.update(
                "UPDATE med_hospital_session SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL",
                now, sessionId);
    }

    @Transactional
    public void revokeUserSessions(String userId, LocalDateTime now) {
        jdbc.update(
                "UPDATE med_hospital_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                now, userId);
    }

    @Transactional
    public void updatePassword(
            String userId,
            String passwordHash,
            String passwordSalt,
            int iterations,
            LocalDateTime now) {
        jdbc.update(
                "UPDATE med_hospital_user SET password_hash=?,password_salt=?,password_iterations=?,"
                        + "must_change_password=0,failed_attempts=0,locked_until=NULL,updated_at=? WHERE user_id=?",
                passwordHash, passwordSalt, iterations, now, userId);
    }

    @Transactional
    public void insertAudit(
            String auditId,
            String action,
            String result,
            String userId,
            String hospitalId,
            String reason,
            LocalDateTime now) {
        jdbc.update(
                "INSERT INTO med_data_access_audit "
                        + "(audit_id,user_id,hospital_id,action,result,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                auditId, userId, hospitalId, action, result, reason, now);
    }

    private static AuthRecords.User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AuthRecords.User(
                rs.getString("user_id"),
                rs.getString("account_id"),
                rs.getString("hospital_id"),
                rs.getString("password_hash"),
                rs.getString("password_salt"),
                rs.getInt("password_iterations"),
                rs.getBoolean("must_change_password"),
                rs.getString("status"),
                rs.getInt("failed_attempts"),
                rs.getObject("locked_until", LocalDateTime.class));
    }

    private static AuthRecords.Session mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new AuthRecords.Session(
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("account_id"),
                rs.getString("hospital_id"),
                rs.getBoolean("must_change_password"),
                rs.getString("status"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("revoked_at", LocalDateTime.class));
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
}
