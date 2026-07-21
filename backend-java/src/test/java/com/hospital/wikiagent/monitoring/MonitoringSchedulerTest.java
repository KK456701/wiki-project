package com.hospital.wikiagent.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MonitoringSchedulerTest {
    @Test
    void disabledSchedulerNeverScansOrExecutesPlans() {
        MonitoringRepository repository = mock(MonitoringRepository.class);
        MonitoringExecutionService execution = mock(MonitoringExecutionService.class);
        MonitoringScheduler scheduler = new MonitoringScheduler(repository, execution, false);

        Map<String, Object> result = scheduler.scanDue();

        assertThat(result).containsEntry("enabled", false).containsEntry("scanned", 0);
        verifyNoInteractions(repository, execution);
    }

    @Test
    void enabledSchedulerRunsDuePlansInRepositoryOrder() {
        MonitoringRepository repository = mock(MonitoringRepository.class);
        MonitoringExecutionService execution = mock(MonitoringExecutionService.class);
        Map<String, Object> plan = Map.of("plan_id", "PLAN_1");
        when(repository.duePlans(any())).thenReturn(List.of(plan));
        when(execution.runScheduled(plan)).thenReturn(Map.of("status", "success"));
        MonitoringScheduler scheduler = new MonitoringScheduler(repository, execution, true);

        Map<String, Object> result = scheduler.scanDue();

        assertThat(result).containsEntry("enabled", true).containsEntry("scanned", 1);
        assertThat(result.get("results")).asList().containsExactly(Map.of("status", "success"));
    }
}
