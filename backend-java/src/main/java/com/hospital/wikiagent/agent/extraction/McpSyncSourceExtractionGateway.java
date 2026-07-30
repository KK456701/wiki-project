package com.hospital.wikiagent.agent.extraction;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dto.SyncDataDto;
import com.hospital.wikiagent.dto.TableDataDto;
import com.hospital.wikiagent.service.SyncDataService;

/**
 * 源数据抽取网关：将 {@link ExtractionRequest} 映射为 {@link SyncDataDto}，
 * 转调同事的 {@link SyncDataService#syncEventData} 完成清库 + 抽取 + 写入 winex_aima。
 *
 * <p>保留 {@link SourceExtractionGateway} 接口与上层 Workflow 调用结构不变，
 * 内部不再自行调 MCP / 拼 INSERT，全部委托给 SyncDataService。</p>
 */
@Component
@ConditionalOnProperty(prefix = "wiki.agent.extraction", name = "mode", havingValue = "required")
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class McpSyncSourceExtractionGateway implements SourceExtractionGateway {

    private static final Logger log = LoggerFactory.getLogger(McpSyncSourceExtractionGateway.class);

    private final SyncDataService syncDataService;

    public McpSyncSourceExtractionGateway(SyncDataService syncDataService) {
        this.syncDataService = syncDataService;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        try (SourceExtractionLease lease = prepare(request)) {
            return lease.result();
        }
    }

    @Override
    public SourceExtractionLease prepare(ExtractionRequest request) {
        String extractionId = "EXT_" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (request.hospitalSoid() == null) {
                return SourceExtractionLease.completed(failed(
                        extractionId, "EXTRACTION_HOSPITAL_SOID_MISSING",
                        "未配置业务 MCP 所需的医院 SOID。"));
            }

            Map<String, Object> contract = request.extractionContract();
            String eventTable = text(contract.get("event_table"));
            List<String> dependencyTables = strings(contract.get("dependency_tables"));

            String sourceSql = text(request.sourceSql());
            if (sourceSql.isBlank() || eventTable.isBlank()) {
                return SourceExtractionLease.completed(failed(
                        extractionId, "EXTRACTION_SQL_MISSING",
                        "抽取 SQL 或事件表名为空，无法执行。"));
            }

            // ---- 构建 SyncDataDto，委托 SyncDataService 完成全部抽取 ----
            SyncDataDto dto = new SyncDataDto();
            dto.setHospitalSOID(request.hospitalSoid());

            // eventDataList：核心制度事件表（sourceSql + 时间范围）
            TableDataDto eventData = new TableDataDto();
            eventData.setTable(eventTable);
            eventData.setSqlScript(sourceSql);
            eventData.setStartTime(toDate(request.statStart()));
            eventData.setEndTime(toDate(request.statEnd()));
            dto.setEventDataList(List.of(eventData));

            // bizDataList：依赖表（基础表 / 患者表由 SyncDataService 内部判断）
            if (!dependencyTables.isEmpty()) {
                List<TableDataDto> bizList = new ArrayList<>();
                for (String depTable : dependencyTables) {
                    if (depTable.equalsIgnoreCase(eventTable)) {
                        continue;
                    }
                    TableDataDto biz = new TableDataDto();
                    biz.setTable(depTable);
                    bizList.add(biz);
                }
                dto.setBizDataList(bizList);
            }

            // eventTableList：关联拓展事件（部分指标需要的额外患者事件表）
            List<TableDataDto> extList = buildEventTableList(contract, request);
            if (!extList.isEmpty()) {
                dto.setEventTableList(extList);
            }

            log.info("[{}] 开始抽取: eventTable={}, bizTables={} ({} ~ {})",
                    extractionId, eventTable, dependencyTables,
                    request.statStart(), request.statEnd());

            syncDataService.syncEventData(dto);

            ExtractionResult result = new ExtractionResult(
                    extractionId,
                    ExtractionResult.Status.SUCCESS,
                    0, 0, 0, 0,
                    Instant.now(),
                    request.idempotencyKey(),
                    "SNAP_" + UUID.randomUUID().toString().replace("-", ""),
                    "",
                    "已通过 SyncDataService 刷新真实库快照。");
            return SourceExtractionLease.completed(result);

        } catch (Exception exception) {
            log.error("[{}] 抽取失败: {}", extractionId, exception.getMessage(), exception);
            return SourceExtractionLease.completed(failed(
                    extractionId,
                    errorCode(exception),
                    safeMessage(exception)));
        }
    }

    // ==================== 辅助方法 ====================

    private static Date toDate(java.time.LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static ExtractionResult failed(String extractionId, String code, String message) {
        return new ExtractionResult(
                extractionId, ExtractionResult.Status.FAILED,
                0, 0, 0, 0, Instant.now(),
                "", "", code, message);
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof DbHubMcpException) {
            return "MCP_CALL_FAILED";
        }
        return "SOURCE_EXTRACTION_FAILED";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "源数据抽取失败。";
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<TableDataDto> buildEventTableList(
            Map<String, Object> contract, ExtractionRequest request) {
        Object extObj = contract.get("extended_events");
        if (!(extObj instanceof List<?> extList) || extList.isEmpty()) {
            return List.of();
        }
        List<TableDataDto> result = new ArrayList<>();
        for (Object item : extList) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String eventNo = text(map.get("eventNo"));
            String sqlScript = text(map.get("sqlScript"));
            if (eventNo.isBlank() || sqlScript.isBlank()) continue;
            TableDataDto extEvent = new TableDataDto();
            extEvent.setEventNo(eventNo);
            extEvent.setTable("MRAS_PATIENT_EVENT");
            extEvent.setSqlScript(sqlScript);
            extEvent.setStartTime(toDate(request.statStart()));
            extEvent.setEndTime(toDate(request.statEnd()));
            result.add(extEvent);
        }
        return result;
    }
}
