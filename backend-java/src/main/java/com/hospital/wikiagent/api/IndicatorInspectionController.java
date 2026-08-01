package com.hospital.wikiagent.api;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchJobSnapshot;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.model.AgentModelInfo;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.IndicatorDetailException;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 将“进入排查”结构化动作绑定到已持久化批次事实，并记录 AI 分析审计。
 *
 * <p>接口不运行 SQL、不修改指标值，只从经过医院和用户权限校验的批次快照中组装确定性事实，
 * 生成供云端模型解释的结构化提示词，并记录操作者、指标、口径和时间。模型只能解释这些事实，
 * 不能借此切换口径或改写卡片结果。</p>
 */
@RestController
@RequestMapping("/api/agent/actions")
public class IndicatorInspectionController {
    private final HospitalAuthService auth;
    private final BatchJobStore jobs;
    private final JdbcTemplate jdbc;
    private final AgentModelRegistry models;
    private final AgentModelInvoker modelInvoker;

    public IndicatorInspectionController(
            HospitalAuthService auth,
            BatchJobStore jobs,
            JdbcTemplate jdbc,
            AgentModelRegistry models,
            AgentModelInvoker modelInvoker) {
        this.auth = auth;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.models = models;
        this.modelInvoker = modelInvoker;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_indicator_analysis_audit (
                  audit_id VARCHAR(64) PRIMARY KEY,
                  batch_run_id VARCHAR(64) NOT NULL,
                  hospital_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64) NOT NULL,
                  rule_id VARCHAR(64) NOT NULL,
                  profile_id VARCHAR(64),
                  action VARCHAR(40) NOT NULL,
                  model_requirement VARCHAR(64) NOT NULL,
                  created_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_indicator_analysis_result (
                  audit_id VARCHAR(64) PRIMARY KEY,
                  model_id VARCHAR(64) NOT NULL,
                  answer TEXT NOT NULL,
                  completed_at TIMESTAMP NOT NULL
                )
                """);
    }

    @PostMapping("/inspect-indicator")
    public Map<String, Object> inspect(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody InspectIndicatorRequest request) {
        HospitalPrincipal principal =
                auth.authenticate(BearerTokens.require(authorization));
        BatchTaskSnapshot task = jobs.loadTask(
                        request.batchRunId(),
                        principal.hospitalId(),
                        principal.userId(),
                        request.indicatorId(),
                        request.profileId())
                .orElseThrow(() -> new IndicatorDetailException(
                        "INSPECTION_RUN_NOT_FOUND",
                        "原批次指标不存在或无权访问。",
                        HttpStatus.NOT_FOUND));
        String auditId = "IAUD_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        jdbc.update("""
                INSERT INTO med_indicator_analysis_audit (
                  audit_id, batch_run_id, hospital_id, user_id, rule_id,
                  profile_id, action, model_requirement, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                task.batchRunId(),
                principal.hospitalId(),
                principal.userId(),
                task.ruleId(),
                task.profileId(),
                request.action(),
                "cloud_api_only",
                Timestamp.from(Instant.now()));

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("batchRunId", task.batchRunId());
        facts.put("indicatorId", task.ruleId());
        facts.put("indicatorName", task.ruleName());
        facts.put("profileId", task.profileId());
        facts.put("profileName", task.profileName());
        facts.put("status", task.status());
        facts.put("resultValue", task.resultValue());
        facts.put("calculationDisplay", task.calculationDisplay());
        facts.put("numeratorCount", task.numeratorCount());
        facts.put("denominatorCount", task.denominatorCount());
        facts.put("sampleCount", task.sampleCount());
        facts.put("unit", task.unit());
        facts.put("targetValue", task.targetValue());
        facts.put("targetDirection", task.targetDirection());
        facts.put("qualityStatus", task.qualityStatus());
        facts.put("statStart", task.statStart());
        facts.put("statEnd", task.statEnd());
        facts.put("detailKind", task.detailKind());
        facts.put("errorCode", task.errorCode());
        facts.put("errorMessage", task.errorMessage());

        String prompt = """
                进入指标排查。你只能解释下列已固化事实，不得重新计算、改写或补造指标值；
                如果事实不足，请明确指出需人工确认的字段。请区分达标状态与数据质量，
                给出可能原因、核查顺序和建议，但不得自动替换公版口径。

                结构化事实：%s
                审计编号：%s
                """.formatted(facts, auditId);
        AgentModelInfo model = models.requireInfo(request.modelId());
        if ("ollama".equals(model.provider()) || !model.available()) {
            throw new IndicatorDetailException(
                    "INSPECTION_CLOUD_MODEL_REQUIRED",
                    "指标排查必须使用可用的云端 API 模型，已停止调用。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String answer = modelInvoker.complete(
                        model.id(),
                        """
                                你是医院核心制度指标排查助手。只能解释用户提供的已固化批次事实，
                                不得重新计算指标、生成 SQL、改写数值或自动切换口径。
                                回答应简洁区分“结果状态”和“数据质量”，列出最可能原因、核查顺序、
                                需要人工确认的信息。事实不足时必须明确说不知道。
                                """,
                        prompt,
                        Duration.ofSeconds(60))
                .content()
                .strip();
        jdbc.update("""
                INSERT INTO med_indicator_analysis_result (
                  audit_id, model_id, answer, completed_at
                ) VALUES (?, ?, ?, ?)
                """, auditId, model.id(), answer, Timestamp.from(Instant.now()));
        return Map.of(
                "action", request.action(),
                "auditId", auditId,
                "requiredModelProvider", "cloud_api",
                "modelId", model.id(),
                "facts", java.util.Collections.unmodifiableMap(facts),
                "prompt", prompt,
                "answer", answer);
    }

    /**
     * 批次预制问题不再进入自由问句路由。答案只由已保存批次事实生成，
     * 因而不会触发重新核算、错误选指标或口径漂移。
     */
    @PostMapping("/analyze-batch")
    public Map<String, Object> analyzeBatch(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Valid @RequestBody BatchAnalysisRequest request) {
        HospitalPrincipal principal =
                auth.authenticate(BearerTokens.require(authorization));
        BatchJobSnapshot job = jobs.loadJob(
                        request.batchRunId(), principal.hospitalId(), principal.userId())
                .orElseThrow(() -> new IndicatorDetailException(
                        "BATCH_ANALYSIS_RUN_NOT_FOUND",
                        "原批次不存在或无权访问。",
                        HttpStatus.NOT_FOUND));
        List<BatchTaskSnapshot> tasks = jobs.loadTasks(
                request.batchRunId(), principal.hospitalId(), principal.userId());
        if (tasks.isEmpty()) {
            throw new IndicatorDetailException(
                    "BATCH_ANALYSIS_EMPTY",
                    "原批次没有可分析的指标事实。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String auditId = "BAUD_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        jdbc.update("""
                INSERT INTO med_indicator_analysis_audit (
                  audit_id, batch_run_id, hospital_id, user_id, rule_id,
                  profile_id, action, model_requirement, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                job.batchRunId(),
                principal.hospitalId(),
                principal.userId(),
                "__BATCH__",
                null,
                request.action(),
                "deterministic_saved_facts",
                Timestamp.from(Instant.now()));

        String displayPrompt;
        String answer;
        if ("batch_confirmation_checklist".equals(request.action())) {
            displayPrompt = "把本批次最需要处理的指标生成确认清单";
            answer = confirmationChecklist(job, tasks);
        } else {
            displayPrompt = "分析本批次哪些未达标更可能是数据问题";
            answer = dataQualityReview(job, tasks);
        }
        jdbc.update("""
                INSERT INTO med_indicator_analysis_result (
                  audit_id, model_id, answer, completed_at
                ) VALUES (?, ?, ?, ?)
                """, auditId, "deterministic_saved_facts", answer, Timestamp.from(Instant.now()));
        return Map.of(
                "action", request.action(),
                "auditId", auditId,
                "batchRunId", job.batchRunId(),
                "displayPrompt", displayPrompt,
                "answer", answer);
    }

    private static String confirmationChecklist(
            BatchJobSnapshot job, List<BatchTaskSnapshot> tasks) {
        List<BatchFinding> all = tasks.stream()
                .map(IndicatorInspectionController::finding)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(BatchFinding::priority)
                        .thenComparing(item -> item.task().ruleId()))
                .toList();
        List<BatchFinding> focus = diversifiedFocus(all, 5);
        StringBuilder answer = new StringBuilder()
                .append("## 重点指标确认清单\n\n")
                .append("批次：`").append(job.batchRunId()).append("`。")
                .append("共发现 ").append(all.size()).append(" 个需关注口径，")
                .append("下面优先列出跨类别的 ").append(focus.size()).append(" 项；")
                .append("无样本不会被误写成未达标。\n\n");
        for (int index = 0; index < focus.size(); index++) {
            BatchFinding item = focus.get(index);
            answer.append(index + 1).append(". **")
                    .append(item.label()).append("｜")
                    .append(item.task().ruleName()).append("**（`")
                    .append(item.task().ruleId()).append("`）\n")
                    .append("   - 当前事实：").append(item.reason()).append("\n")
                    .append("   - 建议确认：").append(confirmAction(item)).append("\n");
        }
        answer.append("\n> 清单只引用已保存批次事实，未重算指标、未切换口径。");
        return answer.toString();
    }

    private static String dataQualityReview(
            BatchJobSnapshot job, List<BatchTaskSnapshot> tasks) {
        List<BatchTaskSnapshot> unavailable = tasks.stream()
                .filter(task -> "FAILED".equals(task.status())
                        || "NO_SAMPLE".equals(task.status()))
                .toList();
        List<BatchTaskSnapshot> abnormalQuality = tasks.stream()
                .filter(task -> "SUCCESS".equals(task.status()))
                .filter(task -> qualityAbnormal(task.qualityStatus()))
                .toList();
        List<BatchTaskSnapshot> notReached = tasks.stream()
                .filter(task -> "SUCCESS".equals(task.status()))
                .filter(task -> Boolean.FALSE.equals(reached(task)))
                .toList();
        List<BatchTaskSnapshot> likelyDataIssue = notReached.stream()
                .filter(task -> qualityAbnormal(task.qualityStatus()))
                .toList();

        StringBuilder answer = new StringBuilder()
                .append("## 未达标与数据问题核查\n\n")
                .append("批次：`").append(job.batchRunId()).append("`。\n\n")
                .append("- **真正未达标**：").append(notReached.size()).append(" 个口径。\n")
                .append("- **有明确质量异常证据且未达标**：")
                .append(likelyDataIssue.size()).append(" 个口径。\n")
                .append("- **无样本或计算失败**：").append(unavailable.size())
                .append(" 个口径；这些属于“无法计算”，**不能归入未达标**。\n")
                .append("- **计算成功但质量标记异常**：").append(abnormalQuality.size())
                .append(" 个口径。\n\n");
        if (likelyDataIssue.isEmpty()) {
            answer.append("当前保存事实中，**没有足够证据把某个未达标结果归因于数据质量**。")
                    .append("建议先按指标业务含义处理未达标项，再单独核查无样本/失败项的数据采集。\n");
        } else {
            answer.append("### 优先核查\n\n");
            for (BatchTaskSnapshot task : likelyDataIssue) {
                answer.append("- **").append(task.ruleName()).append("**（`")
                        .append(task.ruleId()).append("`）：结果 ")
                        .append(displayValue(task)).append("，质量标记 ")
                        .append(task.qualityStatus()).append("。\n");
            }
        }
        if (!unavailable.isEmpty()) {
            answer.append("\n### 数据可用性问题（不是未达标）\n\n");
            unavailable.stream().limit(10).forEach(task -> answer
                    .append("- **").append(task.ruleName()).append("**（`")
                    .append(task.ruleId()).append("`）：")
                    .append("NO_SAMPLE".equals(task.status())
                            ? "统计窗口内无可核算记录。"
                            : first(task.errorMessage(), "计算链路失败。"))
                    .append("\n"));
            if (unavailable.size() > 10) {
                answer.append("- 其余 ").append(unavailable.size() - 10)
                        .append(" 项请在完整报告中查看。\n");
            }
        }
        answer.append("\n> 结论来自已保存批次事实，未重新执行 SQL。");
        return answer.toString();
    }

    private static BatchFinding finding(BatchTaskSnapshot task) {
        if ("FAILED".equals(task.status())) {
            return new BatchFinding(task, "failure", "计算失败",
                    first(task.errorMessage(), "数据源或执行链路未完成"), 0);
        }
        if ("NO_SAMPLE".equals(task.status())) {
            return new BatchFinding(task, "quality", "无可用样本",
                    "统计窗口内没有可核算记录", 1);
        }
        if (qualityAbnormal(task.qualityStatus())) {
            return new BatchFinding(task, "quality", "数据质量",
                    "质量标记：" + task.qualityStatus(), 1);
        }
        Boolean reached = reached(task);
        if (reached == null) {
            return new BatchFinding(task, "pending", "待确认",
                    "缺少可判定的目标值或方向", 2);
        }
        if (!reached) {
            return new BatchFinding(task, "not_reached", "未达标",
                    "结果 " + displayValue(task) + "，目标 "
                            + first(task.targetDirection(), "") + first(task.targetValue(), "待确认"),
                    3);
        }
        return null;
    }

    private static List<BatchFinding> diversifiedFocus(
            List<BatchFinding> all, int limit) {
        List<BatchFinding> selected = new ArrayList<>();
        List<String> categories = List.of("failure", "quality", "pending", "not_reached");
        for (String category : categories) {
            all.stream()
                    .filter(item -> category.equals(item.category()))
                    .filter(item -> !selected.contains(item))
                    .findFirst()
                    .ifPresent(selected::add);
            if (selected.size() >= limit) {
                return selected;
            }
        }
        for (BatchFinding item : all) {
            if (!selected.contains(item)) {
                selected.add(item);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected;
    }

    private static String confirmAction(BatchFinding item) {
        return switch (item.category()) {
            case "failure" -> "确认错误所指的数据源/知识库 SQL 是否已部署，修复后重跑；不要补造结果。";
            case "quality" -> "确认源表采集覆盖、统计窗口和业务模块是否实际启用。";
            case "pending" -> "由业务负责人确认目标值和升降方向后再判定。";
            default -> "核对明细母集与业务流程，确认是真实改善机会还是采集偏差。";
        };
    }

    private static Boolean reached(BatchTaskSnapshot task) {
        if (task.resultValue() == null || task.targetValue() == null
                || task.targetDirection() == null) {
            return null;
        }
        try {
            double target = Double.parseDouble(task.targetValue()
                    .replace("%", "").replace("倍", "").strip());
            String direction = task.targetDirection().replace(" ", "");
            if (direction.contains("<")) {
                return direction.contains("=")
                        ? task.resultValue() <= target : task.resultValue() < target;
            }
            if (direction.contains(">")) {
                return direction.contains("=")
                        ? task.resultValue() >= target : task.resultValue() > target;
            }
            return Double.compare(task.resultValue(), target) == 0;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean qualityAbnormal(String qualityStatus) {
        if (qualityStatus == null || qualityStatus.isBlank()) {
            return false;
        }
        String normalized = qualityStatus.strip().toUpperCase();
        return !List.of("NORMAL", "OK", "PASS", "SUCCESS", "正常").contains(normalized);
    }

    private static String displayValue(BatchTaskSnapshot task) {
        if (task.resultValue() == null) {
            return "—";
        }
        String suffix = "percentage".equals(task.unit()) || "percent".equals(task.unit())
                ? "%" : "ratio".equals(task.unit()) ? " 倍" : first(task.unit(), "");
        return task.resultValue() + suffix;
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record InspectIndicatorRequest(
            @NotBlank
            @Pattern(regexp = "^inspect_indicator$")
            String action,
            @NotBlank @Size(max = 64) String batchRunId,
            @NotBlank @Size(max = 64) String indicatorId,
            @Size(max = 64) String profileId,
            @NotBlank @Size(max = 64) String modelId) {
    }

    public record BatchAnalysisRequest(
            @NotBlank
            @Pattern(regexp = "^(batch_confirmation_checklist|batch_data_quality_review)$")
            String action,
            @NotBlank @Size(max = 64) String batchRunId) {
    }

    private record BatchFinding(
            BatchTaskSnapshot task,
            String category,
            String label,
            String reason,
            int priority) {
    }
}
