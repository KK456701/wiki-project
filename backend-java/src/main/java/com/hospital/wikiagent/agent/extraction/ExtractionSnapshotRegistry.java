package com.hospital.wikiagent.agent.extraction;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

/**
 * 记录每家医院当前唯一的真实库中间表快照身份。
 *
 * <p>本组件只登记抽取结果，不执行清表、建表或复制；这些动作仍完全由既有
 * {@code SyncDataService} 负责。注册键固定为医院，值包含指标、口径、窗口和完整抽取
 * 契约；因此后一个指标完成抽取后会覆盖前一个指标身份，不会把“曾经抽取过”误判为
 * 当前全局可变快照仍可复用。</p>
 */
@Component
public class ExtractionSnapshotRegistry {
    private final JdbcTemplate jdbc;

    public ExtractionSnapshotRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_mras_extraction_state (
                  artifact_key VARCHAR(255) PRIMARY KEY,
                  fingerprint VARCHAR(64) NOT NULL,
                  updated_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_mras_reference_state (
                  hospital_soid VARCHAR(64) NOT NULL,
                  table_name VARCHAR(128) NOT NULL,
                  business_date VARCHAR(10) NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (hospital_soid, table_name)
                )
                """);
    }

    /**
     * 只有当前医院的唯一快照身份完全一致时才复用。
     */
    public boolean isCurrent(Map<String, String> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> artifact : artifacts.entrySet()) {
            String current = jdbc.query(
                    "SELECT fingerprint FROM med_mras_extraction_state WHERE artifact_key=?",
                    result -> result.next() ? result.getString(1) : null,
                    artifact.getKey());
            if (!artifact.getValue().equals(current)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 成功抽取后原子更新所有受影响中间表的当前指纹。
     */
    @Transactional
    public void markCurrent(Map<String, String> artifacts) {
        Timestamp now = Timestamp.from(Instant.now());
        for (Map.Entry<String, String> artifact : artifacts.entrySet()) {
            int updated = jdbc.update("""
                    UPDATE med_mras_extraction_state
                    SET fingerprint=?, updated_at=?
                    WHERE artifact_key=?
                    """, artifact.getValue(), now, artifact.getKey());
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO med_mras_extraction_state
                          (artifact_key, fingerprint, updated_at)
                        VALUES (?, ?, ?)
                        """, artifact.getKey(), artifact.getValue(), now);
            }
        }
    }

    /**
     * 抽取中途失败时相关表可能已被清空，必须撤销可复用标记。
     */
    @Transactional
    public void invalidate(Iterable<String> artifactKeys) {
        for (String artifactKey : artifactKeys) {
            jdbc.update(
                    "DELETE FROM med_mras_extraction_state WHERE artifact_key=?",
                    artifactKey);
        }
    }

    /**
     * 全量基础配置表与指标窗口无关，可在同一业务日安全复用；患者关联表不得走此路径。
     */
    public boolean isReferenceCurrent(long hospitalSoid, String table, LocalDate businessDate) {
        String value = jdbc.query(
                """
                SELECT business_date FROM med_mras_reference_state
                WHERE hospital_soid=? AND table_name=?
                """,
                result -> result.next() ? result.getString(1) : null,
                String.valueOf(hospitalSoid),
                table.toUpperCase(java.util.Locale.ROOT));
        return businessDate.toString().equals(value);
    }

    @Transactional
    public void markReferenceCurrent(
            long hospitalSoid,
            Iterable<String> tables,
            LocalDate businessDate) {
        Timestamp now = Timestamp.from(Instant.now());
        for (String table : tables) {
            String normalized = table.toUpperCase(java.util.Locale.ROOT);
            int updated = jdbc.update("""
                    UPDATE med_mras_reference_state
                    SET business_date=?, updated_at=?
                    WHERE hospital_soid=? AND table_name=?
                    """,
                    businessDate.toString(), now, String.valueOf(hospitalSoid), normalized);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO med_mras_reference_state (
                          hospital_soid, table_name, business_date, updated_at
                        ) VALUES (?, ?, ?, ?)
                        """,
                        String.valueOf(hospitalSoid), normalized,
                        businessDate.toString(), now);
            }
        }
    }

    @Transactional
    public void invalidateReferences(long hospitalSoid, Iterable<String> tables) {
        for (String table : tables) {
            jdbc.update("""
                    DELETE FROM med_mras_reference_state
                    WHERE hospital_soid=? AND table_name=?
                    """,
                    String.valueOf(hospitalSoid),
                    table.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
