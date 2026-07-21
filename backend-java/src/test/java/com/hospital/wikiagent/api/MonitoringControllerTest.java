package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.hospital.wikiagent.auth.AdminSessionService;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.monitoring.MonitoringRepository;
import com.hospital.wikiagent.monitoring.MonitoringExecutionService;
import com.hospital.wikiagent.monitoring.MonitoringScheduler;
import com.hospital.wikiagent.monitoring.MonitoringService;

class MonitoringControllerTest {
    private AdminSessionService admins;
    private HospitalAuthService hospitals;
    private MonitoringRepository repository;
    private MonitoringExecutionService execution;
    private MonitoringScheduler scheduler;
    private MonitoringController controller;

    @BeforeEach
    void setUp() {
        admins = Mockito.mock(AdminSessionService.class);
        hospitals = Mockito.mock(HospitalAuthService.class);
        repository = Mockito.mock(MonitoringRepository.class);
        execution = Mockito.mock(MonitoringExecutionService.class);
        scheduler = Mockito.mock(MonitoringScheduler.class);
        controller = new MonitoringController(admins, hospitals, repository,
                Mockito.mock(MonitoringService.class), execution, scheduler);
        when(hospitals.authenticate("hospital-token")).thenReturn(new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of("indicator:view"), false, "SESSION_1"));
    }

    @Test
    void listPlansRequiresBothSessionsAndKeepsHospitalScope() {
        when(repository.listPlans("hospital_001")).thenReturn(List.of(Map.of("plan_id", "PLAN_1")));

        Map<String, Object> response = controller.listPlans(
                "Bearer admin-token", "Bearer hospital-token", "hospital_001");

        assertThat(response.get("items")).asList().hasSize(1);
        verify(admins).require("Bearer admin-token");
        verify(repository).listPlans("hospital_001");
    }

    @Test
    void rejectsCrossHospitalReadBeforeRepositoryAccess() {
        assertThatThrownBy(() -> controller.listPlans(
                "Bearer admin-token", "Bearer hospital-token", "hospital_002"))
                .isInstanceOf(HospitalAuthException.class)
                .hasMessageContaining("其他医院");
        verify(admins).require("Bearer admin-token");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void manualRunUsesAuthenticatedHospitalPrincipal() {
        HospitalPrincipal principal = hospitals.authenticate("hospital-token");
        when(execution.runManual("PLAN_1", "hospital_001", "2026-01-01~2026-01-31", principal))
                .thenReturn(Map.of("run_status", "success"));

        Map<String, Object> response = controller.runPlan(
                "Bearer admin-token", "Bearer hospital-token", "PLAN_1",
                new MonitoringController.RunRequest("hospital_001", "2026-01-01~2026-01-31"));

        assertThat(response).containsEntry("run_status", "success");
        verify(execution).runManual("PLAN_1", "hospital_001", "2026-01-01~2026-01-31", principal);
    }
}
