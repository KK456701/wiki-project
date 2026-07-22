package com.hospital.wikiagent.monitoring;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 实现 {@code MonitoringScheduler} 对应的领域职责。
 *
 * <p>调度器只触发已配置且具备租约的任务，避免多实例重复执行。单次失败会记录稳定状态，但不会无限重试或扩大数据访问范围。</p>
 */
@Component
public class MonitoringScheduler {
    private final MonitoringRepository repository;
    private final MonitoringExecutionService execution;
    private final boolean enabled;
    private volatile LocalDateTime lastScanAt;
    private volatile String lastStatus = "not_started";
    private volatile String lastErrorCode;

    public MonitoringScheduler(
            MonitoringRepository repository,
            MonitoringExecutionService execution,
            @Value("${wiki.monitoring.scheduler-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.execution = execution;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${wiki.monitoring.scan-delay-ms:60000}")
    public void scheduledScan() {
        if (!enabled) return;
        try {
            scanDue();
        } catch (RuntimeException exception) {
            lastStatus = "failed";
            lastErrorCode = "MONITOR_SCHEDULER_SCAN_FAILED";
        }
    }

    public Map<String, Object> scanDue() {
        LocalDateTime current = LocalDateTime.now().withNano(0);
        lastScanAt = current;
        if (!enabled) {
            lastStatus = "disabled";
            return Map.of("enabled", false, "status", "disabled", "scanned", 0, "results", List.of());
        }
        List<Map<String, Object>> plans = repository.duePlans(current);
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (Map<String, Object> plan : plans) {
            try {
                results.add(execution.runScheduled(plan));
            } catch (RuntimeException exception) {
                failed++;
                results.add(Map.of(
                        "plan_id", String.valueOf(plan.get("plan_id")),
                        "status", "failed",
                        "error_code", "MONITOR_PLAN_EXECUTION_FAILED"));
            }
        }
        lastStatus = failed == 0 ? "completed" : "completed_with_failures";
        lastErrorCode = failed == 0 ? null : "MONITOR_PLAN_EXECUTION_FAILED";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", true);
        response.put("status", lastStatus);
        response.put("scanned", plans.size());
        response.put("failed", failed);
        response.put("results", results);
        response.put("scanned_at", current);
        return response;
    }

    public Map<String, Object> status() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", enabled);
        response.put("status", enabled ? lastStatus : "disabled");
        response.put("last_scan_at", lastScanAt);
        response.put("last_error_code", lastErrorCode);
        return response;
    }
}
