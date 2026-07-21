package com.hospital.wikiagent.agent.trace;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentTraceRepository {
    private final JdbcTemplate jdbc;

    public AgentTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void start(
            String traceId, String sessionId, String hospitalId, String userId,
            String userQuery, LocalDateTime startedAt) {
        jdbc.update("""
                INSERT INTO med_agent_trace
                  (trace_id,session_id,hospital_id,user_id,user_query,intent,final_status,
                   started_at,created_at)
                VALUES (?,?,?,?,?,NULL,'running',?,?)
                """, traceId, sessionId, hospitalId, userId, userQuery, startedAt, startedAt);
    }

    public void node(TraceNode node) {
        jdbc.update("""
                INSERT INTO med_agent_trace_node
                  (trace_id,node_id,node_name,node_type,status,input_summary,output_summary,
                   error_code,error_message,tool_name,db_source,sql_id,run_id,rule_id,llm_model,
                   started_at,ended_at,duration_ms,parent_node_id,subtask_id,sequence,
                   started_offset_ms,exclusive_duration_ms,capability,model_id,failure_class,
                   input_tokens,output_tokens,cache_reused,retry_count,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                node.traceId(), node.nodeId(), node.nodeName(), node.nodeType(), node.status(),
                node.inputSummary(), node.outputSummary(), node.errorCode(), node.errorMessage(),
                node.toolName(), node.dbSource(), node.sqlId(), node.runId(), node.ruleId(),
                node.modelId(), node.startedAt(), node.endedAt(), node.durationMs(),
                node.parentNodeId(), node.subtaskId(), node.sequence(), node.startedOffsetMs(),
                node.exclusiveDurationMs(), node.capability(), node.modelId(), node.failureClass(),
                node.inputTokens(), node.outputTokens(), node.cacheReused() ? 1 : 0,
                node.retryCount(), node.startedAt());
    }

    public void finish(
            String traceId, String status, String intent, String answer,
            int errors, int fallbacks, LocalDateTime endedAt, long durationMs) {
        jdbc.update("""
                UPDATE med_agent_trace SET final_status=?,intent=?,final_answer_summary=?,
                  error_count=?,fallback_count=?,ended_at=?,duration_ms=? WHERE trace_id=?
                """, status, intent, shorten(answer, 2000), errors, fallbacks,
                endedAt, durationMs, traceId);
    }

    public Map<String, Object> get(String traceId, String hospitalId) {
        try {
            Map<String, Object> trace = normalize(jdbc.queryForMap(
                    "SELECT * FROM med_agent_trace WHERE trace_id=? AND hospital_id=?",
                    traceId, hospitalId));
            List<Map<String, Object>> nodes = jdbc.queryForList(
                    "SELECT * FROM med_agent_trace_node WHERE trace_id=? ORDER BY COALESCE(sequence,id),id",
                    traceId).stream().map(AgentTraceRepository::normalize).toList();
            trace.put("nodes", nodes);
            return trace;
        } catch (EmptyResultDataAccessException exception) {
            return Map.of();
        }
    }

    public List<Map<String, Object>> evidence(String traceId, String hospitalId) {
        try {
            return jdbc.queryForList("""
                    SELECT evidence_id,fact_type,rule_id,rule_version,stat_start,stat_end,
                           source_tool,source_object_id,created_at,expires_at
                    FROM med_agent_evidence WHERE trace_id=? AND hospital_id=? ORDER BY created_at
                    """, traceId, hospitalId).stream().map(AgentTraceRepository::normalize).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    public List<Map<String, Object>> list(
            String hospitalId,
            LocalDateTime startedAfter,
            LocalDateTime startedBefore,
            String status,
            String modelId,
            String toolName,
            String failureClass,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.trace_id,t.session_id,t.hospital_id,t.intent,t.final_status,
                       t.error_count,t.fallback_count,t.started_at,t.ended_at,t.duration_ms
                FROM med_agent_trace t WHERE t.hospital_id=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(hospitalId);
        if (startedAfter != null) {
            sql.append(" AND t.started_at>=?");
            args.add(startedAfter);
        }
        if (startedBefore != null) {
            sql.append(" AND t.started_at<?");
            args.add(startedBefore);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.final_status=?");
            args.add(status.strip());
        }
        List<String> nodeClauses = new ArrayList<>();
        if (modelId != null && !modelId.isBlank()) {
            nodeClauses.add("n.model_id=?");
            args.add(modelId.strip());
        }
        if (toolName != null && !toolName.isBlank()) {
            nodeClauses.add("n.tool_name=?");
            args.add(toolName.strip());
        }
        if (failureClass != null && !failureClass.isBlank()) {
            nodeClauses.add("n.failure_class=?");
            args.add(failureClass.strip());
        }
        if (!nodeClauses.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM med_agent_trace_node n WHERE n.trace_id=t.trace_id AND ")
                    .append(String.join(" AND ", nodeClauses)).append(")");
        }
        sql.append(" ORDER BY t.started_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(500, limit)));
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(AgentTraceRepository::normalize).toList();
    }

    public List<Map<String, Object>> nodesFor(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(traceIds.size(), "?"));
        return jdbc.queryForList(
                "SELECT * FROM med_agent_trace_node WHERE trace_id IN (" + placeholders + ")",
                traceIds.toArray()).stream().map(AgentTraceRepository::normalize).toList();
    }

    public int prune(LocalDateTime before) {
        List<String> expired = jdbc.queryForList(
                "SELECT trace_id FROM med_agent_trace WHERE started_at<? ORDER BY started_at LIMIT 1000",
                String.class, before);
        if (expired.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(expired.size(), "?"));
        jdbc.update("DELETE FROM med_agent_trace_node WHERE trace_id IN (" + placeholders + ")",
                expired.toArray());
        return jdbc.update("DELETE FROM med_agent_trace WHERE trace_id IN (" + placeholders + ")",
                expired.toArray());
    }

    private static String shorten(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> value = new LinkedHashMap<>();
        source.forEach((key, item) -> value.put(key.toLowerCase(Locale.ROOT), item));
        return value;
    }

    public record TraceNode(
            String traceId, String nodeId, String nodeName, String nodeType, String status,
            String inputSummary, String outputSummary, String errorCode, String errorMessage,
            String toolName, String dbSource, String sqlId, String runId, String ruleId,
            String modelId, LocalDateTime startedAt, LocalDateTime endedAt, long durationMs,
            String parentNodeId, String subtaskId, int sequence, long startedOffsetMs,
            long exclusiveDurationMs, String capability, String failureClass,
            Integer inputTokens, Integer outputTokens, boolean cacheReused, int retryCount) {
    }
}
