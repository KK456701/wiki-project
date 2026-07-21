package com.hospital.wikiagent.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.monitoring.MonitoringService.PlanCommand;

class MonitoringServiceTest {
    private JdbcTemplate jdbc;
    private MonitoringRepository repository;
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("monitoring-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        repository = new MonitoringRepository(jdbc);
        service = new MonitoringService(repository,
                Clock.fixed(Instant.parse("2026-07-21T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void managesPlansWithDeterministicNextRunAndHospitalScope() {
        Map<String, Object> created = service.create(new PlanCommand(
                null, "hospital_001", "MQSI2025_001", "每月转科率", "monthly", "02:30", 5,
                "Asia/Shanghai", true, 20.0, true, 30.0), "admin");

        assertThat(created.get("plan_id")).asString().startsWith("PLAN_");
        assertThat(created.get("next_run_at")).asString().startsWith("2026-08-05 02:30");
        assertThat(repository.listPlans("hospital_001")).hasSize(1);
        assertThat(repository.listPlans("hospital_002")).isEmpty();

        String planId = String.valueOf(created.get("plan_id"));
        assertThat(service.status(planId, "hospital_001", "disabled").get("status")).isEqualTo("disabled");
        assertThatThrownBy(() -> service.status(planId, "hospital_002", "enabled"))
                .isInstanceOf(MonitoringException.class);
    }

    @Test
    void readsResultsAndTransitionsAlertsWithoutCrossHospitalLeakage() {
        jdbc.update("""
                INSERT INTO med_index_run_result
                (hospital_id,rule_id,stat_period,result_value,is_abnormal,created_at,run_status,no_sample)
                VALUES ('hospital_001','MQSI2025_001','2026-06',2.83,1,CURRENT_TIMESTAMP,'success',0)
                """);
        jdbc.update("""
                INSERT INTO med_indicator_alert
                (alert_id,hospital_id,rule_id,result_id,alert_type,alert_level,conclusion_code,
                 diagnose_status,status,created_at,updated_at)
                VALUES ('ALERT_1','hospital_001','MQSI2025_001',1,'wave','warning','MOM_HIGH',
                        'pending','open',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);

        assertThat(repository.listResults("hospital_001", null, 100)).hasSize(1);
        assertThat(repository.listResults("hospital_002", null, 100)).isEmpty();
        assertThat(repository.updateAlert("ALERT_1", "hospital_001", "acknowledged", "user_001",
                java.time.LocalDateTime.of(2026, 7, 21, 16, 0)).get("status")).isEqualTo("acknowledged");
        assertThatThrownBy(() -> repository.updateAlert("ALERT_1", "hospital_002", "closed", "user_002",
                java.time.LocalDateTime.of(2026, 7, 21, 16, 1))).isInstanceOf(MonitoringException.class);
    }
}
