package com.hospital.wikiagent.agent.diagnosis;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hospital.wikiagent.agent.batch.DetailResultCache;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.initialization.KnowledgeDataDictionary;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.EntitySqlDialectResolver;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor.DetailExtraction;
import com.hospital.wikiagent.agent.mras.MrasDetailContractRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.model.AgentModelInfo;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.details.IndicatorDetailException;
import com.hospital.wikiagent.details.DetailGroupCatalog;
import com.hospital.wikiagent.details.MrasSpecialDetailService;
import com.hospital.wikiagent.details.MrasSpecialDetailSnapshotService;
import com.hospital.wikiagent.details.UnifiedDetailQueryService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

/**
 * 沿当前生效数据链路为一个具体案例自动收集只读证据。组件依次查询业务源结果、
 * 真实库指标中间表和最终概览结果，并保留实际 SQL与返回行；某一层无法自动关联
 * 时只标记该层需要人工补证，不会把查询失败解释成业务根因或绕过前三道关卡。
 */
@Component
public class DiagnosisCaseEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosisCaseEvidenceService.class);
    private final EntityPageParser entities;
    private final MrasParameterMapper parameters;
    private final MrasTemplateRenderer renderer;
    private final IndicatorDatabaseQueryClient query;
    private final MrasDetailSqlExtractor detailExtractor;
    private final MrasSqlExecutionService mrasExecution;
    private final DetailResultCache detailCache;
    private final AgentModelRegistry modelRegistry;
    private final AgentModelInvoker models;
    private final ObjectMapper mapper;
    private final KnowledgeDataDictionary dataDictionary;
    private PublicDataScreeningRuleService screeningRules;
    private EntitySqlDialectResolver sqlDialects;
    private MrasSpecialDetailService specialDetails;
    private MrasSpecialDetailSnapshotService specialSnapshots;
    private UnifiedDetailQueryService unifiedDetails;
    private SyncDataService syncDataService;
    private SqlServerProperties sqlServerProperties;

    public DiagnosisCaseEvidenceService(
            EntityPageParser entities,
            MrasParameterMapper parameters,
            MrasTemplateRenderer renderer,
            IndicatorDatabaseQueryClient query,
            MrasDetailSqlExtractor detailExtractor,
            MrasSqlExecutionService mrasExecution,
            DetailResultCache detailCache,
            AgentModelRegistry modelRegistry,
            AgentModelInvoker models,
            ObjectMapper mapper,
            KnowledgeDataDictionary dataDictionary) {
        this.entities = entities;
        this.parameters = parameters;
        this.renderer = renderer;
        this.query = query;
        this.detailExtractor = detailExtractor;
        this.mrasExecution = mrasExecution;
        this.detailCache = detailCache;
        this.modelRegistry = modelRegistry;
        this.models = models;
        this.mapper = mapper;
        this.dataDictionary = dataDictionary;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScreeningRules(PublicDataScreeningRuleService value) {
        this.screeningRules = value;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSqlDialects(EntitySqlDialectResolver value) {
        this.sqlDialects = value;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPatientCandidateDependencies(
            SyncDataService syncDataService,
            SqlServerProperties sqlServerProperties) {
        this.syncDataService = syncDataService;
        this.sqlServerProperties = sqlServerProperties;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSpecialDetails(
            MrasSpecialDetailService details,
            MrasSpecialDetailSnapshotService snapshots) {
        this.specialDetails = details;
        this.specialSnapshots = snapshots;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUnifiedDetails(UnifiedDetailQueryService value) {
        this.unifiedDetails = value;
    }

    public Map<String, Object> collect(
            DiagnosisCaseSnapshot snapshot, LocalDateTime start, LocalDateTime end) {
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        if (entity == null) throw new IllegalStateException("当前生效口径不存在");
        String field = text(snapshot.caseInput().get("recordField")).toUpperCase(Locale.ROOT);
        List<String> values = DiagnosisCaseService.recordIds(snapshot.caseInput());
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]*") || values.isEmpty()) {
            throw new IllegalStateException("案例记录标识不安全或为空");
        }
        Map<String, Object> templateParams = new LinkedHashMap<>(parameters.mapTimeOnly(start, end));
        templateParams.put("syncType", "outHosp");
        List<Map<String, Object>> stages = new ArrayList<>();
        String sourceField = resolveSourceField(field, entity.sourceTableSql());
        String targetField = resolveTargetField(field, entity);
        if (!text(entity.sourceTableSql()).isBlank()) {
            String source = renderer.render(entity.sourceTableSql(), templateParams);
            if (sourceField.isBlank()) {
                stages.add(manual("业务记录与事件抽取结果",
                        "无法从源查询输出列确定记录标识字段，请人工补充字段映射"));
            } else {
                stages.add(execute("业务记录与事件抽取结果", DatabaseRole.BUSINESS,
                        caseQuery(source, sourceField, values), true));
            }
        } else {
            stages.add(manual("业务记录与事件抽取结果", "知识库没有独立业务库抽取 SQL"));
        }
        if (!text(entity.targetTable()).isBlank() && !targetField.isBlank()) {
            String target = "SELECT * FROM [dbo].[" + entity.targetTable()
                    + "] WHERE CONVERT(NVARCHAR(200), [" + targetField + "]) IN ("
                    + values.stream().map(DiagnosisCaseEvidenceService::literal)
                            .collect(java.util.stream.Collectors.joining(",")) + ")";
            stages.add(execute("真实库指标中间表", DatabaseRole.REAL, target, false));
        } else if (!text(entity.targetTable()).isBlank()) {
            stages.add(manual("真实库指标中间表",
                    "无法从真实库统计 SQL 确定记录标识字段，请人工补充字段映射"));
        } else {
            stages.add(manual("真实库指标中间表", "当前口径属于真实库已有表直接统计，无独立指标中间表"));
        }
        if (!text(entity.overviewSql()).isBlank()) {
            stages.add(execute("最终指标结果", DatabaseRole.REAL,
                    renderer.render(entity.overviewSql(), templateParams), false));
        } else {
            stages.add(manual("最终指标结果", "知识库没有可执行的概览统计 SQL"));
        }
        long completed = stages.stream().filter(item -> "COMPLETED".equals(item.get("status"))).count();
        Map<String, Object> display = display(stages);
        return Map.of(
                "summary", text(display.get("conclusion")),
                "stages", List.copyOf(stages),
                "display", display,
                "identifierMapping", Map.of(
                        "recordType", field,
                        "recordIds", values,
                        "businessSourceField", sourceField,
                        "realTargetField", targetField),
                "allStagesCompleted", completed == stages.size());
    }

    /**
     * 按基础校验冻结的卡片值加载分子或分母明细。首次查询执行同源明细并做双计数
     * 对账，随后把两组记录缓存30分钟，切换分子/分母和翻页时不重复抽取。
     */
    public Map<String, Object> details(
            DiagnosisCaseSnapshot snapshot, String group, int page, int pageSize) {
        return details(snapshot, group, page, pageSize, "", "");
    }

    /**
     * 按结构化患者身份条件查询完整指标链路。候选优先来自指标中间表；中间表
     * 未命中或当前口径没有中间表时，才使用相同条件精确查询 Oracle 住院记录。
     * 最终分子、分母归属始终使用已经和卡片结果对账的冻结明细判断。
     */
    public Map<String, Object> searchPatientCandidates(
            DiagnosisCaseSnapshot snapshot,
            String direction,
            String lookupMode,
            String keyword,
            String fullName,
            String bedNo,
            String imrn,
            String admissionDate,
            String encounterId,
            Integer requestedPage,
            Integer requestedPageSize) {
        MrasDetailKind kind = MrasDetailContractRegistry.kindFor(
                snapshot.ruleId(), snapshot.profileId());
        if (kind != MrasDetailKind.COUNT_RATIO) {
            throw error("DIAGNOSIS_PATIENT_LOOKUP_UNSUPPORTED",
                    "当前指标不是普通比例指标，请使用该指标对应的贡献、样本或数据源明细核对。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int page = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedPageSize == null ? 50 : requestedPageSize;
        if (page < 1 || pageSize < 1 || pageSize > 50) {
            throw error("DIAGNOSIS_PATIENT_LOOKUP_PAGE_INVALID",
                    "页码必须大于0，每页最多查询50条患者记录。", HttpStatus.BAD_REQUEST);
        }
        PatientLookupCriteria criteria = PatientLookupCriteria.of(
                lookupMode, keyword, fullName, bedNo, imrn, admissionDate, encounterId);
        criteria.validate();

        DetailRows details = loadDetailRows(snapshot);
        Map<String, Map<String, Object>> denominatorByEncounter = rowsByEncounter(
                details.denominatorRows());
        Map<String, Map<String, Object>> numeratorByEncounter = rowsByEncounter(
                details.numeratorRows());
        boolean overCounted = "OVER_COUNTED".equalsIgnoreCase(text(direction));
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        boolean targetTableAvailable = entity != null && !text(entity.targetTable()).isBlank();
        CandidateSearchRows target = CandidateSearchRows.unavailable("");
        CandidateSearchRows primary = new CandidateSearchRows(List.of(), 0, true, "");
        String sourceLayer = "RECONCILED_DETAIL";

        List<Map<String, Object>> matchedDetails = details.denominatorRows().stream()
                .filter(row -> matchesPatientCriteria(row, criteria)).toList();
        if (!overCounted) {
            if (targetTableAvailable) {
                target = searchTargetCandidates(snapshot, entity, criteria, page, pageSize);
                primary = target;
                sourceLayer = "TARGET";
            } else {
                primary = searchSourceCandidates(snapshot, entity, criteria, page, pageSize);
                sourceLayer = "SOURCE_EXTRACTION";
            }
            if (primary.total() == 0 && !criteria.isEmpty()) {
                CandidateSearchRows fallback = searchBusinessCandidates(
                        snapshot, criteria, page, pageSize);
                if (fallback.available() && fallback.total() > 0) {
                    primary = fallback;
                    sourceLayer = targetTableAvailable
                            ? "BUSINESS_FALLBACK" : "BUSINESS_DIRECT";
                } else if (!fallback.available() && !primary.available()) {
                    primary = fallback;
                }
            }
        }

        LinkedHashMap<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Map<String, Object> row : primary.rows()) {
            addPatientCandidate(candidates, row, sourceLayer,
                    "TARGET".equals(sourceLayer) || "SOURCE_EXTRACTION".equals(sourceLayer),
                    denominatorByEncounter, numeratorByEncounter);
        }

        if (overCounted) {
            int from = Math.min(matchedDetails.size(), (page - 1) * pageSize);
            int to = Math.min(matchedDetails.size(), from + pageSize);
            for (Map<String, Object> row : matchedDetails.subList(from, to)) {
                addPatientCandidate(candidates, row, "RECONCILED_DETAIL", true,
                        denominatorByEncounter, numeratorByEncounter);
            }
        }

        List<Map<String, Object>> items = candidates.values().stream()
                .limit(pageSize).map(Map::copyOf).toList();
        long total = overCounted ? matchedDetails.size() : primary.total();
        boolean truncated = total > (long) page * pageSize || candidates.size() > pageSize;
        String emptyReason = items.isEmpty()
                ? overCounted
                        ? "当前完整分子、分母明细中没有匹配患者，请核对身份信息和统计周期。"
                        : primary.available()
                                ? criteria.isEmpty()
                                        ? "当前抽取结果没有可选择的患者；可输入就诊ID、住院号等条件精确核对业务源。"
                                        : "当前抽取结果及业务源均未找到匹配患者，请核对身份信息和统计周期。"
                                : firstText(primary.message(), target.message(), "患者查询暂不可用。")
                : "";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", snapshot.caseId());
        result.put("ruleId", snapshot.ruleId());
        result.put("direction", direction);
        result.put("lookupMode", lookupMode);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total);
        result.put("truncated", truncated);
        result.put("items", items);
        result.put("targetTableAvailable", targetTableAvailable);
        result.put("sourceLayer", sourceLayer);
        result.put("statStart", text(snapshot.caseInput().get("statStart")));
        result.put("statEnd", text(snapshot.caseInput().get("statEnd")));
        result.put("emptyReason", emptyReason);
        result.put("warning", overCounted ? "" : firstText(primary.message(), target.message()));
        return Collections.unmodifiableMap(result);
    }

    private CandidateSearchRows searchTargetCandidates(
            DiagnosisCaseSnapshot snapshot,
            EntityPageData entity,
            PatientLookupCriteria criteria,
            int page,
            int pageSize) {
        if (entity == null || text(entity.targetTable()).isBlank()) {
            return CandidateSearchRows.unavailable("当前口径没有独立指标中间表。");
        }
        String table = text(entity.targetTable());
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return CandidateSearchRows.unavailable("当前口径登记的中间表名称不安全。");
        }
        try {
            String metadataSql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = " + nationalLiteral(table);
            List<String> columns = query.execute(DatabaseRole.REAL, metadataSql).stream()
                    .map(row -> row.entrySet().stream()
                            .filter(entry -> "COLUMN_NAME".equalsIgnoreCase(entry.getKey()))
                            .map(entry -> text(entry.getValue())).findFirst().orElse(""))
                    .filter(value -> !value.isBlank()).toList();
            TargetPatientColumns fields = TargetPatientColumns.resolve(columns);
            String predicate = targetPatientPredicate(snapshot, criteria, fields);
            if (predicate.isBlank()) {
                return CandidateSearchRows.unavailable(
                        "当前中间表缺少可用于医院、周期或患者筛选的字段，未执行无范围全表查询。");
            }
            String from = " FROM [dbo].[" + table + "] WHERE " + predicate;
            long total = aggregateCount(query.execute(DatabaseRole.REAL,
                    "SELECT COUNT_BIG(1) AS __candidate_count" + from));
            if (total == 0) return new CandidateSearchRows(List.of(), 0, true, "");
            String orderColumn = firstText(fields.encounterId(), fields.imrn(),
                    fields.admissionDate(), fields.fullName());
            String orderBy = orderColumn.isBlank()
                    ? " ORDER BY (SELECT NULL)"
                    : " ORDER BY [" + orderColumn + "]";
            int offset = (page - 1) * pageSize;
            String rowsSql = "SELECT *" + from + orderBy + " OFFSET " + offset
                    + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
            return new CandidateSearchRows(
                    query.execute(DatabaseRole.REAL, rowsSql).stream()
                            .map(DiagnosisCaseEvidenceService::jsonSafeDetailRow).toList(),
                    total, true, "");
        } catch (RuntimeException exception) {
            return CandidateSearchRows.unavailable(
                    "中间表患者查询未完成：" + safeMessage(exception));
        }
    }

    private CandidateSearchRows searchSourceCandidates(
            DiagnosisCaseSnapshot snapshot,
            EntityPageData entity,
            PatientLookupCriteria criteria,
            int page,
            int pageSize) {
        if (entity == null) {
            return CandidateSearchRows.unavailable("当前口径没有中间表，业务源抽取查询暂不可用。请输入患者条件精确核对业务源。");
        }
        boolean sourceFromReal = sqlDialects != null && sqlDialects.sourceQueryFromReal(entity);
        boolean oracle = !sourceFromReal && (sqlDialects == null || sqlDialects.oracleActive());
        if (!sourceFromReal && syncDataService == null) {
            return CandidateSearchRows.unavailable("当前口径没有中间表，业务源抽取查询暂不可用。请输入患者条件精确核对业务源。");
        }
        String template = sqlDialects == null
                ? entity.sourceTableSql() : sqlDialects.sourceTableSql(entity);
        if (text(template).isBlank()) {
            return CandidateSearchRows.unavailable("当前口径没有中间表，也没有登记可执行的源抽取 SQL。");
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>(parameters.mapTimeOnly(
                    parseTime(text(snapshot.caseInput().get("statStart"))),
                    parseTime(text(snapshot.caseInput().get("statEnd")))));
            params.put("syncType", "outHosp");
            String sourceSql = sourceFromReal
                    ? renderer.render(template, params).strip()
                    : renderer.renderTemplate(template, params).strip();
            while (sourceSql.endsWith(";")) sourceSql = sourceSql.substring(0, sourceSql.length() - 1).strip();
            String predicate = sourcePatientPredicate(criteria, oracle);
            String where = predicate.isBlank() ? "" : " WHERE " + predicate;
            String from = " FROM (" + sourceSql + ") source_rows" + where;
            String countSql = "SELECT COUNT(1) AS __candidate_count" + from;
            List<Map<String, Object>> countRows = sourceFromReal
                    ? query.execute(DatabaseRole.REAL, countSql)
                    : syncDataService.queryTrustedBusinessSource(countSql, params);
            long total = aggregateCount(countRows);
            if (total == 0) return new CandidateSearchRows(List.of(), 0, true, "");
            int offset = (page - 1) * pageSize;
            String pageSql = oracle
                    ? "SELECT *" + from + " ORDER BY encounterId OFFSET " + offset
                            + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY"
                    : "SELECT *" + from + " ORDER BY (SELECT NULL) OFFSET " + offset
                            + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
            List<Map<String, Object>> rows = sourceFromReal
                    ? query.execute(DatabaseRole.REAL, pageSql)
                    : syncDataService.queryTrustedBusinessSource(pageSql, params);
            return new CandidateSearchRows(
                    rows.stream()
                            .map(DiagnosisCaseEvidenceService::jsonSafeDetailRow).toList(),
                    total, true, "当前指标没有独立中间表，候选来自当前生效源抽取 SQL。");
        } catch (RuntimeException exception) {
            return CandidateSearchRows.unavailable(
                    "当前口径没有中间表，源抽取 SQL 查询未完成：" + safeMessage(exception));
        }
    }

    private CandidateSearchRows searchBusinessCandidates(
            DiagnosisCaseSnapshot snapshot,
            PatientLookupCriteria criteria,
            int page,
            int pageSize) {
        if (criteria.isEmpty()) {
            return CandidateSearchRows.unavailable(
                    "未输入患者条件，不执行 Oracle 业务库全表患者浏览。");
        }
        try {
            List<String> predicates = new ArrayList<>();
            if (!criteria.fullName().isBlank()) {
                predicates.add("UPPER(FULL_NAME) LIKE '%' || UPPER("
                        + plainLiteral(criteria.fullName()) + ") || '%'");
            }
            if (!criteria.bedNo().isBlank()) {
                predicates.add("TO_CHAR(BED_NO) = " + plainLiteral(criteria.bedNo()));
            }
            if (!criteria.imrn().isBlank()) {
                predicates.add("TO_CHAR(IMRN) = " + plainLiteral(criteria.imrn()));
            }
            if (!criteria.encounterId().isBlank()) {
                predicates.add("TO_CHAR(ENCOUNTER_ID) = " + plainLiteral(criteria.encounterId()));
            }
            if (!criteria.admissionDate().isBlank()) {
                predicates.add("TRUNC(COALESCE(ADMITTED_AT, FIRST_ADMITTED_TO_WARD_AT)) = DATE "
                        + plainLiteral(criteria.admissionDate()));
            }
            String keywordPredicate = businessKeywordPredicate(criteria);
            if (!keywordPredicate.isBlank()) predicates.add(keywordPredicate);
            if (criteria.admissionDate().isBlank()) {
                String start = text(snapshot.caseInput().get("statStart"));
                String end = text(snapshot.caseInput().get("statEnd"));
                if (!start.isBlank() && !end.isBlank()) {
                    predicates.add("COALESCE(ADMITTED_AT, FIRST_ADMITTED_TO_WARD_AT) < TIMESTAMP "
                            + plainLiteral(oracleTimestamp(end)));
                    predicates.add("(DISCHARGED_FROM_WARD_AT IS NULL OR DISCHARGED_FROM_WARD_AT >= TIMESTAMP "
                            + plainLiteral(oracleTimestamp(start)) + ")");
                }
            }
            String where = " WHERE " + String.join(" AND ", predicates);
            String table = " FROM INPATIENT_ENCOUNTER";
            long total = aggregateCount(query.execute(DatabaseRole.BUSINESS,
                    "SELECT COUNT(1) AS __candidate_count" + table + where));
            if (total == 0) return new CandidateSearchRows(List.of(), 0, true, "");
            int offset = (page - 1) * pageSize;
            String rowsSql = "SELECT ENCOUNTER_ID, FULL_NAME, IMRN, BED_NO, "
                    + "COALESCE(ADMITTED_AT, FIRST_ADMITTED_TO_WARD_AT) AS ADMITTED_AT, "
                    + "CURRENT_WARD_ID" + table + where + " ORDER BY ENCOUNTER_ID OFFSET "
                    + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
            return new CandidateSearchRows(
                    query.execute(DatabaseRole.BUSINESS, rowsSql).stream()
                            .map(DiagnosisCaseEvidenceService::jsonSafeDetailRow).toList(),
                    total, true, "");
        } catch (RuntimeException exception) {
            return CandidateSearchRows.unavailable(
                    "医院业务源患者查询未完成：" + safeMessage(exception));
        }
    }

    private String targetPatientPredicate(
            DiagnosisCaseSnapshot snapshot,
            PatientLookupCriteria criteria,
            TargetPatientColumns columns) {
        List<String> predicates = new ArrayList<>();
        Long hospitalSoid = sqlServerProperties == null
                ? null : sqlServerProperties.getHospitalSoid();
        if (hospitalSoid != null && !columns.hospitalSoid().isBlank()) {
            predicates.add("CONVERT(NVARCHAR(40), [" + columns.hospitalSoid() + "]) = "
                    + nationalLiteral(String.valueOf(hospitalSoid)));
        }
        String start = text(snapshot.caseInput().get("statStart"));
        String end = text(snapshot.caseInput().get("statEnd"));
        if (!columns.periodAt().isBlank() && !start.isBlank() && !end.isBlank()) {
            predicates.add("CONVERT(date, [" + columns.periodAt() + "]) BETWEEN "
                    + literal(start.substring(0, Math.min(10, start.length()))) + " AND "
                    + literal(end.substring(0, Math.min(10, end.length()))));
        }
        if (!appendTargetPredicate(predicates, columns.fullName(), criteria.fullName(), true, false)
                || !appendTargetPredicate(predicates, columns.bedNo(), criteria.bedNo(), false, false)
                || !appendTargetPredicate(predicates, columns.imrn(), criteria.imrn(), false, false)
                || !appendTargetPredicate(predicates, columns.encounterId(),
                        criteria.encounterId(), false, false)
                || !appendTargetPredicate(predicates, columns.admissionDate(),
                        criteria.admissionDate(), false, true)) {
            return "";
        }
        String keywordPredicate = targetKeywordPredicate(criteria, columns);
        if (!criteria.keyword().isBlank()) {
            if (keywordPredicate.isBlank()) return "";
            predicates.add(keywordPredicate);
        }
        if (criteria.isEmpty() && predicates.isEmpty()) return "";
        return String.join(" AND ", predicates);
    }

    private static String businessKeywordPredicate(PatientLookupCriteria criteria) {
        String value = criteria.keyword();
        if (value.isBlank()) return "";
        List<String> options = new ArrayList<>();
        switch (criteria.lookupMode()) {
            case "NAME_BED" -> {
                options.add("UPPER(FULL_NAME) LIKE '%' || UPPER("
                        + plainLiteral(value) + ") || '%'");
                options.add("TO_CHAR(BED_NO) = " + plainLiteral(value));
            }
            case "IMRN_ADMISSION_DATE" -> {
                options.add("TO_CHAR(IMRN) = " + plainLiteral(value));
                try {
                    LocalDate.parse(value);
                    options.add("TRUNC(COALESCE(ADMITTED_AT, FIRST_ADMITTED_TO_WARD_AT)) = DATE "
                            + plainLiteral(value));
                } catch (RuntimeException ignored) {
                    // 非日期关键词只按住院号匹配。
                }
            }
            case "ENCOUNTER_ID" -> options.add(
                    "TO_CHAR(ENCOUNTER_ID) = " + plainLiteral(value));
            case "NAME_IMRN" -> {
                options.add("UPPER(FULL_NAME) LIKE '%' || UPPER("
                        + plainLiteral(value) + ") || '%'");
                options.add("TO_CHAR(IMRN) = " + plainLiteral(value));
            }
            default -> {
            }
        }
        return options.isEmpty() ? "" : "(" + String.join(" OR ", options) + ")";
    }

    private static String targetKeywordPredicate(
            PatientLookupCriteria criteria, TargetPatientColumns columns) {
        String value = criteria.keyword();
        if (value.isBlank()) return "";
        List<String> options = new ArrayList<>();
        switch (criteria.lookupMode()) {
            case "NAME_BED" -> {
                appendKeywordContains(options, columns.fullName(), value);
                appendKeywordEquals(options, columns.bedNo(), value, false);
            }
            case "IMRN_ADMISSION_DATE" -> {
                appendKeywordEquals(options, columns.imrn(), value, false);
                appendKeywordEquals(options, columns.admissionDate(), value, true);
            }
            case "ENCOUNTER_ID" -> appendKeywordEquals(
                    options, columns.encounterId(), value, false);
            case "NAME_IMRN" -> {
                appendKeywordContains(options, columns.fullName(), value);
                appendKeywordEquals(options, columns.imrn(), value, false);
            }
            default -> {
            }
        }
        return options.isEmpty() ? "" : "(" + String.join(" OR ", options) + ")";
    }

    private static void appendKeywordContains(List<String> options, String column, String value) {
        if (!column.isBlank()) {
            options.add("CONVERT(NVARCHAR(200), [" + column + "]) LIKE "
                    + nationalLiteral("%" + value + "%"));
        }
    }

    private static void appendKeywordEquals(
            List<String> options, String column, String value, boolean date) {
        if (column.isBlank()) return;
        if (date) {
            try {
                LocalDate.parse(value);
                options.add("CONVERT(date, [" + column + "]) = " + literal(value));
            } catch (RuntimeException ignored) {
                // 非日期关键词只按同组的住院号匹配。
            }
        } else {
            options.add("CONVERT(NVARCHAR(200), [" + column + "]) = "
                    + nationalLiteral(value));
        }
    }

    private static String sourcePatientPredicate(
            PatientLookupCriteria criteria, boolean oracle) {
        if (criteria.isEmpty()) return "";
        List<String> predicates = new ArrayList<>();
        if (!criteria.fullName().isBlank()) {
            predicates.add(containsSql("personName", criteria.fullName(), oracle));
        }
        if (!criteria.imrn().isBlank()) {
            predicates.add(equalsSql("imrn", criteria.imrn(), oracle));
        }
        if (!criteria.encounterId().isBlank()) {
            predicates.add(equalsSql("encounterId", criteria.encounterId(), oracle));
        }
        if (!criteria.admissionDate().isBlank()) {
            predicates.add(dateEqualsSql("eventAt", criteria.admissionDate(), oracle));
        }
        if (!criteria.keyword().isBlank()) {
            List<String> options = new ArrayList<>();
            String value = criteria.keyword();
            switch (criteria.lookupMode()) {
                case "NAME_BED", "NAME_IMRN" -> {
                    options.add(containsSql("personName", value, oracle));
                    if ("NAME_IMRN".equals(criteria.lookupMode())) {
                        options.add(equalsSql("imrn", value, oracle));
                    }
                }
                case "IMRN_ADMISSION_DATE" -> {
                    options.add(equalsSql("imrn", value, oracle));
                    try {
                        LocalDate.parse(value);
                        options.add(dateEqualsSql("eventAt", value, oracle));
                    } catch (RuntimeException ignored) {
                        // 非日期关键词只按住院号匹配。
                    }
                }
                case "ENCOUNTER_ID" -> options.add(
                        equalsSql("encounterId", value, oracle));
                default -> {
                }
            }
            if (!options.isEmpty()) predicates.add("(" + String.join(" OR ", options) + ")");
        }
        return String.join(" AND ", predicates);
    }

    private static String containsSql(String column, String value, boolean oracle) {
        return oracle
                ? "UPPER(" + column + ") LIKE '%' || UPPER(" + plainLiteral(value) + ") || '%'"
                : "UPPER(CONVERT(NVARCHAR(200), " + column + ")) LIKE "
                        + nationalLiteral("%" + value.toUpperCase(Locale.ROOT) + "%");
    }

    private static String equalsSql(String column, String value, boolean oracle) {
        return oracle
                ? "TO_CHAR(" + column + ") = " + plainLiteral(value)
                : "CONVERT(NVARCHAR(200), " + column + ") = " + nationalLiteral(value);
    }

    private static String dateEqualsSql(String column, String value, boolean oracle) {
        return oracle
                ? "TRUNC(" + column + ") = DATE " + plainLiteral(value)
                : "CONVERT(date, " + column + ") = " + literal(value);
    }

    private static boolean appendTargetPredicate(
            List<String> predicates,
            String column,
            String value,
            boolean contains,
            boolean date) {
        if (value.isBlank()) return true;
        if (column.isBlank()) return false;
        String expression = "[" + column + "]";
        if (date) {
            predicates.add("CONVERT(date, " + expression + ") = " + literal(value));
        } else if (contains) {
            predicates.add("CONVERT(NVARCHAR(200), " + expression + ") LIKE "
                    + nationalLiteral("%" + value + "%"));
        } else {
            predicates.add("CONVERT(NVARCHAR(200), " + expression + ") = "
                    + nationalLiteral(value));
        }
        return true;
    }

    private static Map<String, Map<String, Object>> rowsByEncounter(
            List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String encounter = patientValue(row, PatientField.ENCOUNTER_ID);
            if (!encounter.isBlank()) result.putIfAbsent(encounter, row);
        }
        return result;
    }

    private static void addPatientCandidate(
            Map<String, Map<String, Object>> candidates,
            Map<String, Object> sourceRow,
            String sourceLayer,
            boolean targetPresent,
            Map<String, Map<String, Object>> denominatorByEncounter,
            Map<String, Map<String, Object>> numeratorByEncounter) {
        String encounter = patientValue(sourceRow, PatientField.ENCOUNTER_ID);
        if (encounter.isBlank()) return;
        Map<String, Object> denominator = denominatorByEncounter.get(encounter);
        Map<String, Object> numerator = numeratorByEncounter.get(encounter);
        Map<String, Object> displayRow = new LinkedHashMap<>(sourceRow);
        if (denominator != null) displayRow.putAll(denominator);
        if (numerator != null) displayRow.putAll(numerator);
        boolean inDenominator = denominator != null;
        boolean inNumerator = numerator != null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("encounterId", encounter);
        item.put("fullName", patientValue(displayRow, PatientField.FULL_NAME));
        item.put("imrn", patientValue(displayRow, PatientField.IMRN));
        item.put("bedNo", patientValue(displayRow, PatientField.BED_NO));
        item.put("admittedAt", patientValue(displayRow, PatientField.ADMISSION_DATE));
        item.put("departmentName", patientValue(displayRow, PatientField.DEPARTMENT_NAME));
        item.put("sourceLayer", sourceLayer);
        item.put("targetPresent", targetPresent || inDenominator);
        item.put("denominatorPresent", inDenominator);
        item.put("numeratorPresent", inNumerator);
        item.put("membership", inNumerator ? "IN_NUMERATOR"
                : inDenominator ? "IN_DENOMINATOR"
                        : targetPresent ? "IN_TARGET_ONLY" : "BUSINESS_ONLY");
        item.put("row", jsonSafeDetailRow(displayRow));
        candidates.merge(encounter, item, DiagnosisCaseEvidenceService::mergePatientCandidate);
    }

    private static Map<String, Object> mergePatientCandidate(
            Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> result = new LinkedHashMap<>(first);
        second.forEach((key, value) -> {
            if (value != null && !text(value).isBlank()) result.put(key, value);
        });
        if (Boolean.TRUE.equals(first.get("numeratorPresent"))) {
            result.put("numeratorPresent", true);
            result.put("denominatorPresent", true);
            result.put("targetPresent", true);
            result.put("membership", "IN_NUMERATOR");
        } else if (Boolean.TRUE.equals(first.get("denominatorPresent"))) {
            result.put("denominatorPresent", true);
            result.put("targetPresent", true);
            result.put("membership", "IN_DENOMINATOR");
        }
        return result;
    }

    private static boolean matchesPatientCriteria(
            Map<String, Object> row, PatientLookupCriteria criteria) {
        boolean explicitMatches = matchesPatientValue(patientValue(row, PatientField.FULL_NAME),
                        criteria.fullName(), true, false)
                && matchesPatientValue(patientValue(row, PatientField.BED_NO),
                        criteria.bedNo(), false, false)
                && matchesPatientValue(patientValue(row, PatientField.IMRN),
                        criteria.imrn(), false, false)
                && matchesPatientValue(patientValue(row, PatientField.ENCOUNTER_ID),
                        criteria.encounterId(), false, false)
                && matchesPatientValue(patientValue(row, PatientField.ADMISSION_DATE),
                        criteria.admissionDate(), false, true);
        return explicitMatches && matchesPatientKeyword(row, criteria);
    }

    private static boolean matchesPatientKeyword(
            Map<String, Object> row, PatientLookupCriteria criteria) {
        String keyword = criteria.keyword();
        if (keyword.isBlank()) return true;
        return switch (criteria.lookupMode()) {
            case "NAME_BED" -> matchesPatientValue(
                    patientValue(row, PatientField.FULL_NAME), keyword, true, false)
                    || matchesPatientValue(patientValue(row, PatientField.BED_NO),
                            keyword, false, false);
            case "IMRN_ADMISSION_DATE" -> matchesPatientValue(
                    patientValue(row, PatientField.IMRN), keyword, false, false)
                    || matchesPatientValue(patientValue(row, PatientField.ADMISSION_DATE),
                            keyword, false, true);
            case "ENCOUNTER_ID" -> matchesPatientValue(
                    patientValue(row, PatientField.ENCOUNTER_ID), keyword, false, false);
            case "NAME_IMRN" -> matchesPatientValue(
                    patientValue(row, PatientField.FULL_NAME), keyword, true, false)
                    || matchesPatientValue(patientValue(row, PatientField.IMRN),
                            keyword, false, false);
            default -> false;
        };
    }

    private static boolean matchesPatientValue(
            String actual, String expected, boolean contains, boolean date) {
        if (expected.isBlank()) return true;
        if (actual.isBlank()) return false;
        String left = date && actual.length() >= 10 ? actual.substring(0, 10) : actual;
        return contains
                ? left.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))
                : left.equalsIgnoreCase(expected);
    }

    private static String patientValue(Map<String, Object> row, PatientField field) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (field.matches(entry.getKey()) && entry.getValue() != null) {
                String value = text(entry.getValue()).strip();
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private static String nationalLiteral(String value) {
        return literal(value);
    }

    private static String plainLiteral(String value) {
        return "'" + text(value).replace("'", "''") + "'";
    }

    private static String oracleTimestamp(String value) {
        return parseTime(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public Map<String, Object> details(
            DiagnosisCaseSnapshot snapshot, String group, int page, int pageSize,
            String search, String department) {
        return details(null, snapshot, group, page, pageSize, search, department);
    }

    public Map<String, Object> details(
            HospitalPrincipal principal,
            DiagnosisCaseSnapshot snapshot, String group, int page, int pageSize,
            String search, String department) {
        MrasDetailKind kind = MrasDetailContractRegistry.kindFor(
                snapshot.ruleId(), snapshot.profileId());
        Map<String, Object> calculation = frozenCalculation(snapshot);
        BatchTaskSnapshot frozenTask = specialTask(snapshot, calculation, kind);
        if (principal != null && unifiedDetails != null
                && text(search).isBlank() && text(department).isBlank()) {
            return unifiedDetails.loadDiagnosis(
                    principal, snapshot.sessionId(), frozenTask,
                    group, page, pageSize);
        }
        if (kind != MrasDetailKind.COUNT_RATIO) {
            return specialDetails(principal, snapshot, kind, group, page, pageSize);
        }
        String normalizedGroup = group == null || group.isBlank()
                ? DetailGroupCatalog.defaultGroup(kind) : group;
        if (!DetailGroupCatalog.keys(kind).contains(normalizedGroup)) {
            throw error("DIAGNOSIS_DETAIL_GROUP_INVALID",
                    "普通比例不支持明细分组 " + normalizedGroup + "。",
                    HttpStatus.BAD_REQUEST);
        }
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw error("DIAGNOSIS_DETAIL_PAGE_INVALID",
                    "页码必须大于0，每页条数应在1至100之间。", HttpStatus.BAD_REQUEST);
        }
        DetailRows detail = loadDetailRows(snapshot);
        List<Map<String, Object>> allRows = switch (normalizedGroup) {
            case "numerator" -> detail.numeratorRows();
            case "difference" -> detail.denominatorRows().stream()
                    .filter(row -> !meetsNumerator(row)).toList();
            default -> detail.denominatorRows();
        };
        List<String> departments = allRows.stream()
                .flatMap(row -> row.entrySet().stream())
                .filter(entry -> isDepartmentNameField(entry.getKey()))
                .map(entry -> text(entry.getValue()))
                .filter(value -> !value.isBlank())
                .distinct().sorted().limit(500).toList();
        String keyword = text(search).toLowerCase(Locale.ROOT);
        String departmentFilter = text(department);
        List<Map<String, Object>> rows = allRows.stream()
                .filter(row -> keyword.isBlank() || row.values().stream()
                        .map(DiagnosisCaseEvidenceService::text)
                        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(keyword)))
                .filter(row -> departmentFilter.isBlank() || row.entrySet().stream()
                        .filter(entry -> isDepartmentField(entry.getKey()))
                        .map(entry -> text(entry.getValue()))
                        .anyMatch(departmentFilter::equals))
                .toList();
        String startText = text(snapshot.caseInput().get("statStart"));
        String endText = text(snapshot.caseInput().get("statEnd"));
        int from = Math.min(rows.size(), (page - 1) * pageSize);
        int to = Math.min(rows.size(), from + pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchRunId", firstText(
                calculation.get("batchRunId"),
                calculation.get("calculationId"),
                "diagnosis:" + snapshot.caseId()));
        body.put("ruleId", snapshot.ruleId());
        body.put("ruleName", text(snapshot.caliberSnapshot().get("ruleName")));
        body.put("group", normalizedGroup);
        body.put("statStart", startText);
        body.put("statEnd", endText);
        body.put("page", page);
        body.put("pageSize", pageSize);
        body.put("rowCount", rows.size());
        body.put("rows", rows.subList(from, to).stream()
                .map(DiagnosisCaseEvidenceService::jsonSafeRow)
                .toList());
        body.put("search", text(search));
        body.put("department", departmentFilter);
        body.put("departments", departments);
        body.put("unfilteredRowCount", allRows.size());
        body.put("truncated", to < rows.size());
        body.put("snapshotReused", detail.reused());
        body.put("sqlSource", detail.reused()
                ? "diagnosis_detail_cache" : "diagnosis_bound_detail");
        body.put("detailKind", detail.extraction().detailKind().name());
        body.put("detailContractVersion", detail.extraction().contractVersion());
        body.put("cardNumerator", detail.expectedNumerator());
        body.put("cardDenominator", detail.expectedDenominator());
        body.put("detailNumerator", detail.expectedNumerator());
        body.put("detailDenominator", detail.expectedDenominator());
        body.put("overviewSqlHash", detail.expectedHash());
        body.put("groups", DetailGroupCatalog.descriptors(kind, Map.of(
                "numerator", detail.expectedNumerator(),
                "denominator", detail.expectedDenominator(),
                "difference", detail.expectedDenominator() - detail.expectedNumerator())));
        body.put("summary", Map.of(
                "numeratorCount", detail.expectedNumerator(),
                "denominatorCount", detail.expectedDenominator(),
                "differenceCount", detail.expectedDenominator() - detail.expectedNumerator()));
        return Map.copyOf(body);
    }

    private Map<String, Object> specialDetails(
            HospitalPrincipal principal,
            DiagnosisCaseSnapshot snapshot,
            MrasDetailKind kind,
            String group,
            int page,
            int pageSize) {
        if (principal == null || specialDetails == null || specialSnapshots == null) {
            throw error("DIAGNOSIS_DETAIL_CONTEXT_MISSING",
                    "特殊指标明细服务尚未就绪。", HttpStatus.CONFLICT);
        }
        Map<String, Object> calculation = frozenCalculation(snapshot);
        BatchTaskSnapshot task = specialTask(snapshot, calculation, kind);
        LocalDateTime start = parseTime(text(snapshot.caseInput().get("statStart")));
        LocalDateTime end = parseTime(text(snapshot.caseInput().get("statEnd")));
        return specialSnapshots.loadOrCreate(
                principal, task, kind, group, page, pageSize,
                () -> specialDetails.details(task, kind, start, end));
    }

    private static BatchTaskSnapshot specialTask(
            DiagnosisCaseSnapshot snapshot,
            Map<String, Object> calculation,
            MrasDetailKind kind) {
        String batchRunId = snapshot.caseId();
        return new BatchTaskSnapshot(
                batchRunId, 0, snapshot.ruleId(),
                firstText(snapshot.caliberSnapshot().get("ruleName"), snapshot.ruleId()),
                snapshot.profileId(), text(snapshot.caliberSnapshot().get("profileName")),
                firstText(calculation.get("status"), "SUCCESS"),
                nullableDouble(calculation.get("resultValue")),
                nullableLong(calculation.get("numeratorCount")),
                nullableLong(calculation.get("denominatorCount")),
                nullableLong(calculation.get("sampleCount")),
                text(calculation.get("unit")), text(calculation.get("targetValue")),
                text(calculation.get("targetDirection")),
                firstText(calculation.get("qualityStatus"), "NORMAL"),
                text(snapshot.caseInput().get("statStart")),
                text(snapshot.caseInput().get("statEnd")),
                text(calculation.get("overviewSqlHash")), kind.name(),
                firstText(calculation.get("detailContractVersion"),
                        MrasDetailContractRegistry.CONTRACT_VERSION),
                null, text(calculation.get("calculationDisplay")), null, null);
    }

    /**
     * 浏览器只能精确表示 2^53 以内的整数。就诊号、人员 ID 等数据库 numeric(19,0)
     * 若直接序列化为 JSON number 会在患者选择后发生末位变化，因此只把超出安全范围的
     * 整数转成字符串；计数、时间和普通数值继续保留原类型。
     */
    private static Map<String, Object> jsonSafeRow(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        row.forEach((key, value) -> safe.put(key, jsonSafeInteger(value)));
        return Collections.unmodifiableMap(safe);
    }

    private static Object jsonSafeInteger(Object value) {
        BigInteger integer = null;
        if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
            integer = decimal.toBigIntegerExact();
        } else if (value instanceof Long longValue) {
            integer = BigInteger.valueOf(longValue);
        }
        if (integer == null || integer.abs().compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) <= 0) {
            return value;
        }
        return integer.toString();
    }

    private static boolean isDepartmentField(String field) {
        String upper = text(field).toUpperCase(Locale.ROOT);
        if (upper.endsWith("_AT") || upper.endsWith("_TIME")) return false;
        return upper.contains("DEPT")
                || upper.matches(".*WARD_(ID|NO|CODE|NAME)$")
                || upper.equals("WARD_ID") || upper.equals("WARD_NAME")
                || upper.contains("科室") || upper.contains("病区");
    }

    private static boolean isDepartmentNameField(String field) {
        String upper = text(field).toUpperCase(Locale.ROOT);
        if (upper.endsWith("_AT") || upper.endsWith("_TIME")
                || upper.endsWith("_ID") || upper.endsWith("_NO")
                || upper.endsWith("_CODE")) return false;
        return upper.contains("DEPT_NAME") || upper.equals("DEPT")
                || upper.contains("科室名称") || upper.equals("科室");
    }

    /** 对已对账的最终分子、分母明细执行知识库 PUBLIC 规则，不让模型猜测异常。 */
    public Map<String, Object> screenData(DiagnosisCaseSnapshot snapshot) {
        DetailRows detail = loadDetailRows(snapshot);
        List<Map<String, Object>> findings = new ArrayList<>();
        List<PublicDataScreeningRuleService.ScreeningRule> activeRules = activeScreeningRules();
        activeRules.stream().filter(rule -> "PATIENT_NAME".equals(rule.target()))
                .forEach(rule -> screenTextRule(detail.denominatorRows(), rule, findings,
                        DiagnosisCaseEvidenceService::isPatientNameField));
        activeRules.stream().filter(rule -> "DEPARTMENT_NAME".equals(rule.target()))
                .forEach(rule -> screenTextRule(detail.denominatorRows(), rule, findings,
                        DiagnosisCaseEvidenceService::isDepartmentNameField));
        activeRules.stream().filter(rule -> "BUSINESS_KEY".equals(rule.target()))
                .forEach(rule -> {
                    screenDuplicates(detail.denominatorRows(), detail.extraction().identityColumns(),
                            "DENOMINATOR_DETAIL", rule, findings);
                    screenDuplicates(detail.numeratorRows(), detail.extraction().identityColumns(),
                            "NUMERATOR_DETAIL", rule, findings);
                });
        Map<String, Map<String, Object>> distinctFindings = new LinkedHashMap<>();
        findings.forEach(finding -> distinctFindings.putIfAbsent(
                text(finding.get("findingId")), finding));
        List<Map<String, Object>> allFindings = List.copyOf(distinctFindings.values());
        List<Map<String, Object>> limited = allFindings.stream().limit(100).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", snapshot.caseId());
        result.put("ruleId", snapshot.ruleId());
        result.put("profileId", snapshot.profileId());
        result.put("scannedRows", detail.denominatorRows().size());
        result.put("findingCount", allFindings.size());
        result.put("truncated", allFindings.size() > limited.size());
        result.put("rules", activeRules.stream().map(rule -> Map.of(
                "ruleId", rule.ruleId(),
                "target", rule.target(),
                "changeLayer", rule.changeLayer(),
                "action", rule.action(),
                "sourcePath", rule.sourcePath())).toList());
        result.put("findings", limited);
        result.put("departmentOptions", departmentOptions(detail));
        result.put("modelUsed", false);
        result.put("countsReconciled", true);
        result.put("overviewSqlHash", detail.expectedHash());
        return Collections.unmodifiableMap(result);
    }

    private List<PublicDataScreeningRuleService.ScreeningRule> activeScreeningRules() {
        return screeningRules == null ? List.of() : screeningRules.activeRules();
    }

    private static void screenTextRule(
            List<Map<String, Object>> rows,
            PublicDataScreeningRuleService.ScreeningRule rule,
            List<Map<String, Object>> findings,
            java.util.function.Predicate<String> fieldMatcher) {
        for (Map<String, Object> row : rows) {
            String rowKey = screeningRowKey(row);
            row.entrySet().stream()
                    .filter(entry -> fieldMatcher.test(entry.getKey()))
                    .filter(entry -> rule.matches(entry.getValue()))
                    .findFirst()
                    .ifPresent(entry -> {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("findingId", rule.ruleId() + ":" + rowKey);
                        finding.put("ruleCode", rule.ruleId());
                        finding.put("ruleSource", rule.sourcePath());
                        finding.put("changeLayer", rule.changeLayer());
                        finding.put("target", rule.target());
                        finding.put("reason", screeningReason(rule, entry.getKey(), entry.getValue()));
                        finding.put("rowKey", rowKey);
                        finding.put("field", entry.getKey());
                        finding.put("value", text(entry.getValue()));
                        finding.put("sourceGroup", "DENOMINATOR_DETAIL");
                        finding.put("row", compactScreeningRow(row));
                        findings.add(Collections.unmodifiableMap(finding));
                    });
        }
    }

    private static String screeningReason(
            PublicDataScreeningRuleService.ScreeningRule rule, String field, Object value) {
        if ("PUBLIC_001".equals(rule.ruleId())) {
            return "患者姓名包含公共排除字样：“" + text(value) + "”";
        }
        return "科室或病区名称命中公共排除规则：“" + text(value) + "”";
    }

    private static void screenDuplicates(
            List<Map<String, Object>> rows,
            List<String> identityColumns,
            String sourceGroup,
            PublicDataScreeningRuleService.ScreeningRule rule,
            List<Map<String, Object>> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> keyedRows = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String rowKey = screeningBusinessKey(row, identityColumns);
            counts.merge(rowKey, 1, Integer::sum);
            keyedRows.putIfAbsent(rowKey, row);
        }
        counts.forEach((rowKey, count) -> {
            if (count <= 1 || findings.size() >= 100) return;
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("findingId", rule.ruleId() + ":" + sourceGroup + ":" + rowKey);
            finding.put("ruleCode", rule.ruleId());
            finding.put("ruleSource", rule.sourcePath());
            finding.put("changeLayer", rule.changeLayer());
            finding.put("target", rule.target());
            finding.put("reason", ("NUMERATOR_DETAIL".equals(sourceGroup) ? "分子" : "分母")
                    + "明细中同一业务编号出现" + count + "次");
            finding.put("rowKey", rowKey);
            finding.put("count", count);
            finding.put("sourceGroup", sourceGroup);
            finding.put("row", compactScreeningRow(keyedRows.get(rowKey)));
            findings.add(Collections.unmodifiableMap(finding));
        });
    }

    private static String screeningBusinessKey(
            Map<String, Object> row, List<String> identityColumns) {
        for (String field : identityColumns == null ? List.<String>of() : identityColumns) {
            String key = row.keySet().stream().filter(item -> item.equalsIgnoreCase(unqualify(field)))
                    .findFirst().orElse("");
            String value = key.isBlank() ? "" : text(row.get(key));
            if (!value.isBlank()) return key + ":" + value;
        }
        for (String key : List.of("EVENT_ID", "eventId", "ORDER_ID", "CLI_ORDER_ID", "orderId",
                "SURGERY_ID", "surgeryId", "BIZ_ID", "bizId", "ENCOUNTER_ID", "encounterId")) {
            String value = text(row.get(key));
            if (!value.isBlank()) return key + ":" + value;
        }
        return "ROW:" + Integer.toUnsignedString(row.hashCode());
    }

    private static List<Map<String, Object>> departmentOptions(DetailRows detail) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map<String, Object> row : detail.denominatorRows()) {
            DepartmentValue department = departmentValue(row);
            if (department == null) continue;
            counts.computeIfAbsent(department.value(), ignored -> new long[2])[0]++;
            labels.putIfAbsent(department.value(), department.label());
            fields.putIfAbsent(department.value(), department.field());
        }
        for (Map<String, Object> row : detail.numeratorRows()) {
            DepartmentValue department = departmentValue(row);
            if (department == null) continue;
            counts.computeIfAbsent(department.value(), ignored -> new long[2])[1]++;
            labels.putIfAbsent(department.value(), department.label());
            fields.putIfAbsent(department.value(), department.field());
        }
        return counts.entrySet().stream().map(entry -> Map.<String, Object>of(
                "field", fields.get(entry.getKey()),
                "value", entry.getKey(),
                "label", labels.get(entry.getKey()),
                "denominatorCount", entry.getValue()[0],
                "numeratorCount", entry.getValue()[1]))
                .sorted(java.util.Comparator.comparing(value -> text(value.get("label"))))
                .toList();
    }

    private static DepartmentValue departmentValue(Map<String, Object> row) {
        String name = firstPresent(row, List.of("CURRENT_DEPT_NAME", "currentDeptName",
                "DEPT_NAME", "deptName", "CURRENT_WARD_NAME", "currentWardName"));
        for (String field : List.of("CURRENT_DEPT_ID", "currentDeptId", "DEPT_ID", "deptId",
                "CURRENT_WARD_ID", "currentWardId")) {
            String value = text(row.get(field));
            if (!value.isBlank()) return new DepartmentValue(field, value,
                    name.isBlank() ? value : name + "（" + value + "）");
        }
        if (!name.isBlank()) return new DepartmentValue("CURRENT_DEPT_NAME", name, name);
        return null;
    }

    private static String firstPresent(Map<String, Object> row, List<String> fields) {
        for (String field : fields) {
            String value = text(row.get(field));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String screeningRowKey(Map<String, Object> row) {
        for (String key : List.of("ENCOUNTER_ID", "encounterId", "BIZ_ID", "bizId",
                "ORDER_ID", "orderId", "SURGERY_ID", "surgeryId", "IMRN", "imrn")) {
            String value = text(row.get(key));
            if (!value.isBlank()) return key + ":" + value;
        }
        return "ROW:" + Integer.toUnsignedString(row.hashCode());
    }

    private static Map<String, Object> compactScreeningRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return Map.of();
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("ENCOUNTER_ID", "encounterId", "BIZ_ID", "bizId",
                "IMRN", "imrn", "FULL_NAME", "PERSON_NAME", "personName", "CURRENT_DEPT_NAME",
                "currentDeptName", "CURRENT_WARD_NAME", "currentWardName")) {
            if (row.containsKey(key) && row.get(key) != null) compact.put(key, row.get(key));
        }
        return Map.copyOf(compact);
    }

    /**
     * 用已经和卡片数值对账过的同源明细，解释一个患者或科室为什么出现在明细中。
     * 该方法只陈述记录集合事实，不让模型根据名称猜测业务结论。
     */
    public Map<String, Object> clarifyScope(DiagnosisCaseSnapshot snapshot) {
        return clarifyScope(snapshot, true);
    }

    private Map<String, Object> clarifyScope(
            DiagnosisCaseSnapshot snapshot, boolean organizeWithModel) {
        String scopeType = text(snapshot.caseInput().get("scopeType")).toUpperCase(Locale.ROOT);
        if (!List.of("RECORD", "DEPARTMENT", "TIME_RANGE", "DATA_CATEGORY", "OVERALL")
                .contains(scopeType)) {
            throw error("DIAGNOSIS_SCOPE_CLARIFICATION_UNSUPPORTED",
                    "目前只支持记录、科室、时间、数据范围或整体结果澄清。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String issueDirection = normalizeIssueDirection(snapshot.caseInput().get("issueDirection"));
        DetailRows detail = loadDetailRows(snapshot);
        String field = "RECORD".equals(scopeType)
                ? text(snapshot.caseInput().get("recordField"))
                : text(snapshot.caseInput().get("scopeField"));
        List<String> values = scopeValues(snapshot, scopeType);
        if (!"OVERALL".equals(scopeType) && values.stream().allMatch(String::isBlank)) {
            throw error("DIAGNOSIS_SCOPE_VALUE_MISSING",
                    "请先填写要澄清的记录、科室、时间或数据范围。", HttpStatus.BAD_REQUEST);
        }

        List<Map<String, Object>> matched = detail.denominatorRows().stream()
                .filter(row -> matchesScope(row, scopeType, field, values))
                .toList();
        long numeratorCount = matched.stream()
                .filter(DiagnosisCaseEvidenceService::meetsNumerator).count();
        String status = matched.isEmpty() ? "NOT_IN_DETAIL"
                : numeratorCount > 0 ? "IN_NUMERATOR_AND_DENOMINATOR"
                : "IN_DENOMINATOR_ONLY";
        List<String> matchedFields = matched.stream()
                .flatMap(row -> matchingFields(row, scopeType, field, values).stream())
                .distinct().toList();
        String object = scopeObject(scopeType, values);
        String displayObject = scopeDisplayObject(scopeType, object, field, matched);
        String denominatorRule = firstText(
                snapshot.caseExpectedClassification().get("denominatorRule"), "当前口径分母范围");
        String numeratorRule = firstText(
                snapshot.caseExpectedClassification().get("numeratorRule"), "当前口径分子条件");

        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        List<Map<String, Object>> stageEvidence = buildStageEvidence(
                snapshot, entity, scopeType, field, values, matched,
                matched.stream().filter(DiagnosisCaseEvidenceService::meetsNumerator).toList());
        String firstDifferenceStage = firstDifferenceStage(issueDirection, stageEvidence);
        String conclusion = evidenceConclusion(issueDirection, scopeType, displayObject, status,
                firstDifferenceStage, matched.size(), numeratorCount, stageEvidence);
        String nextAction = evidenceNextAction(
                issueDirection, scopeType, firstDifferenceStage, status);
        NaturalExplanation explanation = organizeWithModel
                ? naturalExplanation(snapshot, entity, scopeType, displayObject, field,
                        matchedFields, matched, numeratorCount, denominatorRule, numeratorRule,
                        status, issueDirection, stageEvidence, conclusion)
                : new NaturalExplanation(programExplanation(entity, scopeType, displayObject,
                        matched.size(), numeratorCount, denominatorRule, numeratorRule, status,
                        issueDirection, stageEvidence, conclusion), "PROGRAM_EVIDENCE", "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("issueDirection", issueDirection);
        body.put("traceMode", traceMode(issueDirection));
        body.put("object", displayObject);
        body.put("requestedField", field);
        body.put("matchedFields", matchedFields);
        body.put("status", status);
        body.put("denominatorCount", (long) matched.size());
        body.put("numeratorCount", numeratorCount);
        body.put("denominatorRule", denominatorRule);
        body.put("numeratorRule", numeratorRule);
        body.put("statStart", text(snapshot.caseInput().get("statStart")));
        body.put("statEnd", text(snapshot.caseInput().get("statEnd")));
        body.put("summary", scopeSummary(scopeType, displayObject, status,
                matched.size(), numeratorCount));
        body.put("stageEvidence", stageEvidence);
        body.put("firstDifferenceStage", firstDifferenceStage);
        body.put("conclusion", conclusion);
        body.put("nextAction", nextAction);
        body.put("naturalLanguageExplanation", explanation.content());
        body.put("explanationSource", explanation.source());
        body.put("explanationModel", explanation.model());
        body.put("detailCountsReconciled", true);
        body.put("overviewSqlHash", detail.expectedHash());
        body.put("snapshotReused", detail.reused());
        return Map.copyOf(body);
    }

    /**
     * Reuses the existing evidence-backed scope explanation for the compact
     * standard-workspace confirmation controls.  The selected rows or
     * department are converted into an explicit scope before the model is
     * asked to rewrite the already verified facts in plain language.
     */
    public Map<String, Object> clarifyDataConfirmation(
            DiagnosisCaseSnapshot snapshot, String direction, Map<String, Object> payload) {
        String description = firstText(payload.get("description"),
                "OVER_INCLUDED".equals(direction) ? payload.get("overIncludedNote")
                        : payload.get("underIncludedNote"));
        List<Map<String, Object>> targets = mapList(payload.get("targets"));
        if (targets.isEmpty()) targets = legacyClarificationTargets(direction, payload, description);
        if (targets.isEmpty() && !description.isBlank()) {
            Map<String, Object> inferred = new LinkedHashMap<>(snapshot.caseInput());
            applyMissingScope(inferred, snapshot, description);
            String inferredType = text(inferred.getOrDefault("scopeType", "OVERALL"));
            targets = List.of(Map.of(
                    "targetType", inferredType,
                    "field", "RECORD".equals(inferredType)
                            ? text(inferred.get("recordField")) : text(inferred.get("scopeField")),
                    "values", scopeValuesFromInput(inferred),
                    "labels", scopeValuesFromInput(inferred),
                    "sourceGroup", "IMPLEMENTER_DESCRIPTION"));
        }
        if (targets.isEmpty()) targets = List.of(Map.of(
                "targetType", "OVERALL", "field", "", "values", List.of(),
                "labels", List.of(), "sourceGroup", "OVERALL"));

        List<Map<String, Object>> patientEvidenceRows = loadPatientEvidenceRows(snapshot);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> target : targets.stream().limit(20).toList()) {
            results.add(clarifyTarget(
                    snapshot, direction, target, description, patientEvidenceRows));
        }
        NaturalExplanation aggregateExplanation = aggregateNaturalExplanation(
                snapshot, direction, results, description);
        long numerator = results.stream().mapToLong(result -> number(result.get("numeratorCount"))).sum();
        long denominator = results.stream().mapToLong(
                result -> number(result.get("denominatorCount"))).sum();
        boolean anyFound = results.stream().anyMatch(result -> "IN_NUMERATOR".equals(text(result.get("status"))));
        boolean anyDenominator = results.stream().anyMatch(
                result -> "IN_DENOMINATOR_ONLY".equals(text(result.get("status"))));
        Map<String, Object> clarified = new LinkedHashMap<>();
        if (results.size() == 1) clarified.putAll(results.get(0));
        clarified.put("direction", direction);
        clarified.put("targets", targets);
        clarified.put("targetResults", results);
        clarified.put("status", anyFound ? "IN_NUMERATOR"
                : anyDenominator ? "IN_DENOMINATOR_ONLY" : "NOT_IN_DETAIL");
        clarified.put("numeratorCount", numerator);
        clarified.put("denominatorCount", denominator);
        clarified.put("summary", anyFound
                ? "已确认所选对象出现在当前统计分子明细，并按具体业务数据和分子口径解释命中原因。"
                : anyDenominator
                        ? "所选对象未进入分子，但已在当前统计分母明细中找到，并按实际明细和分母口径解释命中原因。"
                        : "当前统计分子和分母明细中均未找到所选对象，系统已继续核对抽取结果和业务源数据。" );
        clarified.put("description", description);
        if ("UNDER_INCLUDED".equals(direction)) clarified.put("requestedMissingScope", description);
        clarified.put("naturalLanguageExplanation", threeParagraphExplanation(
                direction, results, aggregateExplanation.content()));
        clarified.put("explanationSource", aggregateExplanation.source());
        clarified.put("explanationModel", aggregateExplanation.model());
        clarified.put("evidenceVerified", true);
        return Map.copyOf(clarified);
    }

    private Map<String, Object> clarifyTarget(
            DiagnosisCaseSnapshot snapshot, String direction,
            Map<String, Object> target, String description,
            List<Map<String, Object>> patientEvidenceRows) {
        Map<String, Object> caseInput = new LinkedHashMap<>(snapshot.caseInput());
        String targetType = text(target.get("targetType")).toUpperCase(Locale.ROOT);
        String field = text(target.get("field"));
        List<String> values = stringList(target.get("values")).stream()
                .filter(value -> !value.isBlank()).distinct().limit(20).toList();
        caseInput.put("issueDirection", direction);
        caseInput.put("scopeType", targetType.isBlank() ? "OVERALL" : targetType);
        if ("RECORD".equals(targetType)) {
            caseInput.put("recordField", firstText(field, "ENCOUNTER_ID"));
            caseInput.put("recordIds", values);
            if (!values.isEmpty()) caseInput.put("recordId", values.get(0));
        } else if ("DEPARTMENT".equals(targetType)) {
            caseInput.put("scopeField", firstText(field, "CURRENT_DEPT_NAME"));
            caseInput.put("scopeValues", values);
            if (!values.isEmpty()) caseInput.put("scopeValue", values.get(0));
        }
        caseInput.put("caseDescription", description);
        DiagnosisCaseSnapshot scoped = withCaseInput(snapshot, caseInput);
        Map<String, Object> result = new LinkedHashMap<>(clarifyScope(scoped, false));
        // 命中判断必须使用数据确认页已经对账并展示的原始明细。若用带 recordIds 的
        // scoped 快照重新加载，缓存契约与统计 SQL 会改变，用户刚从分子列表选中的记录
        // 可能反而被判定为“不在分子”。范围快照仅用于三段取证，不参与当前明细命中。
        DetailRows detail = loadDetailRows(snapshot);
        String scopeType = text(caseInput.get("scopeType"));
        String scopeField = "RECORD".equals(scopeType)
                ? text(caseInput.get("recordField")) : text(caseInput.get("scopeField"));
        List<String> scopeValues = scopeValues(scoped, scopeType);
        List<Map<String, Object>> denominatorRows = detail.denominatorRows().stream()
                .filter(row -> matchesScope(row, scopeType, scopeField, scopeValues))
                .toList();
        List<Map<String, Object>> numeratorRows = detail.numeratorRows().stream()
                .filter(row -> matchesScope(row, scopeType, scopeField, scopeValues))
                .toList();
        boolean inNumerator = !numeratorRows.isEmpty();
        boolean inDenominator = !denominatorRows.isEmpty();
        List<Map<String, Object>> readableRows = new ArrayList<>();
        boolean patientDetailUsed = false;
        boolean businessTransferUsed = false;
        if (inNumerator || inDenominator) {
            List<Map<String, Object>> matchedRows = inNumerator ? numeratorRows : denominatorRows;
            List<String> evidenceValues = evidenceTargetValues(scopeValues, matchedRows);
            readableRows = patientEvidenceRows.stream()
                    .filter(row -> matchesEvidenceTarget(row, scopeType, scopeField, evidenceValues))
                    .toList();
            patientDetailUsed = !readableRows.isEmpty();
            if (!patientDetailUsed && inNumerator) {
                List<Map<String, Object>> transferRows = loadBusinessTransferEvidence(
                        snapshot, numeratorRows);
                if (!transferRows.isEmpty()) {
                    readableRows = transferRows;
                    businessTransferUsed = true;
                } else {
                    readableRows = numeratorRows;
                }
            } else if (!patientDetailUsed) {
                readableRows = denominatorRows;
            }
        }
        String object = stringList(target.get("labels")).stream()
                .filter(label -> !label.isBlank()).findFirst()
                .orElse(firstText(result.get("object"), scopeObject(scopeType, scopeValues)));
        result.put("object", object);
        String membershipStatus = inNumerator ? "IN_NUMERATOR"
                : inDenominator ? "IN_DENOMINATOR_ONLY" : "NOT_IN_DETAIL";
        result.put("status", membershipStatus);
        result.put("numeratorCount", (long) numeratorRows.size());
        result.put("denominatorCount", (long) denominatorRows.size());
        if (inNumerator) {
            result.remove("denominatorRule");
            result.put("summary", "“" + object + "”在当前统计分子明细中找到"
                    + numeratorRows.size() + "条记录。");
            result.put("numeratorEvidenceRows", readableRows.stream().limit(20)
                    .map(DiagnosisCaseEvidenceService::completeEvidenceRow).toList());
            result.put("numeratorEvidenceComplete", numeratorEvidenceComplete(
                    text(result.get("numeratorRule")), readableRows));
            result.put("numeratorEvidenceSource", patientDetailUsed
                    ? "PATIENT_DETAIL_SQL" : businessTransferUsed
                            ? "BUSINESS_TRANSFER" : "RECONCILED_NUMERATOR");
            result.put("stageEvidence", mapList(result.get("stageEvidence")).stream()
                    .filter(stage -> !"DENOMINATOR".equals(text(stage.get("stageKey"))))
                    .toList());
            result.put("conclusion", "该对象已命中当前统计分子；上述实际字段满足当前生效分子条件，"
                    + "因此按当前生效口径计入分子是正确的。");
            result.put("nextAction", "若医院确认不应计入，请核对分子判定或抽取排除条件。");
        } else if (inDenominator) {
            result.remove("numeratorEvidenceRows");
            result.put("summary", "“" + object + "”没有进入当前统计分子，但在当前统计分母明细中找到"
                    + denominatorRows.size() + "条记录。");
            result.put("denominatorEvidenceRows", readableRows.stream().limit(20)
                    .map(DiagnosisCaseEvidenceService::completeEvidenceRow).toList());
            result.put("numeratorNonMatchReason", numeratorNonMatchReason(
                    text(result.get("numeratorRule")), denominatorRows));
            result.put("conclusion", "该对象没有满足当前生效分子口径，因此未进入分子。"
                    + "当前生效分母口径为“同期入院患者总人次数”；该对象由正式分母 SQL 实际返回，"
                    + "因此按当前生效口径计入分母有明细依据。");
            result.put("nextAction", "若医院确认不应进入分母，请核对分母范围、统计时间和排除条件。");
        } else {
            result.remove("numeratorEvidenceRows");
            result.put("summary", "“" + object + "”没有出现在当前统计分子或分母明细中。");
            result.put("conclusion", "当前统计结果中没有该对象，不能据此解释分子或分母命中原因。");
            result.put("nextAction", "请核对对象编号、科室和统计时间后重新查询。");
        }
        result.put("naturalLanguageExplanation", result.get("summary"));
        result.put("target", target);
        result.put("membershipVerdict", membershipStatus);
        result.put("firstMissingStage", firstMissingStage(membershipStatus, result));
        result.put("evidenceSections", evidenceSections(readableRows, patientDetailUsed,
                businessTransferUsed, inNumerator, inDenominator));
        result.put("ruleEvaluations", ruleEvaluations(result, inNumerator, inDenominator));
        result.put("finalConclusion", text(result.get("conclusion")));
        result.put("missingFields", missingEvidenceFields(result, readableRows));
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        result.put("sourceTables", entity == null ? List.of()
                : sourceTableNames(entity));
        return Map.copyOf(result);
    }

    private static String threeParagraphExplanation(
            String direction, List<Map<String, Object>> results, String aggregateExplanation) {
        String membership = results.stream().limit(8)
                .map(result -> text(result.get("summary")))
                .filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        String facts = results.stream().limit(8).map(result -> {
            List<Map<String, Object>> rows = mapList(firstNonEmpty(
                    result.get("numeratorEvidenceRows"), result.get("denominatorEvidenceRows")));
            String details = rows.stream().limit(6)
                    .map(row -> specificEvidenceSentence(
                            firstText(result.get("object"), "所选对象"), row))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
            String reason = text(result.get("numeratorNonMatchReason"));
            return String.join(" ", List.of(details, reason)).strip();
        }).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining(" "));
        if (facts.isBlank()) facts = aggregateExplanation;
        String conclusion = results.stream().limit(8)
                .map(result -> firstText(result.get("finalConclusion"), result.get("conclusion")))
                .filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        if (conclusion.isBlank()) {
            conclusion = "UNDER_INCLUDED".equals(direction)
                    ? "现有证据已定位该对象最早未命中的数据层，请从该层继续核查。"
                    : "已按当前生效口径完成该对象的分子、分母核验。";
        }
        return String.join("\n\n", List.of(
                firstText(membership, "当前分子、分母明细中没有找到所选对象。"),
                firstText(facts, "当前没有取得足够的指标相关字段，无法进一步解释规则判断。"),
                conclusion));
    }

    private static Object firstNonEmpty(Object first, Object second) {
        return mapList(first).isEmpty() ? second : first;
    }

    private static Map<String, Object> completeEvidenceRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (text(key).startsWith("__")) return;
            result.put(key, value == null || text(value).isBlank() ? "为空" : value);
        });
        return jsonSafeDetailRow(result);
    }

    private static String firstMissingStage(
            String membershipStatus, Map<String, Object> result) {
        if ("IN_NUMERATOR".equals(membershipStatus)) return "NONE";
        if ("IN_DENOMINATOR_ONLY".equals(membershipStatus)) return "NUMERATOR";
        for (Map<String, Object> stage : mapList(result.get("stageEvidence"))) {
            Object count = stage.get("count");
            if (count instanceof Number number && number.longValue() == 0) {
                return firstText(stage.get("stageKey"), "UNKNOWN");
            }
        }
        return "DETAIL";
    }

    private static List<Map<String, Object>> evidenceSections(
            List<Map<String, Object>> rows,
            boolean patientDetailUsed,
            boolean businessTransferUsed,
            boolean inNumerator,
            boolean inDenominator) {
        String source = patientDetailUsed ? "PATIENT_DETAIL_SQL"
                : businessTransferUsed ? "BUSINESS_SOURCE"
                : inNumerator ? "NUMERATOR_DETAIL"
                : inDenominator ? "DENOMINATOR_DETAIL" : "NO_MATCH";
        String label = patientDetailUsed ? "当前口径患者明细 SQL"
                : businessTransferUsed ? "上游业务表"
                : inNumerator ? "分子明细" : inDenominator ? "分母明细" : "未命中";
        return List.of(Map.of(
                "source", source,
                "label", label,
                "rows", rows.stream().limit(20)
                        .map(DiagnosisCaseEvidenceService::completeEvidenceRow).toList()));
    }

    private static List<Map<String, Object>> ruleEvaluations(
            Map<String, Object> result, boolean inNumerator, boolean inDenominator) {
        List<Map<String, Object>> evaluations = new ArrayList<>();
        evaluations.add(Map.of(
                "rule", firstText(result.get("denominatorRule"), "当前生效分母范围"),
                "stage", "DENOMINATOR",
                "matched", inDenominator,
                "explanation", inDenominator ? "正式分母 SQL 返回了该对象。" : "正式分母 SQL 未返回该对象。"));
        evaluations.add(Map.of(
                "rule", firstText(result.get("numeratorRule"), "当前生效分子条件"),
                "stage", "NUMERATOR",
                "matched", inNumerator,
                "explanation", inNumerator ? "正式分子明细返回了该对象。"
                        : firstText(result.get("numeratorNonMatchReason"), "正式分子条件未命中。")));
        return List.copyOf(evaluations);
    }

    private static List<String> missingEvidenceFields(
            Map<String, Object> result, List<Map<String, Object>> rows) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        rows.forEach(row -> row.forEach((key, value) -> {
            if (value == null || text(value).isBlank()) fields.add(evidenceFieldLabel(key));
        }));
        String numeratorRule = text(result.get("numeratorRule"));
        if (numeratorRule.contains("转科") && numeratorRule.contains("48小时")) {
            Map<String, Object> first = rows.stream().findFirst().orElse(Map.of());
            if (!hasEvidenceField(first, "INPAT_TRANSFER_AT", "转科时间")) fields.add("转科时间");
            if (!hasEvidenceField(first, "ORIGIN_DEPT_NAME", "转出科室")) fields.add("转出科室");
            if (!hasEvidenceField(first, "DESTINATION_DEPT_NAME", "转入科室")) fields.add("转入科室");
        }
        return List.copyOf(fields);
    }

    private static List<String> sourceTableNames(EntityPageData entity) {
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        tables.addAll(entity.bizTables() == null ? List.of() : entity.bizTables());
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?i)\\b(?:FROM|JOIN)\\s+(?:[A-Za-z0-9_]+\\.)?([A-Za-z_][A-Za-z0-9_$#]*)")
                .matcher(firstText(entity.oracleSourceTableSql(), entity.sourceTableSql(), "")
                        + "\n" + text(entity.patientDetailSql()));
        while (matcher.find()) tables.add(matcher.group(1).toUpperCase(Locale.ROOT));
        return List.copyOf(tables);
    }

    private List<Map<String, Object>> loadPatientEvidenceRows(DiagnosisCaseSnapshot snapshot) {
        try {
            ToolResult result = mrasExecution.executePatientDetail(
                    snapshot.ruleId(), snapshot.profileId(),
                    parseTime(text(snapshot.caseInput().get("statStart"))),
                    parseTime(text(snapshot.caseInput().get("statEnd"))),
                    null, null);
            if (result == null || !result.ok()) {
                log.warn("患者明细取证未完成 caseId={} ruleId={} code={} summary={}",
                        snapshot.caseId(), snapshot.ruleId(),
                        result == null ? "NO_RESULT" : result.code(),
                        result == null ? "执行器未返回结果" : result.summary());
                return List.of();
            }
            List<Map<String, Object>> detailRows = rows(result.data().get("rows")).stream()
                    .map(DiagnosisCaseEvidenceService::jsonSafeDetailRow)
                    .toList();
            log.info("患者明细取证完成 caseId={} ruleId={} rows={} fields={}",
                    snapshot.caseId(), snapshot.ruleId(), detailRows.size(),
                    detailRows.isEmpty() ? List.of() : detailRows.get(0).keySet());
            return detailRows;
        } catch (RuntimeException exception) {
            log.warn("患者明细取证失败 caseId={} ruleId={}",
                    snapshot.caseId(), snapshot.ruleId(), exception);
            return List.of();
        }
    }

    private List<Map<String, Object>> loadBusinessTransferEvidence(
            DiagnosisCaseSnapshot snapshot, List<Map<String, Object>> numeratorRows) {
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        if (entity == null || !text(entity.sourceTableSql()).toUpperCase(Locale.ROOT)
                .contains("INPAT_TRANSFER")) {
            return List.of();
        }
        List<String> encounterIds = numeratorRows.stream()
                .map(row -> evidenceValue(row, "ENCOUNTER_ID", "BIZ_ID", "就诊号"))
                .filter(value -> !value.isBlank()).distinct().limit(20).toList();
        if (encounterIds.isEmpty()) return List.of();
        String sql = """
                SELECT
                    t.ENCOUNTER_ID AS ENCOUNTER_ID,
                    t.INPAT_TRANSFER_AT AS INPAT_TRANSFER_AT,
                    t.INPAT_TRANSFER_TYPE_CODE AS INPAT_TRANSFER_TYPE_CODE,
                    t.CREATED_AT AS CREATED_AT,
                    t.ORIGIN_DEPT_ID AS ORIGIN_DEPT_ID,
                    od.ORG_NAME AS ORIGIN_DEPT_NAME,
                    od.ORG_NO AS ORIGIN_DEPT_NO,
                    t.DESTINATION_DEPT_ID AS DESTINATION_DEPT_ID,
                    dd.ORG_NAME AS DESTINATION_DEPT_NAME,
                    dd.ORG_NO AS DESTINATION_DEPT_NO,
                    ow.ORG_NO AS ORIGIN_WARD_NO,
                    dw.ORG_NO AS DESTINATION_WARD_NO
                FROM INPAT_TRANSFER t
                LEFT JOIN ORGANIZATION od ON t.ORIGIN_DEPT_ID = od.ORG_ID
                LEFT JOIN ORGANIZATION dd ON t.DESTINATION_DEPT_ID = dd.ORG_ID
                LEFT JOIN ORGANIZATION ow ON t.ORIGIN_WARD_ID = ow.ORG_ID
                LEFT JOIN ORGANIZATION dw ON t.DESTINATION_WARD_ID = dw.ORG_ID
                WHERE t.IS_DEL = '0'
                  AND t.ENCOUNTER_ID IN (%s)
                ORDER BY t.ENCOUNTER_ID, t.CREATED_AT
                """.formatted(encounterIds.stream()
                        .map(DiagnosisCaseEvidenceService::literal)
                        .collect(java.util.stream.Collectors.joining(",")));
        try {
            List<Map<String, Object>> transferRows = query.execute(DatabaseRole.BUSINESS, sql);
            if (transferRows == null || transferRows.isEmpty()) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (String encounterId : encounterIds) {
                Map<String, Object> base = numeratorRows.stream()
                        .filter(row -> encounterId.equals(evidenceValue(
                                row, "ENCOUNTER_ID", "BIZ_ID", "就诊号")))
                        .findFirst().orElse(Map.of());
                Map<String, Object> transfer = transferRows.stream()
                        .filter(row -> encounterId.equals(text(columnValue(row, "ENCOUNTER_ID"))))
                        .filter(DiagnosisCaseEvidenceService::isApplicableTransfer)
                        .findFirst().orElse(Map.of());
                if (transfer.isEmpty()) continue;
                result.add(mergeTransferEvidence(base, transfer));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            log.warn("业务库转科明细取证失败 caseId={} ruleId={} message={}",
                    snapshot.caseId(), snapshot.ruleId(), exception.getMessage());
            return List.of();
        }
    }

    private static boolean isApplicableTransfer(Map<String, Object> row) {
        String type = text(columnValue(row, "INPAT_TRANSFER_TYPE_CODE"));
        String origin = text(columnValue(row, "ORIGIN_DEPT_ID"));
        String destination = text(columnValue(row, "DESTINATION_DEPT_ID"));
        boolean validType = "399549991".equals(type)
                || ("399549990".equals(type) && !origin.equals(destination));
        if (!validType) return false;
        var excluded = java.util.Set.of(
                "12800000", "42800000", "42800200", "31301", "22800000",
                "33802", "34001", "22800100", "22800200", "42800100",
                "12800200", "27401", "12800100");
        return java.util.stream.Stream.of(
                        "ORIGIN_DEPT_NO", "DESTINATION_DEPT_NO",
                        "ORIGIN_WARD_NO", "DESTINATION_WARD_NO")
                .map(key -> text(columnValue(row, key)))
                .noneMatch(excluded::contains);
    }

    private static Map<String, Object> mergeTransferEvidence(
            Map<String, Object> base, Map<String, Object> transfer) {
        Map<String, Object> result = new LinkedHashMap<>();
        putEvidence(result, "患者姓名", columnValue(base, "PERSON_NAME", "患者姓名"));
        putEvidence(result, "就诊号", columnValue(transfer, "ENCOUNTER_ID"));
        putEvidence(result, "住院号", columnValue(base, "IMRN", "住院号"));
        Object admitted = columnValue(base, "ADMITTED_TO_WARD_AT", "入区时间");
        Object transferred = columnValue(transfer, "INPAT_TRANSFER_AT");
        putEvidence(result, "入区时间", admitted);
        putEvidence(result, "转科时间", transferred);
        String transferType = text(columnValue(transfer, "INPAT_TRANSFER_TYPE_CODE"));
        putEvidence(result, "转科类型", "399549991".equals(transferType) ? "转科"
                : "399549990".equals(transferType) ? "转区" : transferType);
        putEvidence(result, "转出科室", columnValue(transfer, "ORIGIN_DEPT_NAME"));
        putEvidence(result, "转入科室", columnValue(transfer, "DESTINATION_DEPT_NAME"));
        LocalDateTime admittedAt = parseEvidenceDateTime(admitted);
        LocalDateTime transferredAt = parseEvidenceDateTime(transferred);
        if (admittedAt != null && transferredAt != null) {
            long minutes = ChronoUnit.MINUTES.between(admittedAt, transferredAt);
            BigDecimal hours = BigDecimal.valueOf(minutes)
                    .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
            result.put("转科时间-入院时间", hours.stripTrailingZeros().toPlainString());
            result.put("是否48小时内转科", minutes >= 0 && minutes < 48L * 60L ? "是" : "否");
        }
        return Map.copyOf(result);
    }

    private static Object columnValue(Map<String, Object> row, String... candidates) {
        for (String candidate : candidates) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (text(entry.getKey()).equalsIgnoreCase(candidate)) return entry.getValue();
            }
        }
        return null;
    }

    private static void putEvidence(Map<String, Object> target, String key, Object value) {
        target.put(key, value == null || text(value).isBlank() ? "为空" : value);
    }

    private static LocalDateTime parseEvidenceDateTime(Object rawValue) {
        String value = text(rawValue);
        if (value.isBlank()) return null;
        if (value.matches("\\d{13}")) {
            try {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            return parseTime(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matchesEvidenceTarget(
            Map<String, Object> row, String scopeType, String field, List<String> values) {
        if (matchesScope(row, scopeType, field, values)) return true;
        if (!"RECORD".equals(scopeType)) return false;
        return row.entrySet().stream()
                .filter(entry -> text(entry.getKey()).toUpperCase(Locale.ROOT)
                        .matches(".*(?:ROWNUM|ENCOUNTER|BIZ|ORDER|SURGERY|IMRN|PERSON_NAME|住院号|就诊号|患者姓名).*"))
                .anyMatch(entry -> values.stream()
                        .anyMatch(value -> valueMatches(entry.getValue(), value, false)));
    }

    private static List<String> evidenceTargetValues(
            List<String> scopeValues, List<Map<String, Object>> numeratorRows) {
        LinkedHashSet<String> values = new LinkedHashSet<>(scopeValues);
        numeratorRows.forEach(row -> row.forEach((key, value) -> {
            String upper = text(key).toUpperCase(Locale.ROOT);
            if (value == null || text(value).isBlank()) return;
            if (upper.matches(".*(?:ROWNUM|ENCOUNTER|BIZ|IMRN|住院号|就诊号|患者姓名|PERSON_NAME).*$")) {
                values.add(text(value));
            }
        }));
        return List.copyOf(values);
    }

    private NaturalExplanation aggregateNaturalExplanation(
            DiagnosisCaseSnapshot snapshot, String direction,
            List<Map<String, Object>> results, String description) {
        if ("UNDER_INCLUDED".equals(direction)) {
            return new NaturalExplanation(
                    underIncludedExplanation(results), "PROGRAM_EVIDENCE", "");
        }
        boolean anyNumerator = results.stream().anyMatch(
                result -> "IN_NUMERATOR".equals(text(result.get("status"))));
        if (!anyNumerator) {
            boolean anyDenominator = results.stream().anyMatch(
                    result -> "IN_DENOMINATOR_ONLY".equals(text(result.get("status"))));
            return new NaturalExplanation(anyDenominator
                    ? denominatorOnlyExplanation(results)
                    : notInCurrentDetailsExplanation(results), "PROGRAM_EVIDENCE", "");
        }
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        String numeratorRule = results.stream().map(result -> text(result.get("numeratorRule")))
                .filter(value -> !value.isBlank()).findFirst().orElse("当前口径分子条件");
        String evidence = aggregateNumeratorExistenceExplanation(direction, results);
        String concreteData = aggregateNumeratorEvidence(results);
        String conclusion = aggregateNumeratorConclusion(direction, results);
        String fallback = numeratorNaturalFallback(evidence, concreteData, conclusion);
        if (entity == null || models == null || modelRegistry == null) {
            return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
        }
        try {
            AgentModelInfo model = modelRegistry.requireInfo(snapshot.modelId());
            if (!model.available()) {
                return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("指标", entity.name());
            facts.put("分子规则", numeratorRule);
            facts.put("口径条件参考", cleanKnowledgeText(entity.caliber()));
            facts.put("程序核验的分子明细", results.stream().map(result -> Map.of(
                    "对象", firstText(result.get("object"), "所选对象"),
                    "是否在分子明细", "IN_NUMERATOR".equals(text(result.get("status"))),
                    "分子记录数", number(result.get("numeratorCount")),
                    "具体记录", mapList(result.get("numeratorEvidenceRows")).stream()
                            .limit(3).map(DiagnosisCaseEvidenceService::readableEvidenceRow).toList()))
                    .toList());
            String answer = models.complete(model.id(),
                    """
                    你是医院核心指标实施口径说明助手。本次澄清只解释分子命中原因，不讨论分母。
                    本功能的目标是用实际数据证明当前分子统计为什么正确，不负责采纳实施人员的质疑、判定程序错误或提出口径修改。
                    只能整理程序与当前生效知识库已经提供的事实，不能修改数量和程序结论。
                    请直接围绕所选患者或科室的真实字段值解释：具体发生时间、时间间隔、状态、从哪里到哪里，以及这些值怎样满足当前分子条件，最后明确说明“按当前生效口径，计入分子是正确的”。
                    只选用与本次具体记录直接相关的口径条件；不要罗列完整知识库口径，不要泛泛介绍数据链路、数据来自哪里或系统如何抽取。
                    没有提供的时间、科室或状态必须不写，严禁补造。不要输出SQL、英文表名、英文字段名、Markdown、编号、小标题或固定模板标签。
                    “数据多了”仅表示实施人员提出核对，不是已确认的错误。严禁输出“本不应计入”“不应计入的记录”“错误计算”“错误计入”“程序算错”“统计错误”等结论。
                    只返回JSON：{"explanation":"一段面向实施人员的自然说明"}
                    返回内容中不得出现“分母”。
                    """,
                    "以下是程序和知识库提供的事实：\n" + facts,
                    Duration.ofSeconds(45)).content().strip();
            String value = answer.replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```$", "");
            int start = value.indexOf('{');
            int end = value.lastIndexOf('}');
            if (start >= 0 && end > start) {
                Map<String, Object> parsed = mapper.readValue(value.substring(start, end + 1),
                        new TypeReference<Map<String, Object>>() { });
                String explanation = safeNumeratorPhrase(
                        text(parsed.get("explanation")), fallback);
                return new NaturalExplanation(explanation, "MODEL", model.id());
            }
        } catch (RuntimeException exception) {
            log.warn("多对象澄清说明整理失败 caseId={} modelId={}",
                    snapshot.caseId(), snapshot.modelId(), exception);
        } catch (Exception exception) {
            log.warn("多对象澄清说明解析失败 caseId={}", snapshot.caseId(), exception);
        }
        return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
    }

    private static String denominatorOnlyExplanation(List<Map<String, Object>> results) {
        return results.stream().limit(8).map(result -> {
            String object = firstText(result.get("object"), "所选对象");
            long count = number(result.get("denominatorCount"));
            String rule = firstText(result.get("denominatorRule"), "当前生效分母范围");
            List<Map<String, Object>> rows = mapList(result.get("denominatorEvidenceRows"));
            String detail = rows.isEmpty() ? ""
                    : "实际分母记录显示：" + rows.stream().limit(3)
                            .map(DiagnosisCaseEvidenceService::readableEvidenceRow)
                            .filter(value -> !value.isBlank())
                            .collect(java.util.stream.Collectors.joining("；")) + "。";
            String numeratorReason = text(result.get("numeratorNonMatchReason"));
            return "“" + object + "”没有进入当前统计分子，但在当前统计分母明细中找到"
                    + count + "条记录。" + detail + numeratorReason + "当前生效分母口径为“" + rule
                    + "”；该对象由正式分母 SQL 实际返回，因此按当前生效口径计入分母有明细依据。";
        }).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static String numeratorNonMatchReason(
            String numeratorRule, List<Map<String, Object>> denominatorRows) {
        for (Map<String, Object> row : denominatorRows) {
            Object transferFlag = columnValue(
                    row, "TRANSFER_WITHIN_TWO_DAY", "是否48小时内转科");
            if (transferFlag != null
                    && "否".equals(evidenceFieldValue("TRANSFER_WITHIN_TWO_DAY", transferFlag))) {
                return "该记录的“48小时内转科判定”为“否”，不满足当前分子口径“"
                        + firstText(numeratorRule, "入院后48小时内发生符合条件的转科")
                        + "”，因此未进入分子。";
            }
        }
        return "正式统计 SQL 对该记录的分子条件判定为未命中；当前分母明细没有返回足够的分子判定字段，"
                + "暂时无法进一步确认具体未满足哪一项条件。";
    }

    private static String notInCurrentDetailsExplanation(List<Map<String, Object>> results) {
        String objects = results.stream().limit(8)
                .map(result -> "“" + firstText(result.get("object"), "所选对象") + "”")
                .distinct().collect(java.util.stream.Collectors.joining("、"));
        return firstText(objects, "所选对象")
                + "既未出现在当前统计分子明细，也未出现在当前统计分母明细。"
                + "当前结果无法解释其分子或分母命中原因，请先核对对象编号、科室和统计时间。";
    }

    /**
     * “数据少了”只回答分子记录在哪一层开始缺失。这里使用程序已经执行出的
     * 业务源、抽取结果和分子明细证据，不再让模型复述整段指标口径或讨论分母。
     */
    private static String underIncludedExplanation(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "当前没有识别出可反查的患者、记录或科室。请补充就诊号、事件号、科室名称或科室编码后重新澄清。";
        }
        List<String> conclusions = results.stream().limit(8)
                .map(DiagnosisCaseEvidenceService::underIncludedTargetExplanation)
                .toList();
        String remaining = results.size() > 8
                ? " 其余" + (results.size() - 8) + "个对象已完成同样检查，可分批查看。" : "";
        return String.join("\n\n", conclusions) + remaining;
    }

    private static String underIncludedTargetExplanation(Map<String, Object> result) {
        String object = firstText(result.get("object"), "所填对象");
        long numerator = number(result.get("numeratorCount"));
        if ("IN_NUMERATOR".equals(text(result.get("status"))) || numerator > 0) {
            return "“" + object + "”实际已经出现在当前统计分子明细中，共"
                    + numerator + "条，因此这条记录并没有少算。";
        }

        List<Map<String, Object>> stages = mapList(result.get("stageEvidence"));
        Long source = stageCount(stages, "BUSINESS_SOURCE");
        Long target = stageCount(stages, "REAL_TARGET");
        long denominator = number(result.get("denominatorCount"));
        if (source != null && source == 0) {
            return "“" + object + "”确实不在当前统计分子明细中；按当前统计时间和所填对象查询医院业务源也没有找到记录。"
                    + "目前不能判断为抽取或统计遗漏，请先确认对象编号、科室名称、统计时间以及医院业务数据是否已经产生。";
        }
        if (source != null && source > 0 && target != null && target == 0) {
            return "“" + object + "”确实不在当前统计分子明细中。医院业务源找到"
                    + source + "条，但抽取后的指标数据为0条，记录最早在抽取环节消失；应重点核对源表抽取 SQL 的筛选、关联和去重条件。";
        }
        if (denominator > 0) {
            String reason = text(result.get("numeratorNonMatchReason"));
            return "“" + object + "”已经进入当前统计分母，共" + denominator
                    + "条，但没有进入分子。" + (reason.isBlank()
                            ? "说明患者不满足当前分子条件，应核对分子判定字段。"
                            : reason);
        }
        if (target != null && target > 0) {
            String sourceFact = source == null ? "" : "医院业务源找到" + source + "条，";
            return "“" + object + "”已存在于中间表，但没有进入当前统计分母。" + sourceFact
                    + "抽取后的指标数据有" + target
                    + "条，说明记录已经完成抽取；应重点核对分母统计时间、状态、删除标记和排除条件。";
        }
        if (source != null && source > 0 && target == null) {
            return "“" + object + "”在医院业务源找到" + source
                    + "条，但当前口径没有独立中间表，且记录没有进入统计分母；应直接核对分母统计时间、关联和排除条件。";
        }
        if (target != null && target == 0) {
            String sourceFact = source == null ? "医院业务源取证尚未完成" : "医院业务源找到" + source + "条";
            return "“" + object + "”确实不在当前统计分子明细中，抽取后的指标数据也没有找到。"
                    + sourceFact + "，现有证据还不能确定是业务数据未产生还是抽取遗漏；请先补齐业务源查询证据。";
        }
        return "“" + object + "”确实不在当前统计分子明细中，但医院业务源和抽取结果的自动取证没有完成。"
                + "现阶段只能确认分子缺少该对象，暂时不能判断是抽取 SQL 还是概览统计 SQL 遗漏。";
    }

    private static String aggregateNumeratorExistenceExplanation(
            String direction, List<Map<String, Object>> results) {
        List<String> facts = results.stream().limit(8).map(result -> {
            String object = firstText(result.get("object"), "所选对象");
            long numerator = number(result.get("numeratorCount"));
            boolean exists = "IN_NUMERATOR".equals(text(result.get("status")));
            if ("UNDER_INCLUDED".equals(direction)) {
                if (!exists) return "“" + object + "”确实不在当前统计分子明细中";
                return "“" + object + "”实际已经进入统计分子明细" + numerator
                        + "条，与“数据少了”的描述不一致";
            }
            if (!exists) {
                return "“" + object + "”没有出现在当前统计分子明细中，与“分子数据多了”的描述不一致";
            }
            return "“" + object + "”确实在当前统计分子明细中，共" + numerator + "条";
        }).distinct().toList();
        String suffix = results.size() > 8 ? "；另有" + (results.size() - 8) + "个对象已完成同样核对" : "";
        return facts.isEmpty() ? "当前没有可核对的明确对象。"
                : String.join("；", facts) + suffix + "。";
    }

    private static String aggregateNumeratorEvidence(List<Map<String, Object>> results) {
        List<String> facts = results.stream().limit(8).map(result -> {
            String object = firstText(result.get("object"), "所选对象");
            List<Map<String, Object>> rows = mapList(result.get("numeratorEvidenceRows"));
            if (rows.isEmpty()) return "“" + object + "”没有可展示的分子命中记录。";
            return rows.stream().limit(3)
                    .map(row -> specificEvidenceSentence(object, row))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining("；"));
        }).toList();
        return String.join(" ", facts);
    }

    private static String aggregateNumeratorConclusion(
            String direction, List<Map<String, Object>> results) {
        List<Map<String, Object>> found = results.stream()
                .filter(result -> "IN_NUMERATOR".equals(text(result.get("status")))).toList();
        List<Map<String, Object>> missing = results.stream()
                .filter(result -> !"IN_NUMERATOR".equals(text(result.get("status")))).toList();
        if ("OVER_INCLUDED".equals(direction)) {
            if (found.isEmpty()) {
                return "所选对象均未出现在当前统计分子明细中，现有结果不支持“分子数据多了”的判断。请先核对对象编号、科室和统计时间。";
            }
            if (found.stream().anyMatch(result -> !Boolean.TRUE.equals(
                    result.get("numeratorEvidenceComplete")))) {
                return "系统已经把上述对象计入分子，但当前明细缺少解释分子命中所必需的具体业务字段，不能仅凭结果标记断言计算正确。下一步应在数据链路核查中补查这些字段。";
            }
            return "上述具体数据逐条满足当前生效分子口径，因此程序将这些对象计入分子是正确的。";
        }
        if ("UNDER_INCLUDED".equals(direction)) {
            if (missing.isEmpty()) {
                return "你填写的对象已经出现在当前统计分子明细中，现有证据不支持“分子数据少了”的判断。";
            }
            return "这些对象确实没有进入当前统计分子明细。请根据业务源和抽取结果中的具体数据，核对哪一项分子条件没有满足。";
        }
        return "已按当前分子口径完成对象核对。";
    }

    private static boolean numeratorEvidenceComplete(
            String numeratorRule, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return false;
        if (!numeratorRule.contains("48小时") || !numeratorRule.contains("转科")) return true;
        return rows.stream().anyMatch(row -> hasEvidenceField(row, "ADMITTED_TO_WARD_AT", "入区时间")
                && hasEvidenceField(row, "INPAT_TRANSFER_AT", "转科时间")
                && hasEvidenceField(row, "ORIGIN_DEPT", "转出科室")
                && hasEvidenceField(row, "DESTINATION_DEPT", "转入科室"));
    }

    private static String specificEvidenceSentence(String object, Map<String, Object> row) {
        String patient = evidenceValue(row, "PERSON_NAME", "患者姓名");
        String encounter = evidenceValue(row, "ENCOUNTER_ID", "就诊号");
        String admitted = evidenceValue(row, "ADMITTED_TO_WARD_AT", "入区时间");
        String transferred = evidenceValue(row, "INPAT_TRANSFER_AT", "转科时间");
        String interval = evidenceValue(row, "转科时间-入院时间");
        String transferType = evidenceValue(row, "转科类型");
        String origin = evidenceValue(row, "ORIGIN_DEPT", "转出科室");
        String destination = evidenceValue(row, "DESTINATION_DEPT", "转入科室");
        String flag = evidenceValue(row, "TRANSFER_WITHIN_TWO_DAY", "是否48小时内转科");
        if (!admitted.isBlank() || !transferred.isBlank() || !origin.isBlank()
                || !destination.isBlank() || !flag.isBlank()) {
            StringBuilder sentence = new StringBuilder();
            if (!patient.isBlank()) sentence.append("患者“").append(patient).append("”");
            else sentence.append("“").append(object).append("”");
            if (!encounter.isBlank()) sentence.append("（就诊号 ").append(encounter).append("）");
            if (!admitted.isBlank()) sentence.append("于 ").append(admitted).append(" 入区");
            if (!transferred.isBlank()) sentence.append("，于 ").append(transferred).append(" 发生转科");
            if (!transferType.isBlank()) sentence.append("，流转类型为“").append(transferType).append("”");
            if (!origin.isBlank() || !destination.isBlank()) {
                sentence.append("，从 ").append(firstText(origin, "未取得的转出科室"))
                        .append(" 转到 ").append(firstText(destination, "未取得的转入科室"));
            }
            if (!interval.isBlank()) {
                sentence.append("，与入区时间相隔 ")
                        .append(transferIntervalDescription(admitted, transferred, interval));
            }
            if (!flag.isBlank()) {
                String decision = evidenceFieldValue("TRANSFER_WITHIN_TWO_DAY", flag);
                sentence.append("；系统记录的48小时内转科判定为“").append(decision).append("”");
                if ("是".equals(decision)) {
                    sentence.append("，统计SQL正是把该判定为“是”的记录计入分子，所以当前分子包含这名患者");
                }
            }
            if (transferred.isBlank() || origin.isBlank() || destination.isBlank()) {
                sentence.append("。当前没有取得完整的转科时间和转入、转出科室，暂时不能说明这个判定具体是怎样算出的");
            }
            return sentence.append("。").toString();
        }
        return "“" + object + "”的实际记录显示：" + readableEvidenceRow(row) + "。";
    }

    private static String transferIntervalDescription(
            String admitted, String transferred, String fallbackHours) {
        LocalDateTime admittedAt = parseEvidenceDateTime(admitted);
        LocalDateTime transferredAt = parseEvidenceDateTime(transferred);
        if (admittedAt != null && transferredAt != null) {
            long minutes = ChronoUnit.MINUTES.between(admittedAt, transferredAt);
            if (minutes >= 0) {
                String duration;
                if (minutes < 60) {
                    duration = minutes + "分钟（不足1小时）";
                } else {
                    long hours = minutes / 60;
                    long remainingMinutes = minutes % 60;
                    duration = hours + "小时" + (remainingMinutes == 0 ? "" : remainingMinutes + "分钟");
                }
                return duration + (minutes < 48L * 60L ? "，小于48小时" : "，不少于48小时");
            }
        }
        try {
            return fallbackHours + "小时"
                    + (Double.parseDouble(fallbackHours) < 48D ? "，小于48小时" : "，不少于48小时");
        } catch (NumberFormatException ignored) {
            return fallbackHours;
        }
    }

    private static boolean hasEvidenceField(Map<String, Object> row, String... candidates) {
        return !evidenceValue(row, candidates).isBlank();
    }

    private static String evidenceValue(Map<String, Object> row, String... candidates) {
        for (String candidate : candidates) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (text(entry.getKey()).equalsIgnoreCase(candidate)) {
                    String value = evidenceFieldValue(entry.getKey(), entry.getValue());
                    return "为空".equals(value) ? "" : value;
                }
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String upper = text(entry.getKey()).toUpperCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (upper.contains(candidate.toUpperCase(Locale.ROOT))) {
                    String value = evidenceFieldValue(entry.getKey(), entry.getValue());
                    return "为空".equals(value) ? "" : value;
                }
            }
        }
        return "";
    }

    private static String numeratorNaturalFallback(
            String evidence, String concreteData, String conclusion) {
        return evidence + " " + concreteData + " " + conclusion;
    }

    private static DiagnosisCaseSnapshot withCaseInput(
            DiagnosisCaseSnapshot snapshot, Map<String, Object> caseInput) {
        return new DiagnosisCaseSnapshot(
                snapshot.caseId(), snapshot.hospitalId(), snapshot.userId(), snapshot.sessionId(),
                snapshot.status(), snapshot.currentStep(), snapshot.ruleId(), snapshot.profileId(),
                snapshot.knowledgeReleaseId(), snapshot.modelId(), Map.copyOf(caseInput),
                snapshot.caliberSnapshot(), snapshot.caseExpectedClassification(),
                snapshot.gateResults(), snapshot.evidence(), snapshot.causeConclusion(),
                snapshot.changeProposal(), snapshot.candidateSql(), snapshot.shadowTrial(),
                snapshot.dataConfirmation(), snapshot.investigationMode(), snapshot.autonomousRun(),
                snapshot.draftResult(), snapshot.releaseResult(), snapshot.createdAt(), snapshot.updatedAt());
    }

    private static List<String> scopeValuesFromInput(Map<String, Object> value) {
        String scopeType = text(value.get("scopeType"));
        List<String> values = "RECORD".equals(scopeType)
                ? stringList(value.get("recordIds")) : stringList(value.get("scopeValues"));
        if (!values.isEmpty()) return values;
        String single = "RECORD".equals(scopeType)
                ? text(value.get("recordId")) : text(value.get("scopeValue"));
        return single.isBlank() ? List.of() : List.of(single);
    }

    private static List<Map<String, Object>> legacyClarificationTargets(
            String direction, Map<String, Object> payload, String description) {
        List<Map<String, Object>> targets = new ArrayList<>();
        List<Map<String, Object>> rows = mapList(payload.get("overIncludedRows"));
        if ("OVER_INCLUDED".equals(direction) && !rows.isEmpty()) {
            String rowKey = text(rows.get(0).get("rowKey"));
            String field = rowKey.contains(":") ? rowKey.substring(0, rowKey.indexOf(':')) : "ENCOUNTER_ID";
            targets.add(Map.of("targetType", "RECORD", "field", field,
                    "values", rows.stream().map(row -> text(row.get("recordId"))).filter(v -> !v.isBlank()).toList(),
                    "labels", rows.stream().map(row -> text(row.get("label"))).toList(),
                    "sourceGroup", "DETAIL_SELECTION"));
        }
        Map<String, Object> department = map(payload.get("overIncludedDepartment"));
        if ("OVER_INCLUDED".equals(direction) && !text(department.get("value")).isBlank()) {
            targets.add(Map.of("targetType", "DEPARTMENT",
                    "field", firstText(department.get("field"), "CURRENT_DEPT_NAME"),
                    "values", List.of(text(department.get("value"))),
                    "labels", List.of(text(department.get("value"))),
                    "sourceGroup", "DEPARTMENT_SELECTION"));
        }
        return List.copyOf(targets);
    }

    /**
     * “少算了”不能直接退化成整体说明。先从已经对账的分子/分母明细识别用户
     * 写到的患者、就诊号或科室；即使对象确实不在最终明细，也保留明确的范围，
     * 让后续证据查询继续向业务源和抽取结果追踪。
     */
    private void applyMissingScope(
            Map<String, Object> caseInput, DiagnosisCaseSnapshot snapshot, String note) {
        DetailRows detail = loadDetailRows(snapshot);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(detail.denominatorRows());
        rows.addAll(detail.numeratorRows());

        ScopeHint knownDepartment = findKnownTextScope(rows, note,
                DiagnosisCaseEvidenceService::isDepartmentNameField, "DEPARTMENT");
        if (knownDepartment != null) {
            applyScopeHint(caseInput, knownDepartment);
            return;
        }
        ScopeHint knownPatient = findKnownTextScope(rows, note,
                DiagnosisCaseEvidenceService::isPatientNameField, "RECORD");
        if (knownPatient != null) {
            applyScopeHint(caseInput, knownPatient);
            return;
        }

        var identifier = Pattern.compile("(?<!\\d)\\d{6,30}(?!\\d)").matcher(note);
        if (identifier.find()) {
            String value = identifier.group();
            String field = rows.stream().flatMap(row -> row.entrySet().stream())
                    .filter(entry -> value.equals(text(entry.getValue())))
                    .map(Map.Entry::getKey).findFirst().orElse("ENCOUNTER_ID");
            applyScopeHint(caseInput, new ScopeHint("RECORD", field, List.of(value)));
            return;
        }

        var department = Pattern.compile(
                "([\\p{IsHan}A-Za-z0-9（）()·\\-]{2,24}(?:科室|病区|科))")
                .matcher(note);
        if (department.find()) {
            applyScopeHint(caseInput, new ScopeHint(
                    "DEPARTMENT", "CURRENT_DEPT_NAME", List.of(department.group(1))));
            return;
        }
        caseInput.put("scopeType", "OVERALL");
    }

    private static ScopeHint findKnownTextScope(
            List<Map<String, Object>> rows,
            String note,
            java.util.function.Predicate<String> fieldFilter,
            String scopeType) {
        return rows.stream().flatMap(row -> row.entrySet().stream())
                .filter(entry -> fieldFilter.test(entry.getKey()))
                .map(entry -> new ScopeHint(scopeType, entry.getKey(), List.of(text(entry.getValue()))))
                .filter(hint -> !hint.values().get(0).isBlank() && note.contains(hint.values().get(0)))
                .max(java.util.Comparator.comparingInt(hint -> hint.values().get(0).length()))
                .orElse(null);
    }

    private static boolean isPatientNameField(String field) {
        String upper = text(field).toUpperCase(Locale.ROOT);
        return upper.equals("FULL_NAME") || upper.equals("PERSON_NAME")
                || upper.equals("PATIENT_NAME") || upper.equals("PERSONNAME")
                || upper.equals("PATIENTNAME") || upper.equals("姓名")
                || upper.contains("患者姓名");
    }

    private static void applyScopeHint(Map<String, Object> caseInput, ScopeHint hint) {
        caseInput.put("scopeType", hint.scopeType());
        if ("RECORD".equals(hint.scopeType())) {
            caseInput.put("recordField", hint.field());
            caseInput.put("recordIds", hint.values());
            caseInput.put("recordId", hint.values().get(0));
        } else {
            caseInput.put("scopeField", hint.field());
            caseInput.put("scopeValue", hint.values().get(0));
        }
    }

    private record ScopeHint(String scopeType, String field, List<String> values) { }

    private NaturalExplanation naturalExplanation(
            DiagnosisCaseSnapshot snapshot,
            EntityPageData entity,
            String scopeType,
            String object,
            String requestedField,
            List<String> matchedFields,
            List<Map<String, Object>> matched,
            long numeratorCount,
            String denominatorRule,
            String numeratorRule,
            String status,
            String issueDirection,
            List<Map<String, Object>> stageEvidence,
            String conclusion) {
        String fallback = programExplanation(entity, scopeType, object, matched.size(),
                numeratorCount, denominatorRule, numeratorRule, status,
                issueDirection, stageEvidence, conclusion);
        if (entity == null || models == null || modelRegistry == null) {
            return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
        }
        try {
            AgentModelInfo model = modelRegistry.requireInfo(snapshot.modelId());
            if (!model.available()) {
                return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("指标", entity.name());
            facts.put("口径名称", entity.variantLabel());
            facts.put("对象类型", switch (scopeType) {
                case "RECORD" -> "患者或业务记录";
                case "DEPARTMENT" -> "科室或病区";
                case "TIME_RANGE" -> "时间范围";
                case "DATA_CATEGORY" -> "一类业务数据";
                default -> "整体指标结果";
            });
            facts.put("实施人员填写的对象", object);
            facts.put("医院认为应该是什么结果", text(snapshot.caseInput().get("caseDescription")));
            facts.put("匹配字段", matchedFields.isEmpty() ? List.of(requestedField) : matchedFields);
            facts.put("统计窗口", text(snapshot.caseInput().get("statStart"))
                    + " 至 " + text(snapshot.caseInput().get("statEnd")));
            facts.put("数据从哪里来（优先使用这些中文说明）", businessSourceDescriptions(entity));
            facts.put("抽取后放到哪里", "抽取后的指标数据，用于后续正式统计");
            facts.put("指标定义", entity.definition());
            facts.put("统计口径", entity.caliber());
            facts.put("分母规则", denominatorRule);
            facts.put("分子规则", numeratorRule);
            facts.put("该对象分母记录数", matched.size());
            facts.put("该对象分子记录数", numeratorCount);
            facts.put("程序判定", switch (status) {
                case "IN_NUMERATOR_AND_DENOMINATOR" -> "既进入分母，也有记录进入分子";
                case "IN_DENOMINATOR_ONLY" -> "进入分母，但没有记录进入分子";
                default -> "当前统计SQL分母结果没有找到";
            });
            facts.put("与当前对象匹配的具体记录",
                    matched.stream().limit(1).map(DiagnosisCaseEvidenceService::compactModelRow).toList());
            facts.put("程序从当前抽取脚本中确认的业务规则",
                    extractionRuleHints(entity.sourceTableSql(), entity.caliber()));
            facts.put("实施人员选择的问题方向", issueDirectionLabel(issueDirection));
            facts.put("程序固定的问题理解", scopeIssueExplanation(issueDirection, object));
            facts.put("必须围绕的排查重点", issueDirectionFocus(issueDirection, scopeType));
            facts.put("从医院数据到统计结果的逐层数量", modelStageFacts(stageEvidence));
            facts.put("程序结论", conclusion);
            String answer = models.complete(model.id(),
                    """
                    你是医院核心指标实施口径说明助手。请结合当前指标知识库口径、数据来源、抽取规则和真实明细，把程序已经核验的事实整理成实施人员一眼能看懂的中文说明。
                    只能使用提供的事实，不得补造表、字段、筛选条件、患者情况或业务结论。
                    不要输出SQL、Markdown、编号、英文表名、英文字段名、SQL别名、RN或程序状态码；只说“医院原始业务记录”“抽取后的指标数据”“统计SQL分母结果”等实施人员能理解的名称。
                    把技术条件翻译成业务语言，例如把“RN=1”说成“同一次住院有多条记录时按当前排序只取一条”。
                    每项最多两句话。只返回以下JSON对象，不得增加任何前后文字：
                    {"scopeExplanation":"当前具体核对的是谁或什么范围，以及医院认为哪里不对","dataJourney":"这批数据来自什么业务记录、按哪些已确认规则被抽取、抽取后进入哪里","calculationRule":"统计SQL的分母和分子分别怎样判定","actualEvidence":"当前对象在医院业务记录、抽取后的指标数据、统计SQL分母结果、统计SQL分子结果中的实际数量和具体表现","conclusion":"直接回答为什么当前会被统计、没有被统计或首先在哪一环节减少，以及下一步应核对什么"}
                    dataJourney只能整理“程序从当前抽取脚本中确认的业务规则”，不能把分子、分母定义改写成抽取规则；没有已确认规则时直说“现有证据只能确认记录已通过抽取，不能逐项反推所有业务条件”。
                    actualEvidence必须使用提供的真实数量和记录事实；conclusion必须针对实施人员填写的对象和问题方向直接回答。
                    “实施人员选择的问题方向”和“程序固定的问题理解”属于不可改写事实。选择“多算了”时，必须解释该对象为什么没有被排除、为什么仍进入分母或分子，严禁改写成“应进入分子但没有进入”等少算问题。
                    calculationRule必须结合完整“统计口径”，说明统计对象、去重方式、转科或转区定义以及明确登记的排除范围，不能只复述一句分子和分母。
                    dataJourney必须围绕当前具体对象，口语化说明业务记录经过哪些筛选成为抽取后数据，再怎样被统计SQL判断为分母或分子；不能只写“按当前规则抽取”这种空话。
                    某一环节数量减少只说明记录从该环节开始被筛掉，不代表这些记录一定应该纳入；没有医院业务确认时只能写“需要核对”，不得写“本应计入”或“已经漏算”。
                    不得把“程序无法证明”写成“数据异常”，不得把科室编号说成患者编号。
                    """,
                    "以下是只读程序已经核验的事实：\n" + facts,
                    Duration.ofSeconds(45)).content().strip();
            String rendered = renderModelExplanation(answer, entity, scopeType, object,
                    matched.size(), numeratorCount, denominatorRule, numeratorRule,
                    status, issueDirection, stageEvidence, conclusion);
            if (!rendered.isBlank()) {
                return new NaturalExplanation(rendered, "MODEL", model.id());
            }
        } catch (RuntimeException exception) {
            log.warn("口径自然语言说明整理失败 caseId={} modelId={}",
                    snapshot.caseId(), snapshot.modelId(), exception);
            // 模型只负责改写；不可用时保留程序事实，不影响澄清接口和明细证据。
        }
        return new NaturalExplanation(fallback, "PROGRAM_FALLBACK", "");
    }

    private String renderModelExplanation(
            String answer,
            EntityPageData entity,
            String scopeType,
            String object,
            int denominatorCount,
            long numeratorCount,
            String denominatorRule,
            String numeratorRule,
            String status,
            String issueDirection,
            List<Map<String, Object>> stageEvidence,
            String conclusion) {
        String value = text(answer).replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "");
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) return "";
        try {
            Map<String, Object> parsed = mapper.readValue(value.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() { });
            // 问题方向由实施人员明确选择，不能允许模型把“多算”改写成“少算”。
            String modelDataJourney = safeBusinessPhrase(text(parsed.get("dataJourney")),
                    "系统从" + String.join("、", businessSourceDescriptions(entity))
                            + "取数，按当前生效抽取规则整理为该指标后续统计使用的数据。");
            String dataJourney = modelDataJourney + " "
                    + extractionOutcomeExplanation(issueDirection, object, stageEvidence);
            String subject = scopeSubject(scopeType);
            String modelCalculationRule = safeBusinessPhrase(text(parsed.get("calculationRule")),
                    "正式统计时，以“" + denominatorRule + "”作为分母，以“"
                            + numeratorRule + "”作为分子。");
            String calculationRule = knowledgeCaliberExplanation(
                    entity, denominatorRule, numeratorRule, modelCalculationRule);
            String actualEvidence = actualEvidenceExplanation(issueDirection, object,
                    stageEvidence, denominatorCount, numeratorCount);
            String inclusionFallback = switch (status) {
                case "IN_NUMERATOR_AND_DENOMINATOR" -> "系统在已对账明细中找到了“" + object
                        + "”，说明" + subject + "已通过分母范围；其中" + numeratorCount
                        + "条又满足分子条件，所以会同时进入统计SQL分母和分子结果。";
                case "IN_DENOMINATOR_ONLY" -> "系统在统计SQL分母结果中找到了“" + object
                        + "”，说明" + subject + "已通过分母范围；但没有记录满足分子条件，"
                        + "所以只进入统计SQL分母结果。";
                default -> "系统在本次统计窗口的统计SQL分母结果中没有找到“" + object
                        + "”。应先核对填写的编号或科室、统计时间和数据是否已经同步。";
            };
            String modelConclusion = conclusion.isBlank() ? inclusionFallback : conclusion;
            return "我先核对了你选择的对象：" + actualEvidence + "\n\n"
                    + "这批数据从哪里来：" + dataJourney + "\n\n"
                    + "正式统计如何判断分母和分子：" + calculationRule + "\n\n"
                    + "当前最早发现问题的环节和下一步：" + modelConclusion;
        } catch (Exception exception) {
            return "";
        }
    }

    private String safeBusinessPhrase(String candidate, String fallback) {
        String value = text(candidate).strip();
        if (value.isBlank() || containsImplementationJargon(value)) return fallback;
        return value;
    }

    private String safeNumeratorPhrase(String candidate, String fallback) {
        String value = text(candidate).strip();
        if (value.contains("分母")
                || value.contains("知识库当前登记")
                || value.contains("这些数据怎样形成指标记录")
                || value.contains("这批数据来自")
                || value.contains("当前分子口径怎样判断")
                || value.contains("本不应计入")
                || value.contains("不应计入的记录")
                || value.contains("错误计算")
                || value.contains("错误计入")
                || value.contains("程序算错")
                || value.contains("统计错误")) {
            return fallback;
        }
        return safeBusinessPhrase(value, fallback);
    }

    private static String scopeIssueExplanation(String direction, String object) {
        return switch (direction) {
            case "OVER_INCLUDED" -> "实施人员选择的是“多算了”。本次要核对“" + object
                    + "”为什么没有被当前排除规则过滤，仍然进入了正式统计；不是排查它为什么没有进入分子。";
            case "UNDER_INCLUDED" -> "实施人员选择的是“少算了”。本次要核对“" + object
                    + "”从业务数据、抽取结果到统计分母的哪一步开始没有被保留。";
            case "WRONG_CLASSIFICATION" -> "实施人员选择的是“归类不对”。本次要核对“" + object
                    + "”进入分母后，分子条件是否被正确判断。";
            case "SUSPECT_SYNC" -> "实施人员怀疑数据没有同步完整。本次要比较“" + object
                    + "”在业务库和真实库抽取结果中的数量与记录。";
            default -> "本次逐层核对“" + object + "”在业务数据、抽取结果、统计分母和统计分子中的实际去向。";
        };
    }

    private static String issueDirectionFocus(String direction, String scopeType) {
        String subject = "DEPARTMENT".equals(scopeType) ? "这个科室/病区" : "当前对象";
        return switch (direction) {
            case "OVER_INCLUDED" -> "围绕“" + subject + "是否属于医院要求排除的范围、当前抽取规则为何仍保留它、它进入了分母还是分子”解释。";
            case "UNDER_INCLUDED" -> "围绕“" + subject + "从哪一层开始没有记录、是哪条筛选或关联需要核对”解释。";
            case "WRONG_CLASSIFICATION" -> "围绕分母已纳入记录和分子判定字段解释。";
            case "SUSPECT_SYNC" -> "围绕业务库与真实库抽取前后的数量和记录集合解释。";
            default -> "严格按逐层证据解释，不预设多算或少算。";
        };
    }

    private static String extractionOutcomeExplanation(
            String direction, String object, List<Map<String, Object>> stages) {
        Long source = stageCount(stages, "BUSINESS_SOURCE");
        Long target = stageCount(stages, "REAL_TARGET");
        if (source == null || target == null) {
            return "程序没有同时取得业务库和真实库的可比数量，因此不能判断抽取前后是否发生数量变化。";
        }
        String comparison = "程序实际查到：业务库有" + source + "条，抽取后的真实库指标数据有"
                + target + "条";
        if (source.equals(target)) {
            comparison += "，数量一致；这只证明数量没有增减，不代表每条记录和字段值已经逐项相同。";
        } else {
            comparison += "，抽取前后相差" + Math.abs(source - target) + "条。";
        }
        if ("OVER_INCLUDED".equals(direction) && target > 0) {
            comparison += "“" + object + "”在抽取后仍然存在，说明当前生效的抽取和排除条件没有把它过滤掉。";
        }
        return comparison;
    }

    private static String knowledgeCaliberExplanation(
            EntityPageData entity,
            String denominatorRule,
            String numeratorRule,
            String modelExplanation) {
        String caliber = entity == null ? "" : cleanKnowledgeText(entity.caliber());
        String rules = "统计SQL以“" + denominatorRule + "”作为分母，以“"
                + numeratorRule + "”作为分子。";
        if (!caliber.isBlank()) {
            rules += " 知识库当前登记的统计口径是：" + caliber;
        }
        String consistencyNote = caliberConsistencyNote(entity, caliber);
        if (!consistencyNote.isBlank()) rules += " " + consistencyNote;
        if (!modelExplanation.isBlank() && !rules.contains(modelExplanation)) {
            rules += " 口语化理解：" + modelExplanation;
        }
        return rules;
    }

    private static String caliberConsistencyNote(EntityPageData entity, String caliber) {
        if (entity == null || caliber.isBlank()) return "";
        String sql = text(entity.sourceTableSql()).toUpperCase(Locale.ROOT);
        if (caliber.contains("不包含转区") && caliber.contains("流转类型=转区")
                && sql.contains("INPAT_TRANSFER_TYPE_CODE")
                && sql.contains("ORIGIN_DEPT_ID <> T.DESTINATION_DEPT_ID")) {
            return "口径提示：知识库同时写有“不包含转区”和“转区且转入、转出科室不同时视为转科”；当前抽取SQL实际采用后者，需要医院确认最终应执行哪一种定义。";
        }
        return "";
    }

    private static String cleanKnowledgeText(String value) {
        return text(value).replace("**", "").replace("`", "")
                .replaceAll("[\\r\\n]+", "；")
                .replaceAll("；{2,}", "；").strip();
    }

    private static String actualEvidenceExplanation(
            String direction,
            String object,
            List<Map<String, Object>> stages,
            int denominatorCount,
            long numeratorCount) {
        Long source = stageCount(stages, "BUSINESS_SOURCE");
        Long target = stageCount(stages, "REAL_TARGET");
        StringBuilder result = new StringBuilder("“").append(object).append("”");
        if ("OVER_INCLUDED".equals(direction)) {
            result.append(denominatorCount > 0
                    ? "确实出现在当前统计明细中"
                    : "没有出现在当前统计明细中，与你描述的“多算”现象不一致");
        } else if ("UNDER_INCLUDED".equals(direction)) {
            result.append(denominatorCount > 0
                    ? "实际已经出现在当前统计分母明细中，并非整条记录缺失"
                    : "确实没有出现在当前统计分母明细中");
        }
        if (source != null) result.append("，在医院业务库中查到").append(source).append("条");
        if (target != null) result.append("，抽取后的真实库指标数据中有").append(target).append("条");
        result.append("，统计SQL最终保留").append(denominatorCount).append("条作为分母，其中")
                .append(numeratorCount).append("条命中分子。");
        if ("OVER_INCLUDED".equals(direction)) {
            result.append("本次“多算了”的疑问针对的是这")
                    .append(denominatorCount).append("条为什么仍被纳入；分子为")
                    .append(numeratorCount).append("只说明分子判定结果，不代表本次在排查少算。");
        } else if ("UNDER_INCLUDED".equals(direction) && denominatorCount > 0 && numeratorCount == 0) {
            result.append("如果实施人员实际怀疑的是“没有进入分子”，后续应核对分子条件，而不是继续按整条数据缺失处理。");
        }
        return result.toString();
    }

    private static Long stageCount(List<Map<String, Object>> stages, String stageKey) {
        return stages.stream()
                .filter(stage -> stageKey.equals(text(stage.get("stageKey"))))
                .map(stage -> stage.get("count"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .findFirst().orElse(null);
    }

    private List<String> extractionRuleHints(String sql, String caliber) {
        String upper = text(sql).toUpperCase(Locale.ROOT);
        List<String> rules = new ArrayList<>();
        if (upper.contains("DISCHARGED_FROM_WARD_AT BETWEEN")
                && upper.contains(":STARTTIME") && upper.contains(":ENDTIME")) {
            rules.add("正式计算时按出区时间筛选本次统计窗口内的住院记录");
        }
        if (upper.contains("ROW_NUMBER()") && upper.contains("RN = 1")) {
            rules.add("同一次住院存在多条候选记录时，按脚本规定的排序只保留第一条");
        }
        if (upper.contains("INPAT_TRANSFER_TYPE_CODE")
                && upper.contains("ORIGIN_DEPT_ID <> T.DESTINATION_DEPT_ID")) {
            rules.add("保留转科记录；转区记录只有转出科室和转入科室不同时才保留");
        }
        if (upper.contains("DATEDIFF(HOUR") && upper.contains("< 48")) {
            rules.add("用首次入区时间到转科时间的间隔是否小于48小时形成转科判定");
        }
        if (upper.contains("ORIGIN_DEPT_ID NOT IN")
                && upper.contains("DESTINATION_DEPT_ID NOT IN")) {
            rules.add(text(caliber).toUpperCase(Locale.ROOT).contains("ICU")
                    ? "转入或转出ICU及其病区的记录按当前排除清单过滤"
                    : "转入或转出科室、病区属于当前排除清单时过滤该记录");
        }
        if (upper.contains(":EXDEPTSET")) {
            rules.add("排除系统配置的不纳入统计科室");
        }
        if (upper.contains(":EXPATIENTSET")) {
            rules.add("排除系统配置的不纳入统计患者");
        }
        return List.copyOf(rules);
    }

    private boolean containsImplementationJargon(String value) {
        if (value.isBlank()) return true;
        return Pattern.compile("(?i)\\b(?:SELECT|FROM|WHERE|JOIN|RN|[A-Za-z_]{4,})\\b")
                .matcher(value).find();
    }

    private List<String> businessSourceDescriptions(EntityPageData entity) {
        if (entity == null) {
            return List.of("当前指标登记的医院业务数据");
        }
        LinkedHashSet<String> sourceTables = new LinkedHashSet<>();
        // “业务表(影响数据)”不一定包含主事实表，因此同时读取知识库“源表”行，
        // 避免只告诉模型转科记录而遗漏住院就诊主记录。
        for (String line : text(entity.dataSource()).split("\\R")) {
            if (!line.matches("(?i)^\\s*\\|\\s*源表\\s*\\|.*")) continue;
            var matcher = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b").matcher(line);
            while (matcher.find()) sourceTables.add(matcher.group());
        }
        sourceTables.addAll(entity.bizTables());
        List<String> descriptions = sourceTables.stream().map(table -> {
            if (dataDictionary == null) return "";
            String system = text(dataDictionary.sourceSystem(table));
            String description = businessFriendlyDescription(dataDictionary.tableDescription(table));
            if (!description.isBlank() && !"未登记".equals(system)) {
                return system + "的" + description;
            }
            if (!description.isBlank()) return description;
            if (!"未登记".equals(system)) return system + "业务数据";
            return "";
        }).filter(value -> !value.isBlank()).distinct().toList();
        return descriptions.isEmpty() ? List.of("当前指标登记的医院业务数据") : descriptions;
    }

    private static String businessFriendlyDescription(String description) {
        String value = text(description).strip();
        int separator = Math.max(value.lastIndexOf('；'), value.lastIndexOf(';'));
        return separator >= 0 && separator + 1 < value.length()
                ? value.substring(separator + 1).strip() : value;
    }

    private String programExplanation(
            EntityPageData entity,
            String scopeType,
            String object,
            int denominatorCount,
            long numeratorCount,
            String denominatorRule,
            String numeratorRule,
            String status,
            String issueDirection,
            List<Map<String, Object>> stageEvidence,
            String conclusion) {
        String source = String.join("、", businessSourceDescriptions(entity));
        String target = "抽取后的指标数据";
        String subject = scopeSubject(scopeType);
        String inclusion = switch (status) {
            case "IN_NUMERATOR_AND_DENOMINATOR" -> subject + "有" + denominatorCount
                    + "条进入分母，其中" + numeratorCount + "条同时进入分子。";
            case "IN_DENOMINATOR_ONLY" -> subject + "有" + denominatorCount
                    + "条进入分母，但没有记录满足分子条件。";
            default -> "本次统计窗口的统计SQL分母结果中没有找到" + subject + "。";
        };
        String path = stageEvidence.stream().map(stage -> text(stage.get("label")) + "："
                + stageStatusLabel(text(stage.get("status")))
                + (stage.get("count") instanceof Number number ? "（" + number + "条）" : ""))
                .collect(java.util.stream.Collectors.joining("，"));
        String ruleHints = entity == null ? "" : String.join("、",
                extractionRuleHints(entity.sourceTableSql(), entity.caliber()));
        return "我先核对了你选择的对象：" + actualEvidenceExplanation(issueDirection, object,
                        stageEvidence, denominatorCount, numeratorCount) + "\n\n"
                + "这批数据从哪里来：系统从" + source + "取得业务记录，"
                + (ruleHints.isBlank() ? "按当前生效抽取规则" : "依次按“" + ruleHints + "”")
                + "整理为" + target + "，再交给正式统计SQL计算。"
                + extractionOutcomeExplanation(issueDirection, object, stageEvidence) + "\n\n"
                + "正式统计如何判断分母和分子：" + knowledgeCaliberExplanation(
                        entity, denominatorRule, numeratorRule, "") + inclusion + "\n\n"
                + "当前最早发现问题的环节和下一步：" + (!conclusion.isBlank() ? conclusion : "NOT_IN_DETAIL".equals(status)
                        ? "最终已对账的统计SQL分母结果中没有找到该对象；需要沿上方证据链从第一个未找到的环节继续排查。"
                        : "该对象已通过当前统计窗口和分母范围；其中满足分子条件的记录会同时进入统计分子。")
                + (path.isBlank() ? "" : " 逐层核验：" + path + "。");
    }

    private List<Map<String, Object>> buildStageEvidence(
            DiagnosisCaseSnapshot snapshot,
            EntityPageData entity,
            String scopeType,
            String field,
            List<String> values,
            List<Map<String, Object>> denominatorRows,
            List<Map<String, Object>> numeratorRows) {
        List<Map<String, Object>> stages = new ArrayList<>();
        if (entity == null) {
            stages.add(unavailableStage("BUSINESS_SOURCE", "医院原始业务记录", "当前生效口径不存在，无法核对业务源。"));
            stages.add(unavailableStage("REAL_TARGET", "抽取后的指标数据", "当前生效口径不存在，无法核对中间数据。"));
        } else {
            Map<String, Object> templateParams = new LinkedHashMap<>(parameters.mapTimeOnly(
                    parseTime(text(snapshot.caseInput().get("statStart"))),
                    parseTime(text(snapshot.caseInput().get("statEnd")))));
            templateParams.put("syncType", "outHosp");
            String sourceTemplate = sqlDialects == null
                    ? entity.sourceTableSql() : sqlDialects.sourceTableSql(entity);
            boolean oracleBusiness = sqlDialects != null && sqlDialects.oracleActive();
            String sourceSql = renderer.render(sourceTemplate, templateParams);
            String sourceField = resolveScopeSourceField(field, sourceSql, scopeType);
            if (text(sourceSql).isBlank()) {
                stages.add(unavailableStage("BUSINESS_SOURCE", "医院原始业务记录",
                        "当前口径没有独立业务源抽取 SQL，不能在这一层自动筛选对象。"));
            } else if (!"OVERALL".equals(scopeType) && sourceField.isBlank()) {
                stages.add(unavailableStage("BUSINESS_SOURCE", "医院原始业务记录",
                        "当前抽取结果没有可确定映射的范围字段，程序没有猜字段。"));
            } else {
                stages.add(executeScopeStage("BUSINESS_SOURCE", "医院原始业务记录", DatabaseRole.BUSINESS,
                        sourceSql, sourceField, scopeType, values,
                        "按当前抽取SQL查询医院业务库，确认原始业务记录中是否包含这个范围。",
                        oracleBusiness));
            }

            String targetField = resolveScopeTargetField(field, entity, scopeType);
            if (text(entity.targetTable()).isBlank()) {
                stages.add(unavailableStage("REAL_TARGET", "抽取后的指标数据",
                        "当前口径直接读取真实库已有表，没有独立指标中间表。"));
            } else if (!"OVERALL".equals(scopeType) && targetField.isBlank()) {
                stages.add(unavailableStage("REAL_TARGET", "抽取后的指标数据",
                        "当前口径没有登记可用于筛选这个范围的中间表字段。"));
            } else {
                String targetSql = "SELECT * FROM [dbo].[" + entity.targetTable() + "]";
                stages.add(executeScopeStage("REAL_TARGET", "抽取后的指标数据", DatabaseRole.REAL,
                        targetSql, targetField, scopeType, values,
                        "确认医院业务记录经过抽取后，是否进入当前指标正式统计使用的数据。",
                        false));
            }
        }
        stages.add(detailStage("DENOMINATOR", "统计SQL分母结果", denominatorRows,
                "正式统计SQL按“" + firstText(
                        snapshot.caseExpectedClassification().get("denominatorRule"), "当前口径分母")
                        + "”筛选后保留下来的记录。"));
        stages.add(detailStage("NUMERATOR", "统计SQL分子结果", numeratorRows,
                "在统计分母记录中，又满足“" + firstText(
                        snapshot.caseExpectedClassification().get("numeratorRule"), "当前口径分子")
                        + "”的记录。"));
        return List.copyOf(stages);
    }

    private Map<String, Object> executeScopeStage(
            String stageKey,
            String label,
            DatabaseRole role,
            String baseSql,
            String field,
            String scopeType,
            List<String> values,
            String meaning,
            boolean oracle) {
        String trimmed = trimSemicolon(baseSql);
        String predicate = scopePredicate(scopeType, field, values, oracle);
        String where = predicate.isBlank() ? "" : " WHERE " + predicate;
        String alias = oracle ? " __DIAG_SCOPE" : " AS __DIAG_SCOPE";
        String countSql = "SELECT " + (oracle ? "COUNT(1)" : "COUNT_BIG(1)")
                + " AS __evidence_count FROM (\n" + trimmed + "\n)" + alias + where;
        String sampleSql = oracle
                ? "SELECT * FROM (\n" + trimmed + "\n)" + alias + where
                        + " FETCH FIRST 10 ROWS ONLY"
                : "SELECT TOP (10) * FROM (\n" + trimmed + "\n)" + alias + where;
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stageKey", stageKey);
        stage.put("label", label);
        stage.put("databaseRole", role.name());
        stage.put("meaning", meaning);
        stage.put("sql", countSql);
        try {
            long count = aggregateCount(query.execute(role, countSql));
            List<Map<String, Object>> samples = count > 0
                    ? query.execute(role, sampleSql).stream().limit(10).toList() : List.of();
            stage.put("status", count > 0 ? "FOUND" : "NOT_FOUND");
            stage.put("count", count);
            stage.put("sampleRows", samples);
        } catch (RuntimeException exception) {
            stage.put("status", "UNAVAILABLE");
            stage.put("sampleRows", List.of());
            stage.put("error", safeMessage(exception));
        }
        return Map.copyOf(stage);
    }

    private static Map<String, Object> detailStage(
            String stageKey, String label, List<Map<String, Object>> rows, String meaning) {
        return Map.of(
                "stageKey", stageKey,
                "label", label,
                "databaseRole", "RECONCILED_DETAIL",
                "status", rows.isEmpty() ? "NOT_FOUND" : "FOUND",
                "count", (long) rows.size(),
                "meaning", meaning,
                "sampleRows", rows.stream().limit(10).toList());
    }

    private static Map<String, Object> unavailableStage(String key, String label, String reason) {
        return unavailableStage(key, label, reason, "", "");
    }

    private static Map<String, Object> unavailableStage(
            String key, String label, String reason, String databaseRole, String sql) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stageKey", key);
        result.put("label", label);
        result.put("status", "UNAVAILABLE");
        result.put("meaning", reason);
        result.put("sampleRows", List.of());
        if (!databaseRole.isBlank()) result.put("databaseRole", databaseRole);
        if (!sql.isBlank()) result.put("sql", sql);
        return Map.copyOf(result);
    }

    private static String scopePredicate(
            String scopeType, String field, List<String> values, boolean oracle) {
        if ("OVERALL".equals(scopeType)) return "";
        String safeField = text(field);
        if (!safeField.matches("[A-Za-z_][A-Za-z0-9_]*")) return "";
        String column = oracle ? safeField : "[" + safeField + "]";
        if ("TIME_RANGE".equals(scopeType) && values.size() >= 2) {
            return column + " >= " + literal(values.get(0)) + " AND " + column + " < " + literal(values.get(1));
        }
        if (values.isEmpty()) return "";
        String exact = values.stream().map(DiagnosisCaseEvidenceService::literal)
                .collect(java.util.stream.Collectors.joining(","));
        String cast = oracle ? "TO_CHAR(" + column + ")"
                : "CONVERT(NVARCHAR(200), " + column + ")";
        String predicate = cast + " IN (" + exact + ")";
        if ("DEPARTMENT".equals(scopeType) || "DATA_CATEGORY".equals(scopeType)) {
            String contains = values.stream().map(value -> cast
                    + " LIKE " + (oracle ? "" : "N") + "'%"
                    + text(value).replace("'", "''") + "%'")
                    .collect(java.util.stream.Collectors.joining(" OR "));
            predicate = "(" + predicate + " OR " + contains + ")";
        }
        return predicate;
    }

    private static long aggregateCount(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0L;
        Object value = rows.get(0).entrySet().stream()
                .filter(entry -> "__evidence_count".equalsIgnoreCase(entry.getKey())
                        || "__candidate_count".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(0L);
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static String resolveScopeSourceField(String requested, String sql, String scopeType) {
        if ("OVERALL".equals(scopeType)) return "";
        String normalized = unqualify(requested);
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        candidates.add(toLowerCamel(normalized));
        for (String candidate : candidates) {
            if (Pattern.compile("(?i)\\bAS\\s+[\\[\"]?" + Pattern.quote(candidate)
                    + "[\\]\"]?(?![A-Za-z0-9_])").matcher(text(sql)).find()) return candidate;
        }
        return Pattern.compile("(?i)\\b" + Pattern.quote(normalized) + "\\b")
                .matcher(text(sql)).find() ? normalized : "";
    }

    private static String resolveScopeTargetField(
            String requested, EntityPageData entity, String scopeType) {
        if ("OVERALL".equals(scopeType)) return "";
        if ("RECORD".equals(scopeType)) return resolveTargetField(requested, entity);
        String normalized = unqualify(requested);
        String searchable = text(entity.overviewSql()) + "\n" + text(entity.patientDetailSql())
                + "\n" + text(entity.sourceTableSql());
        return Pattern.compile("(?i)\\b" + Pattern.quote(normalized) + "\\b")
                .matcher(searchable).find() ? normalized : "";
    }

    private static String toLowerCamel(String value) {
        String[] parts = text(value).toLowerCase(Locale.ROOT).split("_+");
        if (parts.length == 0) return "";
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) result.append(Character.toUpperCase(parts[i].charAt(0)))
                    .append(parts[i].substring(1));
        }
        return result.toString();
    }

    private static List<String> scopeValues(DiagnosisCaseSnapshot snapshot, String scopeType) {
        return switch (scopeType) {
            case "RECORD" -> DiagnosisCaseService.recordIds(snapshot.caseInput());
            case "TIME_RANGE" -> List.of(text(snapshot.caseInput().get("scopeStart")),
                    text(snapshot.caseInput().get("scopeEnd")));
            case "OVERALL" -> List.of();
            default -> {
                List<String> values = stringList(snapshot.caseInput().get("scopeValues"));
                yield values.isEmpty()
                        ? List.of(text(snapshot.caseInput().get("scopeValue"))) : values;
            }
        };
    }

    private static String scopeObject(String scopeType, List<String> values) {
        return switch (scopeType) {
            case "RECORD" -> String.join("、", values);
            case "TIME_RANGE" -> values.size() < 2 ? "所选时间范围" : values.get(0) + " 至 " + values.get(1);
            case "OVERALL" -> "本次整体结果";
            default -> values.isEmpty() ? "所选范围" : String.join("、", values);
        };
    }

    private static String normalizeIssueDirection(Object value) {
        String direction = text(value).toUpperCase(Locale.ROOT);
        return List.of("OVER_INCLUDED", "UNDER_INCLUDED", "WRONG_CLASSIFICATION", "SUSPECT_SYNC")
                .contains(direction) ? direction : "UNKNOWN";
    }

    private static String traceMode(String direction) {
        return switch (direction) {
            case "OVER_INCLUDED" -> "REVERSE_INCLUSION";
            case "UNDER_INCLUDED" -> "FORWARD_MISSING";
            case "WRONG_CLASSIFICATION" -> "CLASSIFICATION_CHECK";
            case "SUSPECT_SYNC" -> "SYNC_CHECK";
            default -> "FORWARD_MISSING";
        };
    }

    private static String firstDifferenceStage(
            String direction, List<Map<String, Object>> stages) {
        if (!List.of("UNDER_INCLUDED", "SUSPECT_SYNC", "UNKNOWN").contains(direction)) return "";
        Long previous = null;
        for (Map<String, Object> stage : stages) {
            if ("NUMERATOR".equals(stage.get("stageKey"))) break;
            if ("NOT_FOUND".equals(stage.get("status"))) return text(stage.get("label"));
            if (!"FOUND".equals(stage.get("status")) || !(stage.get("count") instanceof Number number)) {
                continue;
            }
            long current = number.longValue();
            if (previous != null && current < previous) return text(stage.get("label"));
            previous = current;
        }
        return "";
    }

    private static String evidenceConclusion(
            String direction, String scopeType, String object, String status, String firstDifference,
            long denominator, long numerator, List<Map<String, Object>> stages) {
        if (!firstDifference.isBlank()) {
            String drop = stageDropSummary(stages, firstDifference);
            return "“" + object + "”从“" + firstDifference + "”开始出现记录减少"
                    + (drop.isBlank() ? "" : "（" + drop + "）")
                    + "；这就是当前最先需要核对的环节。";
        }
        if ("OVER_INCLUDED".equals(direction) && !"NOT_IN_DETAIL".equals(status)) {
            return "“" + object + "”当前有" + denominator + "条进入统计分母、" + numerator
                    + "条进入统计分子。本次排查的是这些记录为什么没有被排除，不是分子漏算；"
                    + ("DEPARTMENT".equals(scopeType)
                            ? "需要确认该科室是否属于医院要求排除的范围，并核对当前科室排除配置和抽取SQL。"
                            : "需要核对当前排除范围与抽取SQL是否符合医院规则。");
        }
        if ("WRONG_CLASSIFICATION".equals(direction)) {
            return "“" + object + "”当前有" + denominator + "条进入分母、" + numerator
                    + "条进入分子；请以这个归类结果与医院预期逐项对照。";
        }
        if ("SUSPECT_SYNC".equals(direction)) {
            return "医院原始业务记录和抽取后的指标数据均已取得当前证据；如果两层数量不同，应优先重新抽取核对。";
        }
        return "已按当前生效数据链路完成逐层核对，未发现可以直接确认的首个缺失环节。";
    }

    private static String stageDropSummary(List<Map<String, Object>> stages, String label) {
        Long previous = null;
        for (Map<String, Object> stage : stages) {
            if ("NUMERATOR".equals(stage.get("stageKey"))) break;
            if (!"FOUND".equals(stage.get("status")) || !(stage.get("count") instanceof Number number)) {
                continue;
            }
            long current = number.longValue();
            if (label.equals(text(stage.get("label"))) && previous != null && current < previous) {
                return previous + "条变为" + current + "条，减少" + (previous - current) + "条";
            }
            previous = current;
        }
        return "";
    }

    private static String evidenceNextAction(
            String direction, String scopeType, String firstDifference, String status) {
        if (!firstDifference.isBlank()) return "先核对“" + firstDifference + "”的筛选条件、关联字段和同步状态；修复后重新执行本次核对。";
        return switch (direction) {
            case "OVER_INCLUDED" -> "DEPARTMENT".equals(scopeType)
                    ? "请先由医院确认该科室是否应排除，并提供科室代码或排除范围；再核对当前科室排除配置和抽取SQL。"
                    : "确认当前对象应满足的排除条件，再核对抽取SQL是否缺少对应过滤。";
            case "WRONG_CLASSIFICATION" -> "对照分母、分子样例和医院预期，确认是抽取数据问题还是分子判定问题。";
            case "SUSPECT_SYNC" -> "先用当前正式 SQL 重新抽取到影子环境；结果恢复即可按同步问题处理。";
            case "UNDER_INCLUDED" -> "补充一条医院确认应纳入的业务记录，继续核对业务源字段和关联条件。";
            default -> "根据逐层证据选择重新抽取、修改抽取 SQL 或修改统计 SQL。";
        };
    }

    private static String issueDirectionLabel(String direction) {
        return switch (direction) {
            case "OVER_INCLUDED" -> "本来不应统计但统计了";
            case "UNDER_INCLUDED" -> "本来应该统计但没有统计";
            case "WRONG_CLASSIFICATION" -> "分子或分母归类不对";
            case "SUSPECT_SYNC" -> "怀疑数据没有同步完整";
            default -> "暂不确定是多算还是少算";
        };
    }

    private static String scopeSubject(String scopeType) {
        return switch (scopeType) {
            case "RECORD" -> "这条患者/记录";
            case "DEPARTMENT" -> "这个科室/病区";
            case "TIME_RANGE" -> "这个时间范围";
            case "DATA_CATEGORY" -> "这类数据";
            default -> "本次整体结果";
        };
    }

    private static String stageStatusLabel(String status) {
        return switch (status) {
            case "FOUND" -> "已找到";
            case "NOT_FOUND" -> "未找到";
            case "UNAVAILABLE" -> "未能自动核对";
            default -> "不适用";
        };
    }

    private static List<Map<String, Object>> modelStageFacts(List<Map<String, Object>> stages) {
        return stages.stream().map(stage -> {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("环节", stage.get("label"));
            fact.put("结果", stageStatusLabel(text(stage.get("status"))));
            if (stage.get("count") instanceof Number count) fact.put("记录数", count);
            fact.put("这一环节代表什么", stage.get("meaning"));
            if (stage.get("sampleRows") instanceof List<?> rows && !rows.isEmpty()) {
                fact.put("部分具体记录", rows.stream().limit(1)
                        .filter(Map.class::isInstance).map(Map.class::cast)
                        .map(DiagnosisCaseEvidenceService::compactModelRow).toList());
            }
            return Map.copyOf(fact);
        }).toList();
    }

    private static Map<String, Object> compactModelRow(Map<?, ?> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.entrySet().stream().limit(12).forEach(entry -> {
            Object value = entry.getValue();
            if (value == null) {
                result.put(text(entry.getKey()), "—");
                return;
            }
            String display = text(value);
            result.put(text(entry.getKey()), display.length() <= 160
                    ? value : display.substring(0, 160) + "…");
        });
        return Map.copyOf(result);
    }

    static Map<String, Object> numeratorEvidenceRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (isNumeratorEvidenceField(key) && value != null && !text(value).isBlank()) {
                result.put(key, value);
            }
        });
        return jsonSafeDetailRow(result);
    }

    private static boolean isNumeratorEvidenceField(String field) {
        String upper = text(field).toUpperCase(Locale.ROOT);
        if (upper.matches(".*(?:CREATED|MODIFIED|UPDATED|EXTRACT|DELETED|TARGET_DEFINITION|HOSPITAL_SOID|HOSPITAL_AREA|VERSION).*$")) {
            return false;
        }
        return upper.matches(".*(?:患者姓名|住院号|就诊号|当前科室|入区时间|转科时间|转科时间-入院时间|转科类型|转出科室|转入科室|是否48小时内转科|状态|判定结果).*$")
                || upper.matches(".*(?:ENCOUNTER_ID|IMRN|PERSON_NAME|CURRENT_DEPT_NAME|ADMITTED_TO_WARD_AT|INPAT_TRANSFER_AT|ORIGIN_DEPT_NAME|DESTINATION_DEPT_NAME|TRANSFER_WITHIN_TWO_DAY|STATUS|RESULT|FLAG).*$");
    }

    private static String readableEvidenceRow(Map<String, Object> row) {
        return row.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("__"))
                .sorted(java.util.Comparator.comparingInt(
                        entry -> evidenceFieldPriority(entry.getKey())))
                .limit(14)
                .map(entry -> evidenceFieldLabel(entry.getKey()) + "为“"
                        + evidenceFieldValue(entry.getKey(), entry.getValue()) + "”")
                .collect(java.util.stream.Collectors.joining("，"));
    }

    private static int evidenceFieldPriority(String field) {
        String upper = text(field).toUpperCase(Locale.ROOT);
        if (upper.contains("PERSON") || upper.contains("患者姓名")) return 10;
        if (upper.contains("ENCOUNTER") || upper.contains("就诊号")) return 20;
        if (upper.contains("IMRN") || upper.contains("住院号")) return 30;
        if (upper.contains("ADMITTED") || upper.equals("入区时间")) return 40;
        if ((upper.contains("TRANSFER") && upper.endsWith("_AT")) || upper.equals("转科时间")) return 50;
        if (upper.contains("转科时间-入院时间")) return 60;
        if (upper.contains("ORIGIN") || upper.contains("转出科室")) return 70;
        if (upper.contains("DESTINATION") || upper.contains("转入科室")) return 80;
        if (upper.contains("CURRENT_DEPT") || upper.contains("当前科室")) return 90;
        if (upper.contains("TRANSFER_WITHIN_TWO_DAY") || upper.contains("是否48小时内转科")) return 100;
        return 200;
    }

    private static String evidenceFieldValue(String field, Object rawValue) {
        String value = text(rawValue);
        String upper = text(field).toUpperCase(Locale.ROOT);
        if (upper.contains("TRANSFER_WITHIN_TWO_DAY") || upper.contains("是否48小时内转科")) {
            if ("98175".equals(value) || "1".equals(value) || "是".equals(value)) return "是";
            if ("98176".equals(value) || "0".equals(value) || "否".equals(value)) return "否";
        }
        if ((upper.endsWith("_AT") || upper.contains("时间") || upper.contains("日期"))
                && value.matches("\\d{13}")) {
            try {
                return LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (RuntimeException ignored) {
                return value;
            }
        }
        return value;
    }

    private static String evidenceFieldLabel(String field) {
        String value = text(field);
        if (Pattern.compile("[\u4e00-\u9fa5]").matcher(value).find()) return value;
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("ENCOUNTER")) return "就诊号";
        if (upper.equals("IMRN")) return "住院号";
        if (upper.contains("PERSON") && upper.contains("NAME")) return "患者姓名";
        if (upper.contains("ADMITTED") && upper.endsWith("_AT")) return "入区时间";
        if (upper.contains("TRANSFER") && upper.endsWith("_AT")) return "转科时间";
        if (upper.contains("ORIGIN") && upper.contains("DEPT")) return "转出科室";
        if (upper.contains("DESTINATION") && upper.contains("DEPT")) return "转入科室";
        if (upper.contains("DEPT") && upper.contains("NAME")) return "科室";
        if (upper.contains("WARD") && upper.contains("NAME")) return "病区";
        if (upper.contains("TRANSFER_WITHIN_TWO_DAY")) return "48小时内转科判定";
        if (upper.contains("STATUS")) return "状态";
        if (upper.contains("RESULT") || upper.contains("FLAG")) return "判定结果";
        return value;
    }

    private static String compactSql(String sql) {
        String value = text(sql);
        if (value.length() <= 24000) return value;
        return value.substring(0, 12000) + "\n-- 中间部分因长度省略 --\n"
                + value.substring(value.length() - 12000);
    }

    private DetailRows loadDetailRows(DiagnosisCaseSnapshot snapshot) {
        Map<String, Object> frozenCalculation = frozenCalculation(snapshot);
        long expectedNumerator = requiredCount(
                snapshot.caseExpectedClassification(), frozenCalculation,
                "numeratorCount", "分子");
        long expectedDenominator = requiredCount(
                snapshot.caseExpectedClassification(), frozenCalculation,
                "denominatorCount", "分母");
        String expectedHash = firstText(
                snapshot.caseExpectedClassification().get("overviewSqlHash"),
                frozenCalculation.get("overviewSqlHash"));
        if (expectedHash.isBlank()) {
            throw error("DIAGNOSIS_DETAIL_CONTEXT_MISSING",
                    "本次排查没有冻结统计 SQL 版本，请重新执行基础校验。", HttpStatus.CONFLICT);
        }
        DetailExtraction extraction = detailExtractor.extract(
                snapshot.ruleId(), snapshot.profileId());
        if (!extraction.supported()) {
            throw error("DIAGNOSIS_DETAIL_UNSUPPORTED",
                    "当前口径暂不能安全生成分子分母明细："
                            + text(extraction.unsupportedReason()),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!expectedHash.equals(extraction.overviewSqlHash())) {
            throw error("DIAGNOSIS_DETAIL_CONTRACT_CHANGED",
                    "知识库统计口径已变化，请重新执行基础校验后再查看明细。",
                    HttpStatus.CONFLICT);
        }

        String startText = text(snapshot.caseInput().get("statStart"));
        String endText = text(snapshot.caseInput().get("statEnd"));
        String cacheKey = "DIAGNOSIS:" + snapshot.caseId() + ":" + expectedHash;
        List<Map<String, Object>> denominatorRows = detailCache.get(
                cacheKey, startText, endText, "denominator");
        List<Map<String, Object>> numeratorRows = detailCache.get(
                cacheKey, startText, endText, "numerator");
        boolean reused = denominatorRows != null && numeratorRows != null;
        if (!reused) {
            ToolResult result = mrasExecution.executeBoundDetail(
                    snapshot.caseId(), snapshot.ruleId(), snapshot.profileId(), extraction.detailSql(),
                    expectedHash, parseTime(startText), parseTime(endText),
                    expectedNumerator, expectedDenominator);
            if (!result.ok()) {
                String code = "MRAS_DETAIL_COUNT_MISMATCH".equals(result.code())
                        ? "DETAIL_COUNT_MISMATCH" : "DIAGNOSIS_DETAIL_QUERY_FAILED";
                throw error(code, text(result.summary()).isBlank()
                                ? "分子分母明细查询失败。" : result.summary(),
                        HttpStatus.CONFLICT);
            }
            denominatorRows = rows(result.data().get("rows")).stream()
                    .map(DiagnosisCaseEvidenceService::jsonSafeDetailRow)
                    .toList();
            numeratorRows = denominatorRows.stream()
                    .filter(DiagnosisCaseEvidenceService::meetsNumerator)
                    .toList();
            detailCache.put(cacheKey, startText, endText,
                    "denominator", denominatorRows);
            detailCache.put(cacheKey, startText, endText,
                    "numerator", numeratorRows);
        }
        return new DetailRows(denominatorRows, numeratorRows, reused,
                expectedNumerator, expectedDenominator, expectedHash, extraction);
    }

    /**
     * 浏览器中的 JavaScript 不能精确表示超过 2^53-1 的整数。医院业务编号、
     * 就诊号和中间表主键经常是 18 位 long，因此在离开服务端前转成字符串；
     * 普通计数和布尔标记仍保留数值类型。
     */
    static Map<String, Object> jsonSafeDetailRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (isUnsafeJsonInteger(value)) {
                result.put(key, integerText(value));
            } else {
                result.put(key, value);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static boolean isUnsafeJsonInteger(Object value) {
        BigDecimal number;
        if (value instanceof BigDecimal decimal) {
            number = decimal;
        } else if (value instanceof BigInteger integer) {
            number = new BigDecimal(integer);
        } else if (value instanceof Long integer) {
            number = BigDecimal.valueOf(integer);
        } else {
            return false;
        }
        BigDecimal normalized = number.stripTrailingZeros();
        return normalized.scale() <= 0
                && normalized.abs().compareTo(BigDecimal.valueOf(9_007_199_254_740_991L)) > 0;
    }

    private static String integerText(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.toBigIntegerExact().toString();
        return value.toString();
    }

    private static boolean matchesScope(
            Map<String, Object> row, String scopeType, String field, List<String> values) {
        if ("OVERALL".equals(scopeType)) return true;
        if ("TIME_RANGE".equals(scopeType)) {
            String key = row.keySet().stream().filter(item -> item.equalsIgnoreCase(unqualify(field)))
                    .findFirst().orElse("");
            if (key.isBlank() || values.size() < 2) return false;
            try {
                LocalDateTime actual = parseTime(text(row.get(key)));
                LocalDateTime start = parseTime(values.get(0));
                LocalDateTime end = parseTime(values.get(1));
                return !actual.isBefore(start) && actual.isBefore(end);
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return !matchingFields(row, scopeType, field, values).isEmpty();
    }

    private static List<String> matchingFields(
            Map<String, Object> row, String scopeType, String field, List<String> values) {
        String normalizedField = unqualify(field);
        List<String> candidates = row.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(normalizedField))
                .toList();
        if (candidates.isEmpty() && "DEPARTMENT".equals(scopeType)) {
            candidates = row.keySet().stream()
                    .filter(key -> key.toUpperCase(Locale.ROOT).matches(".*(DEPT|WARD).*(ID|NO|CODE|NAME).*"))
                    .toList();
        }
        return candidates.stream().filter(key -> values.stream()
                .filter(value -> !value.isBlank())
                .anyMatch(value -> valueMatches(row.get(key), value,
                        "DEPARTMENT".equals(scopeType) || "DATA_CATEGORY".equals(scopeType))))
                .toList();
    }

    private static boolean valueMatches(Object actual, String expected, boolean allowContains) {
        String left = text(actual);
        String right = text(expected);
        if (left.equalsIgnoreCase(right)) return true;
        return allowContains && !right.isBlank()
                && left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
    }

    private static String unqualify(String value) {
        String normalized = text(value).replace("[", "").replace("]", "");
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String scopeDisplayObject(
            String scopeType, String object, String requestedField,
            List<Map<String, Object>> matched) {
        if (!"DEPARTMENT".equals(scopeType) || matched.isEmpty()) return object;
        String normalized = unqualify(requestedField).toUpperCase(Locale.ROOT);
        String prefix = normalized.replaceFirst("_(?:ID|NO|CODE)$", "");
        List<String> names = matched.stream()
                .flatMap(row -> row.entrySet().stream())
                .filter(entry -> {
                    String key = entry.getKey().toUpperCase(Locale.ROOT);
                    if (normalized.endsWith("_NAME")) return key.equals(normalized);
                    return key.equals(prefix + "_NAME")
                            || (key.contains(prefix) && key.contains("NAME"));
                })
                .map(Map.Entry::getValue)
                .map(DiagnosisCaseEvidenceService::text)
                .filter(value -> !value.isBlank() && !value.equalsIgnoreCase(object))
                .distinct().limit(3).toList();
        return names.isEmpty() ? object : object + "（" + String.join("、", names) + "）";
    }

    private static String scopeSummary(
            String scopeType, String object, String status, long denominator, long numerator) {
        String subject = switch (scopeType) {
            case "RECORD" -> "患者/记录 ";
            case "DEPARTMENT" -> "科室/病区 ";
            case "TIME_RANGE" -> "时间范围 ";
            case "DATA_CATEGORY" -> "数据范围 ";
            default -> "整体结果 ";
        };
        return switch (status) {
            case "IN_NUMERATOR_AND_DENOMINATOR" -> subject + object + "在本次统计SQL分母结果中有"
                    + denominator + "条，其中" + numerator + "条进入统计SQL分子结果。";
            case "IN_DENOMINATOR_ONLY" -> subject + object + "在本次统计SQL分母结果中有"
                    + denominator + "条，但没有记录进入统计SQL分子结果。";
            default -> subject + object + "没有出现在本次统计窗口的统计SQL分母结果中。";
        };
    }

    private enum PatientField {
        ENCOUNTER_ID("^(ENCOUNTER_ID|ENCOUNTERID|VISIT_ID|ADMISSION_ID|就诊号|就诊ID)$"),
        FULL_NAME(".*(FULL_NAME|PERSON_NAME|PATIENT_NAME|患者姓名|姓名).*$"),
        IMRN("^(IMRN|INPATIENT_NO|HOSPITAL_NO|MEDICAL_RECORD_NO|住院号)$"),
        BED_NO(".*(BED_NO|BED_CODE|BED_NAME|床位号|床号).*$"),
        ADMISSION_DATE(".*(ADMITTED_AT|ADMITTED_TO_WARD_AT|FIRST_ADMITTED_TO_WARD_AT|入院时间|入区时间|入院日期).*$"),
        DEPARTMENT_NAME(".*(CURRENT_DEPT_NAME|CURRENT_WARD_NAME|DEPARTMENT_NAME|科室名称|当前科室|病区名称).*$");

        private final Pattern pattern;

        PatientField(String expression) {
            this.pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
        }

        boolean matches(String value) {
            return pattern.matcher(text(value)).matches();
        }
    }

    private record PatientLookupCriteria(
            String lookupMode,
            String keyword,
            String fullName,
            String bedNo,
            String imrn,
            String admissionDate,
            String encounterId) {

        static PatientLookupCriteria of(
                String lookupMode,
                String keyword,
                String fullName,
                String bedNo,
                String imrn,
                String admissionDate,
                String encounterId) {
            String mode = text(lookupMode).toUpperCase(Locale.ROOT);
            return switch (mode) {
                case "NAME_BED" -> new PatientLookupCriteria(
                        mode, text(keyword), text(fullName), text(bedNo), "", "", "");
                case "IMRN_ADMISSION_DATE" -> new PatientLookupCriteria(
                        mode, text(keyword), "", "", text(imrn), text(admissionDate), "");
                case "ENCOUNTER_ID" -> new PatientLookupCriteria(
                        mode, text(keyword), "", "", "", "", text(encounterId));
                case "NAME_IMRN" -> new PatientLookupCriteria(
                        mode, text(keyword), text(fullName), "", text(imrn), "", "");
                default -> throw error("DIAGNOSIS_PATIENT_LOOKUP_MODE_INVALID",
                        "不支持的患者查询方式。", HttpStatus.BAD_REQUEST);
            };
        }

        void validate() {
            if (!fullName.isBlank() && fullName.length() < 2) {
                throw error("DIAGNOSIS_PATIENT_LOOKUP_NAME_TOO_SHORT",
                        "患者姓名至少输入两个字符。", HttpStatus.BAD_REQUEST);
            }
            if (!keyword.isBlank()
                    && ("NAME_BED".equals(lookupMode) || "NAME_IMRN".equals(lookupMode))
                    && keyword.length() < 2) {
                throw error("DIAGNOSIS_PATIENT_LOOKUP_NAME_TOO_SHORT",
                        "姓名相关查询至少输入两个字符。", HttpStatus.BAD_REQUEST);
            }
            if (!admissionDate.isBlank()) {
                try {
                    LocalDate.parse(admissionDate);
                } catch (RuntimeException exception) {
                    throw error("DIAGNOSIS_PATIENT_LOOKUP_DATE_INVALID",
                            "入院日期必须使用 yyyy-MM-dd 格式。", HttpStatus.BAD_REQUEST);
                }
            }
        }

        boolean isEmpty() {
            return keyword.isBlank() && fullName.isBlank() && bedNo.isBlank()
                    && imrn.isBlank() && admissionDate.isBlank() && encounterId.isBlank();
        }
    }

    private record TargetPatientColumns(
            String encounterId,
            String fullName,
            String imrn,
            String bedNo,
            String admissionDate,
            String periodAt,
            String hospitalSoid) {

        static TargetPatientColumns resolve(List<String> columns) {
            return new TargetPatientColumns(
                    findColumn(columns, "ENCOUNTER_ID", "ENCOUNTERID", "VISIT_ID"),
                    findColumn(columns, "FULL_NAME", "PERSON_NAME", "PATIENT_NAME"),
                    findColumn(columns, "IMRN", "INPATIENT_NO", "HOSPITAL_NO",
                            "MEDICAL_RECORD_NO"),
                    findColumn(columns, "BED_NO", "BED_CODE", "CURRENT_BED_NO", "BED_NAME"),
                    findColumn(columns, "ADMITTED_AT", "ADMITTED_TO_WARD_AT",
                            "FIRST_ADMITTED_TO_WARD_AT", "INPATIENT_AT"),
                    findColumn(columns, "EVENT_AT", "ADMITTED_AT", "ADMITTED_TO_WARD_AT",
                            "FIRST_ADMITTED_TO_WARD_AT", "INPATIENT_AT"),
                    findColumn(columns, "HOSPITAL_SOID", "HOSPITALSOID"));
        }

        private static String findColumn(List<String> columns, String... candidates) {
            for (String candidate : candidates) {
                String matched = columns.stream()
                        .filter(column -> candidate.equalsIgnoreCase(column))
                        .findFirst().orElse("");
                if (!matched.isBlank()) return matched;
            }
            return "";
        }
    }

    private record CandidateSearchRows(
            List<Map<String, Object>> rows,
            long total,
            boolean available,
            String message) {
        static CandidateSearchRows unavailable(String message) {
            return new CandidateSearchRows(List.of(), 0, false, message);
        }
    }

    private record DetailRows(
            List<Map<String, Object>> denominatorRows,
            List<Map<String, Object>> numeratorRows,
            boolean reused,
            long expectedNumerator,
            long expectedDenominator,
            String expectedHash,
            DetailExtraction extraction) { }

    private record NaturalExplanation(String content, String source, String model) { }

    private record DepartmentValue(String field, String value, String label) { }

    private static String resolveSourceField(String type, String sql) {
        for (String candidate : candidates(type, true)) {
            Pattern alias = Pattern.compile("(?i)\\bAS\\s+[\\[\"]?"
                    + Pattern.quote(candidate) + "[\\]\"]?(?![A-Za-z0-9_])");
            if (alias.matcher(text(sql)).find()) return candidate;
        }
        return "";
    }

    private static String resolveTargetField(String type, EntityPageData entity) {
        String searchable = text(entity.overviewSql()) + "\n" + text(entity.patientDetailSql());
        for (String candidate : candidates(type, false)) {
            if (Pattern.compile("(?i)\\b" + Pattern.quote(candidate) + "\\b")
                    .matcher(searchable).find()) return candidate;
        }
        return "";
    }

    private static List<String> candidates(String type, boolean source) {
        return switch (text(type).toUpperCase(Locale.ROOT)) {
            case "ENCOUNTER_ID" -> source
                    ? List.of("encounterId", "ENCOUNTER_ID", "bizId") : List.of("ENCOUNTER_ID");
            case "EVENT_ID" -> source
                    ? List.of("eventId", "EVENT_ID", "bizId") : List.of("EVENT_ID");
            case "ORDER_ID" -> source
                    ? List.of("orderId", "ORDER_ID", "CLI_ORDER_ID", "bizId")
                    : List.of("ORDER_ID", "CLI_ORDER_ID");
            case "SURGERY_ID" -> source
                    ? List.of("surgeryId", "SURGERY_ID", "bizId") : List.of("SURGERY_ID");
            default -> List.of();
        };
    }

    private Map<String, Object> execute(
            String stage, DatabaseRole role, String sql, boolean sourceQuery) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("databaseRole", role.name());
        result.put("sql", sql);
        try {
            List<Map<String, Object>> rows = query.execute(role, sql);
            result.put("status", "COMPLETED");
            result.put("rowCount", rows.size());
            result.put("rows", rows.stream().limit(50).toList());
            result.put("meaning", sourceQuery
                    ? "用于证明案例是否进入当前抽取口径，不等于最终已纳入分子。"
                    : "用于与上一层记录和最终统计结果继续对账。");
        } catch (RuntimeException exception) {
            result.put("status", "NEEDS_MANUAL_EVIDENCE");
            result.put("error", safeMessage(exception));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> manual(String stage, String reason) {
        return Map.of("stage", stage, "status", "NEEDS_MANUAL_EVIDENCE", "reason", reason);
    }

    private static String caseQuery(String sql, String field, List<String> values) {
        return "SELECT * FROM (\n" + trimSemicolon(sql)
                + "\n) AS __DIAG_SOURCE WHERE CONVERT(NVARCHAR(200), [" + field
                + "]) IN (" + values.stream().map(DiagnosisCaseEvidenceService::literal)
                        .collect(java.util.stream.Collectors.joining(",")) + ")";
    }

    private static Map<String, Object> display(List<Map<String, Object>> stages) {
        List<String> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            String name = text(stage.get("stage"));
            if ("COMPLETED".equals(stage.get("status"))) {
                long rows = stage.get("rowCount") instanceof Number number
                        ? number.longValue() : 0L;
                if (rows > 0) found.add(name + "：查到" + rows + "条记录");
                else notFound.add(name + "：没有查到该记录");
            } else {
                unfinished.add(name + "：查询未完成，请展开技术详情");
            }
        }
        String conclusion;
        String nextAction;
        if (!unfinished.isEmpty()) {
            conclusion = "自动取证没有全部完成，目前不能判断这条记录是否计算正确。";
            nextAction = "先修复未完成的查询，或在下方粘贴从 Navicat 查到的结果。";
        } else if (!notFound.isEmpty()) {
            conclusion = "数据链路中有环节没有找到这条记录，需要核对记录号、统计时间或数据同步。";
            nextAction = "确认记录号和统计窗口后，重点检查没有查到记录的环节。";
        } else {
            conclusion = "业务库、真实库和最终统计都取得了证据，可以继续判断该记录是否进入分母和分子。";
            nextAction = "核对下面的记录字段；证据一致就确认计算正确，不一致再填写具体原因。";
        }
        return Map.of(
                "found", List.copyOf(found),
                "notFound", List.copyOf(notFound),
                "unfinished", List.copyOf(unfinished),
                "conclusion", conclusion,
                "nextAction", nextAction);
    }

    private static String trimSemicolon(String value) {
        return text(value).replaceFirst(";+\\s*$", "");
    }

    private static String literal(String value) {
        return "N'" + text(value).replace("'", "''") + "'";
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName()
                : value.replaceAll("(?i)(password|pwd|token)=[^;\\s]+", "$1=***");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Double nullableDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return text(value).isBlank() ? null : Double.valueOf(text(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long nullableLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return text(value).isBlank() ? null : Long.valueOf(text(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
    }

    private static boolean meetsNumerator(Map<String, Object> row) {
        Object value = row.get(MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN);
        if (value instanceof Number number) return number.intValue() == 1;
        return value != null && ("1".equals(value.toString().strip())
                || "true".equalsIgnoreCase(value.toString().strip()));
    }

    private static long requiredCount(
            Map<String, Object> primary,
            Map<String, Object> fallback,
            String key,
            String label) {
        Object value = primary.get(key) != null ? primary.get(key) : fallback.get(key);
        if (value instanceof Number number) return number.longValue();
        throw error("DIAGNOSIS_DETAIL_CONTEXT_MISSING",
                "本次排查没有冻结" + label + "数量，请重新执行基础校验。",
                HttpStatus.CONFLICT);
    }

    private static LocalDateTime parseTime(String value) {
        try {
            String normalized = text(value).replace(' ', 'T');
            return normalized.length() <= 10
                    ? LocalDate.parse(normalized).atStartOfDay()
                    : LocalDateTime.parse(normalized);
        } catch (RuntimeException exception) {
            throw error("DIAGNOSIS_DETAIL_TIME_INVALID",
                    "本次排查的统计时间无效，请重新创建排查任务。",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static IndicatorDetailException error(
            String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }

    private static Map<String, Object> frozenCalculation(DiagnosisCaseSnapshot snapshot) {
        return snapshot.gateResults().stream()
                .filter(item -> number(item.get("gate")) == 2)
                .map(item -> map(item.get("facts")))
                .map(item -> map(item.get("executionEvidence")))
                .findFirst()
                .orElse(Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? (Map<String, Object>) source : Map.of();
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
                .map(DiagnosisCaseEvidenceService::map).toList();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(DiagnosisCaseEvidenceService::text)
                    .filter(item -> !item.isBlank()).toList();
        }
        String item = text(value);
        return item.isBlank() ? List.of() : List.of(item);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }
}
