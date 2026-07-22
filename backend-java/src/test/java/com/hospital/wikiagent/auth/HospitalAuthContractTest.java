package com.hospital.wikiagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

class HospitalAuthContractTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private HospitalAuthService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setName("auth_" + System.nanoTime())
                .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        HospitalAuthProperties properties = new HospitalAuthProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC);
        service = new HospitalAuthService(
                new HospitalAuthRepository(jdbc), properties, new SecureRandom(), clock);

        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 12, 0);
        String salt = "ABEiM0RVZneImaq7zN3u/w==";
        jdbc.update(
                "INSERT INTO med_hospital_user "
                        + "(user_id,account_id,hospital_id,password_hash,password_salt,password_iterations,"
                        + "must_change_password,status,failed_attempts,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "user_001", "doctor", "hospital_001",
                "73zmGSGvMovUH82nX/L55uoqFu6lG7WEdH37HgCdmM4=", salt, 310000,
                0, "active", 0, now, now);
        jdbc.update(
                "INSERT INTO med_hospital_user_permission (user_id,permission_code,created_at) VALUES (?,?,?)",
                "user_001", "indicator_detail_view", now);
    }

    @Test
    void cryptoMatchesFrozenCompatibilityVector() throws Exception {
        Path fixture = Path.of("..", "contracts", "migration", "v1", "auth-crypto-vector.json").normalize();
        @SuppressWarnings("unchecked")
        Map<String, Object> vector = new ObjectMapper().readValue(Files.readString(fixture), Map.class);
        byte[] salt = HexFormat.of().parseHex(vector.get("salt_hex").toString());

        assertThat(HospitalAuthService.hashPassword(
                vector.get("password").toString(), salt, ((Number) vector.get("iterations")).intValue()))
                .isEqualTo(vector.get("password_hash_base64"));
        assertThat(HospitalAuthService.hashToken(vector.get("token").toString()))
                .isEqualTo(vector.get("token_sha256"));
    }

    @Test
    void javaIssuedSessionCanBeAuthenticatedFromSharedTables() {
        HospitalAuthService.LoginResult login = service.login("doctor", "contract-test-password-123");

        HospitalPrincipal principal = service.authenticate(login.token());

        assertThat(principal.hospitalId()).isEqualTo("hospital_001");
        assertThat(principal.permissions()).containsExactly("indicator_detail_view");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_hospital_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsWrongPasswordWithoutRevealingAccountState() {
        assertThatThrownBy(() -> service.login("doctor", "wrong"))
                .isInstanceOf(HospitalAuthException.class)
                .hasMessage("账号或密码错误");
    }
}
