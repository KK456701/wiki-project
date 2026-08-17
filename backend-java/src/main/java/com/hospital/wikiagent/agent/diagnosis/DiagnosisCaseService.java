package com.hospital.wikiagent.agent.diagnosis;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator;
import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator.ValidationTarget;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.Decision;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;
import com.hospital.wikiagent.agent.initialization.KnowledgeDataDictionary;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.EntitySqlDialectResolver;
import com.hospital.wikiagent.agent.mras.IndicatorDataFlowSqlExporter;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.IndicatorDetailException;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 对话式指标异常排查的顺序状态机。它冻结本次医院口径，按表字段、事件配置、
 * 现场数值、案例查因、候选方案、影子试跑和医院草稿保存推进；程序负责关卡判定，
 * 模型只在允许的步骤解释证据或草拟 SQL，不能跳关、执行 SQL或激活版本。
 */
@Service
public class DiagnosisCaseService {
    private final DiagnosisCaseStore store;
    private final RuleReadRepository rules;
    private final EntityPageParser entities;
    private final IndicatorDataFlowSqlExporter dataFlowSqlExporter;
    private final MrasTemplateRenderer templateRenderer;
    private final BatchDataInitializationValidator initialization;
    private final DiagnosisEventGateService eventGate;
    private final DiagnosisValueGateService valueGate;
    private final DiagnosisCaseEvidenceService evidenceService;
    private final ReadOnlySqlValidator sqlValidator;
    private final MrasSqlLineageAnalyzer lineage;
    private final AgentModelRegistry modelRegistry;
    private final AgentModelInvoker models;
    private final ObjectProvider<DiagnosisShadowRunner> shadowRunner;
    private final HospitalKnowledgeDraftService drafts;
    private final AgentConversationMemory conversations;
    private KnowledgeDataDictionary dataDictionary;
    private EntitySqlDialectResolver sqlDialects;
    private DiagnosisAutonomousRunner autonomousRunner;
    private PublicDataScreeningRuleService publicScreeningRules;
    private DiagnosisAssistantConversationStore assistantConversations;
    private DiagnosisSqlRepairService sqlRepairs;
    private final Set<String> cancelledClarificationRequests = ConcurrentHashMap.newKeySet();

    public DiagnosisCaseService(
            DiagnosisCaseStore store,
            RuleReadRepository rules,
            EntityPageParser entities,
            IndicatorDataFlowSqlExporter dataFlowSqlExporter,
            MrasTemplateRenderer templateRenderer,
            BatchDataInitializationValidator initialization,
            DiagnosisEventGateService eventGate,
            DiagnosisValueGateService valueGate,
            DiagnosisCaseEvidenceService evidenceService,
            ReadOnlySqlValidator sqlValidator,
            MrasSqlLineageAnalyzer lineage,
            AgentModelRegistry modelRegistry,
            AgentModelInvoker models,
            ObjectProvider<DiagnosisShadowRunner> shadowRunner,
            HospitalKnowledgeDraftService drafts,
            AgentConversationMemory conversations) {
        this.store = store;
        this.rules = rules;
        this.entities = entities;
        this.dataFlowSqlExporter = dataFlowSqlExporter;
        this.templateRenderer = templateRenderer;
        this.initialization = initialization;
        this.eventGate = eventGate;
        this.valueGate = valueGate;
        this.evidenceService = evidenceService;
        this.sqlValidator = sqlValidator;
        this.lineage = lineage;
        this.modelRegistry = modelRegistry;
        this.models = models;
        this.shadowRunner = shadowRunner;
        this.drafts = drafts;
        this.conversations = conversations;
    }

    @Autowired(required = false)
    void setDataDictionary(KnowledgeDataDictionary value) {
        this.dataDictionary = value;
    }

    @Autowired(required = false)
    void setSqlDialects(EntitySqlDialectResolver value) {
        this.sqlDialects = value;
    }

    @Autowired(required = false)
    void setAutonomousRunner(DiagnosisAutonomousRunner value) {
        this.autonomousRunner = value;
    }

    @Autowired(required = false)
    void setPublicScreeningRules(PublicDataScreeningRuleService value) {
        this.publicScreeningRules = value;
    }

    @Autowired(required = false)
    void setAssistantConversations(DiagnosisAssistantConversationStore value) {
        this.assistantConversations = value;
    }

    @Autowired
    void setSqlRepairs(DiagnosisSqlRepairService value) {
        this.sqlRepairs = value;
    }

    public Map<String, Object> sqlRepairOptions(
            HospitalPrincipal principal, String caseId) {
        return sqlRepairs.options(load(principal, caseId));
    }

    public Map<String, Object> analyzeUploadedSql(
            HospitalPrincipal principal, String caseId, Map<String, Object> request) {
        DiagnosisCaseSnapshot current = load(principal, caseId);
        Map<String, Object> result = sqlRepairs.analyze(current, request);
        Map<String, Object> auditInput = new LinkedHashMap<>(request);
        auditInput.remove("sqlText");
        auditInput.put("sqlTextRecorded", false);
        store.appendEvent(current, "ANALYZE_UPLOADED_SQL", Map.copyOf(auditInput),
                Map.of("status", "COMPLETED", "impactAnalysis",
                        result.getOrDefault("impactAnalysis", Map.of())), current.modelId());
        return result;
    }

    public DiagnosisCaseSnapshot createUploadedChangeSet(
            HospitalPrincipal principal, String caseId, Map<String, Object> request) {
        DiagnosisCaseSnapshot current = load(principal, caseId);
        Map<String, Object> candidate = sqlRepairs.createChangeSet(current, request);
        Map<String, Object> proposal = new LinkedHashMap<>(request);
        proposal.remove("sqlText");
        proposal.put("type", "SQL_CHANGE");
        proposal.put("source", "UPLOADED_SQL");
        proposal.put("impactAnalysis", candidate.getOrDefault("impactAnalysis", Map.of()));
        DiagnosisCaseSnapshot updated = update(current, "CANDIDATE_READY", "SHADOW_TRIAL",
                current.gateResults(), current.evidence(), current.causeConclusion(),
                Map.copyOf(proposal), candidate, Map.of(), current.releaseResult());
        store.save(updated);
        store.appendEvent(updated, "CREATE_CANDIDATE_CHANGE_SET", proposal,
                Map.of("status", updated.status(), "changeSetId",
                        candidate.getOrDefault("changeSetId", "")), updated.modelId());
        conversations.touchSession(principal, updated.sessionId());
        return updated;
    }

