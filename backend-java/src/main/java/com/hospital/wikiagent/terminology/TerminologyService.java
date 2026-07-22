package com.hospital.wikiagent.terminology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * 编排 {@code TerminologyService} 对应的业务流程，并集中维护事务与安全边界。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class TerminologyService {
    private final TerminologyRepository repository;

    public TerminologyService(TerminologyRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> listConcepts(String query, String conceptType, String ruleId) {
        List<Map<String, Object>> aliases = repository.aliases("approved");
        Map<String, List<String>> aliasNames = new LinkedHashMap<>();
        for (Map<String, Object> item : aliases) {
            aliasNames.computeIfAbsent(text(item.get("concept_code")), ignored -> new ArrayList<>())
                    .add(text(item.get("alias_text")));
        }
        Set<String> linked = new LinkedHashSet<>();
        for (Map<String, Object> link : repository.ruleLinks()) {
            if (blank(ruleId) || text(link.get("index_code")).equals(ruleId)) {
                linked.add(text(link.get("concept_code")));
            }
        }
        String needle = text(query).strip().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> concept : repository.concepts()) {
            String code = text(concept.get("concept_code"));
            List<String> names = new ArrayList<>();
            names.add(text(concept.get("canonical_name")));
            names.addAll(aliasNames.getOrDefault(code, List.of()));
            if (!needle.isBlank() && names.stream().noneMatch(
                    value -> value.toLowerCase(Locale.ROOT).contains(needle))) continue;
            if (!blank(conceptType) && !text(concept.get("concept_type")).equals(conceptType)) continue;
            if (!blank(ruleId) && !linked.contains(code)) continue;
            Map<String, Object> value = new LinkedHashMap<>(concept);
            List<String> conceptAliases = aliasNames.getOrDefault(code, List.of());
            value.put("alias_count", conceptAliases.size());
            value.put("aliases_preview", conceptAliases.stream().limit(3).toList());
            items.add(value);
        }
        return Map.of("items", List.copyOf(items), "total", items.size());
    }

    public Map<String, Object> concept(String conceptCode, String hospitalId) {
        Map<String, Object> concept = repository.concept(conceptCode);
        if (concept.isEmpty()) throw new TerminologyNotFoundException("未找到该标准概念。");
        Map<String, Object> result = new LinkedHashMap<>(concept);
        result.put("aliases", repository.conceptAliases(conceptCode, hospitalId));
        result.put("rule_links", repository.conceptRuleLinks(conceptCode));
        result.put("hospital_id", hospitalId);
        result.put("hospital_mappings", repository.hospitalMappings(hospitalId, conceptCode));
        result.put("active_release", repository.activeRelease());
        return result;
    }

    public List<Map<String, Object>> releases() {
        return repository.releases();
    }

    public Map<String, Object> normalize(String original, String hospitalId) {
        long started = System.currentTimeMillis();
        String input = text(original);
        Map<String, Map<String, Object>> concepts = new LinkedHashMap<>();
        for (Map<String, Object> item : repository.concepts()) {
            concepts.put(text(item.get("concept_code")), item);
        }
        Map<String, List<Map<String, Object>>> links = new LinkedHashMap<>();
        for (Map<String, Object> item : repository.ruleLinks()) {
            links.computeIfAbsent(text(item.get("concept_code")), ignored -> new ArrayList<>()).add(item);
        }
        List<Entry> entries = new ArrayList<>();
        List<Map<String, Object>> companyAliases = repository.aliases("approved");
        Set<String> safeConcepts = new HashSet<>();
        for (Map<String, Object> alias : companyAliases) {
            if (truth(alias.get("sql_safe")) && !blockedRelation(text(alias.get("relation_type")))) {
                safeConcepts.add(text(alias.get("concept_code")));
            }
        }
        for (Map.Entry<String, Map<String, Object>> concept : concepts.entrySet()) {
            entries.add(entry(concept.getValue(), text(concept.getValue().get("canonical_name")),
                    "exact", true, safeConcepts.contains(concept.getKey()), "company",
                    links.getOrDefault(concept.getKey(), List.of())));
        }
        for (Map<String, Object> alias : companyAliases) {
            addAlias(entries, alias, concepts, links, "company");
        }
        for (Map<String, Object> alias : repository.hospitalAliases(hospitalId)) {
            addAlias(entries, alias, concepts, links, "hospital");
        }
        for (Map<String, Object> mapping : repository.activeHospitalMappings(hospitalId)) {
            Map<String, Object> concept = concepts.get(text(mapping.get("concept_code")));
            if (concept == null) continue;
            Set<String> values = new LinkedHashSet<>(List.of(
                    text(mapping.get("local_name")), text(mapping.get("local_code"))));
            values.remove("");
            for (String value : values) {
                entries.add(entry(concept, value, "value_mapping", true, true, "hospital",
                        links.getOrDefault(text(mapping.get("concept_code")), List.of())));
            }
        }

        Selection selection = select(input, entries);
        String normalized = input;
        List<Map<String, Object>> matches = new ArrayList<>();
        List<Span> replacements = new ArrayList<>();
        boolean unsafe = false;
        for (Span span : selection.matches()) {
            Entry value = span.entry();
            matches.add(match(input.substring(span.start(), span.end()), value));
            if (List.of("exact", "abbreviation", "colloquial", "value_mapping")
                    .contains(value.relationType())) replacements.add(span);
            if (blockedRelation(value.relationType()) || (!value.sqlSafe()
                    && (!value.businessFields().isEmpty() || value.ruleIds().isEmpty()))) unsafe = true;
        }
        replacements.sort(Comparator.comparingInt(Span::start).reversed());
        for (Span span : replacements) {
            normalized = normalized.substring(0, span.start()) + span.entry().canonicalName()
                    + normalized.substring(span.end());
        }
        Map<String, Object> release = repository.activeRelease();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("original_text", input);
        result.put("normalized_text", normalized);
        result.put("matches", matches);
        result.put("ambiguities", selection.ambiguities());
        result.put("release_version", release.isEmpty() ? "unreleased" : text(release.get("release_id")));
        result.put("duration_ms", Math.max(0, System.currentTimeMillis() - started));
        result.put("sql_eligible", !matches.isEmpty() && selection.ambiguities().isEmpty() && !unsafe);
        return result;
    }

    private static void addAlias(
            List<Entry> entries, Map<String, Object> alias,
            Map<String, Map<String, Object>> concepts,
            Map<String, List<Map<String, Object>>> links, String source) {
        String code = text(alias.get("concept_code"));
        Map<String, Object> concept = concepts.get(code);
        if (concept == null) return;
        entries.add(entry(concept, text(alias.get("alias_text")),
                text(alias.get("relation_type")), truth(alias.get("retrieval_enabled")),
                truth(alias.get("sql_safe")), source, links.getOrDefault(code, List.of())));
    }

    private static Entry entry(
            Map<String, Object> concept, String phrase, String relation,
            boolean retrieval, boolean sqlSafe, String source, List<Map<String, Object>> links) {
        Set<String> ruleIds = new LinkedHashSet<>();
        Set<String> businessFields = new LinkedHashSet<>();
        for (Map<String, Object> link : links) {
            ruleIds.add(text(link.get("index_code")));
            String field = text(link.get("business_field_key"));
            if (!field.isBlank()) businessFields.add(field);
        }
        return new Entry(phrase, text(concept.get("concept_code")),
                text(concept.get("canonical_name")), relation, retrieval, sqlSafe, source,
                List.copyOf(ruleIds), List.copyOf(businessFields));
    }

    private static Selection select(String text, List<Entry> entries) {
        String lowered = text.toLowerCase(Locale.ROOT);
        List<Span> candidates = new ArrayList<>();
        for (Entry entry : entries) {
            String needle = entry.phrase().strip().toLowerCase(Locale.ROOT);
            if (needle.isBlank()) continue;
            int offset = 0;
            while (offset < lowered.length()) {
                int start = lowered.indexOf(needle, offset);
                if (start < 0) break;
                candidates.add(new Span(start, start + needle.length(), entry));
                offset = start + Math.max(1, needle.length());
            }
        }
        candidates.sort(Comparator.comparingInt(Span::length).reversed()
                .thenComparingInt(Span::start)
                .thenComparing(value -> "hospital".equals(value.entry().source()) ? 0 : 1)
                .thenComparing(value -> value.entry().conceptCode()));
        Set<Integer> occupied = new HashSet<>();
        List<Span> selected = new ArrayList<>();
        List<Map<String, Object>> ambiguities = new ArrayList<>();
        Set<String> ambiguityKeys = new HashSet<>();
        for (Span candidate : candidates) {
            Set<String> codes = new LinkedHashSet<>();
            codes.add(candidate.entry().conceptCode());
            for (Span other : candidates) {
                if (candidate.start() == other.start() && candidate.end() == other.end()) {
                    codes.add(other.entry().conceptCode());
                }
            }
            if (codes.size() > 1) {
                List<String> sorted = codes.stream().sorted().toList();
                String ambiguityKey = candidate.start() + ":" + candidate.end() + ":" + sorted;
                if (ambiguityKeys.add(ambiguityKey)) {
                    ambiguities.add(Map.of("text", text.substring(candidate.start(), candidate.end()),
                            "concept_codes", sorted));
                }
                continue;
            }
            boolean overlap = false;
            for (int index = candidate.start(); index < candidate.end(); index++) {
                if (occupied.contains(index)) overlap = true;
            }
            if (overlap) continue;
            for (int index = candidate.start(); index < candidate.end(); index++) occupied.add(index);
            selected.add(candidate);
        }
        selected.sort(Comparator.comparingInt(Span::start));
        return new Selection(List.copyOf(selected), List.copyOf(ambiguities));
    }

    private static Map<String, Object> match(String matchedText, Entry value) {
        return Map.of("matched_text", matchedText, "concept_code", value.conceptCode(),
                "canonical_name", value.canonicalName(), "relation_type", value.relationType(),
                "retrieval_enabled", value.retrievalEnabled(), "sql_safe", value.sqlSafe(),
                "source", value.source(), "linked_rule_ids", value.ruleIds(),
                "business_field_keys", value.businessFields());
    }

    private static boolean truth(Object value) {
        if (value instanceof Boolean item) return item;
        if (value instanceof Number item) return item.intValue() != 0;
        return "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private static boolean blockedRelation(String value) {
        return "related".equals(value) || "forbidden".equals(value);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private record Entry(
            String phrase, String conceptCode, String canonicalName, String relationType,
            boolean retrievalEnabled, boolean sqlSafe, String source,
            List<String> ruleIds, List<String> businessFields) { }
    private record Span(int start, int end, Entry entry) { int length() { return end - start; } }
    private record Selection(List<Span> matches, List<Map<String, Object>> ambiguities) { }

    public static class TerminologyNotFoundException extends RuntimeException {
        public TerminologyNotFoundException(String message) { super(message); }
    }
}
