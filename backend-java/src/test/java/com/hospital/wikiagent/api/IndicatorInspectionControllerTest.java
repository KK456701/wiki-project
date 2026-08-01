package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchJobSnapshot;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;

class IndicatorInspectionControllerTest {
    private HospitalAuthService auth;
    private BatchJobStore jobs;
    private IndicatorInspectionController controller;

    @BeforeEach
    void setUp() {
        auth = mock(HospitalAuthService.class);
        jobs = mock(BatchJobStore.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(auth.authenticate("token")).thenReturn(new HospitalPrincipal(
                "u1", "doctor", "h1", Set.of(), false, "s1"));
        controller = new IndicatorInspectionController(
                auth,
                jobs,
                jdbc,
                mock(AgentModelRegistry.class),
                mock(AgentModelInvoker.class));
    }

    @Test
    void checklistDiversifiesCategoriesInsteadOfFillingWithNoSample() {
        stubBatch(List.of(
                task(0, "R-FAIL", "失败项", "FAILED", null, null, null, null, "SQL_MISSING"),
                task(1, "R-EMPTY-1", "无样本一", "NO_SAMPLE", null, 0L, 0L, null, null),
                task(2, "R-EMPTY-2", "无样本二", "NO_SAMPLE", null, 0L, 0L, null, null),
                task(3, "R-PENDING", "待确认项", "SUCCESS", 80d, 8L, 10L, null, null),
                task(4, "R-MISS", "未达标项", "SUCCESS", 80d, 8L, 10L, ">=90", null)));

        Map<String, Object> response = controller.analyzeBatch(
                "Bearer token",
                new IndicatorInspectionController.BatchAnalysisRequest(
                        "batch_confirmation_checklist", "B1"));

        String answer = String.valueOf(response.get("answer"));
        assertThat(answer)
                .contains("失败项", "无样本一", "待确认项", "未达标项")
                .contains("无样本不会被误写成未达标")
                .doesNotContain("真实库概览计算失败");
    }

    @Test
    void qualityReviewSeparatesUnavailableFromNotReached() {
        stubBatch(List.of(
                task(0, "R-EMPTY", "无样本项", "NO_SAMPLE", null, 0L, 0L, null, null),
                task(1, "R-MISS", "未达标项", "SUCCESS", 80d, 8L, 10L, ">=90", null)));

        Map<String, Object> response = controller.analyzeBatch(
                "Bearer token",
                new IndicatorInspectionController.BatchAnalysisRequest(
                        "batch_data_quality_review", "B1"));

        assertThat(String.valueOf(response.get("answer")))
                .contains("真正未达标**：1 个口径")
                .contains("无样本或计算失败**：1 个口径")
                .contains("不能归入未达标")
                .contains("没有足够证据把某个未达标结果归因于数据质量");
    }

    private void stubBatch(List<BatchTaskSnapshot> tasks) {
        when(jobs.loadJob("B1", "h1", "u1")).thenReturn(Optional.of(
                new BatchJobSnapshot(
                        "B1", "h1", "u1", "COMPLETED", tasks.size(),
                        1, 1, 0, "2025-01-01", "2026-01-01", "T1", "", "")));
        when(jobs.loadTasks("B1", "h1", "u1")).thenReturn(tasks);
    }

    private static BatchTaskSnapshot task(
            int position,
            String ruleId,
            String name,
            String status,
            Double result,
            Long numerator,
            Long denominator,
            String target,
            String error) {
        String direction = target == null ? null : target.substring(0, 2);
        String targetValue = target == null ? null : target.substring(2);
        return new BatchTaskSnapshot(
                "B1", position, ruleId, name, null, null, status,
                result, numerator, denominator, null, "percentage", targetValue, direction,
                null, "2025-01-01", "2026-01-01", null, "COUNT_RATIO",
                "v1", null, null, error == null ? null : "ERROR", error);
    }
}