    public DiagnosisCaseSnapshot create(
            HospitalPrincipal principal, CreateCommand command) {
        LocalDateTime start = time(command.statStart(), "统计开始时间");
        LocalDateTime end = time(command.statEnd(), "统计结束时间");
        if (!start.isBefore(end)) fail("DIAGNOSIS_PERIOD_INVALID", "统计开始时间必须早于结束时间");
        Map<String, Object> rule = rules.effectiveRule(
                command.ruleId(), principal.hospitalId(), command.profileId());
        rule = dataFlowSqlExporter.enrichRule(rule, command.statStart(), command.statEnd());
        String profileId = text(rule.get("profileId"));
        EntityPageData entity = entities.getEntity(profileId, principal.hospitalId());
        if (entity == null) fail("DIAGNOSIS_PROFILE_NOT_FOUND", "找不到当前医院的指标口径");
        Map<String, Object> caseInput = new LinkedHashMap<>(command.caseInput());
        caseInput.put("statStart", command.statStart());
        caseInput.put("statEnd", command.statEnd());
        Map<String, Object> caliber = new LinkedHashMap<>();
        copy(rule, caliber, "ruleId", "ruleName", "profileId", "profileName", "effectiveLevel",
                "definition", "formula", "numeratorRule", "denominatorRule", "caliber",
                "dataSource", "standardSql", "sourceExtractSql", "dataFlow",
                "knowledgeReleaseId");
        List<Map<String, Object>> scopeFields = enrichFieldSuggestions(
                SafeSqlPredicatePatcher.fieldSuggestions(
                        MrasSqlExecutionService.stripLeadingTrailingQuotes(sourceSql(entity))));
        caliber.put("diagnosisScopeCapabilities", diagnosisScopeCapabilities(scopeFields));
        caliber.put("timeRange", Map.of("start", command.statStart(), "end", command.statEnd()));
        String now = Instant.now().toString();
        DiagnosisCaseSnapshot snapshot = new DiagnosisCaseSnapshot(
                id("DCASE_"), principal.hospitalId(), principal.userId(), command.sessionId(),
                "WAITING_CALIBER_CONFIRMATION", "CALIBER_CONFIRMATION",
                text(rule.get("ruleId")), profileId,
                entities.knowledgeReleaseId(principal.hospitalId()), command.modelId(),
                caseInput, caliber, command.expectedClassification(), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), "STANDARD", Map.of(),
                Map.of(), Map.of(), now, now);
        store.create(snapshot);
        store.appendEvent(snapshot, "CREATE_CASE", caseInput,
                Map.of("currentStep", snapshot.currentStep()), command.modelId());
        conversations.appendDiagnosisCaseReference(
                principal, command.sessionId(), snapshot.caseId(), snapshot.ruleId(),
                text(rule.get("ruleName")), "启动三步基础排查");
        return snapshot;
    }

    public DiagnosisCaseSnapshot load(HospitalPrincipal principal, String caseId) {
        return store.load(caseId, principal.hospitalId(), principal.userId())
                .map(this::withExecutableDataFlow)
                .orElseThrow(() -> error("DIAGNOSIS_CASE_NOT_FOUND", "排查任务不存在或无权访问", HttpStatus.NOT_FOUND));
    }

    public List<DiagnosisCaseSnapshot> loadSession(
            HospitalPrincipal principal, String sessionId) {
        return store.loadForSession(sessionId, principal.hospitalId(), principal.userId()).stream()
                .map(this::withExecutableDataFlow)
                .toList();
    }

    private DiagnosisCaseSnapshot withExecutableDataFlow(DiagnosisCaseSnapshot source) {
        String statStart = text(source.caseInput().get("statStart"));
        String statEnd = text(source.caseInput().get("statEnd"));
        Map<String, Object> caliber = dataFlowSqlExporter.enrichRule(
                source.caliberSnapshot(), statStart, statEnd);
        Map<String, Object> expected = dataFlowSqlExporter.enrichRule(
                source.caseExpectedClassification(), statStart, statEnd);
        String modelId = "aliyun-qwen-plus".equals(source.modelId())
                ? "aliyun-qwen-distill-7b" : source.modelId();
        return new DiagnosisCaseSnapshot(source.caseId(), source.hospitalId(), source.userId(),
                source.sessionId(), source.status(), source.currentStep(), source.ruleId(),
                source.profileId(), source.knowledgeReleaseId(), modelId,
                source.caseInput(), caliber, expected, source.gateResults(), source.evidence(),
                source.causeConclusion(), source.changeProposal(), source.candidateSql(),
                source.shadowTrial(), source.investigationMode(), source.autonomousRun(),
                source.draftResult(), source.releaseResult(),
                source.createdAt(), source.updatedAt());
    }

    public DiagnosisCaseSnapshot action(
            HospitalPrincipal principal, String caseId, ActionCommand command) {
        DiagnosisCaseSnapshot current = load(principal, caseId);
        try {
            DiagnosisCaseSnapshot updated = switch (command.action()) {
                case "CONFIRM_CALIBER" -> confirmCaliber(current, command.payload());
                case "RUN_BASE_CHECKS" -> rerunBaseChecks(current);
                case "RUN_GATE", "RECHECK_GATE" -> runGate(current, command.payload());
                case "SUBMIT_CASE" -> submitCase(current, command.payload());
                case "SUBMIT_DATA_CONFIRMATION" -> submitDataConfirmation(current, command.payload());
                case "CLARIFY_DATA_CONFIRMATION" -> clarifyDataConfirmation(current, command.payload());
                case "CANCEL_DATA_CLARIFICATION" -> cancelDataClarification(current, command.payload());
                case "CONFIRM_CASE_CALIBER" -> confirmCaseCaliber(current, command.payload());
                case "SUBMIT_EVIDENCE" -> submitEvidence(current, command.payload());
                case "CONFIRM_CAUSE" -> confirmCause(current, command.payload());
                case "CLOSE_AS_CORRECT" -> closeAsCorrect(current, command.payload());
                case "BUILD_CANDIDATE" -> buildCandidate(current, command.payload());
                case "RUN_SHADOW_TRIAL" -> runShadow(current);
                case "RUN_LINEAGE_BASELINE" -> runLineageBaseline(current, command.payload());
                case "RUN_CURRENT_SQL_SHADOW" -> runCurrentSqlShadow(current);
                case "FORMAL_RECALCULATE_CURRENT" -> formalRecalculateCurrent(current);
                case "PREVIEW_PUBLIC_RULE_FIX" -> previewPublicRuleFix(current, command.payload());
                case "RUN_PUBLIC_RULE_FIX" -> runPublicRuleFix(current);
                case "REVISE_CANDIDATE" -> reviseCandidate(current);
                case "SAVE_HOSPITAL_DRAFT" -> saveHospitalDraft(principal, current, command.payload());
                case "REVALIDATE_HOSPITAL_DRAFT" -> revalidateHospitalDraft(current);
                case "START_AUTONOMOUS_INVESTIGATION" -> startAutonomous(current, command.payload());
                case "SEND_AUTONOMOUS_MESSAGE", "RESPOND_AUTONOMOUS_QUESTION" ->
                        sendAutonomousMessage(current, command.payload());
                case "CANCEL_AUTONOMOUS_INVESTIGATION" -> cancelAutonomous(current, command.payload());
                default -> throw error("DIAGNOSIS_ACTION_UNSUPPORTED",
                        "不支持的排查动作: " + command.action(), HttpStatus.BAD_REQUEST);
            };
            store.save(updated);
            if (assistantConversations != null
                    && List.of("START_AUTONOMOUS_INVESTIGATION", "SEND_AUTONOMOUS_MESSAGE",
                            "RESPOND_AUTONOMOUS_QUESTION", "CANCEL_AUTONOMOUS_INVESTIGATION")
                            .contains(command.action())) {
                assistantConversations.syncAutonomous(updated);
            }
            store.appendEvent(updated, command.action(), command.payload(),
                    Map.of("status", updated.status(), "currentStep", updated.currentStep()),
                    updated.modelId());
            conversations.touchSession(principal, updated.sessionId());
            if (List.of("START_AUTONOMOUS_INVESTIGATION", "SEND_AUTONOMOUS_MESSAGE",
                    "RESPOND_AUTONOMOUS_QUESTION")
                    .contains(command.action())) {
                if (autonomousRunner == null) {
                    fail("AUTONOMOUS_RUNNER_UNAVAILABLE", "当前环境未启用自主异常排查执行器");
                }
                autonomousRunner.start(updated);
            }
            return updated;
        } catch (RuntimeException exception) {
            String code = exception instanceof IndicatorDetailException detail
                    ? detail.code() : "DIAGNOSIS_ACTION_FAILED";
            store.appendEvent(current, command.action(), command.payload(), Map.of(
                    "status", "REJECTED", "errorCode", code,
                    "message", exception.getMessage() == null ? "执行失败" : exception.getMessage()),
                    current.modelId());
            throw exception;
        }
    }

    private DiagnosisCaseSnapshot startAutonomous(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireDataConfirmationAvailable(current);
        if (!List.of("CASE_INPUT", "CASE_INVESTIGATION").contains(current.currentStep())) {
            fail("AUTONOMOUS_BASE_CHECKS_REQUIRED", "请先完成三步基础校验");
        }
        String existingStatus = text(current.autonomousRun().get("status"));
        if (List.of("RUNNING", "QUEUED", "WAITING_USER").contains(existingStatus)) {
            fail("AUTONOMOUS_ALREADY_RUNNING", "已有自主排查正在进行，请打开当前对话继续");
        }
        String problem = text(payload.get("problem"));
        if (problem.isBlank()) fail("AUTONOMOUS_PROBLEM_REQUIRED", "请描述需要自主排查的问题");
        Map<String, Object> run = new LinkedHashMap<>();
        String turnId = id("TURN_");
        run.put("conversationId", id("DCONV_"));
        String clientMessageId = text(payload.get("clientMessageId"));
        run.put("status", "RUNNING");
        run.put("problem", problem);
        run.put("activeTurnId", turnId);
        run.put("iteration", 0);
        run.put("toolCalls", 0);
        run.put("toolEvents", List.of());
        run.put("findings", List.of());
        run.put("turns", List.of(userTurn(turnId, clientMessageId, problem, "RUNNING")));
        run.put("startedAt", Instant.now().toString());
        return withAutonomous(current, "AUTONOMOUS", Map.copyOf(run));
    }

    private DiagnosisCaseSnapshot sendAutonomousMessage(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        if (!"AUTONOMOUS".equals(current.investigationMode())) {
            fail("AUTONOMOUS_MODE_REQUIRED", "当前任务尚未进入自主排查模式");
        }
        String message = text(payload.get("message"));
        if (message.isBlank()) message = text(payload.get("answer"));
        if (message.isBlank()) fail("AUTONOMOUS_MESSAGE_REQUIRED", "请输入要继续排查的问题或现场回复");
        Map<String, Object> run = new LinkedHashMap<>(current.autonomousRun());
        String previousStatus = text(run.get("status"));
        String question = text(run.get("pendingQuestion"));
        List<Map<String, Object>> answers = new ArrayList<>(mapList(run.get("userAnswers")));
        answers.add(Map.of("question", question, "answer", message,
                "answeredAt", Instant.now().toString()));
        run.put("userAnswers", List.copyOf(answers));
        String turnId = id("TURN_");
        List<Map<String, Object>> turns = new ArrayList<>(mapList(run.get("turns")));
        turns.add(userTurn(turnId, text(payload.get("clientMessageId")), message,
                "RUNNING".equals(previousStatus) ? "QUEUED" : "RUNNING"));
        run.put("turns", List.copyOf(turns));
        if (!"RUNNING".equals(previousStatus)) {
            run.put("activeTurnId", turnId);
            run.remove("pendingQuestion");
            run.put("status", "RUNNING");
            run.put("iteration", 0);
            run.put("toolCalls", 0);
            run.remove("finalConclusion");
        }
        return withAutonomous(current, "AUTONOMOUS", Map.copyOf(run));
    }

    private DiagnosisCaseSnapshot cancelAutonomous(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        if (autonomousRunner != null) autonomousRunner.cancel(current.caseId());
        Map<String, Object> run = new LinkedHashMap<>(current.autonomousRun());
        run.put("status", "CANCELLED");
        String reason = firstText(payload.get("reason"), "用户已停止本轮自主排查");
        run.put("stopReason", reason);
        run.put("finalConclusion", reason);
        run.put("updatedAt", Instant.now().toString());
        return withAutonomous(current, "AUTONOMOUS", Map.copyOf(run));
    }

    DiagnosisAutonomousRunner.ToolOutcome prepareAutonomousCandidate(
            DiagnosisCaseSnapshot current, Map<String, Object> arguments) {
        Map<String, Object> payload = new LinkedHashMap<>(arguments);
        payload.putIfAbsent("type", "SQL_CHANGE");
        String requirements = text(payload.get("requirements"));
        Map<String, Object> cause = Map.of("status", "AUTONOMOUS_CANDIDATE",
                "conclusion", requirements.isBlank() ? "自主排查根据已确认事实准备候选 SQL" : requirements);
        DiagnosisCaseSnapshot candidate = buildCandidateForTrial(current, payload, cause);
        String candidateId = text(candidate.candidateSql().get("candidateSqlHash"));
        return new DiagnosisAutonomousRunner.ToolOutcome(candidate, Map.of(
                "candidateId", candidateId,
                "layer", candidate.candidateSql().get("layer"),
                "generationMethod", candidate.candidateSql().get("generationMethod"),
                "validation", candidate.candidateSql().get("validation")));
    }

    DiagnosisAutonomousRunner.ToolOutcome runAutonomousShadow(
            DiagnosisCaseSnapshot current, Map<String, Object> arguments) {
        String candidateId = text(arguments.get("candidateId"));
        if (candidateId.isBlank()
                || !candidateId.equals(text(current.candidateSql().get("candidateSqlHash")))) {
            fail("AUTONOMOUS_CANDIDATE_ID_INVALID", "candidateId不存在、已过期或不属于当前任务");
        }
        DiagnosisCaseSnapshot trial = runShadow(current);
        return new DiagnosisAutonomousRunner.ToolOutcome(trial, Map.of(
                "trialId", trial.shadowTrial().getOrDefault("trialId", ""),
                "passed", trial.shadowTrial().getOrDefault("passed", false),
                "originalResult", trial.shadowTrial().getOrDefault("originalResult", Map.of()),
                "candidateResult", trial.shadowTrial().getOrDefault("candidateResult", Map.of()),
                "recordSetDiff", trial.shadowTrial().getOrDefault("recordSetDiff", Map.of()),
                "duplicateCheck", trial.shadowTrial().getOrDefault("duplicateCheck", Map.of())));
    }

    private DiagnosisCaseSnapshot confirmCaliber(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CALIBER_CONFIRMATION");
        if (!Boolean.TRUE.equals(payload.get("confirmed"))) {
            fail("CALIBER_NOT_CONFIRMED", "实施人员尚未确认当前统计口径");
        }
        return update(current, "IN_PROGRESS", "GATE_1_SCHEMA", List.of(), current.evidence(),
                current.causeConclusion(), current.changeProposal(), current.candidateSql(),
                current.shadowTrial(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot rerunBaseChecks(DiagnosisCaseSnapshot current) {
        if (!("BASE_CHECKS_RESULT".equals(current.currentStep())
                || current.currentStep().startsWith("GATE_"))) {
            fail("DIAGNOSIS_STEP_ORDER_VIOLATION",
                    "当前步骤是 " + current.currentStep() + "，不能重新执行基础检查");
        }
        if ("BASE_CHECKS_RESULT".equals(current.currentStep())) return runBaseChecks(current);
        int gate = current.currentStep().startsWith("GATE_2") ? 2
                : current.currentStep().startsWith("GATE_3") ? 3 : 1;
        return runGate(current, Map.of("gate", gate));
    }

    private DiagnosisCaseSnapshot runBaseChecks(DiagnosisCaseSnapshot current) {
        return update(current, "IN_PROGRESS", "GATE_1_SCHEMA", List.of(), current.evidence(),
                current.causeConclusion(), current.changeProposal(), current.candidateSql(),
                current.shadowTrial(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot runGate(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        int gate = number(payload.get("gate"));
        String expectedStep = "GATE_" + gate + switch (gate) {
            case 1 -> "_SCHEMA";
            case 2 -> "_EVENT";
            case 3 -> "_VALUE";
            default -> throw error("DIAGNOSIS_GATE_INVALID", "关卡编号必须为1、2或3", HttpStatus.BAD_REQUEST);
        };
        requireStep(current, expectedStep);
        Map<String, Object> result = switch (gate) {
            case 1 -> runSchemaGate(current);
            case 2 -> eventGate.run(current.hospitalId(), current.ruleId(), current.profileId(),
                    start(current), end(current));
            default -> valueGate.run(executionEvidence(current.gateResults()));
        };
        List<Map<String, Object>> gates = replaceGate(current.gateResults(), gate, result);
        boolean passed = "PASSED".equals(result.get("status"));
        String next = passed ? switch (gate) {
            case 1 -> "GATE_2_EVENT";
            case 2 -> "GATE_3_VALUE";
            default -> hasCaseInput(current.caseInput()) ? "CASE_INVESTIGATION" : "CASE_INPUT";
        } : expectedStep;
        return update(current, passed && gate == 3 ? "GATES_PASSED" : "IN_PROGRESS", next,
                gates, current.evidence(), current.causeConclusion(), current.changeProposal(),
                current.candidateSql(), current.shadowTrial(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot submitCase(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CASE_INPUT");
        validateCaseInput(payload);
        Map<String, Object> caseInput = new LinkedHashMap<>(current.caseInput());
        copy(payload, caseInput, "recordField", "recordId", "recordIds", "symptom",
                "caseDescription", "issueDirection",
                "businessUniqueKey", "expectedValues", "siteConstants", "scopeType",
                "scopeField", "scopeValue", "scopeStart", "scopeEnd");
        List<String> recordIds = recordIds(payload);
        caseInput.put("recordIds", recordIds);
        if (recordIds.isEmpty()) caseInput.remove("recordId");
        else caseInput.put("recordId", recordIds.get(0));
        caseInput.putIfAbsent("symptom", "");
        Map<String, Object> expected = caseCaliberClarification(current, caseInput);
        return new DiagnosisCaseSnapshot(current.caseId(), current.hospitalId(), current.userId(),
                current.sessionId(), "WAITING_CASE_CALIBER_CONFIRMATION",
                "CASE_CALIBER_CLARIFICATION", current.ruleId(),
                current.profileId(), current.knowledgeReleaseId(), current.modelId(), caseInput,
                current.caliberSnapshot(), expected, current.gateResults(), current.evidence(),
                current.causeConclusion(), current.changeProposal(), current.candidateSql(),
                current.shadowTrial(), current.investigationMode(), current.autonomousRun(),
                current.draftResult(), current.releaseResult(), current.createdAt(),
                Instant.now().toString());
    }

    private DiagnosisCaseSnapshot submitDataConfirmation(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireDataConfirmationAvailable(current);

        List<Map<String, Object>> overRows = mapList(payload.get("overIncludedRows"));
        if (overRows.size() > 200) {
            fail("DATA_CONFIRMATION_TOO_MANY_ROWS", "一次最多确认200条疑似多算明细");
        }
        String overNote = text(payload.get("overIncludedNote"));
        String underNote = text(payload.get("underIncludedNote"));
        Map<String, Object> overDepartment = map(payload.get("overIncludedDepartment"));
        List<Map<String, Object>> overDepartments = mapList(payload.get("overIncludedDepartments"));
        List<Map<String, Object>> overTargets = mapList(payload.get("overIncludedTargets"));
        List<String> publicRuleIds = stringList(payload.get("publicRuleIds")).stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .filter(value -> value.matches("PUBLIC_00[1-3]"))
                .distinct().toList();
        List<Map<String, Object>> underTargets = mapList(payload.get("underIncludedTargets"));
        boolean noIssue = Boolean.TRUE.equals(payload.get("confirmedNoIssue"));
        boolean hasOverIssue = !overRows.isEmpty() || !overNote.isBlank() || !overDepartment.isEmpty()
                || !overDepartments.isEmpty() || !overTargets.isEmpty();
        boolean hasUnderIssue = !underNote.isBlank() || !underTargets.isEmpty();
        if (!noIssue && !hasOverIssue && !hasUnderIssue) {
            fail("DATA_CONFIRMATION_EMPTY", "请勾选疑似多算明细、填写少算范围，或确认当前数据无异议");
        }

        Map<String, Object> screening = evidenceService.screenData(current);
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("status", noIssue ? "NO_ISSUE" : "NEEDS_LINEAGE_REVIEW");
        clarification.put("overIncludedCount", overRows.size());
        clarification.put("overIncludedNote", overNote);
        clarification.put("underIncludedNote", underNote);
        clarification.put("summary", noIssue
                ? "实施人员确认当前分子、分母明细无异议。"
                : String.join("；", List.of(
                        overRows.isEmpty() && overNote.isBlank() && overDepartment.isEmpty() ? "未登记多算数据"
                                : "已登记疑似多算数据" + overRows.size() + "条",
                        !hasUnderIssue ? "未登记少算范围" : "已登记疑似少算范围")));
        clarification.put("nextAction", noIssue
                ? "可以确认当前结果正确并结束排查。"
                : "进入数据链路核查，确认问题发生在抽取层还是概览统计层。");

        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("overIncludedRows", List.copyOf(overRows));
        confirmation.put("overIncludedNote", overNote);
        confirmation.put("overIncludedDepartment", Map.copyOf(overDepartment));
        confirmation.put("overIncludedDepartments", List.copyOf(overDepartments));
        confirmation.put("overIncludedTargets", List.copyOf(overTargets));
        confirmation.put("publicRuleIds", List.copyOf(publicRuleIds));
        confirmation.put("underIncludedNote", underNote);
        confirmation.put("underIncludedTargets", List.copyOf(underTargets));
        confirmation.put("screeningFindings", screening.getOrDefault("findings", List.of()));
        confirmation.put("clarification", Map.copyOf(clarification));
        confirmation.put("clarifications", current.dataConfirmation().getOrDefault(
                "clarifications", Map.of()));
        String patientConversationId = text(
                current.dataConfirmation().get("lastConversationId"));
        if (!patientConversationId.isBlank()) {
            confirmation.put("lastConversationId", patientConversationId);
        }
        confirmation.put("confirmedNoIssue", noIssue);
        confirmation.put("submittedAt", Instant.now().toString());

        Map<String, Object> caseInput = new LinkedHashMap<>(current.caseInput());
        caseInput.put("dataConfirmation", Map.copyOf(confirmation));
        caseInput.put("issueDirection", hasUnderIssue
                ? (!hasOverIssue ? "UNDER_INCLUDED" : "OVER_AND_UNDER")
                : noIssue ? "NO_ISSUE" : "OVER_INCLUDED");
        Map<String, Object> expected = new LinkedHashMap<>(
                caseCaliberClarification(current, caseInput));
        expected.put("status", "CONFIRMED");

        List<Map<String, Object>> evidence = new ArrayList<>(current.evidence());
        evidence.add(Map.of(
                "evidenceId", id("EVD_"),
                "type", "DATA_CONFIRMATION",
                "summary", clarification.get("summary"),
                "submittedAt", Instant.now().toString(),
                "screeningModelUsed", false));
        return new DiagnosisCaseSnapshot(current.caseId(), current.hospitalId(), current.userId(),
                current.sessionId(), "IN_PROGRESS", "CASE_INVESTIGATION", current.ruleId(),
                current.profileId(), current.knowledgeReleaseId(), current.modelId(),
                Map.copyOf(caseInput), current.caliberSnapshot(), Map.copyOf(expected),
                current.gateResults(), List.copyOf(evidence), current.causeConclusion(),
                current.changeProposal(), Map.of(), Map.of(),
                current.investigationMode(), current.autonomousRun(), current.draftResult(),
                current.releaseResult(), current.createdAt(), Instant.now().toString());
    }

    private DiagnosisCaseSnapshot clarifyDataConfirmation(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireClarificationAvailable(current);
        String requestId = text(payload.get("requestId"));
        if (consumeClarificationCancellation(requestId)) return current;
        String direction = text(payload.get("direction"));
        if (!List.of("OVER_INCLUDED", "UNDER_INCLUDED").contains(direction)) {
            fail("DATA_CLARIFICATION_DIRECTION_INVALID", "请选择要澄清“数据多了”还是“数据少了”");
        }
        Map<String, Object> submitted = new LinkedHashMap<>(payload);
        List<Map<String, Object>> targets = mapList(payload.get("targets"));
        String description = text(payload.get("description"));
        if ("OVER_INCLUDED".equals(direction)) {
            submitted.put("overIncludedTargets", targets);
            submitted.put("overIncludedNote", description);
        } else {
            submitted.put("underIncludedTargets", targets);
            submitted.put("underIncludedNote", description);
        }
        submitted.put("confirmedNoIssue", false);
        // 患者澄清是只读证据旁路。复用既有数据确认构造逻辑时，仅给它一个
        // 临时的“数据确认阶段”视图；最终快照必须保留调用前的步骤、候选 SQL、
        // 影子试跑和草稿，不能因为查看患者证据把第三步强制退回第二步。
        DiagnosisCaseSnapshot confirmationInput = current;
        if (!List.of("CASE_INPUT", "CASE_INVESTIGATION").contains(current.currentStep())) {
            confirmationInput = new DiagnosisCaseSnapshot(
                    current.caseId(), current.hospitalId(), current.userId(), current.sessionId(),
                    "IN_PROGRESS", "CASE_INVESTIGATION", current.ruleId(), current.profileId(),
                    current.knowledgeReleaseId(), current.modelId(), current.caseInput(),
                    current.caliberSnapshot(), current.caseExpectedClassification(),
                    current.gateResults(), current.evidence(), current.causeConclusion(),
                    current.changeProposal(), current.candidateSql(), current.shadowTrial(),
                    current.dataConfirmation(), current.investigationMode(), current.autonomousRun(),
                    current.draftResult(), current.releaseResult(), current.createdAt(),
                    current.updatedAt());
        }
        DiagnosisCaseSnapshot saved = submitDataConfirmation(confirmationInput, submitted);
        Map<String, Object> result = evidenceService.clarifyDataConfirmation(
                saved, direction, submitted);
        if (consumeClarificationCancellation(requestId)) return current;

        Map<String, Object> confirmation = new LinkedHashMap<>(saved.dataConfirmation());
        Map<String, Object> clarifications = new LinkedHashMap<>(
                map(confirmation.get("clarifications")));
        clarifications.put(direction, Map.copyOf(result));
        confirmation.put("clarifications", Map.copyOf(clarifications));
        String requestedConversationId = text(payload.get("conversationId"));
        String conversationId = firstText(
                validPatientConversationId(current, requestedConversationId),
                confirmation.get("lastConversationId"), id("DCONV_"));
        confirmation.put("lastConversationId", conversationId);
        Map<String, Object> caseInput = new LinkedHashMap<>(saved.caseInput());
        caseInput.put("dataConfirmation", Map.copyOf(confirmation));

        List<Map<String, Object>> evidence = new ArrayList<>(saved.evidence());
        evidence.add(Map.of(
                "evidenceId", id("EVD_"),
                "type", "DATA_CLARIFICATION",
                "direction", direction,
                "summary", text(result.get("summary")),
                "modelUsed", "MODEL".equals(text(result.get("explanationSource"))),
                "submittedAt", Instant.now().toString()));
        DiagnosisCaseSnapshot clarified = new DiagnosisCaseSnapshot(
                saved.caseId(), saved.hospitalId(), saved.userId(),
                saved.sessionId(), current.status(), current.currentStep(), saved.ruleId(),
                saved.profileId(), saved.knowledgeReleaseId(), saved.modelId(),
                Map.copyOf(caseInput), saved.caliberSnapshot(), saved.caseExpectedClassification(),
                saved.gateResults(), List.copyOf(evidence), saved.causeConclusion(),
                current.changeProposal(), current.candidateSql(), current.shadowTrial(),
                Map.copyOf(confirmation), current.investigationMode(), current.autonomousRun(),
                current.draftResult(), current.releaseResult(), saved.createdAt(), Instant.now().toString());
        if (assistantConversations != null) {
            String object = clarificationObject(result);
            String prompt = firstText(payload.get("userMessage"),
                    "请澄清患者：" + object + "，说明其是否进入当前分子或分母以及原因。");
            assistantConversations.savePatientClarification(
                    clarified, conversationId, "患者澄清", prompt, result);
        }
        return clarified;
    }

    private String validPatientConversationId(
            DiagnosisCaseSnapshot snapshot, String conversationId) {
        if (conversationId.isBlank() || assistantConversations == null) return "";
        return assistantConversations.get(snapshot, conversationId)
                .filter(value -> "PATIENT_CLARIFICATION".equals(text(value.get("type"))))
                .map(value -> conversationId)
                .orElse("");
    }

    private DiagnosisCaseSnapshot cancelDataClarification(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        String requestId = text(payload.get("requestId"));
        if (requestId.isBlank()) {
            fail("DATA_CLARIFICATION_REQUEST_ID_MISSING", "缺少要停止的患者澄清请求编号");
        }
        cancelledClarificationRequests.add(requestId);
        return current;
    }

    private boolean consumeClarificationCancellation(String requestId) {
        return !requestId.isBlank() && cancelledClarificationRequests.remove(requestId);
    }

    private static String clarificationObject(Map<String, Object> result) {
        List<Map<String, Object>> targets = mapList(result.get("targets"));
        if (!targets.isEmpty()) {
            List<String> labels = stringList(targets.get(0).get("labels"));
            if (!labels.isEmpty()) return labels.get(0);
            List<String> values = stringList(targets.get(0).get("values"));
            if (!values.isEmpty()) return values.get(0);
        }
        return firstText(result.get("object"), "所选患者");
    }

    private void requireDataConfirmationAvailable(DiagnosisCaseSnapshot current) {
        boolean gatesPassed = java.util.stream.IntStream.rangeClosed(1, 3).allMatch(gate ->
                current.gateResults().stream().anyMatch(item -> number(item.get("gate")) == gate
                        && "PASSED".equals(text(item.get("status")))));
        if (!gatesPassed) {
            fail("DATA_CONFIRMATION_GATES_REQUIRED", "三项基础检查尚未全部通过");
        }
        if ("COMPLETED".equals(current.status()) || "COMPLETED".equals(current.currentStep())) {
            fail("DIAGNOSIS_CASE_ALREADY_COMPLETED",
                    "本任务已经确认结束，不能继续修改；请新建异常排查任务");
        }
        if (!List.of("CASE_INPUT", "CASE_INVESTIGATION").contains(current.currentStep())) {
            fail("DATA_CONFIRMATION_STEP_INVALID",
                    "当前排查已经进入后续步骤，请返回当前步骤继续处理");
        }
    }

    private void requireClarificationAvailable(DiagnosisCaseSnapshot current) {
        boolean gatesPassed = java.util.stream.IntStream.rangeClosed(1, 3).allMatch(gate ->
                current.gateResults().stream().anyMatch(item -> number(item.get("gate")) == gate
                        && "PASSED".equals(text(item.get("status")))));
        if (!gatesPassed) {
            fail("DATA_CONFIRMATION_GATES_REQUIRED", "三项基础检查尚未全部通过");
        }
        if ("COMPLETED".equals(current.status()) || "COMPLETED".equals(current.currentStep())) {
            fail("DIAGNOSIS_CASE_ALREADY_COMPLETED",
                    "本任务已经确认结束，不能继续修改；请新建异常排查任务");
        }
    }

    private DiagnosisCaseSnapshot confirmCaseCaliber(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CASE_CALIBER_CLARIFICATION");
        if (!Boolean.TRUE.equals(payload.get("confirmed"))) {
            fail("CASE_CALIBER_NOT_CONFIRMED", "请先确认已理解本案例的分母、分子和排除顺序");
        }
        Map<String, Object> expected = new LinkedHashMap<>(current.caseExpectedClassification());
        expected.put("status", "CONFIRMED");
        expected.put("confirmedAt", Instant.now().toString());
        // The case statement is only background for the investigation.  The
        // actual inclusion/exclusion conditions are collected in the next
        // turn, and are the sole input used to build a candidate SQL.
        expected.put("hospitalExpectedMembership",
                inferExpectedMembership(text(current.caseInput().get("symptom"))));
        expected.remove("investigationGuide");
        expected.put("investigationGuideVersion", "IMPLEMENTER_REQUIREMENT_V3");
        return new DiagnosisCaseSnapshot(current.caseId(), current.hospitalId(), current.userId(),
                current.sessionId(), "IN_PROGRESS", "CASE_INVESTIGATION", current.ruleId(),
                current.profileId(), current.knowledgeReleaseId(), current.modelId(),
                current.caseInput(), current.caliberSnapshot(), Map.copyOf(expected),
                current.gateResults(), current.evidence(), current.causeConclusion(),
                current.changeProposal(), current.candidateSql(), current.shadowTrial(),
                current.investigationMode(), current.autonomousRun(), current.draftResult(),
                current.releaseResult(), current.createdAt(), Instant.now().toString());
    }

    private Map<String, Object> caseCaliberClarification(
            DiagnosisCaseSnapshot current, Map<String, Object> caseInput) {
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("status", "WAITING_CONFIRMATION");
        clarification.put("caseRecord", scopeDescription(caseInput));
        clarification.put("timeRange", current.caliberSnapshot().get("timeRange"));
        clarification.put("profileName", text(current.caliberSnapshot().get("profileName")));
        clarification.put("definition", text(current.caliberSnapshot().get("definition")));
        clarification.put("formula", text(current.caliberSnapshot().get("formula")));
        clarification.put("dataFlow", current.caliberSnapshot().get("dataFlow") == null
                ? Map.of() : current.caliberSnapshot().get("dataFlow"));
        clarification.put("denominatorRule",
                text(current.caliberSnapshot().get("denominatorRule")));
        clarification.put("numeratorRule",
                text(current.caliberSnapshot().get("numeratorRule")));
        Map<String, Object> calculation = executionEvidence(current.gateResults());
        if (calculation.get("numeratorCount") != null) {
            clarification.put("numeratorCount", calculation.get("numeratorCount"));
        }
        if (calculation.get("denominatorCount") != null) {
            clarification.put("denominatorCount", calculation.get("denominatorCount"));
        }
        if (calculation.get("resultValue") != null) {
            clarification.put("resultValue", calculation.get("resultValue"));
        }
        if (calculation.get("overviewSqlHash") != null) {
            clarification.put("overviewSqlHash", calculation.get("overviewSqlHash"));
        }
        clarification.put("decisionOrder", List.of(
                "先判断该记录是否处于统计时间范围内",
                "再判断是否满足分母范围和排除条件",
                "只有进入分母后，才判断是否命中分子条件"));
        clarification.put("reportedProblem", text(caseInput.get("symptom")));
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        clarification.put("candidateRuleFields", entity == null ? List.of()
                : enrichFieldSuggestions(SafeSqlPredicatePatcher.fieldSuggestions(
                        MrasSqlExecutionService.stripLeadingTrailingQuotes(sourceSql(entity)))));
        clarification.put("nextEvidenceTemplate", Map.of(
                "业务表查询结果", "是否找到该记录；关键状态、时间和关联字段是什么",
                "抽取或中间表结果", "是否找到同一记录；是否重复；关键字段是否改变",
                "判定结论", "按当前分母、分子和排除条件，该记录实际应归入哪里",
                "证据SQL", "粘贴已在现场执行的只读 SQL（可选）"));
        return Map.copyOf(clarification);
    }

    private List<Map<String, Object>> enrichFieldSuggestions(List<Map<String, Object>> values) {
        if (dataDictionary == null) return values;
        return values.stream().map(value -> {
            Map<String, Object> field = new LinkedHashMap<>(value);
            String table = text(value.get("table"));
            int dot = table.lastIndexOf('.');
            if (dot >= 0) table = table.substring(dot + 1);
            String technical = text(value.get("value"));
            String name = dataDictionary.fieldLabel(table, text(value.get("field")));
            String description = dataDictionary.fieldDescription(table, text(value.get("field")));
            field.put("displayName", name.isBlank() ? text(value.get("field")) : name);
            field.put("technicalName", technical);
            field.put("tableName", table);
            field.put("description", description);
            field.put("label", (name.isBlank() ? text(value.get("field")) : name)
                    + " · " + table + " · " + technical);
            return Map.copyOf(field);
        }).toList();
    }

    private static Map<String, Object> diagnosisScopeCapabilities(
            List<Map<String, Object>> fields) {
        List<Map<String, Object>> records = fields.stream()
                .filter(item -> text(item.get("field")).toUpperCase(java.util.Locale.ROOT)
                        .matches(".*(ENCOUNTER_ID|EVENT_ID|ORDER_ID|CLI_ORDER_ID|SURGERY_ID|BIZ_ID)$"))
                .toList();
        List<Map<String, Object>> departments = fields.stream()
                .filter(item -> text(item.get("field")).toUpperCase(java.util.Locale.ROOT)
                        .matches(".*(DEPT|WARD).*(ID|NO|CODE|NAME)$"))
                .toList();
        List<Map<String, Object>> times = fields.stream()
                .filter(item -> text(item.get("field")).toUpperCase(java.util.Locale.ROOT)
                        .matches(".*(_AT|_TIME|_DATE|DATETIME|TIMESTAMP)$"))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordKeyCandidates", records);
        result.put("departmentCandidates", departments);
        result.put("timeFieldCandidates", times);
        result.put("conditionFieldCandidates", fields);
        result.put("supportsAggregateOnly", true);
        result.put("scopeTypes", List.of("RECORD", "DEPARTMENT", "TIME_RANGE", "DATA_CATEGORY", "OVERALL"));
        return Map.copyOf(result);
    }

    private static Map<String, Object> userTurn(
            String turnId, String clientMessageId, String message, String status) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("turnId", turnId);
        turn.put("clientMessageId", clientMessageId.isBlank() ? id("MSG_") : clientMessageId);
        turn.put("userMessage", message);
        turn.put("submittedAt", Instant.now().toString());
        turn.put("status", status);
        turn.put("processEvents", List.of());
        return Map.copyOf(turn);
    }

    private Map<String, Object> runSchemaGate(DiagnosisCaseSnapshot current) {
        InitializationValidationReport report = initialization.validate(
                current.caseId(), current.hospitalId(), List.of(new ValidationTarget(
                        current.ruleId(), text(current.caliberSnapshot().get("ruleName")),
                        current.profileId(), text(current.caliberSnapshot().get("profileName")))),
                start(current), end(current), text(current.caseInput().get("statStart")),
                text(current.caseInput().get("statEnd")));
        var decision = report.profiles().isEmpty() ? null : report.profiles().get(0);
        boolean passed = decision != null && (decision.decision() == Decision.RUNNABLE
                || decision.decision() == Decision.NO_SAMPLE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", 1);
        result.put("name", "数据结构校验");
        result.put("status", passed ? "PASSED" : "BLOCKED");
        result.put("errorCode", decision == null ? "INIT_NO_DECISION" : text(decision.errorCode()));
        result.put("message", schemaGateMessage(report, decision, passed));
        if (!passed) {
            result.put("repairSuggestion", schemaRepairSuggestion(
                    decision == null ? "INIT_NO_DECISION" : text(decision.errorCode())));
        }
        result.put("facts", report.toTraceOutput());
        return Map.copyOf(result);
    }

    private static String schemaGateMessage(
            InitializationValidationReport report,
            InitializationValidationReport.ProfileValidation decision,
            boolean passed) {
        if (decision == null) {
            return "系统没有生成当前口径的数据结构检查结果，暂时不能继续。";
        }
        if (passed) {
            return "业务库和真实库连接正常；已核对当前口径用到的数据表、字段及真实库计算结构，"
                    + "未发现会阻断当前计算的缺表、缺字段或结构不一致问题。";
        }
        List<String> problems = new ArrayList<>();
        if (!report.businessConnected()) problems.add("业务库连接失败");
        if (!report.realConnected()) problems.add("真实库连接失败");
        report.items().stream()
                .filter(InitializationValidationReport.ValidationItem::affectsCalculation)
                .map(item -> {
                    String object = text(item.tableName());
                    if (!text(item.fieldName()).isBlank()) {
                        object += (object.isBlank() ? "" : ".") + item.fieldName();
                    }
                    String reason = text(item.message());
                    if (reason.isBlank()) reason = text(item.errorCode());
                    return object.isBlank() ? reason : object + "：" + reason;
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(3)
                .forEach(problems::add);
        if (problems.isEmpty()) problems.add(text(decision.message()));
        return "未通过。" + String.join("；", problems) + "。当前口径已暂停，其他指标不受影响。";
    }

    private static String schemaRepairSuggestion(String code) {
        if (code.contains("MISSING_TABLE")) {
            return "先确认现场是否部署了提示中的表；表名不同就补医院字段映射，确实缺表就先完成建表或同步，再点击重新检查。";
        }
        if (code.contains("MISSING_COLUMN")) {
            return "对照校验详情中的表名和字段名，确认是现场字段改名还是版本缺失；改名时补医院字段映射，缺失时先升级表结构。";
        }
        if (code.contains("LINEAGE")) {
            return "先核对当前口径 SQL 是否完整、别名是否写对；程序仍看不懂时，再给这个口径补一条精简的初始化血缘映射。";
        }
        return "按上面的具体原因处理数据库连接、表字段或口径 SQL，修复后点击“修复后重新检查”。";
    }

    private DiagnosisCaseSnapshot submitEvidence(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireAnyStep(current, "CASE_INPUT", "CASE_INVESTIGATION", "SHADOW_TRIAL", "DRAFT_SAVE");
        Map<String, Object> evidence = new LinkedHashMap<>(payload);
        if (Boolean.TRUE.equals(payload.get("runAutomatic"))) {
            evidence.putAll(evidenceService.collect(current, start(current), end(current)));
        }
        if (text(evidence.get("summary")).isBlank()) {
            fail("CASE_EVIDENCE_REQUIRED", "请提供证据说明，或启动系统自动取证");
        }
        evidence.put("evidenceId", id("EVD_"));
        evidence.put("submittedAt", Instant.now().toString());
        if ("IMPLEMENTER_SQL_REQUIREMENT".equals(text(evidence.get("type")))) {
            String generationMode = text(evidence.get("generationMode"));
            String confirmationRef = text(evidence.get("dataConfirmationRef"));
            String currentConfirmationRef = text(current.dataConfirmation().get("submittedAt"));
            if (!confirmationRef.isBlank() && !confirmationRef.equals(currentConfirmationRef)) {
                fail("DATA_CONFIRMATION_STALE", "数据澄清内容已经变化，请重新确认后再修改 SQL");
            }
            if ("DIRECT_EDIT".equals(generationMode)
                    && text(evidence.get("candidateSql")).isBlank()) {
                fail("DIRECT_EDIT_SQL_REQUIRED", "请先编辑完整候选 SELECT");
            }
            if ("AI_MODIFY".equals(generationMode)) {
                String normalized = normalizeAiRequirement(current, evidence);
                evidence.put("aiAnalysis", normalized);
                evidence.put("requirement", normalized);
                evidence.put("candidateSql", "");
            }
            // The user message is rendered immediately by the client.  The
            // server then makes a candidate only in an isolated shadow path;
            // it never alters the formal SQL or the formal result here.
            evidence.put("requirementAnalysis", requirementAnalysis(current, evidence));
            evidence.put("sqlContext", activeSqlContext(current, evidence));
            evidence.remove("requestAiAnalysis");
        } else if (Boolean.TRUE.equals(payload.get("requestAiAnalysis"))) {
            evidence.put("aiAnalysis", explain(current, evidence));
            evidence.put("modelId", current.modelId());
        }
        List<Map<String, Object>> values = new ArrayList<>(current.evidence());
        values.add(Map.copyOf(evidence));
        DiagnosisCaseSnapshot registered = update(current, "IN_PROGRESS", "CASE_INVESTIGATION", current.gateResults(),
                values, current.causeConclusion(), current.changeProposal(),
                current.candidateSql(), current.shadowTrial(), current.releaseResult());
        if (!"IMPLEMENTER_SQL_REQUIREMENT".equals(text(evidence.get("type")))) {
            return registered;
        }
        if (Boolean.TRUE.equals(payload.get("deferShadowTrial"))) {
            return buildRegisteredCandidate(registered, evidence);
        }
        return automaticallyBuildAndTrial(registered, evidence);
    }

    private String normalizeAiRequirement(
            DiagnosisCaseSnapshot current, Map<String, Object> evidence) {
        String layer = text(evidence.get("suspectedLayer"));
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        String original = entity == null ? "" : "SOURCE_EXTRACT".equals(layer)
                ? sourceSql(entity) : entity.overviewSql();
        List<Map<String, Object>> fields = enrichFieldSuggestions(
                SafeSqlPredicatePatcher.fieldSuggestions(original));
        String requirement = text(evidence.get("requirement"));
        if (!structuredExclusionCandidate(
                original, mapList(evidence.get("scopeTargets"))).isBlank()) {
            return requirement;
        }
        // Clear single-field rules are safer when the deterministic patcher
        // keeps the implementer's original include/exclude direction.  Asking
        // a model to paraphrase "exclude rows containing X" can accidentally
        // turn it into "exclude rows not containing X", which is syntactically
        // valid but reverses the business effect.
        if (!SafeSqlPredicatePatcher.inferChanges(requirement, fields).isEmpty()) {
            return requirement;
        }
        modelRegistry.requireInfo(current.modelId());
        String answer = models.complete(current.modelId(),
                "你是医院指标SQL修改要求整理助手。只把实施人员已经确认的多算、少算范围"
                        + "整理成一条可以交给确定性SQL过滤器处理的中文要求。只能使用给出的现有字段；"
                        + "不得补造表、字段、值或SQL，不得改变JOIN、分组、去重、计算公式和输出列。"
                        + "能安全表达时直接写“使用字段X排除/只保留条件Y”；无法安全表达时只回复"
                        + "“当前条件不能自动修改，请直接编辑SQL”。不要输出Markdown或解释。",
                "修改层级：" + layer + "\n实施要求：" + requirement
                        + "\n当前SQL可用字段（中文名=技术字段）：" + compactAiFields(fields),
                Duration.ofSeconds(60)).content().strip();
        if (answer.isBlank() || answer.contains("不能自动修改")) {
            fail("AI_SQL_MODIFICATION_UNSUPPORTED",
                    "当前条件无法安全转换为过滤条件，请切换到“直接编辑 SQL”");
        }
        return answer;
    }

    /**
     * A patient or department selected from reconciled details is already a
     * structured fact. Excluding it does not require a model: wrap the
     * current query and filter its proven output column. The wrapper keeps
     * joins, grouping, template parameters and output columns unchanged.
     */
    static String structuredExclusionCandidate(
            String original, List<Map<String, Object>> targets) {
        if (original == null || original.isBlank() || targets == null || targets.isEmpty()) return "";
        List<Map<String, Object>> usable = targets.stream()
                .filter(target -> !scopeValues(target.get("values")).isEmpty())
                .toList();
        java.util.Set<String> types = usable.stream()
                .map(target -> text(target.get("targetType")).toUpperCase(java.util.Locale.ROOT))
                .filter(type -> List.of("RECORD", "DEPARTMENT").contains(type))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (types.size() != 1) return "";
        String targetType = types.iterator().next();
        java.util.Set<String> fields = usable.stream()
                .map(target -> text(target.get("field")))
                .filter(field -> !field.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (fields.size() != 1) return "";
        String outputColumn = exclusionOutputColumn(original, targetType, fields.iterator().next());
        if (outputColumn.isBlank()) return "";
        List<String> values = usable.stream().flatMap(target -> scopeValues(target.get("values")).stream())
                .filter(value -> !value.isBlank()).distinct().limit(100).toList();
        if (values.isEmpty()) return "";
        String literals = values.stream()
                .map(value -> "'" + value.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String base = original.strip().replaceFirst(";\\s*$", "");
        // Oracle 的未加引号标识符必须以字母开头；不要使用 __DIAG_FILTER。
        // DIAG_FILTER 同时兼容 Oracle 与 SQL Server，候选 SQL 才能直接进入影子试跑。
        return "SELECT * FROM (\n" + base + "\n) DIAG_FILTER\nWHERE DIAG_FILTER."
                + outputColumn + " NOT IN (" + literals + ")";
    }

    private static String exclusionOutputColumn(
            String sql, String targetType, String requestedField) {
        String normalized = requestedField.replace("[", "").replace("]", "")
                .replaceAll("^.*\\.", "").toUpperCase(java.util.Locale.ROOT);
        List<String> candidates;
        if ("DEPARTMENT".equals(targetType)) {
            candidates = normalized.contains("NAME")
                    ? List.of("currentDeptName", "deptName", "CURRENT_DEPT_NAME", "DEPT_NAME")
                    : List.of("currentDeptId", "deptId", "CURRENT_DEPT_ID", "DEPT_ID");
        } else if (normalized.contains("ORDER")) {
            candidates = List.of("orderId", "cliOrderId", "ORDER_ID", "CLI_ORDER_ID", "bizId");
        } else if (normalized.contains("SURGERY")) {
            candidates = List.of("surgeryId", "SURGERY_ID", "bizId");
        } else if (normalized.contains("BIZ")) {
            candidates = List.of("bizId", "BIZ_ID", "encounterId");
        } else if (normalized.contains("IMRN")) {
            candidates = List.of("imrn", "IMRN");
        } else {
            candidates = List.of("encounterId", "ENCOUNTER_ID", "bizId");
        }
        for (String candidate : candidates) {
            java.util.regex.Pattern alias = java.util.regex.Pattern.compile(
                    "(?i)\\bAS\\s+[\\[\\\"]?" + java.util.regex.Pattern.quote(candidate)
                            + "[\\]\\\"]?(?![A-Za-z0-9_$#])");
            if (alias.matcher(sql).find()) return candidate;
        }
        return "";
    }

    private static List<String> scopeValues(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(DiagnosisCaseService::text)
                    .filter(item -> !item.isBlank()).toList();
        }
        String item = text(value);
        return item.isBlank() ? List.of() : List.of(item);
    }

    /**
     * AI 只需要判断明确的已有字段，不应把字段字典说明、整份数据确认快照和患者明细
     * 一起塞进提示词。完整证据仍保存在任务快照中；这里限制为紧凑字段目录，避免本地
     * 8K 上下文模型在生成候选 SQL 之前就因 prompt 过长失败。
     */
    private static String compactAiFields(List<Map<String, Object>> fields) {
        return fields.stream().limit(60).map(field -> {
            String technical = firstText(field.get("technicalName"), field.get("field"), field.get("name"));
            String display = firstText(field.get("displayName"), field.get("label"));
            return display.isBlank() || display.equals(technical) ? technical : display + "=" + technical;
        }).filter(value -> !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("、"));
    }

    private DiagnosisCaseSnapshot confirmCause(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CASE_INVESTIGATION");
        if (current.evidence().isEmpty()) fail("CAUSE_EVIDENCE_REQUIRED", "至少需要一条案例证据才能确认原因");
        String conclusion = text(payload.get("conclusion"));
        if (conclusion.isBlank()) fail("CAUSE_CONCLUSION_REQUIRED", "请填写由证据支持的具体原因");
        Map<String, Object> cause = new LinkedHashMap<>(payload);
        cause.put("status", "CONFIRMED");
        cause.put("confirmedAt", Instant.now().toString());
        return update(current, "CAUSE_CONFIRMED", "CHANGE_PROPOSAL", current.gateResults(),
                current.evidence(), cause, current.changeProposal(), current.candidateSql(),
                current.shadowTrial(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot closeAsCorrect(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CASE_INVESTIGATION");
        if (current.evidence().isEmpty()) {
            fail("CORRECT_RESULT_EVIDENCE_REQUIRED", "至少需要一条自动或人工证据才能确认结果正确");
        }
        String conclusion = text(payload.get("conclusion"));
        if (conclusion.isBlank()) conclusion = "业务记录、真实库中间表和指标判定一致，当前计算正确。";
        Map<String, Object> cause = new LinkedHashMap<>();
        cause.put("status", "CONFIRMED_CORRECT");
        cause.put("conclusion", conclusion);
        cause.put("confirmedAt", Instant.now().toString());
        Map<String, Object> outcome = Map.of(
                "outcome", "CALCULATION_CONFIRMED_CORRECT",
                "message", "已确认当前计算正确，本次排查结束。",
                "completedAt", Instant.now().toString());
        return update(current, "COMPLETED", "COMPLETED", current.gateResults(),
                current.evidence(), Map.copyOf(cause), current.changeProposal(),
                current.candidateSql(), current.shadowTrial(), outcome);
    }

    private DiagnosisCaseSnapshot buildCandidate(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireStep(current, "CHANGE_PROPOSAL");
        return buildCandidateForTrial(current, payload, current.causeConclusion());
    }

    /**
     * Turns one implementer requirement into a candidate and an isolated trial.
     * Failure is saved as a readable outcome, so the already-submitted
     * requirement is never lost merely because a model or a trial failed.
     */
    private DiagnosisCaseSnapshot automaticallyBuildAndTrial(
            DiagnosisCaseSnapshot registered, Map<String, Object> evidence) {
        String layer = text(evidence.get("suspectedLayer"));
        if (!List.of("SOURCE_EXTRACT", "OVERVIEW").contains(layer)) {
            return registered;
        }
        try {
            DiagnosisCaseSnapshot candidate = buildRegisteredCandidate(registered, evidence);
            try {
                return runShadow(candidate);
            } catch (RuntimeException exception) {
                return update(candidate, "SHADOW_FAILED", "SHADOW_TRIAL", candidate.gateResults(),
                        candidate.evidence(), candidate.causeConclusion(), candidate.changeProposal(),
                        candidate.candidateSql(), Map.of(
                                "attempted", true,
                                "completed", false,
                                "passed", false,
                                "status", "FAILED",
                                "failureStage", "EXECUTION",
                                "message", safeDiagnosticMessage(exception)),
                        candidate.releaseResult());
            }
        } catch (RuntimeException exception) {
            return requirementGenerationFailed(registered, evidence, exception);
        }
    }

    private DiagnosisCaseSnapshot buildRegisteredCandidate(
            DiagnosisCaseSnapshot registered, Map<String, Object> evidence) {
        String layer = text(evidence.get("suspectedLayer"));
        String requirement = text(evidence.get("requirement"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SQL_CHANGE");
        payload.put("layer", layer);
        payload.put("nodeId", firstText(evidence.get("nodeId"), defaultNodeId(layer)));
        payload.put("requirements", requirement);
        payload.put("expectedCaseEffect", requirement);
        payload.put("validationSql", text(evidence.get("validationSql")));
        payload.put("patchConditions", evidence.get("patchConditions") instanceof List<?>
                ? evidence.get("patchConditions") : List.of());
        payload.put("scopeTargets", evidence.get("scopeTargets") instanceof List<?>
                ? evidence.get("scopeTargets") : List.of());
        payload.put("publicRuleIds", evidence.get("publicRuleIds") instanceof List<?>
                ? evidence.get("publicRuleIds") : List.of());
        if (text(evidence.get("candidateSql")).isBlank()
                && payload.get("publicRuleIds") instanceof List<?> publicRules
                && publicRules.stream().map(DiagnosisCaseService::text)
                        .anyMatch("PUBLIC_003"::equalsIgnoreCase)) {
            // PUBLIC_003 只负责最终明细重复检查。它不能变成一条猜测性的 SQL
            // 过滤条件；如果同时选了患者/科室规则，仍允许先生成那两类候选。
            List<String> modifiableRules = publicRules.stream()
                    .map(DiagnosisCaseService::text)
                    .filter(value -> !"PUBLIC_003".equalsIgnoreCase(value)).toList();
            if (modifiableRules.isEmpty()) {
                fail("PUBLIC_DUPLICATE_REQUIRES_MANUAL_EVENT_CHECK",
                        "当前仅命中重复明细规则。请先人工检查当前指标相关事件是否重复启用，"
                                + "再根据确认的业务编号、去重字段和保留顺序修改源表抽取 SQL。");
            }
        }
        payload.put("sql", text(evidence.get("candidateSql")));
        Map<String, Object> cause = Map.of(
                "status", "CANDIDATE_TRIAL",
                "conclusion", "根据实施人员提交的条件生成候选 SQL并进行影子试跑；"
                        + "试跑结果不会修改正式口径。");
        return buildCandidateForTrial(registered, payload, cause);
    }

    private DiagnosisCaseSnapshot buildCandidateForTrial(
            DiagnosisCaseSnapshot current, Map<String, Object> payload,
            Map<String, Object> cause) {
        String type = text(payload.get("type"));
        if (!List.of("DATA_REPAIR", "EVENT_CONFIG", "SQL_CHANGE", "CALIBER_CHANGE").contains(type)) {
            fail("CHANGE_TYPE_INVALID", "修改类型无效");
        }
        Map<String, Object> proposal = new LinkedHashMap<>(payload);
        if (List.of("DATA_REPAIR", "EVENT_CONFIG").contains(type)) {
            proposal.put("sqlCandidateRequired", false);
            return update(current, "WAITING_EXTERNAL_FIX", "CHANGE_PROPOSAL",
                    current.gateResults(), current.evidence(), cause, proposal,
                    Map.of(), current.shadowTrial(), current.releaseResult());
        }
        String layer = text(payload.get("layer"));
        if (!List.of("SOURCE_EXTRACT", "OVERVIEW").contains(layer)) {
            fail("SQL_LAYER_INVALID", "候选 SQL只能修改抽取或统计其中一层");
        }
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        // Keep the same canonical SQL that the formal execution path uses.
        // A few historical entity pages wrap a whole code block in quotes;
        // those quotes are Markdown noise, not part of a runnable query.
        String original = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                "SOURCE_EXTRACT".equals(layer) ? sourceSql(entity) : entity.overviewSql());
        String candidate = text(payload.get("sql"));
        String generationMethod = candidate.isBlank() ? "" : "实施人员提供 SQL";
        List<String> requestedPublicRuleIds = stringList(payload.get("publicRuleIds"));
        boolean duplicateRuleOnly = requestedPublicRuleIds.size() == 1
                && "PUBLIC_003".equalsIgnoreCase(requestedPublicRuleIds.get(0));
        if (candidate.isBlank() && (requestedPublicRuleIds.isEmpty() || duplicateRuleOnly)) {
            candidate = structuredExclusionCandidate(
                    original, mapList(payload.get("scopeTargets")));
            if (!candidate.isBlank()) {
                String withRules = deterministicCandidateForCurrentDatabase(candidate, text(payload.get("requirements")),
                        text(payload.get("validationSql")), layer);
                if (!withRules.isBlank()) candidate = withRules;
                generationMethod = "程序按已选排除范围和规则生成";
            }
        }
        if (candidate.isBlank()) {
            List<Map<String, Object>> patchConditions = mapList(payload.get("patchConditions"));
            PublicDataScreeningRuleService.PatchPlan publicPlan = publicRulePatchPlan(
                    layer, original, requestedPublicRuleIds);
            if (!publicPlan.blockedRules().isEmpty() && publicPlan.changes().isEmpty()) {
                fail("PUBLIC_RULE_PATCH_UNSUPPORTED", publicRuleBlockMessage(publicPlan.blockedRules()));
            }
            if (!publicPlan.changes().isEmpty()) {
                patchConditions = new ArrayList<>(patchConditions);
                patchConditions.addAll(publicPlan.changes());
                proposal.put("publicRuleApplications", publicPlan.appliedRules());
                proposal.put("publicRuleWarnings", publicPlan.blockedRules());
            }
            if (patchConditions.isEmpty()) {
                patchConditions = SafeSqlPredicatePatcher.inferChanges(
                        text(payload.get("requirements")),
                        enrichFieldSuggestions(SafeSqlPredicatePatcher.fieldSuggestions(original)));
            }
            var patch = SafeSqlPredicatePatcher.apply(original, patchConditions);
            if (patch.attempted() && !patch.applied()) {
                fail("CANDIDATE_PATCH_UNSUPPORTED", patch.message());
            }
            candidate = patch.sql();
            if (!candidate.isBlank()) {
                String withRules = deterministicCandidateForCurrentDatabase(candidate, text(payload.get("requirements")),
                        text(payload.get("validationSql")), layer);
                if (!withRules.isBlank()) candidate = withRules;
                generationMethod = "程序按结构化条件生成";
            }
            if (!publicPlan.appliedRules().isEmpty() && !candidate.isBlank()) {
                generationMethod = "程序按当前指标字段应用公共规则生成";
            }
        }
        if (candidate.isBlank()) {
            candidate = deterministicCandidateForCurrentDatabase(original, text(payload.get("requirements")),
                    text(payload.get("validationSql")), layer);
            if (!candidate.isBlank()) generationMethod = "程序按明确条件生成";
        }
        if (candidate.isBlank()) {
            fail("CANDIDATE_PATCH_UNSUPPORTED",
                    "当前要求超出安全自动改写范围。请按页面提示提供表别名、字段和简单判断条件，"
                            + "或由实施人员提供一条完整候选 SELECT；系统不会让模型重写整段复杂 SQL。");
        }
        var validation = validateCandidateSql(candidate, layer);
        if (!validation.ok()) fail("CANDIDATE_SQL_INVALID", validation.message());
        validateCandidateContract(original, candidate);
        Map<String, Object> candidateValue = new LinkedHashMap<>();
        candidateValue.put("nodeId", firstText(payload.get("nodeId"), defaultNodeId(layer)));
        candidateValue.put("layer", layer);
        candidateValue.put("sql", candidate);
        candidateValue.put("originalSql", original);
        candidateValue.put("originalSqlHash", DiagnosisShadowRunner.sha256(original));
        candidateValue.put("candidateSqlHash", DiagnosisShadowRunner.sha256(candidate));
        candidateValue.put("originalSqlExecutable", executableSql(current, original, layer));
        candidateValue.put("candidateSqlExecutable", executableSql(current, candidate, layer));
        candidateValue.put("generationMethod", generationMethod);
        candidateValue.put("generationMode", firstText(
                payload.get("generationMode"), text(payload.get("candidateSql")).isBlank()
                        ? "AI_MODIFY" : "DIRECT_EDIT"));
        candidateValue.put("publicRuleApplications",
                proposal.getOrDefault("publicRuleApplications", List.of()));
        candidateValue.put("publicRuleWarnings",
                proposal.getOrDefault("publicRuleWarnings", List.of()));
        candidateValue.put("databaseDialect", "OVERVIEW".equals(layer) || entity.sourceQueryFromReal()
                ? "SQL Server" : "当前启用的业务库方言");
        candidateValue.put("validationStages", List.of(
                "单条只读查询检查通过",
                "表与字段访问范围检查通过",
                "模板参数与输出结构检查通过",
                "已转换为当前数据库方言；实际执行成功后才会生成影子对账"));
        candidateValue.put("baselineResult", calculationSummary(executionEvidence(current.gateResults())));
        candidateValue.put("rawSqlNotice",
                "草稿归档保存的是知识库模板 SQL，不是页面复制到 Navicat 的带变量声明的执行脚本。");
        candidateValue.put("validation", Map.of("ok", true, "message", validation.message()));
        candidateValue.put("diffSummary", text(payload.get("requirements")));
        return update(current, "CANDIDATE_READY", "SHADOW_TRIAL", current.gateResults(),
                current.evidence(), cause, proposal, candidateValue,
                Map.of(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot requirementGenerationFailed(
            DiagnosisCaseSnapshot current, Map<String, Object> evidence, RuntimeException exception) {
        List<Map<String, Object>> values = new ArrayList<>(current.evidence());
        if (!values.isEmpty()) {
            Map<String, Object> failed = new LinkedHashMap<>(values.get(values.size() - 1));
            Map<String, Object> analysis = new LinkedHashMap<>(map(failed.get("requirementAnalysis")));
            analysis.put("judgement", "已登记本轮要求，但没有生成可安全试跑的候选 SQL。");
            analysis.put("nextAction", "根据提示补充或调整条件后重新发送要求；正式口径和正式结果没有改变。");
            analysis.put("failureReason", safeDiagnosticMessage(exception));
            failed.put("requirementAnalysis", Map.copyOf(analysis));
            values.set(values.size() - 1, Map.copyOf(failed));
        }
        return update(current, "CANDIDATE_FAILED", "CASE_INVESTIGATION", current.gateResults(),
                List.copyOf(values), current.causeConclusion(), current.changeProposal(),
                current.candidateSql(), current.shadowTrial(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot runShadow(DiagnosisCaseSnapshot current) {
        if (!List.of("SHADOW_TRIAL", "DRAFT_SAVE").contains(current.currentStep())) {
            fail("DIAGNOSIS_STEP_ORDER_VIOLATION",
                    "当前步骤是 " + current.currentStep() + "，不能执行候选 SQL 影子试跑");
        }
        DiagnosisShadowRunner runner = shadowRunner.getIfAvailable();
        if (runner == null) fail("SHADOW_RUNNER_UNAVAILABLE", "当前环境未启用双库影子执行器");
        DiagnosisCaseSnapshot prepared = normalizePersistedDurationCandidate(current);
        Map<String, Object> trial = new LinkedHashMap<>(runner.run(prepared, start(prepared), end(prepared)));
        trial.put("nodeId", firstText(prepared.candidateSql().get("nodeId"),
                defaultNodeId(text(prepared.candidateSql().get("layer")))));
        trial.putIfAbsent("layer", prepared.candidateSql().get("layer"));
        trial.putIfAbsent("status", Boolean.TRUE.equals(trial.get("passed")) ? "PASSED" : "FAILED");
        trial.putIfAbsent("failureStage", Boolean.TRUE.equals(trial.get("passed")) ? "" : "EXECUTION");
        trial.putIfAbsent("message", Boolean.TRUE.equals(trial.get("passed"))
                ? "候选 SQL已完成隔离影子试跑。" : "影子试跑未通过，请查看失败阶段。" );
        boolean passed = Boolean.TRUE.equals(trial.get("passed"));
        return update(prepared, passed ? "SHADOW_PASSED" : "SHADOW_FAILED",
                passed ? "DRAFT_SAVE" : "SHADOW_TRIAL", prepared.gateResults(),
                prepared.evidence(), prepared.causeConclusion(), prepared.changeProposal(),
                prepared.candidateSql(), trial, prepared.releaseResult());
    }

    private DiagnosisCaseSnapshot normalizePersistedDurationCandidate(DiagnosisCaseSnapshot current) {
        Map<String, Object> candidate = new LinkedHashMap<>(current.candidateSql());
        if (!"SOURCE_EXTRACT".equals(text(candidate.get("layer")))) return current;
        boolean oracle = sqlDialects != null && sqlDialects.oracleActive();
        String sql = text(candidate.get("sql"));
        String normalized = normalizeDurationCandidateForExecution(sql, oracle);
        if (normalized.equals(sql)) return current;
        candidate.put("sql", normalized);
        candidate.put("candidateSqlHash", DiagnosisShadowRunner.sha256(normalized));
        candidate.put("candidateSqlExecutable", executableSql(current, normalized, "SOURCE_EXTRACT"));
        return update(current, current.status(), current.currentStep(), current.gateResults(),
                current.evidence(), current.causeConclusion(), current.changeProposal(),
                Map.copyOf(candidate), current.shadowTrial(), current.releaseResult());
    }

    /**
     * 使用当前正式 SQL 完成一次隔离基线试跑。该动作不会生成可保存的医院草稿，
     * 也不会把正式表、正式 SQL 或正式卡片结果替换为影子结果。
     */
    private DiagnosisCaseSnapshot runLineageBaseline(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireAnyStep(current, "CASE_INPUT", "CASE_INVESTIGATION", "SHADOW_TRIAL", "DRAFT_SAVE");
        requireLineageExecutionReady(current);
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        if (entity == null) {
            fail("LINEAGE_BASELINE_ENTITY_MISSING", "当前生效口径实体不存在，无法执行基线试跑");
        }

        String preferredLayer = text(payload.get("layer"));
        boolean sourceAvailable = !text(sourceSql(entity)).isBlank();
        String layer = "OVERVIEW".equals(preferredLayer) || !sourceAvailable
                ? "OVERVIEW" : "SOURCE_EXTRACT";
        String sql = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                "SOURCE_EXTRACT".equals(layer) ? sourceSql(entity) : entity.overviewSql());
        if (sql.isBlank()) {
            fail("LINEAGE_BASELINE_SQL_UNAVAILABLE",
                    "当前口径没有可用于基线试跑的" + ("SOURCE_EXTRACT".equals(layer) ? "抽取" : "概览") + " SQL");
        }

        String nodeId = firstText(payload.get("nodeId"), defaultNodeId(layer));
        Map<String, Object> candidatePayload = new LinkedHashMap<>();
        candidatePayload.put("type", "SQL_CHANGE");
        candidatePayload.put("layer", layer);
        candidatePayload.put("nodeId", nodeId);
        candidatePayload.put("sql", sql);
        candidatePayload.put("requirements", "使用当前正式 SQL 执行完整隔离基线试跑");
        candidatePayload.put("expectedCaseEffect", "核对当前正式链路能否完成抽取、统计与分子分母对账");
        DiagnosisCaseSnapshot prepared = buildCandidateForTrial(current, candidatePayload, Map.of(
                "status", "LINEAGE_BASELINE",
                "conclusion", "使用当前正式 SQL 执行隔离基线试跑，不修改正式数据。"));

        Map<String, Object> candidate = new LinkedHashMap<>(prepared.candidateSql());
        candidate.put("baselineOnly", true);
        candidate.put("nodeId", nodeId);
        candidate.put("layer", layer);

        DiagnosisShadowRunner runner = shadowRunner.getIfAvailable();
        if (runner == null) fail("SHADOW_RUNNER_UNAVAILABLE", "当前环境未启用双库影子执行器");
        Map<String, Object> trial = new LinkedHashMap<>(runner.run(
                prepared, start(prepared), end(prepared)));
        boolean passed = Boolean.TRUE.equals(trial.get("passed"));
        trial.put("baselineOnly", true);
        trial.put("nodeId", nodeId);
        trial.put("layer", layer);
        trial.putIfAbsent("status", passed ? "PASSED" : "FAILED");
        trial.putIfAbsent("failureStage", passed ? "" : "EXECUTION");
        trial.putIfAbsent("message", passed
                ? "当前正式 SQL 已完成隔离基线试跑。"
                : "当前正式 SQL 基线试跑未通过，请查看失败阶段。" );
        return update(prepared, passed ? "BASELINE_PASSED" : "BASELINE_FAILED",
                "CASE_INVESTIGATION", prepared.gateResults(), prepared.evidence(),
                prepared.causeConclusion(), prepared.changeProposal(), Map.copyOf(candidate),
                Map.copyOf(trial), prepared.releaseResult());
    }

    /**
     * 在数据确认页预览公共规则对应的抽取 SQL。预览只保存候选语句，既不改变
     * 当前工作流步骤，也不执行数据库写入，便于实施人员先看清具体改动。
     */
    private DiagnosisCaseSnapshot previewPublicRuleFix(
            DiagnosisCaseSnapshot current, Map<String, Object> payload) {
        requireLineageExecutionReady(current);
        List<String> ruleIds = stringList(payload.get("publicRuleIds")).stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .filter(value -> List.of("PUBLIC_001", "PUBLIC_002").contains(value))
                .distinct().toList();
        if (ruleIds.isEmpty()) {
            fail("PUBLIC_RULE_FIX_UNSUPPORTED",
                    "当前规则需要人工检查事件启用情况，不能自动生成过滤 SQL");
        }
        Map<String, Object> candidatePayload = new LinkedHashMap<>();
        candidatePayload.put("type", "SQL_CHANGE");
        candidatePayload.put("layer", "SOURCE_EXTRACT");
        candidatePayload.put("nodeId", defaultNodeId("SOURCE_EXTRACT"));
        candidatePayload.put("requirements", "按公共初筛规则修复源表抽取 SQL");
        candidatePayload.put("publicRuleIds", ruleIds);
        DiagnosisCaseSnapshot prepared = buildCandidateForTrial(current, candidatePayload, Map.of(
                "status", "PUBLIC_RULE_FIX_PREVIEW",
                "conclusion", "已按当前指标实际字段生成公共规则候选 SQL，尚未执行。"));
        Map<String, Object> candidate = new LinkedHashMap<>(prepared.candidateSql());
        candidate.put("previewOnly", true);
        candidate.put("publicRuleIds", ruleIds);
        return update(current, "PUBLIC_RULE_FIX_READY", current.currentStep(),
                current.gateResults(), current.evidence(), prepared.causeConclusion(),
                prepared.changeProposal(), Map.copyOf(candidate), Map.of(),
                current.releaseResult());
    }

    /** 使用数据确认页已经预览并确认的公共规则候选 SQL 执行隔离影子试跑。 */
    private DiagnosisCaseSnapshot runPublicRuleFix(DiagnosisCaseSnapshot current) {
        requireLineageExecutionReady(current);
        if (!Boolean.TRUE.equals(current.candidateSql().get("previewOnly"))) {
            fail("PUBLIC_RULE_FIX_PREVIEW_REQUIRED", "请先查看公共规则生成的候选 SQL");
        }
        DiagnosisShadowRunner runner = shadowRunner.getIfAvailable();
        if (runner == null) fail("SHADOW_RUNNER_UNAVAILABLE", "当前环境未启用双库影子执行器");
        Map<String, Object> trial = new LinkedHashMap<>(runner.run(current, start(current), end(current)));
        boolean passed = Boolean.TRUE.equals(trial.get("passed"));
        trial.put("nodeId", firstText(current.candidateSql().get("nodeId"),
                defaultNodeId("SOURCE_EXTRACT")));
        trial.put("layer", "SOURCE_EXTRACT");
        trial.put("publicRuleFix", true);
        trial.putIfAbsent("status", passed ? "PASSED" : "FAILED");
        trial.putIfAbsent("failureStage", passed ? "" : "EXECUTION");
        trial.putIfAbsent("message", passed
                ? "公共规则候选 SQL 已完成隔离试跑。"
                : "公共规则候选 SQL 试跑未通过，请查看失败阶段。");
        return update(current, passed ? "PUBLIC_RULE_FIX_PASSED" : "PUBLIC_RULE_FIX_FAILED",
                current.currentStep(), current.gateResults(), current.evidence(),
                current.causeConclusion(), current.changeProposal(), current.candidateSql(),
                Map.copyOf(trial), current.releaseResult());
    }

    private static void requireLineageExecutionReady(DiagnosisCaseSnapshot current) {
        boolean gatesPassed = java.util.stream.IntStream.rangeClosed(1, 3).allMatch(gate ->
                current.gateResults().stream().anyMatch(item -> number(item.get("gate")) == gate
                        && "PASSED".equals(text(item.get("status")))));
        if (!gatesPassed) {
            fail("LINEAGE_BASE_CHECKS_REQUIRED", "本次统计数据尚未准备完成，暂时不能执行影子链路");
        }
    }

    private static String defaultNodeId(String layer) {
        return "OVERVIEW".equals(layer) ? "overview-sql" : "source-extract-sql";
    }

    private DiagnosisCaseSnapshot runCurrentSqlShadow(DiagnosisCaseSnapshot current) {
        requireAnyStep(current, "CASE_INPUT", "CASE_INVESTIGATION");
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        if (entity == null || text(sourceSql(entity)).isBlank()) {
            fail("CURRENT_SOURCE_SQL_UNAVAILABLE", "当前口径没有可用于重新抽取核对的源表 SQL");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SQL_CHANGE");
        payload.put("layer", "SOURCE_EXTRACT");
        payload.put("sql", MrasSqlExecutionService.stripLeadingTrailingQuotes(sourceSql(entity)));
        payload.put("requirements", "使用当前正式口径重新抽取最新业务数据，不修改 SQL");
        payload.put("expectedCaseEffect", "核对最新业务源与当前正式中间表是否存在差异");
        Map<String, Object> cause = Map.of(
                "status", "DATA_REFRESH_CHECK",
                "conclusion", "使用完全相同的正式抽取 SQL写入影子表，仅核对业务数据或快照是否已更新");
        DiagnosisCaseSnapshot prepared = buildCandidateForTrial(current, payload, cause);
        DiagnosisShadowRunner runner = shadowRunner.getIfAvailable();
        if (runner == null) fail("SHADOW_RUNNER_UNAVAILABLE", "当前环境未启用双库影子执行器");
        Map<String, Object> trial = runner.run(prepared, start(prepared), end(prepared));
        return update(prepared, "DATA_REFRESH_CHECKED", "DATA_REFRESH_REVIEW",
                prepared.gateResults(), prepared.evidence(), prepared.causeConclusion(),
                prepared.changeProposal(), prepared.candidateSql(), trial, prepared.releaseResult());
    }

    private DiagnosisCaseSnapshot formalRecalculateCurrent(DiagnosisCaseSnapshot current) {
        requireStep(current, "DATA_REFRESH_REVIEW");
        if (!Boolean.TRUE.equals(current.shadowTrial().get("completed"))) {
            fail("DATA_REFRESH_SHADOW_REQUIRED", "请先完成原口径影子重新抽取核对");
        }
        Map<String, Object> before = calculationSummary(executionEvidence(current.gateResults()));
        Map<String, Object> event = eventGate.run(current.hospitalId(), current.ruleId(),
                current.profileId(), start(current), end(current));
        List<Map<String, Object>> gates = replaceGate(current.gateResults(), 2, event);
        Map<String, Object> value = valueGate.run(executionEvidence(gates));
        gates = replaceGate(gates, 3, value);
        boolean passed = "PASSED".equals(text(event.get("status")))
                && "PASSED".equals(text(value.get("status")));
        Map<String, Object> after = calculationSummary(executionEvidence(gates));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("outcome", passed ? "DATA_REFRESHED" : "DATA_REFRESH_FAILED");
        outcome.put("message", passed
                ? "已使用未修改的正式口径重新抽取并计算；本次结果已更新。"
                : "正式重新计算未通过，请根据抽取错误继续处理。");
        outcome.put("beforeResult", before);
        outcome.put("afterResult", after);
        outcome.put("shadowTrial", current.shadowTrial());
        outcome.put("completedAt", Instant.now().toString());
        return update(current, passed ? "DATA_REFRESHED" : "DATA_REFRESH_FAILED",
                passed ? "COMPLETED" : "DATA_REFRESH_REVIEW", gates, current.evidence(),
                current.causeConclusion(), current.changeProposal(), current.candidateSql(),
                current.shadowTrial(), Map.copyOf(outcome));
    }

    private DiagnosisCaseSnapshot reviseCandidate(DiagnosisCaseSnapshot current) {
        requireStep(current, "SHADOW_TRIAL");
        return update(current, "IN_PROGRESS", "CASE_INVESTIGATION", current.gateResults(),
                current.evidence(), current.causeConclusion(), current.changeProposal(),
                Map.of(), Map.of(), current.releaseResult());
    }

    private DiagnosisCaseSnapshot saveHospitalDraft(
            HospitalPrincipal principal,
            DiagnosisCaseSnapshot current,
            Map<String, Object> payload) {
        requireStep(current, "DRAFT_SAVE");
        if (!Boolean.TRUE.equals(payload.get("confirmed"))) {
            fail("DRAFT_SAVE_NOT_CONFIRMED", "保存医院草稿需要明确确认");
        }
        for (String field : List.of("issueSummary", "changeSummary", "expectedImpact", "verificationSummary")) {
            if (text(payload.get(field)).isBlank()) {
                fail("DRAFT_DESCRIPTION_REQUIRED", "保存前请填写问题说明、本次修改、预期影响和影子验证结论");
            }
        }
        Map<String, Object> proposal = new LinkedHashMap<>(current.changeProposal());
        copy(payload, proposal, "issueSummary", "changeSummary", "expectedImpact", "verificationSummary");
        DiagnosisCaseSnapshot described = withProposal(current, Map.copyOf(proposal));
        // 草稿只写入医院独立草稿目录，formalEffect=false，不是正式发布。
        // 诊断任务本身已完成医院隔离，此处不应沿用旧的“发布”权限阻断实施人员。
        Map<String, Object> saved = drafts.save(described, principal.accountId());
        return updateWithDraft(described, "DRAFT_SAVED", "COMPLETED", saved);
    }

    private DiagnosisCaseSnapshot revalidateHospitalDraft(DiagnosisCaseSnapshot current) {
        requireStep(current, "COMPLETED");
        Map<String, Object> verified = drafts.verifySavedDraft(current);
        if (Boolean.TRUE.equals(verified.get("baselineExpired"))) {
            return updateWithDraft(current, "DRAFT_BASELINE_EXPIRED", "COMPLETED", verified);
        }
        DiagnosisShadowRunner runner = shadowRunner.getIfAvailable();
        if (runner == null) fail("SHADOW_RUNNER_UNAVAILABLE", "当前环境未启用双库影子执行器");
        Map<String, Object> trial = runner.run(current, start(current), end(current));
        Map<String, Object> result = new LinkedHashMap<>(verified);
        result.put("revalidation", trial);
        result.put("revalidationPassed", Boolean.TRUE.equals(trial.get("passed")));
        result.put("revalidatedAt", Instant.now().toString());
        return updateWithDraft(current,
                Boolean.TRUE.equals(trial.get("passed")) ? "DRAFT_REVALIDATED" : "DRAFT_REVALIDATION_FAILED",
                "COMPLETED", Map.copyOf(result));
    }

    private String explain(DiagnosisCaseSnapshot current, Map<String, Object> evidence) {
        modelRegistry.requireInfo(current.modelId());
        return models.complete(current.modelId(),
                "你是医院指标SQL排查助手。只能根据实施人员提交的业务要求和验证证据进行分析。"
                        + "先判断问题属于抽取SQL、目标表概览SQL或证据不足；如果属于抽取SQL，"
                        + "再说明是多抽还是少抽。不能声称已经执行SQL，不能自行补写表名、字段或业务规则。"
                        + "回复最多220个中文字符，固定写三项：判断、依据、下一步。"
                        + "证据不足时只要求补充最关键的一项事实。",
                "当前口径：" + current.caliberSnapshot() + "\n案例：" + current.caseInput()
                        + "\n已验证证据：" + evidence,
                Duration.ofSeconds(90)).content().strip();
    }

    private Map<String, Object> requirementAnalysis(
            DiagnosisCaseSnapshot current, Map<String, Object> evidence) {
        String layer = text(evidence.get("suspectedLayer"));
        String layerLabel = switch (layer) {
            case "SOURCE_EXTRACT" -> "抽取 SQL";
            case "OVERVIEW" -> "目标表概览 SQL";
            default -> "尚未确定";
        };
        String requirement = text(evidence.get("requirement"));
        String next = "SOURCE_EXTRACT".equals(layer)
                ? "系统将基于当前正式抽取 SQL生成候选语句，并在影子表中试跑。"
                : "系统将基于当前正式概览 SQL生成候选语句，并在影子环境中试跑。";
        return Map.of(
                "judgement", "已按“" + layerLabel + "”登记本轮排查要求；正在生成候选 SQL并进行影子试跑，"
                        + "不会修改正式口径。",
                "requirement", requirement,
                "nextAction", next,
                "sqlGeneration", "简单字段过滤由程序在字段所属查询层追加；涉及新增表、JOIN、子查询、聚合或去重时，必须由实施人员提供完整候选 SQL。模型不负责重写整段 SQL。");
    }

    private Map<String, Object> activeSqlContext(
            DiagnosisCaseSnapshot current, Map<String, Object> evidence) {
        String layer = text(evidence.get("suspectedLayer"));
        if (!List.of("SOURCE_EXTRACT", "OVERVIEW").contains(layer)) {
            return Map.of("available", false, "message", "尚未选择抽取 SQL或目标表概览 SQL，暂不展示对应脚本。");
        }
        EntityPageData entity = entities.getEntity(current.profileId(), current.hospitalId());
        String original = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                "SOURCE_EXTRACT".equals(layer) ? text(sourceSql(entity)) : text(entity.overviewSql()));
        if (original.isBlank()) return Map.of("available", false, "message", "当前口径没有可展示的脚本。");
        return Map.of(
                "available", true,
                "layer", layer,
                "layerLabel", "SOURCE_EXTRACT".equals(layer) ? "当前正式抽取 SQL" : "当前正式概览 SQL",
                "executableSql", executableSql(current, original, layer),
                "templateSqlHash", DiagnosisShadowRunner.sha256(original),
                "currentResult", calculationSummary(executionEvidence(current.gateResults())));
    }

    private String executableSql(
            DiagnosisCaseSnapshot current, String templateSql, String layer) {
        if (templateSql == null || templateSql.isBlank()) return "";
        try {
            return dataFlowSqlExporter.exportExecutableSql(templateSql,
                    lineage.analyze(templateSql).tables(), layer, start(current), end(current));
        } catch (RuntimeException ignored) {
            // Export presentation must never make a stored, already-validated
            // candidate unusable. The canonical template remains available.
            return "";
        }
    }

    /**
     * Candidate SQL is stored as a knowledge-base template, but safety is
     * verified against the same single-query T-SQL shape the formal path uses.
     * Rendering removes #ETC/#EQUALS directives only; period parameters remain
     * named so the validator can enforce their presence.
     */
    private ReadOnlySqlValidator.ValidationResult validateCandidateSql(
            String templateSql, String layer) {
        Map<String, Object> validationParams = new LinkedHashMap<>();
        if ("SOURCE_EXTRACT".equals(layer)) {
            // 正式抽取固定走 outHosp 分支；必须先选中该 #EQUALS 分支，
            // 否则渲染器会连同 :startTime/:endTime 一起删除，造成候选脚本误报缺时间。
            validationParams.put("syncType", "outHosp");
        }
        validationParams.put("marptBeginAt", "统计开始时间");
        validationParams.put("marptEndAt", "统计结束时间");
        String rendered = templateRenderer.renderTemplate(templateSql, validationParams);
        return sqlValidator.validateReadOnly(
                MrasSqlExecutionService.stripLeadingTrailingQuotes(rendered));
    }

    private static Map<String, Object> calculationSummary(Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        copy(evidence, result, "numeratorCount", "denominatorCount", "resultValue", "status",
                "calculationId", "executedAt", "durationMs");
        return Map.copyOf(result);
    }

    /**
     * Handles the small, high-confidence filter changes encountered during
     * implementation: a known target-table field plus a plain-language
     * inclusion/exclusion rule.  It deliberately does not try to understand
     * arbitrary business prose; complex changes require an implementer-supplied
     * candidate and still go through the same programmatic validator.
     */
    static String deterministicCandidate(String original, String requirement, String layer) {
        return deterministicCandidate(original, requirement, "", layer);
    }

    static String deterministicCandidate(
            String original, String requirement, String validationSql, String layer) {
        return deterministicCandidate(original, requirement, validationSql, layer,
                looksLikeOracleSql(original));
    }

    private String deterministicCandidateForCurrentDatabase(
            String original, String requirement, String validationSql, String layer) {
        boolean oracleSource = "SOURCE_EXTRACT".equals(layer)
                && sqlDialects != null && sqlDialects.oracleActive();
        return deterministicCandidate(original, requirement, validationSql, layer, oracleSource);
    }

    private static String deterministicCandidate(
            String original, String requirement, String validationSql, String layer,
            boolean oracleSource) {
        if (original == null || original.isBlank()) return "";
        if ("SOURCE_EXTRACT".equals(layer)) {
            String candidate = original;
            boolean changed = false;
            String durationCandidate = crossFieldDurationCandidate(
                    candidate, requirement, oracleSource);
            if (!durationCandidate.isBlank()) {
                changed |= !durationCandidate.equals(candidate);
                candidate = durationCandidate;
            }
            String knownCandidate = sourceConsultationCandidate(candidate, requirement);
            if (!knownCandidate.isBlank()) {
                changed |= !knownCandidate.equals(candidate);
                candidate = knownCandidate;
            }
            String verifiedCandidate = sourceExclusionCandidateFromValidation(
                    candidate, requirement, validationSql);
            if (!verifiedCandidate.isBlank()) {
                changed |= !verifiedCandidate.equals(candidate);
                candidate = verifiedCandidate;
            }
            return changed ? candidate : "";
        }
        if (!"OVERVIEW".equals(layer)) return "";
        String candidate = original;
        boolean changed = false;
        String durationCandidate = crossFieldDurationCandidate(candidate, requirement, false);
        if (!durationCandidate.isBlank()) {
            changed |= !durationCandidate.equals(candidate);
            candidate = durationCandidate;
        }
        String condition = consultationFilterConditions(requirement);
        if (condition.isBlank()) return changed ? candidate : "";
        // The first consultation aggregation block is the only safe insertion
        // point for this known rule.  Do not depend on whitespace or template
        // marker layout in historical entity pages.
        int target = indexOfIgnoreCase(candidate, "MRAS_BUSINESS_CONSULTATION", 0);
        if (target < 0) return changed ? candidate : "";
        int groupBy = indexOfIgnoreCase(candidate, "GROUP BY", target);
        if (groupBy < 0) return changed ? candidate : "";
        String scope = candidate.substring(target, groupBy);
        if (scope.toUpperCase(java.util.Locale.ROOT).contains("EVENT.COURSE_STATUS <>")) {
            return changed ? candidate : "";
        }
        return candidate.substring(0, groupBy) + "\n" + condition + "\n" + candidate.substring(groupBy);
    }

    /**
     * 编译“结束时间－开始时间至少 N 小时”这类跨字段规则。
     * SQL Server 使用 DATEADD，Oracle 使用 NUMTODSINTERVAL，二者都比较真实时间点，
     * 不使用 DATEDIFF 的跨整点边界计数。
     */
    private static String crossFieldDurationCandidate(
            String original, String requirement, boolean oracle) {
        String value = text(requirement);
        java.util.regex.Matcher hours = java.util.regex.Pattern.compile(
                "(?i)(?:(?:排除|剔除|不纳入)[^。；]{0,40}(?:少于|小于|不足)|"
                        + "(?:大于等于|不少于|至少|>=|≥))\\s*(8|24)\\s*(?:小时|h|hour)")
                .matcher(value);
        if (!hours.find()) return "";
        java.util.regex.Matcher admitted = java.util.regex.Pattern.compile(
                "(?i)([A-Za-z_][A-Za-z0-9_]*)\\.(FIRST_ADMITTED_TO_WARD_AT|ADMITTED_TO_WARD_AT|ADMITTED_AT|ADMISSION_AT|START_TIME)")
                .matcher(original);
        java.util.regex.Matcher discharged = java.util.regex.Pattern.compile(
                "(?i)([A-Za-z_][A-Za-z0-9_]*)\\.(DISCHARGED_FROM_WARD_AT|DISCHARGED_AT|DISCHARGE_AT|END_TIME)")
                .matcher(original);
        if (!admitted.find() || !discharged.find()) return "";
        String start = admitted.group(1) + "." + admitted.group(2);
        String end = discharged.group(1) + "." + discharged.group(2);
        String amount = hours.group(1);
        String condition = oracle
                ? end + " >= " + start + " + NUMTODSINTERVAL(" + amount + ", 'HOUR')"
                : end + " >= DATEADD(HOUR, " + amount + ", " + start + ")";
        if (containsIgnoreCase(original, condition)) return original;
        int insertAt = lastOuterWhereEnd(original);
        if (insertAt < 0) return "";
        return original.substring(0, insertAt) + "\n  AND " + condition
                + original.substring(insertAt);
    }

    private static boolean looksLikeOracleSql(String sql) {
        String upper = text(sql).toUpperCase(java.util.Locale.ROOT);
        return upper.contains("NUMTODSINTERVAL") || upper.contains("TIMESTAMP '")
                || upper.contains("SYSDATE") || upper.contains("NVL(")
                || upper.contains("ROWNUM") || sql.contains("||");
    }

    private static int lastOuterWhereEnd(String sql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\bWHERE\\s+1\\s*=\\s*1\\b").matcher(sql);
        int end = -1;
        while (matcher.find()) end = matcher.end();
        return end;
    }

    /**
     * 兼容早期已保存的跨字段候选：旧版本可能把 DATEADD 条件插进嵌套查询，
     * 且在 Oracle 影子执行前没有转换。这里只识别系统曾生成的 8/24 小时
     * 固定形态，移回最外层 WHERE 1=1，并按实际业务库方言重建条件。
     */
    static String normalizeDurationCandidateForExecution(String sql, boolean oracle) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\s*AND\\s+"
                        + "([A-Za-z_][A-Za-z0-9_]*\\.(?:DISCHARGED_FROM_WARD_AT|DISCHARGED_AT|DISCHARGE_AT|END_TIME))"
                        + "\\s*>=\\s*DATEADD\\s*\\(\\s*HOUR\\s*,\\s*(8|24)\\s*,\\s*"
                        + "([A-Za-z_][A-Za-z0-9_]*\\.(?:FIRST_ADMITTED_TO_WARD_AT|ADMITTED_TO_WARD_AT|ADMITTED_AT|ADMISSION_AT|START_TIME))"
                        + "\\s*\\)").matcher(text(sql));
        if (!matcher.find()) return sql;
        String endField = matcher.group(1);
        String amount = matcher.group(2);
        String startField = matcher.group(3);
        String without = sql.substring(0, matcher.start()) + "\n" + sql.substring(matcher.end());
        int insertAt = lastOuterWhereEnd(without);
        if (insertAt < 0) return sql;
        String condition = oracle
                ? endField + " >= " + startField
                        + " + NUMTODSINTERVAL(" + amount + ", 'HOUR')"
                : endField + " >= DATEADD(HOUR, " + amount + ", " + startField + ")";
        return without.substring(0, insertAt) + "\n  AND " + condition
                + without.substring(insertAt);
    }

    /**
     * The normal-consultation reference case changes the extraction contract,
     * not the aggregate formula: invalid consultations must never reach the
     * real-table snapshot.  This deliberately handles only the aliases and
     * fields proved by the current source SQL.  If any requested condition
     * cannot be mapped, returning an empty string makes the request fail closed
     * instead of guessing.
     */
    private static String sourceConsultationCandidate(String original, String requirement) {
        String value = text(requirement);
        boolean excludeCancelled = value.contains("作废");
        boolean requireFinished = value.contains("会诊完成时间") && value.contains("不为空");
        boolean requireFirstOrder = (value.contains("首条医嘱") || value.contains("会诊后医嘱")
                || value.contains("会诊医嘱ID")) && value.contains("不为空");
        if (!excludeCancelled && !requireFinished && !requireFirstOrder) return "";
        if (!containsIgnoreCase(original, "INPATIENT_CONSULT_APPLY A")
                || indexOfIgnoreCase(original, "WHERE 1 = 1", 0) < 0) return "";
        List<String> conditions = new ArrayList<>();
        if (excludeCancelled) {
            java.util.regex.Matcher status = java.util.regex.Pattern.compile(
                    "会诊状态\\s*(?:为|=)\\s*['\"“”]?([A-Za-z0-9_-]+)")
                    .matcher(value);
            if (!status.find()) return "";
            conditions.add("AND A.CONSULT_STATUS_CODE <> '" + status.group(1) + "'");
        }
        if (requireFinished) {
            if (!containsIgnoreCase(original, "D.CONSULT_COMPLETED_AT")) return "";
            conditions.add("AND D.CONSULT_COMPLETED_AT IS NOT NULL");
        }
        if (requireFirstOrder) {
            if (!containsIgnoreCase(original, "t2.PRESCRIBED_AT")) return "";
            conditions.add("AND t2.PRESCRIBED_AT IS NOT NULL");
        }
        int where = lastIndexOfIgnoreCase(original, "WHERE 1 = 1");
        int insertAt = original.indexOf('\n', where);
        if (insertAt < 0) return "";
        String scope = original.substring(where, Math.min(original.length(), insertAt + 1_000));
        if (scope.toUpperCase(java.util.Locale.ROOT).contains("A.CONSULT_STATUS_CODE <>")) return "";
        return original.substring(0, insertAt + 1) + "       "
                + String.join("\n       ", conditions) + "\n"
                + original.substring(insertAt + 1);
    }

    /**
     * Uses an implementer-verified SELECT only as field-level evidence.  The
     * implementation intentionally accepts one unambiguous alias-qualified
     * string equality (for example {@code t1.FULL_NAME='入院712'}) and turns it
     * into a null-safe exclusion in the existing extraction query.  DECLAREs,
     * period predicates and record ids from the verification script are never
     * copied into the knowledge SQL.
     */
    private static String sourceExclusionCandidateFromValidation(
            String original, String requirement, String validationSql) {
        String value = text(requirement);
        if (validationSql == null || validationSql.isBlank()
                || !(value.contains("排除") || value.contains("不纳入")
                || value.contains("不要") || value.contains("剔除"))) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?\\s*\\.\\s*)"
                        + "\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?\\s*=\\s*N?'((?:''|[^'])*)'")
                .matcher(validationSql);
        List<String[]> matches = new ArrayList<>();
        while (matcher.find()) {
            String alias = matcher.group(1);
            String field = matcher.group(2);
            String literal = matcher.group(3).replace("''", "'");
            if (!value.contains(literal) || !hasQualifiedField(original, alias, field)) continue;
            matches.add(new String[] {alias, field, literal});
        }
        if (matches.size() != 1) return "";
        String[] selected = matches.get(0);
        int where = lastIndexOfIgnoreCase(original, "WHERE 1 = 1");
        if (where < 0) return "";
        int insertAt = original.indexOf('\n', where);
        if (insertAt < 0) return "";
        String qualified = selected[0] + "." + selected[1];
        String literal = selected[2].replace("'", "''");
        String condition = "AND (" + qualified + " IS NULL OR " + qualified + " <> N'" + literal + "')";
        String scope = original.substring(where, Math.min(original.length(), insertAt + 1_000));
        if (containsIgnoreCase(scope, condition)) return "";
        return original.substring(0, insertAt + 1) + "       " + condition + "\n"
                + original.substring(insertAt + 1);
    }

    private static boolean hasQualifiedField(String sql, String alias, String field) {
        String pattern = "(?i)(?<![A-Za-z0-9_])\\[?" + java.util.regex.Pattern.quote(alias)
                + "\\]?\\s*\\.\\s*\\[?" + java.util.regex.Pattern.quote(field)
                + "\\]?(?![A-Za-z0-9_])";
        return java.util.regex.Pattern.compile(pattern).matcher(sql).find();
    }

    private static String consultationFilterConditions(String requirement) {
        String value = text(requirement);
        List<String> conditions = new ArrayList<>();
        java.util.regex.Matcher status = java.util.regex.Pattern.compile(
                "会诊状态\\s*(?:为|=)\\s*['\"“”]?([A-Za-z0-9_-]+)")
                .matcher(value);
        if (value.contains("作废") && status.find()) {
            conditions.add("   AND event.COURSE_STATUS <> '" + status.group(1) + "'");
        }
        if (value.contains("会诊完成时间") && value.contains("不为空")) {
            conditions.add("   AND event.FINISH_AT IS NOT NULL");
        }
        if ((value.contains("首条医嘱时间") || value.contains("会诊后医嘱时间"))
                && value.contains("不为空")) {
            conditions.add("   AND event.FIRST_ORDER_AT IS NOT NULL");
        }
        return String.join("\n", conditions);
    }

    private static int indexOfIgnoreCase(String value, String target, int from) {
        return value.toUpperCase(java.util.Locale.ROOT)
                .indexOf(target.toUpperCase(java.util.Locale.ROOT), from);
    }

    private static int lastIndexOfIgnoreCase(String value, String target) {
        return value.toUpperCase(java.util.Locale.ROOT)
                .lastIndexOf(target.toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean containsIgnoreCase(String value, String target) {
        return indexOfIgnoreCase(value, target, 0) >= 0;
    }

    private static DiagnosisCaseSnapshot update(
            DiagnosisCaseSnapshot source, String status, String step,
            List<Map<String, Object>> gates, List<Map<String, Object>> evidence,
            Map<String, Object> cause, Map<String, Object> proposal,
            Map<String, Object> candidate, Map<String, Object> trial,
            Map<String, Object> release) {
        return new DiagnosisCaseSnapshot(source.caseId(), source.hospitalId(), source.userId(),
                source.sessionId(), status, step, source.ruleId(), source.profileId(),
                source.knowledgeReleaseId(), source.modelId(), source.caseInput(),
                source.caliberSnapshot(), source.caseExpectedClassification(), gates, evidence,
                cause, proposal, candidate, trial, source.investigationMode(),
                source.autonomousRun(), source.draftResult(), release,
                source.createdAt(), Instant.now().toString());
    }

    private static DiagnosisCaseSnapshot updateWithDraft(
            DiagnosisCaseSnapshot source, String status, String step, Map<String, Object> draft) {
        return new DiagnosisCaseSnapshot(source.caseId(), source.hospitalId(), source.userId(),
                source.sessionId(), status, step, source.ruleId(), source.profileId(),
                source.knowledgeReleaseId(), source.modelId(), source.caseInput(),
                source.caliberSnapshot(), source.caseExpectedClassification(), source.gateResults(),
                source.evidence(), source.causeConclusion(), source.changeProposal(),
                source.candidateSql(), source.shadowTrial(), source.investigationMode(),
                source.autonomousRun(), draft, source.releaseResult(),
                source.createdAt(), Instant.now().toString());
    }

    private static DiagnosisCaseSnapshot withAutonomous(
            DiagnosisCaseSnapshot source, String mode, Map<String, Object> run) {
        return new DiagnosisCaseSnapshot(source.caseId(), source.hospitalId(), source.userId(),
                source.sessionId(), source.status(), source.currentStep(), source.ruleId(),
                source.profileId(), source.knowledgeReleaseId(), source.modelId(), source.caseInput(),
                source.caliberSnapshot(), source.caseExpectedClassification(), source.gateResults(),
                source.evidence(), source.causeConclusion(), source.changeProposal(),
                source.candidateSql(), source.shadowTrial(), mode, run, source.draftResult(),
                source.releaseResult(), source.createdAt(), Instant.now().toString());
    }

    private static DiagnosisCaseSnapshot withProposal(
            DiagnosisCaseSnapshot source, Map<String, Object> proposal) {
        return new DiagnosisCaseSnapshot(source.caseId(), source.hospitalId(), source.userId(),
                source.sessionId(), source.status(), source.currentStep(), source.ruleId(),
                source.profileId(), source.knowledgeReleaseId(), source.modelId(), source.caseInput(),
                source.caliberSnapshot(), source.caseExpectedClassification(), source.gateResults(),
                source.evidence(), source.causeConclusion(), proposal, source.candidateSql(),
                source.shadowTrial(), source.investigationMode(), source.autonomousRun(),
                source.draftResult(), source.releaseResult(), source.createdAt(), Instant.now().toString());
    }

    private static List<Map<String, Object>> replaceGate(
            List<Map<String, Object>> values, int gate, Map<String, Object> result) {
        List<Map<String, Object>> copy = new ArrayList<>(values);
        copy.removeIf(item -> number(item.get("gate")) == gate);
        copy.add(result);
        copy.sort(java.util.Comparator.comparingInt(item -> number(item.get("gate"))));
        return List.copyOf(copy);
    }

    private static Map<String, Object> executionEvidence(List<Map<String, Object>> gates) {
        return gates.stream()
                .filter(item -> number(item.get("gate")) == 2)
                .findFirst()
                .map(item -> map(item.get("facts")))
                .map(facts -> map(facts.get("executionEvidence")))
                .orElse(Map.of());
    }

    private static void requireStep(DiagnosisCaseSnapshot current, String step) {
        if (!step.equals(current.currentStep())) {
            fail("DIAGNOSIS_STEP_ORDER_VIOLATION",
                    "当前步骤是 " + current.currentStep() + "，不能执行 " + step + " 的动作");
        }
    }

    private static void requireAnyStep(DiagnosisCaseSnapshot current, String... steps) {
        if (java.util.Arrays.asList(steps).contains(current.currentStep())) return;
        fail("DIAGNOSIS_STEP_ORDER_VIOLATION",
                "当前步骤是 " + current.currentStep() + "，不能执行当前动作");
    }

    private static void validateCaseInput(Map<String, Object> value) {
        String direction = text(value.get("issueDirection")).toUpperCase(java.util.Locale.ROOT);
        if (!direction.isBlank() && !List.of("OVER_INCLUDED", "UNDER_INCLUDED",
                "WRONG_CLASSIFICATION", "SUSPECT_SYNC", "UNKNOWN").contains(direction)) {
            fail("CASE_ISSUE_DIRECTION_INVALID", "请选择多算、少算、归类不对、怀疑未同步或暂不确定");
        }
        String scope = text(value.get("scopeType")).toUpperCase(java.util.Locale.ROOT);
        if (scope.isBlank()) scope = "RECORD";
        switch (scope) {
            case "RECORD" -> {
                if (text(value.get("recordField")).isBlank() || recordIds(value).isEmpty()) {
                    fail("CASE_INPUT_INCOMPLETE", "选择具体记录时必须提供记录类型和至少一个记录编号");
                }
            }
            case "DEPARTMENT", "DATA_CATEGORY" -> {
                if (text(value.get("scopeValue")).isBlank()) {
                    fail("CASE_SCOPE_VALUE_REQUIRED", "请填写本次要排查的科室、病区或数据范围");
                }
            }
            case "TIME_RANGE" -> {
                if (text(value.get("scopeStart")).isBlank() || text(value.get("scopeEnd")).isBlank()) {
                    fail("CASE_SCOPE_TIME_REQUIRED", "请填写本次要排查的开始时间和结束时间");
                }
            }
            case "OVERALL" -> {
                if (text(value.get("symptom")).isBlank()
                        && text(value.get("caseDescription")).isBlank()) {
                    fail("CASE_SCOPE_DESCRIPTION_REQUIRED", "排查整体结果时请描述系统结果与医院预期的差异");
                }
            }
            default -> fail("CASE_SCOPE_INVALID", "不支持的排查范围: " + scope);
        }
    }

    private static boolean hasCaseInput(Map<String, Object> value) {
        if (!text(value.get("scopeType")).isBlank()) return true;
        return !text(value.get("recordField")).isBlank() && !recordIds(value).isEmpty();
    }

    private static String scopeDescription(Map<String, Object> value) {
        String scope = text(value.get("scopeType")).toUpperCase(java.util.Locale.ROOT);
        if (scope.isBlank() || "RECORD".equals(scope)) {
            return text(value.get("recordField")) + "=" + String.join("、", recordIds(value));
        }
        return switch (scope) {
            case "DEPARTMENT" -> "科室/病区：" + text(value.get("scopeValue"));
            case "TIME_RANGE" -> "时间段：" + text(value.get("scopeStart")) + " 至 "
                    + text(value.get("scopeEnd"));
            case "DATA_CATEGORY" -> "数据范围：" + text(value.get("scopeValue"));
            default -> "整体结果";
        };
    }

    static List<String> recordIds(Map<String, Object> value) {
        List<String> result = new ArrayList<>();
        Object listed = value.get("recordIds");
        if (listed instanceof List<?> items) {
            for (Object item : items) addRecordIds(result, text(item));
        }
        if (result.isEmpty()) addRecordIds(result, text(value.get("recordId")));
        if (result.size() > 20) {
            fail("CASE_RECORD_LIMIT_EXCEEDED", "一次最多核对20个同类记录编号");
        }
        for (String id : result) {
            if (!id.matches("[A-Za-z0-9_.:-]{1,100}")) {
                fail("CASE_RECORD_ID_INVALID", "记录编号只能包含字母、数字、点、冒号、下划线和短横线");
            }
        }
        return List.copyOf(result);
    }

    private static void addRecordIds(List<String> result, String value) {
        for (String item : value.split("[\\s,，;；]+")) {
            String id = item.strip();
            if (!id.isBlank() && !result.contains(id)) result.add(id);
        }
    }

    private static String inferExpectedMembership(String statement) {
        String value = text(statement);
        if (value.contains("只进入分母")) return "DENOMINATOR_ONLY";
        if (value.contains("不应进入") || value.contains("不进入")
                || value.contains("排除") || value.contains("不纳入")) {
            return "EXCLUDED";
        }
        if (value.contains("应进入分子") || value.contains("进入分子和分母")) return "NUMERATOR";
        return "UNKNOWN";
    }

    static String safeDiagnosticMessage(RuntimeException exception) {
        Throwable mostSpecific = exception;
        while (mostSpecific.getCause() != null && mostSpecific.getCause() != mostSpecific) {
            mostSpecific = mostSpecific.getCause();
        }
        String message = text(mostSpecific.getMessage())
                .replaceAll("(?i)(password|pwd|token)=[^;\\s]+", "$1=***");
        if (message.isBlank()) return "候选 SQL生成或影子试跑未完成。";
        return message.substring(0, Math.min(message.length(), 500));
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        return source.stream().map(DiagnosisCaseService::map)
                .filter(item -> !item.isEmpty()).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        return source.stream().map(DiagnosisCaseService::text)
                .filter(item -> !item.isBlank()).toList();
    }

    private PublicDataScreeningRuleService.PatchPlan publicRulePatchPlan(
            String layer, String original, List<String> publicRuleIds) {
        if (!"SOURCE_EXTRACT".equals(layer) || publicRuleIds.isEmpty()) {
            return PublicDataScreeningRuleService.PatchPlan.empty();
        }
        if (publicScreeningRules == null) {
            fail("PUBLIC_RULE_SERVICE_UNAVAILABLE", "公共初筛规则服务当前不可用，未生成候选 SQL");
        }
        return publicScreeningRules.planSourceExtractChanges(
                publicRuleIds,
                enrichFieldSuggestions(SafeSqlPredicatePatcher.fieldSuggestions(original)));
    }

    private static String publicRuleBlockMessage(List<Map<String, Object>> blockedRules) {
        return blockedRules.stream()
                .map(item -> firstText(item.get("reason"), item.get("ruleId")))
                .filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private static LocalDateTime start(DiagnosisCaseSnapshot value) {
        return time(text(value.caseInput().get("statStart")), "统计开始时间");
    }

    private static LocalDateTime end(DiagnosisCaseSnapshot value) {
        return time(text(value.caseInput().get("statEnd")), "统计结束时间");
    }

    private static LocalDateTime time(String value, String label) {
        try {
            return LocalDateTime.parse(value);
        } catch (Exception exception) {
            fail("DIAGNOSIS_TIME_INVALID", label + "必须是 ISO 日期时间");
            return null;
        }
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }

    private String sourceSql(EntityPageData entity) {
        if (entity == null) return "";
        return sqlDialects == null ? entity.sourceTableSql() : sqlDialects.sourceTableSql(entity);
    }

    private void validateCandidateContract(String original, String candidate) {
        var originalLineage = lineage.analyze(original);
        var candidateLineage = lineage.analyze(candidate);
        List<String> extraTables = candidateLineage.tables().stream()
                .filter(table -> originalLineage.tables().stream()
                        .noneMatch(value -> value.equalsIgnoreCase(table)))
                .toList();
        if (!extraTables.isEmpty()) {
            fail("CANDIDATE_TABLE_OUT_OF_SCOPE",
                    "候选 SQL引用了原口径之外的表：" + String.join("、", extraTables));
        }
        java.util.Set<String> originalParams = templateParameters(original);
        java.util.Set<String> candidateParams = templateParameters(candidate);
        if (!originalParams.equals(candidateParams)) {
            fail("CANDIDATE_TEMPLATE_PARAMETER_CHANGED",
                    "候选 SQL必须保留原口径模板参数；原参数=" + originalParams
                            + "，候选参数=" + candidateParams);
        }
    }

    private static java.util.Set<String> templateParameters(String sql) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("#\\{([A-Za-z0-9_]+)\\}").matcher(text(sql));
        while (matcher.find()) values.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        matcher = java.util.regex.Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)")
                .matcher(text(sql));
        while (matcher.find()) values.add(":" + matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return java.util.Set.copyOf(values);
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }

    private static void fail(String code, String message) {
        throw error(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static IndicatorDetailException error(String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }

    public record CreateCommand(
            String sessionId,
            String ruleId,
            String profileId,
            String statStart,
            String statEnd,
            String modelId,
            Map<String, Object> caseInput,
            Map<String, Object> expectedClassification) {
        public CreateCommand {
            caseInput = caseInput == null ? Map.of() : Map.copyOf(caseInput);
            expectedClassification = expectedClassification == null
                    ? Map.of() : Map.copyOf(expectedClassification);
        }
    }

    public record ActionCommand(String action, Map<String, Object> payload) {
        public ActionCommand {
            action = action == null ? "" : action.strip().toUpperCase();
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
