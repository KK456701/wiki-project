package com.hospital.wikiagent.agent.initialization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.Decision;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.ProfileValidation;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.ValidationItem;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer.JoinEdge;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer.SqlLineage;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.IndicatorDataFlowTypeResolver;
import com.hospital.wikiagent.agent.mras.IndicatorDataFlowTypeResolver.FlowType;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataCatalogClient;

/**
 * 负责批量指标抽取前后的确定性数据初始化校验。它从当前生效知识库 SQL 提取物理依赖，
 * 只向业务库和真实库发送只读聚合探针，并为每个口径生成可执行、无样本或阻断决策。
 * 本类型不修改抽取服务、不判断医院质量阈值，也不会用模型猜测无法可靠解析的血缘关系。
 */
@Component
public class BatchDataInitializationValidator {
    private static final Pattern OUTPUT_ALIAS = Pattern.compile(
            "(?i)\\bAS\\s+\\[?([A-Za-z_][A-Za-z0-9_]*)]?");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|pwd|token|secret)\\s*[:=]\\s*[^,;\\s}]+"
    );
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@"
    );
    private static final Pattern UNRESOLVED_OUTPUT = Pattern.compile(
            "查询块\\s+(.+?)\\s+/\\s+输出字段\\s+([^\\s；]+)\\s+无法追溯"
    );
    private final EntityPageParser entities;
    private final MrasSqlLineageAnalyzer lineageAnalyzer;
    private final MrasTemplateRenderer templateRenderer;
    private final MrasParameterMapper parameterMapper;
    private final ReadOnlySqlValidator sqlValidator;
    private final SqlParameterBinder parameterBinder;
    private final MetadataCatalogClient metadata;
    private final IndicatorDatabaseQueryClient query;
    private final DbHubProperties dbHub;
    private final KnowledgeDataDictionary dictionary;
    private final IndicatorDataFlowTypeResolver flowTypes;

    public BatchDataInitializationValidator(
            EntityPageParser entities,
            MrasSqlLineageAnalyzer lineageAnalyzer,
            MrasTemplateRenderer templateRenderer,
            MrasParameterMapper parameterMapper,
            ReadOnlySqlValidator sqlValidator,
            SqlParameterBinder parameterBinder,
            MetadataCatalogClient metadata,
            IndicatorDatabaseQueryClient query,
            DbHubProperties dbHub,
            KnowledgeDataDictionary dictionary,
            IndicatorDataFlowTypeResolver flowTypes) {
        this.entities = entities;
        this.lineageAnalyzer = lineageAnalyzer;
        this.templateRenderer = templateRenderer;
        this.parameterMapper = parameterMapper;
        this.sqlValidator = sqlValidator;
        this.parameterBinder = parameterBinder;
        this.metadata = metadata;
        this.query = query;
        this.dbHub = dbHub;
        this.dictionary = dictionary;
        this.flowTypes = flowTypes;
    }

    public InitializationValidationReport validate(
            String batchRunId,
            String hospitalId,
            List<ValidationTarget> targets,
            LocalDateTime start,
            LocalDateTime end,
            String statStart,
            String statEnd) {
        return validate(batchRunId, hospitalId, targets, start, end, statStart, statEnd,
                ignored -> { });
    }

    public InitializationValidationReport validate(
            String batchRunId,
            String hospitalId,
            List<ValidationTarget> targets,
            LocalDateTime start,
            LocalDateTime end,
            String statStart,
            String statEnd,
            Consumer<ValidationProgress> progress) {
        long started = System.currentTimeMillis();
        progress.accept(new ValidationProgress("LINEAGE", "正在解析口径依赖", 0, targets.size()));
        List<WorkProfile> work = targets.stream()
                .map(target -> prepare(target, start, end))
                .toList();
        List<ValidationItem> items = new ArrayList<>();

        int structureChecks = requiredTables(work, DatabaseRole.BUSINESS).size()
                + requiredTables(work, DatabaseRole.REAL).size();
        progress.accept(new ValidationProgress("SCHEMA", "正在检查双库表和字段", 0, structureChecks));
        CatalogSnapshot business = loadCatalog(
                DatabaseRole.BUSINESS, requiredTables(work, DatabaseRole.BUSINESS));
        CatalogSnapshot real = loadCatalog(
                DatabaseRole.REAL, requiredTables(work, DatabaseRole.REAL));
        progress.accept(new ValidationProgress("SCHEMA", "双库表和字段检查完成",
                structureChecks, structureChecks));
        if (business.error() != null) {
            items.add(connectionFailure(DatabaseRole.BUSINESS, statStart, statEnd, business.error()));
        }
        if (real.error() != null) {
            items.add(connectionFailure(DatabaseRole.REAL, statStart, statEnd, real.error()));
        }

        progress.accept(new ValidationProgress("BUSINESS_DATA", "正在检查业务库数据质量", 0,
                requiredTables(work, DatabaseRole.BUSINESS).size()));
        Map<String, TableStats> businessStats = business.error() == null
                ? loadBusinessStats(work, business, items, statStart, statEnd) : Map.of();
        Map<String, JoinStats> joinStats = business.error() == null
                ? loadJoinStats(work, business, items, statStart, statEnd) : Map.of();
        Map<String, SourceCount> sourceCounts = business.error() == null
                ? loadSourceCounts(work, items, start, end, statStart, statEnd) : Map.of();
        int businessObjects = requiredTables(work, DatabaseRole.BUSINESS).size();
        progress.accept(new ValidationProgress("BUSINESS_DATA", "业务库数据质量检查完成",
                businessObjects, businessObjects));

        progress.accept(new ValidationProgress("SUMMARY", "正在汇总逐口径校验结果", 0, work.size()));
        List<ProfileValidation> profiles = new ArrayList<>();
        int summarized = 0;
        for (WorkProfile profile : work) {
            List<String> blockers = new ArrayList<>();
            FlowType flowType = profile.entity() == null
                    ? FlowType.INCOMPLETE : flowTypes.resolve(profile.entity());
            if (flowType == FlowType.INCOMPLETE) {
                String message = "知识库明确未配置可执行概览 SQL，本口径未实现，本次跳过。";
                items.add(item("NOT_IMPLEMENTED", "SKIPPED", DatabaseRole.REAL, profile,
                        "", "", "", "当前口径", statStart, statEnd,
                        null, null, null, null, null, null, false, "跳过",
                        "PROFILE_NOT_IMPLEMENTED", message, "", Map.of(), 0, null, ""));
                profiles.add(new ProfileValidation(
                        profile.target().ruleId(), profile.target().ruleName(),
                        profile.target().profileId(), profile.target().profileLabel(),
                        Decision.SKIPPED, "PROFILE_NOT_IMPLEMENTED", message, null,
                        flowType.name()));
                progress.accept(new ValidationProgress("SUMMARY", "正在汇总逐口径校验结果",
                        ++summarized, work.size()));
                continue;
            }
            if (profile.entity() == null) {
                blockers.add("知识库实体不存在");
                items.add(item("UNSUPPORTED", "BLOCKED", DatabaseRole.BUSINESS, profile,
                        "", "", "", "当前口径", statStart, statEnd,
                        null, null, null, null, null, null, true, "阻断",
                        "INIT_LINEAGE_UNCERTAIN", "知识库中没有可解析的指标实体。",
                        "", Map.of(), 0, null, ""));
            } else if (flowType != FlowType.DIRECT_REAL_QUERY
                    && (profile.entity().targetTable() == null
                    || profile.entity().targetTable().isBlank())) {
                blockers.add("知识库未配置真实库目标表");
                items.add(item("UNSUPPORTED", "BLOCKED", DatabaseRole.REAL, profile,
                        "", "", "", "当前口径", statStart, statEnd,
                        null, null, null, null, null, null, true, "阻断",
                        "INIT_LINEAGE_UNCERTAIN",
                        "知识库实体未配置真实库目标表，无法验证抽取落表结构。",
                        "", Map.of(), 0, null, ""));
            }
            if (business.error() != null && flowType != FlowType.DIRECT_REAL_QUERY) {
                blockers.add("业务库不可访问");
            }
            if (real.error() != null) blockers.add("真实库不可访问");

            if (flowType != FlowType.DIRECT_REAL_QUERY) {
                inspectStructure(profile, profile.sourceLineage(), business, DatabaseRole.BUSINESS,
                        blockers, items, statStart, statEnd);
            }
            inspectStructure(profile, profile.overviewLineage(), real, DatabaseRole.REAL,
                    blockers, items, statStart, statEnd);
            if (flowType != FlowType.DIRECT_REAL_QUERY) {
                appendDataFindings(profile, businessStats, joinStats, items, statStart, statEnd);
            } else {
                items.add(item("UPSTREAM_NOT_REGISTERED", "WARNING", DatabaseRole.REAL, profile,
                        "", "", "", "真实库已有表", statStart, statEnd,
                        null, null, null, null, null, null, false, "继续",
                        "INIT_UPSTREAM_SYNC_NOT_REGISTERED",
                        "本指标直接查询真实库已有表；当前知识库未登记其上游同步 SQL。",
                        "", Map.of(), 0, null, ""));
            }

            SourceCount sourceCount = sourceCounts.get(profile.target().profileId());
            if (sourceCount != null && sourceCount.error() != null) {
                items.add(item("UNSUPPORTED", "WARNING", DatabaseRole.BUSINESS, profile,
                        "", "", "", "本次统计窗口", statStart, statEnd,
                        null, null, null, null, null, null, false, "继续",
                        "INIT_WINDOW_PROBE_UNSUPPORTED", sourceCount.error(),
                        sourceCount.sql(), sourceCount.parameters(), sourceCount.durationMs(),
                        sourceCount.returnedRows(), sourceCount.databaseError()));
            }

            Decision decision;
            String errorCode = "";
            String message = "初始化校验通过";
            if (!blockers.isEmpty()) {
                decision = Decision.BLOCKED;
                errorCode = blockerCode(items, profile.target().profileId());
                message = String.join("；", blockers);
            } else if (sourceCount != null && Long.valueOf(0L).equals(sourceCount.count())) {
                decision = Decision.NO_SAMPLE;
                errorCode = "INIT_NO_SOURCE_DATA";
                message = "当前统计窗口内源查询无可用数据。";
                items.add(item("NO_DATA", "NO_SAMPLE", DatabaseRole.BUSINESS, profile,
                        firstTable(profile.sourceLineage()), "", "", "本次统计窗口",
                        statStart, statEnd, 0L, 0L, null, null, null, null,
                        false, "无样本", errorCode, message,
                        sourceCount.sql(), sourceCount.parameters(), sourceCount.durationMs(),
                        sourceCount.returnedRows(), ""));
            } else {
                decision = Decision.RUNNABLE;
            }
            profiles.add(new ProfileValidation(
                    profile.target().ruleId(), profile.target().ruleName(),
                    profile.target().profileId(), profile.target().profileLabel(),
                    decision, errorCode, message,
                    sourceCount == null ? null : sourceCount.count(), flowType.name()));
            progress.accept(new ValidationProgress("SUMMARY", "正在汇总逐口径校验结果",
                    ++summarized, work.size()));
        }

        long blocked = profiles.stream().filter(p -> p.decision() == Decision.BLOCKED).count();
        boolean warnings = items.stream().anyMatch(item -> "WARNING".equals(item.severity()));
        String quality = blocked == profiles.size() && !profiles.isEmpty()
                ? "ALL_BLOCKED" : blocked > 0 ? "PARTIAL_BLOCKED" : warnings ? "WARNING" : "NORMAL";
        progress.accept(new ValidationProgress("DONE", "数据初始化校验完成", work.size(), work.size()));
        return new InitializationValidationReport(
                batchRunId, hospitalId, statStart, statEnd,
                System.currentTimeMillis() - started, quality,
                business.error() == null, real.error() == null,
                List.copyOf(profiles), groupEvidenceItems(items));
    }

    public RealSnapshotValidation validateRealSnapshot(
            ValidationTarget target,
            Long businessSourceCount,
            LocalDateTime start,
            LocalDateTime end) {
        long started = System.currentTimeMillis();
        WorkProfile profile = prepare(target, start, end);
        if (profile.entity() == null) {
            return new RealSnapshotValidation(false, "INIT_LINEAGE_UNCERTAIN",
                    "无法确定真实库目标表。", Map.of());
        }
        String physicalTable = physicalTargetTable(profile.entity(), target.profileId());
        Set<String> availableFields = new LinkedHashSet<>();
        try {
            for (Map<String, Object> row : metadata.listColumns(
                    DatabaseRole.REAL,
                    dbHub.realSource().getDatabaseName(),
                    dbHub.realSource().getSchemaName(),
                    physicalTable)) {
                availableFields.add(upper(value(row, "COLUMN_NAME")));
            }
        } catch (RuntimeException ignored) {
            // 行数一致性仍可继续；字段元数据失败会在输出中体现为空值检查未覆盖。
        }
        List<String> criticalFields = profile.overviewLineage().fieldsByTable()
                .getOrDefault(upper(profile.entity().targetTable()), List.of()).stream()
                .filter(availableFields::contains)
                .toList();
        String sql = nullStatsSql(
                dbHub.realSource().getSchemaName(), physicalTable, criticalFields);
        long queryStarted = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = query.execute(DatabaseRole.REAL, sql);
            Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
            long realCount = number(row, "total_count");
            boolean mismatch = businessSourceCount != null && businessSourceCount != realCount;
            List<Map<String, Object>> nullRates = new ArrayList<>();
            for (int index = 0; index < criticalFields.size(); index++) {
                long nullCount = number(row, "null_" + index);
                if (nullCount > 0) {
                    String fieldLabel = dictionary.fieldLabel(
                            profile.entity().targetTable(), criticalFields.get(index));
                    nullRates.add(Map.of(
                            "fieldName", criticalFields.get(index),
                            "fieldLabel", fieldLabel == null ? "" : fieldLabel,
                            "nullCount", nullCount,
                            "totalCount", realCount,
                            "rate", realCount == 0 ? 0D : (double) nullCount / realCount));
                }
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ruleId", target.ruleId());
            output.put("ruleName", target.ruleName());
            output.put("profileId", target.profileId());
            output.put("profileLabel", target.profileLabel());
            output.put("databaseRole", DatabaseRole.REAL.value());
            output.put("tableName", physicalTable);
            output.put("businessSourceCount",
                    businessSourceCount == null ? "未生成" : businessSourceCount);
            output.put("realRowCount", realCount);
            output.put("nullRates", List.copyOf(nullRates));
            output.put("matched", !mismatch);
            output.put("sql", sql);
            output.put("parameters", Map.of(
                    "statStart", start.toString(), "statEnd", end.toString()));
            output.put("durationMs", System.currentTimeMillis() - started);
            output.put("queryDurationMs", System.currentTimeMillis() - queryStarted);
            output.put("returnedRows", rows.size());
            output.put("reused", false);
            if (mismatch) {
                String code = businessSourceCount > 0 && realCount == 0
                        ? "REAL_EXTRACTION_EMPTY" : "REAL_EXTRACTION_COUNT_MISMATCH";
                output.put("errorCode", code);
                output.put("message", "业务库源查询行数与真实库本次写入行数不一致。");
                return new RealSnapshotValidation(false, code,
                        String.valueOf(output.get("message")), Map.copyOf(output));
            }
            output.put("qualityStatus", nullRates.isEmpty() ? "NORMAL" : "WARNING");
            return new RealSnapshotValidation(true, "", "真实库本次数据校验通过。",
                    Map.copyOf(output));
        } catch (RuntimeException exception) {
            return new RealSnapshotValidation(false, "REAL_SNAPSHOT_VALIDATION_FAILED",
                    "真实库本次数据校验失败：" + safeError(exception), Map.of(
                            "ruleId", target.ruleId(),
                            "profileId", target.profileId(),
                            "tableName", physicalTable,
                            "sql", sql,
                            "durationMs", System.currentTimeMillis() - started,
                            "databaseError", safeError(exception)));
        }
    }

    private WorkProfile prepare(
            ValidationTarget target, LocalDateTime start, LocalDateTime end) {
        EntityPageData entity = entities.getEntity(target.profileId());
        if (entity == null) entity = entities.getEntity(target.ruleId());
        if (entity == null) return new WorkProfile(target, null, emptyLineage(), emptyLineage(), "", "");
        Map<String, Object> params = parameterMapper.mapTimeOnly(start, end);
        String source = templateRenderer.renderTemplate(stripSourceEnvelope(entity.sourceTableSql()),
                Map.of("syncType", "outHosp"));
        String overview = templateRenderer.renderTemplate(entity.overviewSql(), params);
        SqlLineage overviewLineage = addTargetOutputFields(
                lineageAnalyzer.analyze(overview), entity, source, overview);
        return new WorkProfile(target, entity, lineageAnalyzer.analyze(source),
                overviewLineage, source, overview);
    }

    private CatalogSnapshot loadCatalog(DatabaseRole role, Set<String> requiredTables) {
        DbHubProperties.Source source = role == DatabaseRole.BUSINESS
                ? dbHub.businessSource() : dbHub.realSource();
        try {
            Set<String> tables = new LinkedHashSet<>();
            for (Map<String, Object> row : metadata.listTables(
                    role, source.getDatabaseName(), source.getSchemaName())) {
                tables.add(upper(value(row, "TABLE_NAME")));
            }
            Map<String, Set<String>> columns = new LinkedHashMap<>();
            Map<String, String> columnErrors = new LinkedHashMap<>();
            for (String table : requiredTables) {
                if (!tables.contains(table)) continue;
                try {
                    for (Map<String, Object> row : metadata.listColumns(
                            role, source.getDatabaseName(), source.getSchemaName(), table)) {
                        columns.computeIfAbsent(
                                upper(value(row, "TABLE_NAME")),
                                ignored -> new LinkedHashSet<>())
                                .add(upper(value(row, "COLUMN_NAME")));
                    }
                    columns.computeIfAbsent(table, ignored -> new LinkedHashSet<>());
                } catch (RuntimeException exception) {
                    columnErrors.put(table, safeError(exception));
                }
            }
            return new CatalogSnapshot(
                    Set.copyOf(tables), immutableSetMap(columns),
                    Map.copyOf(columnErrors), null);
        } catch (RuntimeException exception) {
            return new CatalogSnapshot(Set.of(), Map.of(), Map.of(), safeError(exception));
        }
    }

    private static Set<String> requiredTables(
            List<WorkProfile> work, DatabaseRole role) {
        Set<String> result = new LinkedHashSet<>();
        for (WorkProfile profile : work) {
            SqlLineage lineage = role == DatabaseRole.BUSINESS
                    ? profile.sourceLineage() : profile.overviewLineage();
            result.addAll(lineage.tables());
        }
        return Set.copyOf(result);
    }

    private Map<String, TableStats> loadBusinessStats(
            List<WorkProfile> work,
            CatalogSnapshot catalog,
            List<ValidationItem> items,
            String statStart,
            String statEnd) {
        Map<String, Set<String>> fieldsByTable = new LinkedHashMap<>();
        for (WorkProfile profile : work) {
            profile.sourceLineage().fieldsByTable().forEach((table, fields) -> {
                if (catalog.tables().contains(table)) {
                    fieldsByTable.computeIfAbsent(table, ignored -> new LinkedHashSet<>())
                            .addAll(fields.stream().filter(field -> catalog.hasColumn(table, field)).toList());
                }
            });
            for (String table : profile.sourceLineage().tables()) {
                if (catalog.tables().contains(table)) {
                    fieldsByTable.computeIfAbsent(table, ignored -> new LinkedHashSet<>());
                }
            }
        }
        Map<String, TableStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : fieldsByTable.entrySet()) {
            String sql = nullStatsSql(dbHub.businessSource().getSchemaName(), entry.getKey(), entry.getValue());
            long started = System.currentTimeMillis();
            try {
                List<Map<String, Object>> rows = query.execute(DatabaseRole.BUSINESS, sql);
                Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
                Map<String, Long> nulls = new LinkedHashMap<>();
                int index = 0;
                for (String field : entry.getValue()) {
                    nulls.put(field, number(row, "null_" + index++));
                }
                result.put(entry.getKey(), new TableStats(
                        number(row, "total_count"), Map.copyOf(nulls), sql,
                        System.currentTimeMillis() - started, (long) rows.size(), ""));
            } catch (RuntimeException exception) {
                result.put(entry.getKey(), new TableStats(
                        null, Map.of(), sql, System.currentTimeMillis() - started,
                        null, safeError(exception)));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, JoinStats> loadJoinStats(
            List<WorkProfile> work,
            CatalogSnapshot catalog,
            List<ValidationItem> items,
            String statStart,
            String statEnd) {
        Map<String, JoinEdge> edges = new LinkedHashMap<>();
        for (WorkProfile profile : work) {
            for (JoinEdge edge : profile.sourceLineage().joins()) {
                if (catalog.hasColumn(edge.baseTable(), edge.baseField())
                        && catalog.hasColumn(edge.joinedTable(), edge.joinedField())) {
                    edges.putIfAbsent(edgeKey(edge), edge);
                }
            }
        }
        Map<String, JoinStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, JoinEdge> entry : edges.entrySet()) {
            String sql = joinCoverageSql(dbHub.businessSource().getSchemaName(), entry.getValue());
            long started = System.currentTimeMillis();
            try {
                List<Map<String, Object>> rows = query.execute(DatabaseRole.BUSINESS, sql);
                Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
                result.put(entry.getKey(), new JoinStats(
                        number(row, "base_count"), number(row, "matched_count"), sql,
                        System.currentTimeMillis() - started, (long) rows.size(), ""));
            } catch (RuntimeException exception) {
                result.put(entry.getKey(), new JoinStats(
                        null, null, sql, System.currentTimeMillis() - started,
                        null, safeError(exception)));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, SourceCount> loadSourceCounts(
            List<WorkProfile> work,
            List<ValidationItem> items,
            LocalDateTime start,
            LocalDateTime end,
            String statStart,
            String statEnd) {
        Map<String, SourceCount> bySql = new LinkedHashMap<>();
        Map<String, SourceCount> result = new LinkedHashMap<>();
        Map<String, Object> params = parameterMapper.mapTimeOnly(start, end);
        for (WorkProfile profile : work) {
            if (profile.entity() == null || !profile.sourceLineage().certain()) continue;
            if (flowTypes.resolve(profile.entity()) == FlowType.DIRECT_REAL_QUERY
                    || profile.sourceSql() == null || profile.sourceSql().isBlank()) continue;
            String rendered = profile.sourceSql();
            ReadOnlySqlValidator.ValidationResult validation = sqlValidator.validateReadOnly(rendered);
            if (!validation.ok()) {
                result.put(profile.target().profileId(), SourceCount.unsupported(
                        "源查询无法生成统计窗口探针：" + validation.message(), rendered, params));
                continue;
            }
            String bound;
            try {
                bound = parameterBinder.bind(rendered, params);
            } catch (RuntimeException exception) {
                result.put(profile.target().profileId(), SourceCount.unsupported(
                        "源查询参数绑定失败：" + exception.getMessage(), rendered, params));
                continue;
            }
            String countSql = wrapCount(bound);
            if (countSql == null) {
                result.put(profile.target().profileId(), SourceCount.unsupported(
                        "暂不支持为该 CTE/排序结构生成窗口数据量探针。", rendered, params));
                continue;
            }
            SourceCount cached = bySql.get(countSql);
            if (cached == null) {
                long queryStarted = System.currentTimeMillis();
                try {
                    List<Map<String, Object>> rows = query.execute(DatabaseRole.BUSINESS, countSql);
                    cached = new SourceCount(rows.isEmpty() ? 0L : number(rows.get(0), "row_count"),
                            countSql, Map.copyOf(params), System.currentTimeMillis() - queryStarted,
                            (long) rows.size(), "", null);
                } catch (RuntimeException exception) {
                    cached = new SourceCount(null, countSql, Map.copyOf(params),
                            System.currentTimeMillis() - queryStarted, null,
                            safeError(exception), "窗口数据量查询执行失败。" );
                }
                bySql.put(countSql, cached);
            }
            result.put(profile.target().profileId(), cached);
        }
        return Map.copyOf(result);
    }

    private void inspectStructure(
            WorkProfile profile,
            SqlLineage lineage,
            CatalogSnapshot catalog,
            DatabaseRole role,
            List<String> blockers,
            List<ValidationItem> items,
            String statStart,
            String statEnd) {
        if (profile.entity() == null || catalog.error() != null) return;
        if (!lineage.certain()) {
            blockers.add((role == DatabaseRole.BUSINESS ? "业务库" : "真实库") + "依赖无法可靠解析");
            items.add(item("UNSUPPORTED", "BLOCKED", role, profile,
                    "", "", "", "当前口径", statStart, statEnd,
                    null, null, null, null, null, null, true, "阻断",
                    "INIT_LINEAGE_UNCERTAIN", String.join("；", lineage.warnings()),
                    role == DatabaseRole.BUSINESS ? profile.sourceSql() : profile.overviewSql(),
                    Map.of(), 0, null, ""));
            return;
        }
        for (String warning : lineage.unresolvedReferences()) {
            items.add(item("UNSUPPORTED", "WARNING", role, profile,
                    "", "", "", "当前口径", statStart, statEnd,
                    null, null, null, null, null, null, false, "继续",
                    "INIT_ALIAS_SCOPE_UNCERTAIN",
                    warning + "；已跳过该字段的空值率或关联覆盖检查，不代表数据库异常。",
                    role == DatabaseRole.BUSINESS ? profile.sourceSql() : profile.overviewSql(),
                    Map.of(), 0, null, ""));
        }
        for (String table : lineage.tables()) {
            if (!catalog.tables().contains(table)) {
                blockers.add("缺少表 " + table);
                items.add(item("MISSING_TABLE", "BLOCKED", role, profile,
                        table, "", "", "数据库结构", statStart, statEnd,
                        null, null, null, null, null, null, true, "阻断",
                        "INIT_MISSING_TABLE", "数据库中不存在该表。",
                        metadataTableSql(role, table), Map.of(), 0, 0L, ""));
            } else if (catalog.columnErrors().containsKey(table)) {
                blockers.add("字段元数据读取失败 " + table);
                items.add(item("UNSUPPORTED", "BLOCKED", role, profile,
                        table, "", "", "数据库结构", statStart, statEnd,
                        null, null, null, null, null, null, true, "阻断",
                        "INIT_LINEAGE_UNCERTAIN", "无法读取该表的字段元数据。",
                        metadataColumnSql(role, table, "*"), Map.of(), 0, null,
                        catalog.columnErrors().get(table)));
            }
        }
        lineage.fieldsByTable().forEach((table, fields) -> {
            if (!catalog.tables().contains(table)
                    || catalog.columnErrors().containsKey(table)) return;
            for (String field : fields) {
                if (!catalog.hasColumn(table, field)) {
                    blockers.add("缺少字段 " + table + "." + field);
                    items.add(item("MISSING_COLUMN", "BLOCKED", role, profile,
                            table, field, dictionary.fieldLabel(table, field),
                            "数据库结构", statStart, statEnd,
                            null, null, null, null, null, null, true, "阻断",
                            "INIT_MISSING_COLUMN", "数据库中不存在该字段。",
                            metadataColumnSql(role, table, field), Map.of(), 0, 0L, ""));
                }
            }
        });
    }

    private void appendDataFindings(
            WorkProfile profile,
            Map<String, TableStats> tableStats,
            Map<String, JoinStats> joinStats,
            List<ValidationItem> items,
            String statStart,
            String statEnd) {
        if (profile.entity() == null) return;
        for (String table : profile.sourceLineage().tables()) {
            TableStats stats = tableStats.get(table);
            if (stats == null) continue;
            if (!stats.error().isBlank()) {
                items.add(item("UNSUPPORTED", "WARNING", DatabaseRole.BUSINESS, profile,
                        table, "", "", "全表", statStart, statEnd,
                        null, null, null, null, null, null, false, "继续",
                        "INIT_DATA_PROBE_FAILED", "表数据量和空值检查执行失败。",
                        stats.sql(), Map.of(), stats.durationMs(), stats.returnedRows(), stats.error()));
                continue;
            }
            if (Long.valueOf(0L).equals(stats.total())) {
                items.add(item("NO_DATA", "WARNING", DatabaseRole.BUSINESS, profile,
                        table, "", "", "全表", statStart, statEnd,
                        0L, 0L, null, null, null, null, false, "继续",
                        "INIT_EMPTY_TABLE", "物理源表当前没有任何记录。",
                        stats.sql(), Map.of(), stats.durationMs(), stats.returnedRows(), ""));
            }
            profile.sourceLineage().fieldsByTable().getOrDefault(table, List.of())
                    .forEach(field -> {
                        Long nullCount = stats.nulls().get(field);
                        if (nullCount != null && nullCount > 0 && stats.total() != null) {
                            double rate = stats.total() == 0 ? 0D
                                    : nullCount.doubleValue() / stats.total().doubleValue();
                            List<String> roles = profile.sourceLineage().roles(table, field)
                                    .stream().map(Enum::name).toList();
                            boolean displayOnly = !roles.isEmpty()
                                    && roles.stream().allMatch("SELECT_ONLY"::equals);
                            items.add(item("NULL_RATE", "WARNING", DatabaseRole.BUSINESS, profile,
                                    table, field, dictionary.fieldLabel(table, field), "全表",
                                    statStart, statEnd, null, stats.total(), nullCount,
                                    null, null, rate, false, "继续", "INIT_FIELD_HAS_NULL",
                                    displayOnly
                                            ? "该字段仅用于明细展示；空值不会改变本次分子、分母，但会造成明细信息不完整。"
                                            : "该字段参与时间、筛选、分子分母、去重、分组或关联；存在空值，需结合本院业务确认是否可接受。",
                                    stats.sql(), Map.of(), stats.durationMs(), stats.returnedRows(), ""));
                        }
                    });
        }
        for (JoinEdge edge : profile.sourceLineage().joins()) {
            JoinStats stats = joinStats.get(edgeKey(edge));
            if (stats == null) continue;
            if (!stats.error().isBlank()) {
                items.add(item("UNSUPPORTED", "WARNING", DatabaseRole.BUSINESS, profile,
                        edge.baseTable(), edge.baseField(),
                        dictionary.fieldLabel(edge.baseTable(), edge.baseField()), "全表关联",
                        statStart, statEnd, null, null, null, null, null, null,
                        false, "继续", "INIT_JOIN_PROBE_FAILED", "关联覆盖率检查执行失败。",
                        stats.sql(), Map.of(), stats.durationMs(), stats.returnedRows(), stats.error()));
                continue;
            }
            long unmatched = Math.max(0, stats.base() - stats.matched());
            if (unmatched > 0) {
                double rate = stats.base() == 0 ? 0D
                        : stats.matched().doubleValue() / stats.base().doubleValue();
                items.add(item("JOIN_COVERAGE", "WARNING", DatabaseRole.BUSINESS, profile,
                        edge.baseTable(), edge.baseField(),
                        dictionary.fieldLabel(edge.baseTable(), edge.baseField()), "全表关联",
                        statStart, statEnd, null, stats.base(), null, stats.matched(), unmatched,
                        rate, false, "继续", "INIT_JOIN_NOT_FULLY_COVERED",
                        "关联存在未匹配记录，未设置医院级合格阈值。",
                        stats.sql(), Map.of(), stats.durationMs(), stats.returnedRows(), ""));
            }
        }
    }

    private ValidationItem item(
            String category, String severity, DatabaseRole role, WorkProfile profile,
            String table, String field, String fieldLabel, String scope,
            String statStart, String statEnd, Long actual, Long total, Long nullCount,
            Long matched, Long unmatched, Double rate, boolean affects, String action,
            String code, String message, String sql, Map<String, Object> parameters,
            long duration, Long returnedRows, String databaseError) {
        ValidationTarget target = profile.target();
        List<String> fieldRoles = field.isBlank() ? List.of()
                : (role == DatabaseRole.BUSINESS ? profile.sourceLineage() : profile.overviewLineage())
                        .roles(table, field).stream().map(Enum::name).toList();
        String impactLevel = impactLevel(category, severity, affects, fieldRoles);
        String queryScope = queryScope(scope);
        String physicalObjectKey = physicalObjectKey(role, category, table, field, code, target);
        EvidenceGroup evidence = evidenceGroup(code, message, physicalObjectKey);
        return new ValidationItem(category, severity, role,
                target.ruleId(), target.ruleName(), target.profileId(), target.profileLabel(),
                table.isBlank() ? "" : dictionary.sourceSystem(table),
                table, field, fieldLabel, scope, statStart, statEnd,
                actual, total, nullCount, matched, unmatched, rate,
                affects, action, code, message, sql, parameters,
                duration, returnedRows, databaseError, impactLevel, fieldRoles,
                queryScope, physicalObjectKey, "DETERMINISTIC_SQL_PROBE",
                evidence.groupId(), evidence.summary(), evidence.queryBlockPaths(),
                evidence.unresolvedSymbols(), 1);
    }

    private ValidationItem connectionFailure(
            DatabaseRole role, String statStart, String statEnd, String error) {
        return new ValidationItem("DATABASE_CONNECTION", "BLOCKED", role,
                "", "", "", "", "", "", "", "", "数据库连接",
                statStart, statEnd, null, null, null, null, null, null,
                true, "阻断", "INIT_DATABASE_UNAVAILABLE",
                "数据库元数据读取失败。", "", Map.of(), 0, null, error,
                "CONFIRMED", List.of(), "DATABASE_CONNECTION",
                role.name() + "|DATABASE_CONNECTION", "DATABASE_METADATA",
                role.name() + "|DATABASE_CONNECTION", "数据库连接失败",
                List.of(), List.of(), 1);
    }

    private static EvidenceGroup evidenceGroup(
            String code, String message, String physicalObjectKey) {
        if (!"INIT_ALIAS_SCOPE_UNCERTAIN".equals(code)) {
            return new EvidenceGroup(physicalObjectKey, "", List.of(), List.of());
        }
        Matcher matcher = UNRESOLVED_OUTPUT.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return new EvidenceGroup(
                    physicalObjectKey, "派生计算字段无法追溯", List.of(), List.of());
        }
        return new EvidenceGroup(
                physicalObjectKey,
                "派生计算字段无法追溯",
                List.of(matcher.group(1).strip()),
                List.of(matcher.group(2).strip()));
    }

    /**
     * 同一口径、同一数据库侧的派生输出追溯失败属于一个根因。页面只展示一张问题卡，
     * 但查询块路径和未解析符号全部保留，不能通过去重把证据静默丢掉。
     */
    static List<ValidationItem> groupEvidenceItems(List<ValidationItem> source) {
        List<ValidationItem> result = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (ValidationItem item : source) {
            if (!"INIT_ALIAS_SCOPE_UNCERTAIN".equals(item.errorCode())) {
                result.add(item);
                continue;
            }
            String key = String.join("|",
                    item.databaseRole().name(), item.ruleId(),
                    item.profileId() == null ? "" : item.profileId(),
                    item.category(), item.errorCode());
            Integer position = positions.get(key);
            if (position == null) {
                positions.put(key, result.size());
                result.add(item);
                continue;
            }
            result.set(position, mergeEvidence(result.get(position), item));
        }
        return List.copyOf(result);
    }

    private static ValidationItem mergeEvidence(ValidationItem first, ValidationItem next) {
        Set<String> paths = new LinkedHashSet<>(first.queryBlockPaths());
        paths.addAll(next.queryBlockPaths());
        Set<String> symbols = new LinkedHashSet<>(first.unresolvedSymbols());
        symbols.addAll(next.unresolvedSymbols());
        int count = first.evidenceCount() + next.evidenceCount();
        String details = symbols.isEmpty()
                ? "" : "：" + String.join("、", symbols);
        String message = "派生计算字段无法追溯（" + count + "项）" + details
                + "；已跳过这些字段的空值率或关联覆盖检查，不代表数据库异常。";
        return new ValidationItem(
                first.category(), first.severity(), first.databaseRole(),
                first.ruleId(), first.ruleName(), first.profileId(), first.profileLabel(),
                first.sourceSystem(), first.tableName(), first.fieldName(), first.fieldLabel(),
                first.scope(), first.statStart(), first.statEnd(), first.actualCount(),
                first.totalCount(), first.nullCount(), first.matchedCount(), first.unmatchedCount(),
                first.rate(), first.affectsCalculation(), first.action(), first.errorCode(),
                message, first.sql(), first.parameters(), first.durationMs(), first.returnedRows(),
                first.databaseError(), first.impactLevel(), first.fieldRoles(), first.queryScope(),
                first.physicalObjectKey(), first.evidenceSource(), first.evidenceGroupId(),
                "派生计算字段无法追溯（" + count + "项）", List.copyOf(paths),
                List.copyOf(symbols), count);
    }

    private record EvidenceGroup(
            String groupId,
            String summary,
            List<String> queryBlockPaths,
            List<String> unresolvedSymbols) {}

    private static String impactLevel(
            String category, String severity, boolean affects, List<String> roles) {
        if (affects || "BLOCKED".equals(severity) || "NO_SAMPLE".equals(severity)) {
            return "CONFIRMED";
        }
        if ("UNSUPPORTED".equals(category) || "UPSTREAM_NOT_REGISTERED".equals(category)) {
            return "UNKNOWN";
        }
        if ("NULL_RATE".equals(category) && !roles.isEmpty()
                && roles.stream().allMatch("SELECT_ONLY"::equals)) {
            return "DISPLAY_ONLY";
        }
        if ("NULL_RATE".equals(category) || "JOIN_COVERAGE".equals(category)
                || "NO_DATA".equals(category)) return "POSSIBLE";
        return "NO_IMPACT";
    }

    private static String queryScope(String scope) {
        if (scope == null) return "UNKNOWN";
        if (scope.contains("本次统计窗口")) return "STAT_WINDOW";
        if (scope.contains("全表")) return "FULL_TABLE";
        if (scope.contains("结构")) return "SCHEMA";
        if (scope.contains("连接")) return "DATABASE_CONNECTION";
        return "PROFILE";
    }

    private static String physicalObjectKey(
            DatabaseRole role, String category, String table, String field,
            String code, ValidationTarget target) {
        if (table != null && !table.isBlank()) {
            return role.name() + "|" + category + "|" + upper(table) + "|" + upper(field);
        }
        return role.name() + "|" + category + "|" + code + "|" + target.profileId();
    }

    private String metadataTableSql(DatabaseRole role, String table) {
        DbHubProperties.Source source = role == DatabaseRole.BUSINESS
                ? dbHub.businessSource() : dbHub.realSource();
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '"
                + literal(source.getSchemaName()) + "' AND TABLE_NAME = '" + literal(table) + "'";
    }

    private String metadataColumnSql(DatabaseRole role, String table, String field) {
        DbHubProperties.Source source = role == DatabaseRole.BUSINESS
                ? dbHub.businessSource() : dbHub.realSource();
        return "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '"
                + literal(source.getSchemaName()) + "' AND TABLE_NAME = '" + literal(table)
                + "' AND COLUMN_NAME = '" + literal(field) + "'";
    }

    private static String nullStatsSql(String schema, String table, Collection<String> fields) {
        StringBuilder sql = new StringBuilder("SELECT COUNT_BIG(1) AS total_count");
        int index = 0;
        for (String field : fields) {
            sql.append(", SUM(CASE WHEN ").append(identifier(field))
                    .append(" IS NULL THEN 1 ELSE 0 END) AS null_").append(index++);
        }
        return sql.append(" FROM ").append(qualified(schema, table)).toString();
    }

    private static String joinCoverageSql(String schema, JoinEdge edge) {
        return "SELECT COUNT_BIG(1) AS base_count, COUNT_BIG(m.matched) AS matched_count FROM "
                + qualified(schema, edge.baseTable()) + " b OUTER APPLY (SELECT TOP 1 1 AS matched FROM "
                + qualified(schema, edge.joinedTable()) + " j WHERE b."
                + identifier(edge.baseField()) + " = j." + identifier(edge.joinedField()) + ") m";
    }

    private static String wrapCount(String sql) {
        String stripped = sql.strip().replaceFirst(";+\\s*$", "");
        if (stripped.regionMatches(true, 0, "SELECT", 0, 6)) {
            return "SELECT COUNT_BIG(1) AS row_count FROM (" + stripped + ") init_source";
        }
        return null;
    }

    private String physicalTargetTable(EntityPageData entity, String profileId) {
        List<EntityPageData> variants = entities.getVariants(entity.code());
        if (variants.size() <= 1) return upper(entity.targetTable());
        for (int index = 0; index < variants.size(); index++) {
            if (variants.get(index).variantCode().equals(profileId)) {
                return upper(entity.targetTable()) + "_" + (index + 1);
            }
        }
        return upper(entity.targetTable()) + "_1";
    }

    private static SqlLineage addTargetOutputFields(
            SqlLineage lineage, EntityPageData entity, String sourceSql, String overviewSql) {
        String targetTable = upper(entity.targetTable());
        if (targetTable.isBlank() || !lineage.tables().contains(targetTable)) return lineage;
        Set<String> candidates = new LinkedHashSet<>();
        // 目标表字段只来自抽取 SQL 最外层 SELECT。嵌套子查询中的 AS 是中间列，
        // 若把它们也算作目标字段，会把 SURGERY_GRADE_CODE 等中间字段误报为缺列。
        Matcher matcher = OUTPUT_ALIAS.matcher(topLevelSelectList(sourceSql));
        while (matcher.find()) {
            candidates.add(toSnakeUpper(matcher.group(1)));
        }
        Set<String> referenced = new LinkedHashSet<>();
        String upperOverview = upper(overviewSql);
        for (String candidate : candidates) {
            if (Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b")
                    .matcher(upperOverview).find()) {
                referenced.add(candidate);
            }
        }
        if (referenced.isEmpty()) return lineage;
        Map<String, List<String>> fields = new LinkedHashMap<>(lineage.fieldsByTable());
        Set<String> merged = new LinkedHashSet<>(fields.getOrDefault(targetTable, List.of()));
        merged.addAll(referenced);
        fields.put(targetTable, List.copyOf(merged));
        return new SqlLineage(lineage.tables(), Map.copyOf(fields), lineage.fieldRolesByTable(),
                lineage.joins(), lineage.ctes(), lineage.warnings(), lineage.unresolvedReferences());
    }

    static String topLevelSelectList(String sql) {
        if (sql == null || sql.isBlank()) return "";
        int depth = 0;
        int selectEnd = -1;
        boolean literal = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!literal && current == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!literal && current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (current == '\'') {
                if (literal && next == '\'') {
                    index++;
                } else {
                    literal = !literal;
                }
                continue;
            }
            if (literal) continue;
            if (current == '(') depth++;
            else if (current == ')') depth--;
            if (depth != 0) continue;
            if (selectEnd < 0 && keywordAt(sql, index, "SELECT")) {
                selectEnd = index + 6;
                index += 5;
            } else if (selectEnd >= 0 && keywordAt(sql, index, "FROM")) {
                return maskNestedExpressions(sql.substring(selectEnd, index));
            }
        }
        return selectEnd < 0 ? "" : maskNestedExpressions(sql.substring(selectEnd));
    }

    private static boolean keywordAt(String sql, int index, String keyword) {
        int end = index + keyword.length();
        if (end > sql.length() || !sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        boolean left = index == 0 || !Character.isLetterOrDigit(sql.charAt(index - 1))
                && sql.charAt(index - 1) != '_';
        boolean right = end == sql.length() || !Character.isLetterOrDigit(sql.charAt(end))
                && sql.charAt(end) != '_';
        return left && right;
    }

    private static String maskNestedExpressions(String selectList) {
        StringBuilder result = new StringBuilder(selectList.length());
        int depth = 0;
        boolean literal = false;
        for (int index = 0; index < selectList.length(); index++) {
            char current = selectList.charAt(index);
            char next = index + 1 < selectList.length() ? selectList.charAt(index + 1) : '\0';
            if (current == '\'') {
                if (literal && next == '\'') {
                    result.append("  ");
                    index++;
                    continue;
                }
                literal = !literal;
                result.append(' ');
                continue;
            }
            if (literal) {
                result.append(current == '\n' ? '\n' : ' ');
                continue;
            }
            if (current == '(') {
                depth++;
                result.append(' ');
            } else if (current == ')') {
                depth--;
                result.append(' ');
            } else {
                result.append(depth == 0 ? current : current == '\n' ? '\n' : ' ');
            }
        }
        return result.toString();
    }

    private static String toSnakeUpper(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String stripSourceEnvelope(String value) {
        String result = value == null ? "" : value.strip();
        if (result.startsWith("\"'") && result.endsWith("'\"") && result.length() > 4) {
            return result.substring(2, result.length() - 2).strip();
        }
        if (result.startsWith("'") && result.length() > 1) {
            result = result.substring(1).strip();
            if (result.endsWith("'")) result = result.substring(0, result.length() - 1).strip();
        }
        return result;
    }

    private static String blockerCode(List<ValidationItem> items, String profileId) {
        return items.stream()
                .filter(item -> profileId.equals(item.profileId()) && "BLOCKED".equals(item.severity()))
                .map(ValidationItem::errorCode).findFirst().orElse("INIT_VALIDATION_BLOCKED");
    }

    private static String firstTable(SqlLineage lineage) {
        return lineage.tables().isEmpty() ? "" : lineage.tables().get(0);
    }

    private static String edgeKey(JoinEdge edge) {
        return edge.baseTable() + "." + edge.baseField() + "->"
                + edge.joinedTable() + "." + edge.joinedField();
    }

    private static String qualified(String schema, String table) {
        return identifier(schema) + "." + identifier(table);
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法数据库标识符: " + value);
        }
        return "[" + value + "]";
    }

    private static String literal(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            }
        }
        return "";
    }

    private static long number(Map<String, Object> row, String key) {
        Object raw = null;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                raw = entry.getValue();
                break;
            }
        }
        if (raw == null) return 0L;
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String sanitized = SECRET_ASSIGNMENT.matcher(message)
                .replaceAll("$1=[已隐藏]");
        sanitized = URI_CREDENTIALS.matcher(sanitized)
                .replaceAll("$1[已隐藏]@");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000) + "…";
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> values) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        values.forEach((key, fields) -> result.put(key, Set.copyOf(fields)));
        return Map.copyOf(result);
    }

    private static SqlLineage emptyLineage() {
        return new SqlLineage(List.of(), Map.of(), Map.of(), List.of(), List.of(),
                List.of("知识库实体不存在"), List.of());
    }

    public record ValidationTarget(
            String ruleId, String ruleName, String profileId, String profileLabel) {}

    public record ValidationProgress(
            String phase, String message, int completed, int total) {}

    public record RealSnapshotValidation(
            boolean ok, String errorCode, String message, Map<String, Object> output) {}

    private record WorkProfile(
            ValidationTarget target,
            EntityPageData entity,
            SqlLineage sourceLineage,
            SqlLineage overviewLineage,
            String sourceSql,
            String overviewSql) {}

    private record CatalogSnapshot(
            Set<String> tables,
            Map<String, Set<String>> columns,
            Map<String, String> columnErrors,
            String error) {
        boolean hasColumn(String table, String field) {
            return columns.getOrDefault(table, Set.of()).contains(field);
        }
    }

    private record TableStats(
            Long total, Map<String, Long> nulls, String sql,
            long durationMs, Long returnedRows, String error) {}

    private record JoinStats(
            Long base, Long matched, String sql,
            long durationMs, Long returnedRows, String error) {}

    private record SourceCount(
            Long count, String sql, Map<String, Object> parameters,
            long durationMs, Long returnedRows, String databaseError, String error) {
        static SourceCount unsupported(
                String error, String sql, Map<String, Object> parameters) {
            return new SourceCount(null, sql, Map.copyOf(parameters), 0, null, "", error);
        }
    }
}
