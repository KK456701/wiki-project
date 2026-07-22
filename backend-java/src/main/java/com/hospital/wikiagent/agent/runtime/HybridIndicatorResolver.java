package com.hospital.wikiagent.agent.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.ModelJsonExtractor;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.rules.RuleReadRepository;
import com.hospital.wikiagent.terminology.TerminologyRepository;
import com.hospital.wikiagent.terminology.TerminologyService;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 指标身份识别边界：规则精确匹配、本地语义召回、候选内 LLM 消歧。
 * 不决定业务意图、工具、SQL 或数据库执行。
 *
 * <p>解析过程优先使用确定性规则并保留原始输入，无法唯一确定时返回歧义而不是猜测。模型结果只能作为候选，仍需经过类型和业务约束校验。</p>
 */
@Component
public class HybridIndicatorResolver {
    public static final String VERSION = "hybrid-indicator-resolver-java-v1";
    private static final double SEMANTIC_THRESHOLD = 0.68;
    private static final double SEMANTIC_MARGIN = 0.12;
    private static final int MAX_INDICATORS = 3;
    private static final Pattern SEGMENT_SPLIT = Pattern.compile(
            "[,，、;；]|(?:还有|以及|另外(?:再|还|也)?|同时(?:还|也)?)");
    private static final Pattern ACTION_PREFIX = Pattern.compile(
            "^(?:请|帮我|给我|再|同时|分别|查询|查一下|计算|算一下|统计|查看|看看)+");
    private static final Pattern ACTION_SUFFIX = Pattern.compile(
            "(?:的)?(?:定义|公式|口径|具体结果|指标结果|结果|数值|指标值|分子分母)?"
                    + "(?:怎么(?:算|计算|写)|如何(?:算|计算)|是多少|是什么|什么意思|给我)?[？?。]*$");
    private static final Pattern TIME_TAIL = Pattern.compile(
            "(?:从|自|在)?(?:\\d{2,4}\\s*年)?(?:1[0-2]|[1-9]|[一二三四五六七八九十]{1,3})"
                    + "\\s*月份?.*$");
    private static final Pattern INDICATOR_HINT = Pattern.compile(
            "指标|率|比例|会诊|转科|查房|患者|住院|手术|抢救|死亡|感染|输血");

    private final TerminologyService terminology;
    private final TerminologyRepository terminologyRepository;
    private final RuleReadRepository rules;
    private final AgentModelInvoker models;
    private final AgentModelRegistry modelRegistry;
    private final AgentModelProperties properties;
    private final PromptCatalog prompts;
    private final ObjectMapper objectMapper;

