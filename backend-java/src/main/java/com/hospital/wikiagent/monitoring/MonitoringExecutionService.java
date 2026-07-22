package com.hospital.wikiagent.monitoring;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;
import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.monitoring.MonitoringPeriodResolver.Period;
import com.hospital.wikiagent.monitoring.MonitoringWaveDetector.Wave;

/**
 * 编排 {@code MonitoringExecutionService} 对应的业务流程，并集中维护事务与安全边界。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class MonitoringExecutionService {
    private final MonitoringRepository repository;
    private final MonitoringPeriodResolver periods;
    private final MonitoringWaveDetector waves;
    private final ToolGateway gateway;
    private final AgentTraceService traces;
    private final int leaseSeconds;
    private final String workerId = "java-monitor-" + shortId();

    public MonitoringExecutionService(
            MonitoringRepository repository,
            MonitoringPeriodResolver periods,
            MonitoringWaveDetector waves,
            ToolGateway gateway,
            AgentTraceService traces,
            @Value("${wiki.monitoring.lease-seconds:600}") int leaseSeconds) {
        this.repository = repository;
        this.periods = periods;
        this.waves = waves;
        this.gateway = gateway;
        this.traces = traces;
        this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    public Map<String, Object> runManual(
            String planId, String hospitalId, String statPeriod, HospitalPrincipal principal) {
        Map<String, Object> plan = repository.plan(planId, hospitalId)
                .orElseThrow(() -> new MonitoringException("MONITOR_NOT_FOUND", "运行计划不存在。", 404));
        return run(plan, statPeriod, "manual", "REQ_" + shortId(), null, principal);
    }

    public Map<String, Object> runScheduled(Map<String, Object> plan) {
        HospitalPrincipal system = new HospitalPrincipal(
                "monitoring_scheduler", "monitoring_scheduler", text(plan.get("hospital_id")),
                Set.of(), false, "MONITORING_" + shortId());
        return run(plan, null, "scheduled", null, null, system);
    }

    public Map<String, Object> diagnoseAlert(
            String alertId, String hospitalId, HospitalPrincipal principal) {
        Map<String, Object> alert = repository.alert(alertId, hospitalId)
                .orElseThrow(() -> new MonitoringException("MONITOR_NOT_FOUND", "指标预警不存在。", 404));
        Map<String, Object> result = repository.result(number(alert.get("result_id")).longValue(), hospitalId);
        String traceId = "TRACE_" + shortId();
        AgentRunState state = new AgentRunState();
        state.currentRuleId(text(alert.get("rule_id")));
        AgentRuntimeContext context = new AgentRuntimeContext(
                principal, "REQ_" + shortId(), traceId, text(result.get("data_source")));
        repository.updateAlertDiagnosis(alertId, hospitalId, "running", null, now());
        ToolResult diagnosis = gateway.execute("diagnose_indicator_issue", Map.of(
                "rule_id", alert.get("rule_id"),
                "issue_description", "人工重新诊断指标监控预警",
                "stat_period", text(result.get("stat_period"))), context, state).join();
        if (!diagnosis.ok()) {
            repository.updateAlertDiagnosis(alertId, hospitalId, "failed", null, now());
            throw new MonitoringException(diagnosis.code(), diagnosis.summary(), 503);
        }
        return repository.updateAlertDiagnosis(alertId, hospitalId, "completed",
                text(diagnosis.data().get("report_id")), now());
    }

    private Map<String, Object> run(
            Map<String, Object> plan,
            String requestedPeriod,
            String trigger,
            String requestId,
            Long retryOf,
            HospitalPrincipal principal) {
        String planId = text(plan.get("plan_id"));
        String traceId = "TRACE_" + shortId();
        long started = System.currentTimeMillis();
        traces.start(traceId, principal.sessionId(), principal, "monitor:" + plan.get("rule_id"));
        boolean leased = false;
        try {
            if ("scheduled".equals(trigger)) {
                LocalDateTime current = now();
                leased = repository.acquireLease(planId, workerId, current,
                        current.plusSeconds(leaseSeconds));
                traceNode(traceId, "monitor_lease_acquire", leased ? "success" : "failed", started,
                        Map.of("plan_id", planId), Map.of("lease_status", leased ? "acquired" : "contended"),
                        null, null, text(plan.get("rule_id")));
                if (!leased) {
                    Map<String, Object> skipped = new LinkedHashMap<>();
                    skipped.put("status", "skipped"); skipped.put("reason", "lease_not_acquired");
                    skipped.put("plan_id", planId); skipped.put("trace_id", traceId);
                    traces.finishStandalone(traceId, "incomplete", "indicator_monitoring", "运行租约未获取。", 0);
                    return skipped;
                }
            }

            Period period = periods.resolve(text(plan.get("frequency")), requestedPeriod,
                    text(plan.get("timezone")));
            String runKey = "scheduled".equals(trigger) ? planId + ":" + period.label()
                    : trigger + ":" + (requestId == null ? shortId() : requestId);
            Map<String, Object> existing = repository.resultByRunKey(runKey).orElse(null);
            if (existing != null) {
                Map<String, Object> reused = new LinkedHashMap<>(existing);
                reused.put("reused", true); reused.put("trace_id", traceId);
                traces.finishStandalone(traceId, "success", "indicator_monitoring", "复用同一运行键结果。", 0);
                return reused;
            }
            return executePlan(plan, period, trigger, runKey, retryOf, principal, traceId, started);
        } catch (RuntimeException exception) {
            traces.finishStandalone(traceId, "failed", "indicator_monitoring", safeMessage(exception), 1);
            throw exception;
        } finally {
            if (leased) {
                LocalDateTime next = periods.nextRun(text(plan.get("frequency")), text(plan.get("run_time")),
                        number(plan.get("day_of_month")).intValue(), text(plan.get("timezone")));
                repository.releaseLease(planId, workerId, now(), next);
            }
        }
    }

    private Map<String, Object> executePlan(
            Map<String, Object> plan, Period period, String trigger, String runKey, Long retryOf,
            HospitalPrincipal principal, String traceId, long traceStarted) {
        String ruleId = text(plan.get("rule_id"));
        AgentRunState state = new AgentRunState();
        state.subtaskId("monitor:" + text(plan.get("plan_id")));
        state.currentRuleId(ruleId);
        AgentRuntimeContext context = new AgentRuntimeContext(
                principal, "REQ_" + shortId(), traceId, null);
        try {
            ToolResult rule = call("get_effective_rule", Map.of("rule_id", ruleId), context, state);
            long sqlStarted = System.currentTimeMillis();
            ToolResult prepared = call("prepare_indicator_sql", Map.of(
                    "rule_id", ruleId,
                    "stat_start_time", period.start().toString(),
                    "stat_end_time", period.end().toString()), context, state);
            String sqlId = text(prepared.data().get("sql_id"));
            if (sqlId.isBlank()) {
                throw new MonitoringException("MONITOR_SQL_OBJECT_MISSING", "受控 SQL 未生成可执行对象。", 503);
            }
            state.validatedSqlIds().add(sqlId);
            ToolResult trial = call("trial_run_indicator_sql",
                    Map.of("sql_id", sqlId), context, state);
            Map<String, Object> traceOutput = new LinkedHashMap<>();
            traceOutput.put("run_id", trial.data().get("run_id"));
            traceOutput.put("result_value", trial.data().get("result_value"));
            traceOutput.put("no_sample", trial.data().get("no_sample"));
            traceNode(traceId, "monitor_indicator_execute_mcp", "success", sqlStarted,
                    Map.of("rule_id", ruleId, "stat_period", period.label()),
                    traceOutput, sqlId, text(trial.data().get("run_id")), ruleId);
            Map<String, Object> saved = saveSuccess(plan, period, trigger, runKey, retryOf, rule, trial);
            saved = applyWave(plan, period, saved);
            Map<String, Object> response = new LinkedHashMap<>(saved);
            if (Boolean.TRUE.equals(saved.get("is_abnormal"))) {
                response.put("alert", createWaveAlert(plan, saved, context, state));
            } else response.put("alert", null);
            response.put("trace_id", traceId);
            traces.finishStandalone(traceId, "success", "indicator_monitoring",
                    text(saved.get("wave_status")), 0);
            return response;
        } catch (RuntimeException exception) {
            Map<String, Object> failed = saveFailure(plan, period, trigger, runKey, retryOf, exception);
            Map<String, Object> response = new LinkedHashMap<>(failed);
            response.put("trace_id", traceId);
            traceNode(traceId, "monitor_indicator_execute_mcp", "failed", traceStarted,
                    Map.of("rule_id", ruleId, "stat_period", period.label()), Map.of(), null, null, ruleId);
            traces.finishStandalone(traceId, "failed", "indicator_monitoring", safeMessage(exception), 1);
            return response;
        }
    }

    private Map<String, Object> saveSuccess(
            Map<String, Object> plan, Period period, String trigger, String runKey, Long retryOf,
            ToolResult rule, ToolResult trial) {
        Map<String, Object> data = trial.data();
        boolean noSample = Boolean.TRUE.equals(data.get("no_sample"));
        Map<String, Object> value = baseResult(plan, period, trigger, runKey, retryOf);
        value.put("run_status", noSample ? "no_sample" : "success");
        value.put("result_value", numberOrNull(data.get("result_value")));
        value.put("no_sample", noSample);
        value.put("effective_level", rule.data().get("effective_level"));
        value.put("national_version", rule.data().get("national_version"));
        value.put("hospital_version", rule.data().get("hospital_version"));
        value.put("data_source", first(data.get("db_source_id"), data.get("source")));
        value.put("duration_ms", number(data.get("duration_ms")).intValue());
        value.put("run_id", data.get("run_id"));
        return repository.createRunResult(value);
    }

    private Map<String, Object> applyWave(Map<String, Object> plan, Period period, Map<String, Object> result) {
        Period momPeriod = periods.compare(period, "mom");
        Period yoyPeriod = periods.compare(period, "yoy");
        Map<String, Object> mom = repository.successfulResult(text(plan.get("hospital_id")),
                text(plan.get("rule_id")), momPeriod.start(), momPeriod.end()).orElse(null);
        Map<String, Object> yoy = bool(plan.get("yoy_enabled"))
                ? repository.successfulResult(text(plan.get("hospital_id")), text(plan.get("rule_id")),
                        yoyPeriod.start(), yoyPeriod.end()).orElse(null) : null;
        Double currentValue = doubleOrNull(result.get("result_value"));
        Double momValue = mom == null ? null : doubleOrNull(mom.get("result_value"));
        Double yoyValue = yoy == null ? null : doubleOrNull(yoy.get("result_value"));
        Wave wave = waves.detect(currentValue, momValue, yoyValue, bool(plan.get("mom_enabled")),
                number(plan.get("mom_threshold_pct")).doubleValue(), bool(plan.get("yoy_enabled")),
                number(plan.get("yoy_threshold_pct")).doubleValue(), bool(result.get("no_sample")));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("previous_value", momValue);
        update.put("mom_baseline_result_id", mom == null ? null : mom.get("id"));
        update.put("mom_change_rate", wave.momChangeRate());
        update.put("yoy_baseline_result_id", yoy == null ? null : yoy.get("id"));
        update.put("yoy_change_rate", wave.yoyChangeRate());
        update.put("wave_status", wave.conclusionCode());
        update.put("is_abnormal", wave.abnormal());
        Map<String, Object> saved = new LinkedHashMap<>(
                repository.updateWave(number(result.get("id")).longValue(), update));
        saved.put("mom_value", momValue);
        saved.put("yoy_value", yoyValue);
        return saved;
    }

    private Map<String, Object> createWaveAlert(
            Map<String, Object> plan, Map<String, Object> result,
            AgentRuntimeContext context, AgentRunState state) {
        Map<String, Object> alertValue = new LinkedHashMap<>();
        alertValue.put("hospital_id", plan.get("hospital_id")); alertValue.put("rule_id", plan.get("rule_id"));
        alertValue.put("plan_id", plan.get("plan_id")); alertValue.put("result_id", result.get("id"));
        alertValue.put("alert_type", "wave"); alertValue.put("alert_level", "warning");
        alertValue.put("conclusion_code", result.get("wave_status"));
        alertValue.put("current_value", result.get("result_value"));
        alertValue.put("mom_value", result.get("mom_value"));
        alertValue.put("mom_change_rate", result.get("mom_change_rate"));
        alertValue.put("yoy_value", result.get("yoy_value"));
        alertValue.put("yoy_change_rate", result.get("yoy_change_rate"));
        alertValue.put("diagnose_status", "running");
        Map<String, Object> alert = repository.createAlert(alertValue);
        ToolResult diagnosis = gateway.execute("diagnose_indicator_issue", Map.of(
                "rule_id", plan.get("rule_id"),
                "issue_description", "监控结果波动超过配置阈值",
                "stat_period", text(result.get("stat_period"))), context, state).join();
        return repository.updateAlertDiagnosis(text(alert.get("alert_id")), text(plan.get("hospital_id")),
                diagnosis.ok() ? "completed" : "failed",
                diagnosis.ok() ? text(diagnosis.data().get("report_id")) : null, now());
    }

    private Map<String, Object> saveFailure(
            Map<String, Object> plan, Period period, String trigger, String runKey, Long retryOf,
            RuntimeException exception) {
        Map<String, Object> value = baseResult(plan, period, trigger, runKey, retryOf);
        value.put("run_status", "failed"); value.put("result_value", null); value.put("no_sample", false);
        value.put("error_code", exception instanceof MonitoringException monitor ? monitor.code() : "MONITOR_EXECUTION_FAILED");
        value.put("error_message", safeMessage(exception));
        Map<String, Object> failed = repository.createRunResult(value);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("hospital_id", plan.get("hospital_id")); alert.put("rule_id", plan.get("rule_id"));
        alert.put("plan_id", plan.get("plan_id")); alert.put("result_id", failed.get("id"));
        alert.put("alert_type", "execution_failed"); alert.put("alert_level", "error");
        alert.put("conclusion_code", "indicator_execution_failed"); alert.put("diagnose_status", "not_applicable");
        Map<String, Object> response = new LinkedHashMap<>(failed);
        response.put("alert", repository.createAlert(alert));
        return response;
    }

    private Map<String, Object> baseResult(
            Map<String, Object> plan, Period period, String trigger, String runKey, Long retryOf) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("run_key", runKey); value.put("plan_id", plan.get("plan_id"));
        value.put("retry_of_result_id", retryOf); value.put("hospital_id", plan.get("hospital_id"));
        value.put("rule_id", plan.get("rule_id")); value.put("trigger_type", trigger);
        value.put("stat_start_time", period.start()); value.put("stat_end_time", period.end());
        value.put("stat_period", period.label()); value.put("created_at", now());
        value.put("is_abnormal", false); value.put("wave_status", "baseline_insufficient");
        return value;
    }

    private ToolResult call(
            String toolName, Map<String, Object> arguments, AgentRuntimeContext context, AgentRunState state) {
        ToolResult result = gateway.execute(toolName, arguments, context, state).join();
        if (!result.ok()) throw new MonitoringException(result.code(), result.summary(), 503);
        return result;
    }

    private void traceNode(String traceId, String name, String status, long started,
            Map<String, Object> input, Map<String, Object> output,
            String sqlId, String runId, String ruleId) {
        long ended = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("node_name", name); event.put("node_type", name.contains("mcp") ? "database" : "code");
        event.put("status", status); event.put("started_at_epoch_ms", started); event.put("ended_at_epoch_ms", ended);
        event.put("duration_ms", Math.max(1, ended - started)); event.put("input", input); event.put("output", output);
        event.put("tool_name", name.contains("mcp") ? "trial_run_indicator_sql" : "");
        event.put("capability", "indicator_monitoring"); event.put("sql_id", sqlId); event.put("run_id", runId);
        event.put("rule_id", ruleId); event.put("subtask_id", "monitor");
        traces.recordStandaloneNode(traceId, event);
    }

    private static LocalDateTime now() { return LocalDateTime.now().withNano(0); }
    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static Number number(Object value) { return value instanceof Number n ? n : value == null || value.toString().isBlank() ? 0 : Double.parseDouble(value.toString()); }
    private static Number numberOrNull(Object value) { return value == null || value.toString().isBlank() ? null : number(value); }
    private static Double doubleOrNull(Object value) { Number n = numberOrNull(value); return n == null ? null : n.doubleValue(); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    private static Object first(Object left, Object right) { return left == null || left.toString().isBlank() ? right : left; }
    private static String safeMessage(Throwable exception) {
        if (exception instanceof MonitoringException monitor) {
            String value = monitor.getMessage();
            return value == null || value.isBlank() ? "指标监控执行失败。"
                    : value.substring(0, Math.min(1000, value.length()));
        }
        return "指标监控执行失败，内部错误已记录。";
    }
}
