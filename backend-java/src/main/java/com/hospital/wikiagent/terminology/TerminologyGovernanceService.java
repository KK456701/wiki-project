package com.hospital.wikiagent.terminology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
/**
 * 编排 {@code TerminologyGovernanceService} 对应的业务流程，并集中维护事务与安全边界。
 */
public class TerminologyGovernanceService {
    private static final Set<String> RELATIONS = Set.of(
            "exact", "abbreviation", "colloquial", "related", "forbidden");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TerminologyGovernanceService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> createAlias(AliasCommand command) {
        requireConcept(command.conceptCode());
        String relation = text(command.relationType());
        if (!RELATIONS.contains(relation)) throw invalid("不支持的术语关系类型。");
        if (command.sqlSafe() && Set.of("related", "forbidden").contains(relation)) {
            throw conflict("TERM_ALIAS_SQL_UNSAFE", "相关词或禁止替换词不能用于 SQL。");
        }
        String hospital = text(command.hospitalId());
        String alias = required(command.aliasText(), "候选词不能为空。", 200);
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM med_term_alias "
                        + "WHERE hospital_id=? AND concept_code=? AND alias_text=?",
                Integer.class, hospital, command.conceptCode(), alias);
        LocalDateTime now = LocalDateTime.now();
        long id = insertId(
                "INSERT INTO med_term_alias (hospital_id,concept_code,alias_text,relation_type,"
                        + "retrieval_enabled,sql_safe,ambiguity_group,source_reference,approval_status,"
                        + "version,created_by,approved_by,created_at,approved_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                hospital, command.conceptCode(), alias, relation,
                command.retrievalEnabled() ? 1 : 0, command.sqlSafe() ? 1 : 0,
                blankToNull(command.ambiguityGroup()), textOr(command.sourceReference(), "manual"),
                "pending", version, "admin", null, now, null);
        Map<String, Object> result = aliasById(id);
        audit("create", "term_alias", String.valueOf(id), "admin", hospital,
                Map.of("concept_code", command.conceptCode(), "version", version));
        return result;
    }

    @Transactional
    public Map<String, Object> approveAlias(long aliasId, String authorizedHospital) {
        Map<String, Object> item = aliasById(aliasId);
        if (item.isEmpty()) throw notFound("TERM_ALIAS_NOT_FOUND", "未找到该候选词。");
        String itemHospital = text(item.get("hospital_id"));
        if (!itemHospital.isBlank() && !itemHospital.equals(authorizedHospital)) {
            throw new TerminologyGovernanceException("TERM_HOSPITAL_SCOPE_DENIED",
                    "只能审批当前登录医院的候选词。", HttpStatus.FORBIDDEN);
        }
        if (Set.of("related", "forbidden").contains(text(item.get("relation_type")))
                && truth(item.get("sql_safe"))) {
            throw conflict("TERM_ALIAS_SQL_UNSAFE", "相关词或禁止替换词不能用于 SQL。");
        }
        Integer conflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_term_alias WHERE alias_text=? "
                        + "AND approval_status='approved' AND hospital_id=? AND concept_code<>?",
                Integer.class, item.get("alias_text"), item.get("hospital_id"), item.get("concept_code"));
        if (conflicts != null && conflicts > 0 && blank(item.get("ambiguity_group"))) {
            throw conflict("TERM_ALIAS_CONFLICT", "该词已指向其他概念，请先设置歧义分组。");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE med_term_alias SET approval_status='approved',approved_by=?,approved_at=? WHERE id=?",
                "admin", now, aliasId);
        audit("approve", "term_alias", String.valueOf(aliasId), "admin",
                blankToNull(itemHospital), Map.of());
        return aliasById(aliasId);
    }

    @Transactional
    public Map<String, Object> createMapping(MappingCommand command, String authorizedHospital) {
        if (!authorizedHospital.equals(command.hospitalId())) {
            throw new TerminologyGovernanceException("TERM_HOSPITAL_SCOPE_DENIED",
                    "只能维护当前登录医院的术语映射。", HttpStatus.FORBIDDEN);
        }
        requireConcept(command.conceptCode());
        String localName = required(command.localName(), "本院名称不能为空。", 200);
        String localValue = required(command.localValue(), "数据库值不能为空。", 500);
        String codeSystem = required(command.codeSystem(), "编码体系不能为空。", 64);
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM med_hospital_term_mapping "
                        + "WHERE hospital_id=? AND concept_code=?",
                Integer.class, authorizedHospital, command.conceptCode());
        LocalDateTime now = LocalDateTime.now();
        long id = insertId(
                "INSERT INTO med_hospital_term_mapping (hospital_id,concept_code,code_system,local_code,"
                        + "local_name,local_value,approval_status,effective_from,effective_to,version,"
                        + "created_by,approved_by,created_at,approved_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                authorizedHospital, command.conceptCode(), codeSystem, text(command.localCode()),
                localName, localValue, "pending", parseDate(command.effectiveFrom()),
                parseDate(command.effectiveTo()), version, "admin", null, now, null);
        audit("create", "hospital_term_mapping", String.valueOf(id), "admin", authorizedHospital,
                Map.of("concept_code", command.conceptCode(), "version", version));
        return mappingById(id);
    }

    @Transactional
    public Map<String, Object> approveMapping(long mappingId, String authorizedHospital) {
        Map<String, Object> item = mappingById(mappingId);
        if (item.isEmpty()) throw notFound("TERM_MAPPING_NOT_FOUND", "未找到该医院术语映射。");
        if (!authorizedHospital.equals(text(item.get("hospital_id")))) {
            throw new TerminologyGovernanceException("TERM_HOSPITAL_SCOPE_DENIED",
                    "只能审批当前登录医院的术语映射。", HttpStatus.FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE med_hospital_term_mapping SET approval_status='approved',approved_by=?,approved_at=? WHERE id=?",
                "admin", now, mappingId);
        String versionId = "TMV_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO med_hospital_term_mapping_version "
                        + "(version_id,hospital_id,concept_code,version,snapshot_json,change_type,oper_user,"
                        + "approver_id,created_at,approved_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                versionId, item.get("hospital_id"), item.get("concept_code"), item.get("version"),
                json(safe(item)), "approve", item.get("created_by"), "admin", item.get("created_at"), now);
        audit("approve", "hospital_term_mapping", String.valueOf(mappingId), "admin", authorizedHospital,
                Map.of("version_id", versionId));
        Map<String, Object> result = new LinkedHashMap<>(mappingById(mappingId));
        result.put("version_id", versionId);
        return result;
    }

    @Transactional
    public Map<String, Object> publish() {
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_term_alias WHERE hospital_id='' AND approval_status='pending'",
                Integer.class);
        if (pending != null && pending > 0) {
            throw conflict("TERM_PENDING_REVIEW_EXISTS", "仍有待审核公司术语，暂不能发布。");
        }
        Map<String, Object> snapshot = snapshot();
        String stable = json(canonical(snapshot));
        String checksum = sha256(stable);
        List<Map<String, Object>> existing = rows(
                "SELECT release_id,version,status,checksum FROM med_term_release WHERE checksum=?", checksum);
        jdbc.update("UPDATE med_term_release SET status='history' WHERE status='active'");
        if (!existing.isEmpty()) {
            Map<String, Object> value = existing.get(0);
            jdbc.update("UPDATE med_term_release SET status='active' WHERE release_id=?", value.get("release_id"));
            return Map.of("release_id", value.get("release_id"), "active_release_id", value.get("release_id"),
                    "version", value.get("version"), "status", "active", "checksum", checksum, "reused", true);
        }
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM med_term_release", Integer.class);
        String releaseId = "TERM_" + java.time.LocalDate.now().toString().replace("-", "")
                + "_" + String.format("%03d", version) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        jdbc.update("INSERT INTO med_term_release (release_id,version,status,checksum,snapshot_json,"
                        + "change_summary,published_by,published_at) VALUES (?,?,?,?,?,?,?,?)",
                releaseId, version, "active", checksum, json(snapshot), "医学术语发布", "admin",
                LocalDateTime.now());
        audit("publish", "term_release", releaseId, "admin", null, Map.of("version", version));
        return Map.of("release_id", releaseId, "active_release_id", releaseId, "version", version,
                "status", "active", "checksum", checksum, "reused", false);
    }

    @Transactional
    public Map<String, Object> restore(String releaseId) {
        List<Map<String, Object>> releases = rows(
                "SELECT release_id,version,snapshot_json FROM med_term_release WHERE release_id=?", releaseId);
        if (releases.isEmpty()) throw notFound("TERM_RELEASE_NOT_FOUND", "未找到该术语版本。");
        Map<String, Object> release = releases.get(0);
        Map<String, Object> snapshot = parseObject(release.get("snapshot_json"));
        replaceProjection(snapshot);
        jdbc.update("UPDATE med_term_release SET status='history' WHERE status='active'");
        jdbc.update("UPDATE med_term_release SET status='active' WHERE release_id=?", releaseId);
        audit("restore", "term_release", releaseId, "admin", null,
                Map.of("restored_version", release.get("version")));
        return Map.of("active_release_id", releaseId, "status", "active");
    }

    private Map<String, Object> snapshot() {
        return Map.of(
                "concepts", safe(rows("SELECT * FROM med_term_concept WHERE status='active' ORDER BY concept_code")),
                "aliases", safe(rows("SELECT * FROM med_term_alias WHERE hospital_id='' "
                        + "AND approval_status='approved' ORDER BY concept_code,alias_text")),
                "rule_links", safe(rows("SELECT * FROM med_term_rule_link ORDER BY index_code,concept_code")));
    }

    @SuppressWarnings("unchecked")
    private void replaceProjection(Map<String, Object> snapshot) {
        jdbc.update("DELETE FROM med_term_rule_link");
        jdbc.update("DELETE FROM med_term_alias WHERE hospital_id=''");
        jdbc.update("DELETE FROM med_term_concept");
        for (Map<String, Object> item : list(snapshot.get("concepts"))) {
            jdbc.update("INSERT INTO med_term_concept (concept_code,canonical_name,concept_type,definition,"
                            + "standard_code,source_level,source_reference,version,status,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    item.get("concept_code"), item.get("canonical_name"), item.get("concept_type"),
                    item.get("definition"), item.get("standard_code"), item.get("source_level"),
                    item.get("source_reference"), item.get("version"), item.get("status"),
                    timestamp(item.get("created_at")), timestamp(item.get("updated_at")));
        }
        for (Map<String, Object> item : list(snapshot.get("aliases"))) {
            jdbc.update("INSERT INTO med_term_alias (hospital_id,concept_code,alias_text,relation_type,"
                            + "retrieval_enabled,sql_safe,ambiguity_group,source_reference,approval_status,"
                            + "version,created_by,approved_by,created_at,approved_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    text(item.get("hospital_id")), item.get("concept_code"), item.get("alias_text"),
                    item.get("relation_type"), item.get("retrieval_enabled"), item.get("sql_safe"),
                    item.get("ambiguity_group"), item.get("source_reference"), item.get("approval_status"),
                    item.get("version"), item.get("created_by"), item.get("approved_by"),
                    timestamp(item.get("created_at")), timestamp(item.get("approved_at")));
        }
        for (Map<String, Object> item : list(snapshot.get("rule_links"))) {
            jdbc.update("INSERT INTO med_term_rule_link (concept_code,index_code,usage_section,"
                            + "business_field_key,source_reference,version) VALUES (?,?,?,?,?,?)",
                    item.get("concept_code"), item.get("index_code"), item.get("usage_section"),
                    item.get("business_field_key"), item.get("source_reference"), item.get("version"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) if (item instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, field) -> normalized.put(String.valueOf(key), field));
            result.add(normalized);
        }
        return result;
    }

    private void requireConcept(String conceptCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_term_concept WHERE concept_code=?", Integer.class, conceptCode);
        if (count == null || count == 0) throw notFound("TERM_CONCEPT_NOT_FOUND", "未找到该标准概念。");
    }

    private Map<String, Object> aliasById(long id) {
        List<Map<String, Object>> values = rows("SELECT * FROM med_term_alias WHERE id=?", id);
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    private Map<String, Object> mappingById(long id) {
        List<Map<String, Object>> values = rows("SELECT * FROM med_hospital_term_mapping WHERE id=?", id);
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    private long insertId(String sql, Object... values) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回新记录编号。");
        return key.longValue();
    }

    private List<Map<String, Object>> rows(String sql, Object... values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, values)) {
            Map<String, Object> item = new LinkedHashMap<>();
            row.forEach((key, value) -> item.put(key.toLowerCase(Locale.ROOT), value));
            result.add(item);
        }
        return result;
    }

    private void audit(String action, String type, String id, String actor, String hospital, Map<String, Object> detail) {
        jdbc.update("INSERT INTO med_term_audit_log (action,object_type,object_id,hospital_id,version,"
                        + "actor_id,detail_json,created_at) VALUES (?,?,?,?,?,?,?,?)",
                action, type, id, hospital, null, actor, json(detail), LocalDateTime.now());
    }

    private Map<String, Object> parseObject(Object value) {
        try {
            return mapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            throw conflict("TERM_RELEASE_INVALID", "术语版本快照无法读取。");
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("术语快照序列化失败。", exception); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(TerminologyGovernanceService::canonical).toList();
        return value;
    }

    private static Object safe(Object value) {
        if (value instanceof Timestamp item) return item.toLocalDateTime().withNano(0).toString();
        if (value instanceof LocalDateTime item) return item.withNano(0).toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!"id".equals(String.valueOf(key))) result.put(String.valueOf(key), safe(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(TerminologyGovernanceService::safe).toList();
        return value;
    }

    private static Timestamp timestamp(Object value) {
        if (value == null || text(value).isBlank()) return null;
        if (value instanceof Timestamp item) return item;
        String normalized = text(value).replace('T', ' ');
        if (normalized.length() == 19) return Timestamp.valueOf(normalized);
        return Timestamp.valueOf(LocalDateTime.parse(text(value)));
    }

    private static LocalDateTime parseDate(String value) {
        if (blank(value)) return null;
        try { return LocalDateTime.parse(value.replace(' ', 'T')); }
        catch (DateTimeParseException exception) { throw invalid("生效时间格式无效，请使用 ISO 日期时间。"); }
    }

    private static String required(String value, String message, int max) {
        String result = text(value).strip();
        if (result.isEmpty() || result.length() > max) throw invalid(message);
        return result;
    }
    private static String textOr(String value, String fallback) { return blank(value) ? fallback : value.strip(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(Object value) { return text(value).isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.strip(); }
    private static boolean truth(Object value) {
        return value instanceof Boolean item ? item
                : value instanceof Number item ? item.intValue() != 0
                : "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }
    private static TerminologyGovernanceException invalid(String message) {
        return new TerminologyGovernanceException("TERM_INVALID", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private static TerminologyGovernanceException conflict(String code, String message) {
        return new TerminologyGovernanceException(code, message, HttpStatus.CONFLICT);
    }
    private static TerminologyGovernanceException notFound(String code, String message) {
        return new TerminologyGovernanceException(code, message, HttpStatus.NOT_FOUND);
    }

    public record AliasCommand(
            String hospitalId, String conceptCode, String aliasText, String relationType,
            boolean retrievalEnabled, boolean sqlSafe, String ambiguityGroup,
            String sourceReference) { }
    public record MappingCommand(
            String hospitalId, String conceptCode, String codeSystem, String localCode,
            String localName, String localValue, String effectiveFrom, String effectiveTo) { }
}