    public HybridIndicatorResolver(
            TerminologyService terminology,
            TerminologyRepository terminologyRepository,
            RuleReadRepository rules,
            AgentModelInvoker models,
            AgentModelRegistry modelRegistry,
            AgentModelProperties properties,
            PromptCatalog prompts,
            ObjectMapper objectMapper) {
        this.terminology = terminology;
        this.terminologyRepository = terminologyRepository;
        this.rules = rules;
        this.models = models;
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    /**
     * 按“规则精确匹配 → 本地语义召回 → 候选内 LLM 消歧”识别一到三个指标。
     * 每一层都会写入独立 Trace 节点，调用方可以确认指标身份是如何得到的。
     */
    public Resolution resolve(
            String query,
            String hospitalId,
            String modelId,
            String traceId,
            String subtaskId,
            AgentRunObserver observer) {
        String input = query == null ? "" : query.strip();
        if (input.isBlank() || !INDICATOR_HINT.matcher(input).find()) {
            return Resolution.empty();
        }
        AgentRunObserver sink = observer == null ? AgentRunObserver.noop() : observer;
        String releaseVersion = "unreleased";
        List<CatalogItem> catalog = catalog(hospitalId);

        // 第一层：医院术语映射和正式指标名精确匹配，结果可直接审计和复现。
        long ruleStarted = TraceEvents.started();
        List<ResolvedIndicator> resolved = new ArrayList<>();
        List<Span> occupied = new ArrayList<>();
        try {
            Map<String, Object> normalization = terminology.normalize(input, hospitalId);
            releaseVersion = text(normalization.get("release_version"));
            addNormalizationMatches(input, normalization, catalog, resolved, occupied);
        } catch (RuntimeException ignored) {
            // 术语治理表不可用时，仍以生效指标目录完成正式名称识别。
        }
        addExactCatalogMatches(input, catalog, resolved, occupied);
        resolved = new ArrayList<>(deduplicate(resolved));
        TraceEvents.completed(sink, traceId, "indicator_rule_match", "code", ruleStarted,
                subtaskId, Map.of("query", input, "hospital_id", safe(hospitalId)), Map.of(
                        "matches", resolved,
                        "release_version", releaseVersion,
                        "resolver_version", VERSION));

        // 第二层：只在未占用文本片段内做本地相似度召回，阈值和领先幅度均固定。
        long semanticStarted = TraceEvents.started();
        List<Ambiguity> ambiguities = new ArrayList<>();
        Set<String> knownRules = new LinkedHashSet<>();
        resolved.forEach(value -> knownRules.add(value.ruleId()));
        List<Segment> segments = candidateSegments(input).stream()
                .filter(segment -> occupied.stream().noneMatch(span -> span.overlaps(segment)))
                .toList();
        for (Segment segment : segments) {
            List<Candidate> ranked = rank(segment.text(), catalog, knownRules);
            if (ranked.isEmpty()) continue;
            Candidate top = ranked.get(0);
            double runnerUp = ranked.size() > 1 ? ranked.get(1).score() : 0.0;
            if (top.score() >= SEMANTIC_THRESHOLD && top.score() - runnerUp >= SEMANTIC_MARGIN) {
                resolved.add(new ResolvedIndicator(
                        segment.text(), top.canonicalName(), top.ruleId(), top.conceptCode(),
                        "semantic", top.score(), segment.start(), segment.end()));
                knownRules.add(top.ruleId());
            } else if (top.score() >= 0.45) {
                ambiguities.add(new Ambiguity(segment.text(), ranked.stream().limit(3).toList()));
            }
        }
        resolved = new ArrayList<>(deduplicate(resolved));
        TraceEvents.completed(sink, traceId, "indicator_semantic_retrieval", "code",
                semanticStarted, subtaskId,
                Map.of("segments", segments.stream().map(Segment::text).toList()), Map.of(
                        "resolved", resolved,
                        "candidate_groups", ambiguities,
                        "algorithm", "normalized-levenshtein+jaccard+containment",
                        "threshold", SEMANTIC_THRESHOLD,
                        "margin", SEMANTIC_MARGIN));

        // 第三层：LLM 只能在最多三个候选中消歧，不能创造目录外的指标或 ruleId。
        boolean usedLlm = false;
        if (!ambiguities.isEmpty()) {
            usedLlm = true;
            Disambiguation disambiguation = disambiguate(
                    input, ambiguities, modelId, traceId, subtaskId, sink);
            resolved.addAll(disambiguation.resolved());
            ambiguities = new ArrayList<>(disambiguation.remaining());
        }
        resolved = new ArrayList<>(deduplicate(resolved));
        if (resolved.size() > MAX_INDICATORS) {
            ambiguities = new ArrayList<>(List.of(new Ambiguity(input, resolved.stream()
                    .map(value -> new Candidate(
                            value.ruleId(), value.canonicalName(), value.conceptCode(), value.confidence()))
                    .toList())));
            resolved = List.of();
        }
        return new Resolution(resolved, ambiguities, usedLlm, releaseVersion);
    }

    private List<CatalogItem> catalog(String hospitalId) {
        Map<String, CatalogBuilder> byRule = new LinkedHashMap<>();
        Map<String, Map<String, Object>> concepts = new LinkedHashMap<>();
        try {
            for (Map<String, Object> concept : terminologyRepository.concepts()) {
                if ("indicator".equals(text(concept.get("concept_type")))) {
                    concepts.put(text(concept.get("concept_code")), concept);
                }
            }
            Map<String, List<String>> aliases = new LinkedHashMap<>();
            List<Map<String, Object>> allAliases = new ArrayList<>(terminologyRepository.aliases("approved"));
            allAliases.addAll(terminologyRepository.hospitalAliases(hospitalId));
            for (Map<String, Object> alias : allAliases) {
                String code = text(alias.get("concept_code"));
                if (concepts.containsKey(code) && truth(alias.get("retrieval_enabled"))) {
                    aliases.computeIfAbsent(code, ignored -> new ArrayList<>())
                            .add(text(alias.get("alias_text")));
                }
            }
            for (Map<String, Object> link : terminologyRepository.ruleLinks()) {
                String code = text(link.get("concept_code"));
                Map<String, Object> concept = concepts.get(code);
                String ruleId = text(link.get("index_code"));
                if (concept == null || ruleId.isBlank()) continue;
                CatalogBuilder builder = byRule.computeIfAbsent(ruleId,
                        ignored -> new CatalogBuilder(ruleId, code,
                                text(concept.get("canonical_name"))));
                builder.names.add(builder.canonicalName);
                builder.names.addAll(aliases.getOrDefault(code, List.of()));
            }
        } catch (RuntimeException ignored) {
            // 生效指标目录仍可单独作为完整兜底。
        }
        for (Map<String, String> item : rules.activeIndicatorNames(hospitalId, 500)) {
            String ruleId = text(item.get("rule_id"));
            String name = text(item.get("rule_name"));
            if (ruleId.isBlank() || name.isBlank()) continue;
            CatalogBuilder builder = byRule.computeIfAbsent(ruleId,
                    ignored -> new CatalogBuilder(ruleId, "RULE:" + ruleId, name));
            if (builder.canonicalName.isBlank()) builder.canonicalName = name;
            builder.names.add(name);
        }
        return byRule.values().stream().map(CatalogBuilder::build).toList();
    }

    @SuppressWarnings("unchecked")
    private static void addNormalizationMatches(
            String query,
            Map<String, Object> normalization,
            List<CatalogItem> catalog,
            List<ResolvedIndicator> resolved,
            List<Span> occupied) {
        Object rawMatches = normalization.get("matches");
        if (!(rawMatches instanceof List<?> matches)) return;
        int cursor = 0;
        for (Object raw : matches) {
            if (!(raw instanceof Map<?, ?> value)) continue;
            String mention = text(value.get("matched_text"));
            String canonicalName = text(value.get("canonical_name"));
            String conceptCode = text(value.get("concept_code"));
            Object rawIds = value.get("linked_rule_ids");
            if (!(rawIds instanceof List<?> ids) || ids.size() != 1 || mention.isBlank()) continue;
            String ruleId = text(ids.get(0));
            CatalogItem item = catalog.stream().filter(candidate -> candidate.ruleId().equals(ruleId))
                    .findFirst().orElse(null);
            if (item == null) continue;
            int start = query.indexOf(mention, cursor);
            if (start < 0) start = query.indexOf(mention);
            if (start < 0) start = 0;
            int end = Math.min(query.length(), start + mention.length());
            cursor = end;
            resolved.add(new ResolvedIndicator(
                    mention, canonicalName.isBlank() ? item.canonicalName() : canonicalName,
                    ruleId, conceptCode.isBlank() ? item.conceptCode() : conceptCode,
                    "rule", 1.0, start, end));
            occupied.add(new Span(start, end));
        }
    }

    private static void addExactCatalogMatches(
            String query,
            List<CatalogItem> catalog,
            List<ResolvedIndicator> resolved,
            List<Span> occupied) {
        List<ExactMatch> matches = new ArrayList<>();
        String lowered = query.toLowerCase(Locale.ROOT);
        for (CatalogItem item : catalog) {
            for (String rawName : item.names()) {
                String name = rawName == null ? "" : rawName.strip();
                if (name.isBlank()) continue;
                String needle = name.toLowerCase(Locale.ROOT);
                int offset = 0;
                while (offset < lowered.length()) {
                    int start = lowered.indexOf(needle, offset);
                    if (start < 0) break;
                    matches.add(new ExactMatch(start, start + name.length(), name, item));
                    offset = start + Math.max(1, name.length());
                }
            }
        }
        matches.sort(Comparator.comparingInt(ExactMatch::length).reversed()
                .thenComparingInt(ExactMatch::start));
        for (ExactMatch match : matches) {
            Span span = new Span(match.start(), match.end());
            if (occupied.stream().anyMatch(value -> value.overlaps(span))) continue;
            resolved.add(new ResolvedIndicator(
                    query.substring(match.start(), match.end()), match.item().canonicalName(),
                    match.item().ruleId(), match.item().conceptCode(), "rule", 1.0,
                    match.start(), match.end()));
            occupied.add(span);
        }
    }

    private Disambiguation disambiguate(
            String query,
            List<Ambiguity> groups,
            String requestedModelId,
            String traceId,
            String subtaskId,
            AgentRunObserver observer) {
        Map<String, Ambiguity> groupMap = new LinkedHashMap<>();
        List<Map<String, Object>> payload = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            String groupId = "candidate_" + (index + 1);
            Ambiguity group = groups.get(index);
            groupMap.put(groupId, group);
            payload.add(Map.of(
                    "group_id", groupId,
                    "mention", group.mention(),
                    "candidates", group.candidates()));
        }
        String modelId = requestedModelId == null || requestedModelId.isBlank()
                ? modelRegistry.defaultModelId() : requestedModelId;
        String systemPrompt = prompts.indicatorCandidateDisambiguator();
        String userPrompt;
        try {
            userPrompt = "用户原话：\n" + query + "\n\n服务端候选组：\n"
                    + objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return new Disambiguation(List.of(), groups);
        }
        long started = TraceEvents.started();
        String raw = "";
        try {
            Duration timeout = properties.getPlannerTimeout();
            raw = models.complete(modelId, systemPrompt, userPrompt, timeout).content();
            Map<?, ?> output = objectMapper.readValue(
                    ModelJsonExtractor.firstObject(raw), Map.class);
            Object rawSelections = output.get("selections");
            if (!(rawSelections instanceof List<?> selections)) {
                throw new IllegalArgumentException("缺少 selections");
            }
            List<ResolvedIndicator> resolved = new ArrayList<>();
            Set<String> selectedGroups = new LinkedHashSet<>();
            for (Object rawSelection : selections) {
                if (!(rawSelection instanceof Map<?, ?> selection)) continue;
                String groupId = text(selection.get("group_id"));
                String ruleId = text(selection.get("rule_id"));
                Ambiguity group = groupMap.get(groupId);
                if (group == null || ruleId.isBlank()) continue;
                Candidate candidate = group.candidates().stream()
                        .filter(value -> value.ruleId().equals(ruleId)).findFirst().orElse(null);
                if (candidate == null) continue;
                int start = Math.max(0, query.indexOf(group.mention()));
                resolved.add(new ResolvedIndicator(
                        group.mention(), candidate.canonicalName(), candidate.ruleId(),
                        candidate.conceptCode(), "llm_disambiguation", candidate.score(),
                        start, start + group.mention().length()));
                selectedGroups.add(groupId);
            }
            List<Ambiguity> remaining = groupMap.entrySet().stream()
                    .filter(entry -> !selectedGroups.contains(entry.getKey()))
                    .map(Map.Entry::getValue).toList();
            TraceEvents.completed(observer, traceId, "indicator_llm_disambiguation", "llm",
                    started, subtaskId,
                    Map.of("system_prompt", systemPrompt, "user_prompt", userPrompt, "tools", List.of()),
                    Map.of("raw_content", raw, "resolved", resolved,
                            "remaining_groups", remaining.size()),
                    "model_id", modelId, "prompt_version", PromptCatalog.VERSION);
            return new Disambiguation(resolved, remaining);
        } catch (Exception exception) {
            TraceEvents.failed(observer, traceId, "indicator_llm_disambiguation", "llm",
                    started, subtaskId, "INDICATOR_DISAMBIGUATION_FAILED", exception.getMessage(),
                    "model_id", modelId, "prompt_version", PromptCatalog.VERSION);
            return new Disambiguation(List.of(), groups);
        }
    }

