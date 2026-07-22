package com.hospital.wikiagent.details;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.ObjectMapper;

class IndicatorDetailRepositorySqliteTest {

    @Test
    void readsEpochMillisWrittenBySqliteTimestampBinding() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE med_indicator_detail_snapshot (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  snapshot_id TEXT NOT NULL UNIQUE,
                  run_id TEXT NOT NULL UNIQUE,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  relative_path TEXT NOT NULL,
                  file_sha256 TEXT,
                  denominator_count INTEGER,
                  numerator_count INTEGER,
                  unmatched_count INTEGER,
                  column_schema_json TEXT,
                  status TEXT NOT NULL,
                  created_by TEXT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  expires_at TIMESTAMP NOT NULL,
                  error_message TEXT
                )
                """);
        long createdAt = Instant.parse("2026-07-22T06:11:52Z").toEpochMilli();
        long expiresAt = Instant.parse("2026-07-23T06:11:52Z").toEpochMilli();
        jdbc.update("""
                INSERT INTO med_indicator_detail_snapshot
                  (snapshot_id,run_id,hospital_id,rule_id,relative_path,status,created_by,
                   created_at,expires_at)
                VALUES (?,?,?,?,?,'creating',?,?,?)
                """, "SNAP_1", "RUN_1", "hospital_001", "MQSI2025_001", "file.gz",
                "user_001", createdAt, expiresAt);

        IndicatorDetailRepository repository = new IndicatorDetailRepository(jdbc, new ObjectMapper());
        var snapshot = repository.snapshotByRun("RUN_1").orElseThrow();

        assertThat(snapshot.createdAt()).isEqualTo(Instant.ofEpochMilli(createdAt));
        assertThat(snapshot.expiresAt()).isEqualTo(Instant.ofEpochMilli(expiresAt));
        dataSource.destroy();
    }
}
