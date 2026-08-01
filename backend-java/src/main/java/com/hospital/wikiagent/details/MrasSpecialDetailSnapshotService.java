package com.hospital.wikiagent.details;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.mras.MrasDetailContractRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import jakarta.annotation.PostConstruct;

/**
 * 特殊指标详情的批次快照与 SQLite 分页。
 *
 * <p>首次查询仍由显式统计契约完成全部对账；对账通过后将各数据组按行落入运行库。
 * 后续切换数据组或翻页只读取运行库，不再访问真实库，也不重复清表抽取。</p>
 */
@Service
public class MrasSpecialDetailSnapshotService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public MrasSpecialDetailSnapshotService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_mras_special_detail_snapshot (
                  cache_key VARCHAR(64) PRIMARY KEY,
                  batch_run_id VARCHAR(64) NOT NULL,
                  hospital_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64) NOT NULL,
                  rule_id VARCHAR(64) NOT NULL,
                  profile_id VARCHAR(64),
                  detail_kind VARCHAR(40) NOT NULL,
                  metadata_json TEXT NOT NULL,
                  created_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_mras_special_detail_snapshot_row (
                  cache_key VARCHAR(64) NOT NULL,
                  group_key VARCHAR(40) NOT NULL,
                  row_no BIGINT NOT NULL,
                  row_json TEXT NOT NULL,
                  PRIMARY KEY (cache_key, group_key, row_no)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_mras_special_detail_page
                ON med_mras_special_detail_snapshot_row
                  (cache_key, group_key, row_no)
                """);
    }

    public Map<String, Object> loadOrCreate(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            MrasDetailKind kind,
            String requestedGroup,
            int page,
            int pageSize,
            Supplier<Map<String, Object>> materializer) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 200));
        String group = normalizeGroup(kind, requestedGroup);
        String cacheKey = cacheKey(principal, task, kind);
        long started = System.nanoTime();
        boolean reused = loadMetadata(cacheKey).isPresent();
        if (!reused) {
            Object lock = locks.computeIfAbsent(cacheKey, ignored -> new Object());
            synchronized (lock) {
                if (loadMetadata(cacheKey).isEmpty()) {
                    persist(
                            cacheKey,
                            principal,
                            task,
                            kind,
                            materializer.get());
                } else {
                    reused = true;
                }
            }
            locks.remove(cacheKey, lock);
        }

        Map<String, Object> body = new LinkedHashMap<>(loadMetadata(cacheKey)
                .orElseThrow(() -> new IndicatorDetailException(
                        "DETAIL_SNAPSHOT_FAILED",
                        "特殊指标明细快照创建失败。",
                        HttpStatus.INTERNAL_SERVER_ERROR)));
        int total = rowCount(cacheKey, group);
        int offset = (safePage - 1) * safePageSize;
        List<Map<String, Object>> rows = pageRows(cacheKey, group, offset, safePageSize);
        body.put(rowField(group), rows);
        body.put("group", group);
        body.put("page", safePage);
        body.put("pageSize", safePageSize);
        body.put("rowCount", total);
        body.put("truncated", safePage * safePageSize < total);
        body.put("snapshotId", cacheKey);
        body.put("snapshotReused", reused);
        body.put("sqlSource", reused ? "batch_special_detail_snapshot" : "mras_extracted");
        body.put("durationMs", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return body;
    }

    private void persist(
            String cacheKey,
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            MrasDetailKind kind,
            Map<String, Object> fullBody) {
        Map<String, List<Map<String, Object>>> groups = extractGroups(kind, fullBody);
        Map<String, Object> metadata = new LinkedHashMap<>(fullBody);
        groups.keySet().forEach(group -> metadata.remove(rowField(group)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        groups.forEach((group, rows) -> counts.put(group, rows.size()));
        metadata.put("groupCounts", java.util.Collections.unmodifiableMap(counts));

        jdbc.update(
                "DELETE FROM med_mras_special_detail_snapshot_row WHERE cache_key=?",
                cacheKey);
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            List<Object[]> values = new ArrayList<>(group.getValue().size());
            int rowNo = 0;
            for (Map<String, Object> row : group.getValue()) {
                values.add(new Object[] {
                        cacheKey, group.getKey(), ++rowNo, json(row)
                });
            }
            if (!values.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO med_mras_special_detail_snapshot_row
                          (cache_key, group_key, row_no, row_json)
                        VALUES (?, ?, ?, ?)
                        """, values);
            }
        }
        jdbc.update("""
                INSERT INTO med_mras_special_detail_snapshot (
                  cache_key, batch_run_id, hospital_id, user_id, rule_id,
                  profile_id, detail_kind, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                cacheKey,
                task.batchRunId(),
                principal.hospitalId(),
                principal.userId(),
                task.ruleId(),
                task.profileId(),
                kind.name(),
                json(metadata),
                Timestamp.from(Instant.now()));
    }

    private java.util.Optional<Map<String, Object>> loadMetadata(String cacheKey) {
        List<String> values = jdbc.query(
                """
                SELECT metadata_json
                FROM med_mras_special_detail_snapshot
                WHERE cache_key=?
                """,
                (result, row) -> result.getString(1),
                cacheKey);
        return values.stream().findFirst().map(this::map);
    }

    private int rowCount(String cacheKey, String group) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(1)
                FROM med_mras_special_detail_snapshot_row
                WHERE cache_key=? AND group_key=?
                """,
                Integer.class,
                cacheKey,
                group);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> pageRows(
            String cacheKey,
            String group,
            int offset,
            int pageSize) {
        return jdbc.queryForList(
                        """
                        SELECT row_json
                        FROM med_mras_special_detail_snapshot_row
                        WHERE cache_key=? AND group_key=?
                        ORDER BY row_no
                        LIMIT ? OFFSET ?
                        """,
                        cacheKey, group, pageSize, offset)
                .stream()
                .map(row -> map(String.valueOf(row.get("row_json"))))
                .toList();
    }

    private static String normalizeGroup(MrasDetailKind kind, String requested) {
        List<String> valid = groups(kind);
        if (requested == null || requested.isBlank()
                || "numerator".equals(requested) || "denominator".equals(requested)) {
            return valid.get(0);
        }
        if (!valid.contains(requested)) {
            throw new IndicatorDetailException(
                    "DETAIL_GROUP_INVALID",
                    "该指标不支持明细分组 " + requested + "。",
                    HttpStatus.BAD_REQUEST);
        }
        return requested;
    }

    private static List<String> groups(MrasDetailKind kind) {
        return switch (kind) {
            case SUM_CONTRIBUTION -> List.of("contributions");
            case MEDIAN_SAMPLE -> List.of("samples");
            case DUAL_SOURCE -> List.of("actual", "registered");
            case RATE_COMPARISON ->
                    List.of("level4Hit", "level4Total", "level3Hit", "level3Total");
            default -> throw new IllegalArgumentException("普通比例不属于特殊明细快照");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> extractGroups(
            MrasDetailKind kind,
            Map<String, Object> body) {
        Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();
        for (String group : groups(kind)) {
            Object raw = body.get(rowField(group));
            values.put(group, raw instanceof List<?> list
                    ? (List<Map<String, Object>>) list
                    : List.of());
        }
        return values;
    }

    private static String rowField(String group) {
        return switch (group) {
            case "contributions", "samples" -> "rows";
            case "actual" -> "actualRows";
            case "registered" -> "registeredRows";
            default -> group;
        };
    }

    private static String cacheKey(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            MrasDetailKind kind) {
        return MrasDetailSqlExtractor.sqlHash(
                principal.hospitalId() + "|" + principal.userId()
                        + "|" + task.batchRunId() + "|" + task.ruleId()
                        + "|" + String.valueOf(task.profileId()) + "|" + kind.name()
                        + "|" + MrasDetailContractRegistry.CONTRACT_VERSION);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IndicatorDetailException(
                    "DETAIL_SNAPSHOT_FAILED",
                    "特殊指标明细快照序列化失败。",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception exception) {
            throw new IndicatorDetailException(
                    "DETAIL_SNAPSHOT_FAILED",
                    "特殊指标明细快照读取失败。",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