    private static List<Candidate> rank(
            String mention, List<CatalogItem> catalog, Set<String> knownRules) {
        List<Candidate> ranked = new ArrayList<>();
        for (CatalogItem item : catalog) {
            if (knownRules.contains(item.ruleId())) continue;
            double score = semanticNames(item.names()).stream()
                    .mapToDouble(name -> semanticScore(mention, name)).max().orElse(0.0);
            ranked.add(new Candidate(
                    item.ruleId(), item.canonicalName(), item.conceptCode(), score));
        }
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::ruleId));
        return ranked;
    }

    private static Set<String> semanticNames(List<String> names) {
        Set<String> values = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            values.add(name);
            String reduced = name.replace("患者入院", "").replace("患者", "")
                    .replace("同期", "").replace("的", "");
            if (!reduced.isBlank()) values.add(reduced);
        }
        return values;
    }

    private static List<Segment> candidateSegments(String query) {
        List<Segment> result = new ArrayList<>();
        int offset = 0;
        for (String part : SEGMENT_SPLIT.split(query)) {
            String raw = part.strip();
            if (raw.isBlank()) continue;
            int start = query.indexOf(raw, offset);
            start = Math.max(0, start);
            offset = start + raw.length();
            String[] conjunction = raw.split("和|与");
            List<String> cleanedParts = java.util.Arrays.stream(conjunction)
                    .map(HybridIndicatorResolver::cleanSegment).toList();
            if (cleanedParts.size() == 2 && cleanedParts.stream()
                    .allMatch(value -> !value.isBlank() && INDICATOR_HINT.matcher(value).find())) {
                int localOffset = 0;
                for (int index = 0; index < conjunction.length; index++) {
                    int localStart = raw.indexOf(conjunction[index], localOffset);
                    localOffset = localStart + conjunction[index].length();
                    String cleaned = cleanedParts.get(index);
                    result.add(new Segment(start + Math.max(0, localStart), cleaned));
                }
                continue;
            }
            String cleaned = cleanSegment(raw);
            if (!cleaned.isBlank() && INDICATOR_HINT.matcher(cleaned).find()) {
                result.add(new Segment(start, cleaned));
            }
        }
        return result;
    }

    private static String cleanSegment(String value) {
        String cleaned = ACTION_PREFIX.matcher(value == null ? "" : value.strip()).replaceFirst("");
        cleaned = TIME_TAIL.matcher(cleaned).replaceFirst("").strip();
        cleaned = ACTION_SUFFIX.matcher(cleaned).replaceFirst("").strip();
        return cleaned.replaceAll("^[\\s,，、;；。？?]+|[\\s,，、;；。？?]+$", "");
    }

    static double semanticScore(String leftValue, String rightValue) {
        String left = compact(leftValue);
        String right = compact(rightValue);
        if (left.isBlank() || right.isBlank()) return 0.0;
        double sequence = 1.0 - ((double) levenshtein(left, right) / Math.max(left.length(), right.length()));
        Set<String> leftGrams = ngrams(left);
        Set<String> rightGrams = ngrams(right);
        Set<String> union = new LinkedHashSet<>(leftGrams);
        union.addAll(rightGrams);
        Set<String> intersection = new LinkedHashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
        double containment = left.contains(right) || right.contains(left)
                ? (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length())
                : 0.0;
        return Math.round(Math.max(sequence, Math.max(jaccard, containment)) * 10_000.0) / 10_000.0;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static Set<String> ngrams(String value) {
        Set<String> grams = new LinkedHashSet<>();
        if (value.length() <= 2) {
            if (!value.isBlank()) grams.add(value);
            return grams;
        }
        for (int index = 0; index < value.length() - 1; index++) {
            grams.add(value.substring(index, index + 2));
        }
        return grams;
    }

    private static String compact(String value) {
        return (value == null ? "" : value.toLowerCase(Locale.ROOT))
                .replaceAll("[^0-9a-z\\u4e00-\\u9fff]+", "");
    }

    private static List<ResolvedIndicator> deduplicate(List<ResolvedIndicator> values) {
        Map<String, ResolvedIndicator> best = new LinkedHashMap<>();
        for (ResolvedIndicator value : values) {
            ResolvedIndicator current = best.get(value.ruleId());
            if (current == null || value.start() < current.start()
                    || (value.start() == current.start() && value.confidence() > current.confidence())) {
                best.put(value.ruleId(), value);
            }
        }
        return best.values().stream().sorted(Comparator
                .comparingInt(ResolvedIndicator::start)
                .thenComparing(ResolvedIndicator::ruleId)).toList();
    }

    private static boolean truth(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    public record ResolvedIndicator(
            String mention,
            String canonicalName,
            String ruleId,
            String conceptCode,
            String source,
            double confidence,
            int start,
            int end) {
    }

    public record Candidate(
            String ruleId,
            String canonicalName,
            String conceptCode,
            double score) {
    }

    public record Ambiguity(String mention, List<Candidate> candidates) {
        public Ambiguity { candidates = List.copyOf(candidates); }
    }

    public record Resolution(
            List<ResolvedIndicator> indicators,
            List<Ambiguity> ambiguities,
            boolean usedLlm,
            String releaseVersion) {
        public Resolution {
            indicators = List.copyOf(indicators);
            ambiguities = List.copyOf(ambiguities);
            releaseVersion = releaseVersion == null ? "" : releaseVersion;
        }
        static Resolution empty() { return new Resolution(List.of(), List.of(), false, ""); }
        public boolean needsClarification() { return !ambiguities.isEmpty(); }
    }

    private record CatalogItem(
            String ruleId, String conceptCode, String canonicalName, List<String> names) {
    }
    private static final class CatalogBuilder {
        private final String ruleId;
        private final String conceptCode;
        private String canonicalName;
        private final Set<String> names = new LinkedHashSet<>();
        private CatalogBuilder(String ruleId, String conceptCode, String canonicalName) {
            this.ruleId = ruleId;
            this.conceptCode = conceptCode;
            this.canonicalName = canonicalName;
        }
        private CatalogItem build() {
            return new CatalogItem(ruleId, conceptCode, canonicalName, List.copyOf(names));
        }
    }
    private record Span(int start, int end) {
        private boolean overlaps(Span other) { return start < other.end && end > other.start; }
        private boolean overlaps(Segment other) { return start < other.end() && end > other.start(); }
    }
    private record Segment(int start, String text) { private int end() { return start + text.length(); } }
    private record ExactMatch(int start, int end, String mention, CatalogItem item) {
        private int length() { return end - start; }
    }
    private record Disambiguation(List<ResolvedIndicator> resolved, List<Ambiguity> remaining) { }
}
