package com.hospital.wikiagent.agent.mras;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 启动时扫描 knowledge-index-mras/entities/*.md，解析为结构化 EntityPageData 并缓存。
 *
 * <p>职责边界：只读解析 Markdown 实体页，不修改知识库文件、不执行 SQL、不访问网络。
 * 解析失败的文件仅记录警告日志，不阻塞应用启动。</p>
 */
@Component
public class EntityPageParser {

    private static final Logger log = LoggerFactory.getLogger(EntityPageParser.class);
    private static final String ENTITIES_PATTERN =
            "classpath:knowledge-index-mras/entities/*.md";
    private static final Pattern FILENAME_CODE = Pattern.compile(
            "^(HXZD-\\d{3}-\\d{3})(?:_(\\d{3}))?_(.+?)(?:_([^_]+))?\\.md$");
    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n(.*?)```", Pattern.DOTALL);

    /** 按扩展编码索引（如 HXZD-003-003_001），无变体编号的以基础编码为 key。 */
    private final Map<String, EntityPageData> entitiesByVariantCode;
    /** 按基础编码索引（如 HXZD-001-001），指向主方案。 */
    private final Map<String, EntityPageData> entitiesByBaseCode;

    public EntityPageParser() {
        this.entitiesByVariantCode = loadAll();
        this.entitiesByBaseCode = buildBaseIndex();
    }

    /**
     * 获取所有已解析的实体页（按扩展编码索引）。
     */
    public Map<String, EntityPageData> getAllEntities() {
        return Collections.unmodifiableMap(entitiesByVariantCode);
    }

    /**
     * 按基础指标编码获取主方案实体页数据（兼容旧调用）。
     *
     * @param indicatorCode 如 "HXZD-001-001"
     * @return 主方案实体页数据，不存在时返回 null
     */
    public EntityPageData getEntity(String indicatorCode) {
        EntityPageData direct = entitiesByVariantCode.get(indicatorCode);
        if (direct != null) {
            return direct;
        }
        return entitiesByBaseCode.get(indicatorCode);
    }

    /**
     * 获取同一指标的所有变体方案。
     *
     * @param baseCode 基础编码，如 "HXZD-003-003"
     * @return 变体列表（含主方案），无匹配时返回空列表
     */
    public List<EntityPageData> getVariants(String baseCode) {
        List<EntityPageData> result = new ArrayList<>();
        for (EntityPageData data : entitiesByVariantCode.values()) {
            if (data.code().equals(baseCode)) {
                result.add(data);
            }
        }
        return result;
    }

    /**
     * 已解析的实体页数量（含所有变体）。
     */
    public int size() {
        return entitiesByVariantCode.size();
    }

    /**
     * 去重后的基础指标数量。
     */
    public int baseIndicatorCount() {
        return entitiesByBaseCode.size();
    }

    private Map<String, EntityPageData> loadAll() {
        Map<String, EntityPageData> map = new LinkedHashMap<>();
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(ENTITIES_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    EntityPageData data = parse(filename, content);
                    if (data != null) {
                        map.merge(data.variantCode(), data, EntityPageParser::preferWithSql);
                    }
                } catch (Exception exception) {
                    log.warn("解析实体页失败 {}: {}", filename, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法扫描 knowledge-index-mras/entities 目录", exception);
        }
        log.info("领导知识库实体页加载完成: {} 个实体（含变体）", map.size());
        return map;
    }

    private Map<String, EntityPageData> buildBaseIndex() {
        Map<String, EntityPageData> base = new LinkedHashMap<>();
        for (EntityPageData data : entitiesByVariantCode.values()) {
            base.merge(data.code(), data, (existing, incoming) -> {
                // 优先保留主方案且有 SQL 的
                if (incoming.isPrimary() && incoming.hasOverviewSql()) {
                    return incoming;
                }
                if (existing.hasOverviewSql() && !incoming.hasOverviewSql()) {
                    return existing;
                }
                return existing;
            });
        }
        return base;
    }

    /**
     * 同编码多文件合并策略：优先保留有概览 SQL 的条目（占位空文件不覆盖已实现方案）。
     */
    private static EntityPageData preferWithSql(EntityPageData existing, EntityPageData incoming) {
        if (incoming.hasOverviewSql()) {
            return incoming;
        }
        return existing;
    }

    private EntityPageData parse(String filename, String content) {
        Matcher fileMatcher = FILENAME_CODE.matcher(filename);
        if (!fileMatcher.matches()) {
            log.debug("跳过非标准文件名: {}", filename);
            return null;
        }
        String code = fileMatcher.group(1);
        String variantNum = fileMatcher.group(2); // 001/002 或 null
        String name = fileMatcher.group(3);
        String dimension = fileMatcher.group(4) == null ? "" : fileMatcher.group(4);

        String variantCode = variantNum == null ? code : code + "_" + variantNum;
        String variantLabel = variantNum == null || "001".equals(variantNum)
                ? "推荐方案（公版）" : "变体方案";

        Map<String, String> sections = splitSections(content);

        String definition = sectionText(sections, "指标定义");
        String formula = sectionText(sections, "计算公式");
        String caliber = sectionText(sections, "统计口径");
        String dataSource = sectionText(sections, "数据来源");
        String monitorParams = sectionText(sections, "监测参数");

        String sourceTableSql = extractSql(sections, "源表");
        String overviewSql = extractSql(sections, "目标表-概览");
        String deptStatSql = extractSql(sections, "目标表-科室统计");
        String patientDetailSql = extractSql(sections, "目标表-患者明细");

        // 从 frontmatter tags 解析制度和分类
        String system = extractTag(content, 1); // tags[1] 通常是制度名
        String category = extractCategory(content);
        String eventNo = extractTag(content, 2); // tags[2] 通常是事件编码（如 CORE_FDR）

        // 从“数据来源”章节解析中间表（目标表）和业务表（影响数据）
        String targetTable = extractTableFromDataSource(dataSource, "中间表");
        List<String> bizTables = extractBizTables(dataSource);

        return new EntityPageData(
                code, name, dimension, variantCode, variantLabel,
                definition, formula, caliber, dataSource, monitorParams,
                "", "", system, category,
                sourceTableSql, overviewSql, deptStatSql, patientDetailSql,
                eventNo, targetTable, bizTables);
    }

    /**
     * 从“数据来源”章节文本中提取指定行对应的表名（反引号内内容）。
     */
    private String extractTableFromDataSource(String dataSourceText, String rowLabel) {
        if (dataSourceText == null || dataSourceText.isBlank()) {
            return "";
        }
        for (String line : dataSourceText.split("\n")) {
            if (line.contains(rowLabel)) {
                Matcher m = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*)`").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return "";
    }

    /**
     * 从“数据来源”章节提取“业务表(影响数据)”行中的表名列表。
     */
    private List<String> extractBizTables(String dataSourceText) {
        if (dataSourceText == null || dataSourceText.isBlank()) {
            return List.of();
        }
        for (String line : dataSourceText.split("\n")) {
            if (line.contains("业务表") && line.contains("影响数据") && !line.contains("不影响")) {
                Matcher m = Pattern.compile("`([^`]+)`").matcher(line);
                if (m.find()) {
                    return Arrays.stream(m.group(1).split(","))
                            .map(String::strip)
                            .filter(s -> !s.isBlank())
                            .toList();
                }
            }
        }
        return List.of();
    }

    /**
     * 从 frontmatter tags 数组中提取指定位置的标签。
     */
    private String extractTag(String content, int index) {
        Matcher m = Pattern.compile("tags:\\s*\\[([^]]+)]").matcher(content);
        if (m.find()) {
            String[] tags = m.group(1).split(",");
            if (index < tags.length) {
                return tags[index].strip().replace("\"", "");
            }
        }
        return "";
    }

    /**
     * 从 frontmatter tags 中提取四维分类（时限类/逻辑判定类/内容完整性/AI模型调优）。
     */
    private String extractCategory(String content) {
        Matcher m = Pattern.compile("tags:\\s*\\[([^]]+)]").matcher(content);
        if (m.find()) {
            String tagsStr = m.group(1);
            for (String cat : List.of("时限类", "逻辑判定类", "内容完整性", "AI模型调优")) {
                if (tagsStr.contains(cat)) {
                    return cat;
                }
            }
        }
        return "";
    }

    /**
     * 按 "## " 前缀切割 Markdown 为章节 Map（标题 → 内容）。
     */
    private Map<String, String> splitSections(String markdown) {
        Map<String, String> sections = new LinkedHashMap<>();
        String[] lines = markdown.split("\\n");
        String currentTitle = "";
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (!currentTitle.isEmpty()) {
                    sections.put(currentTitle, currentContent.toString());
                }
                currentTitle = line.substring(3).strip();
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }
        if (!currentTitle.isEmpty()) {
            sections.put(currentTitle, currentContent.toString());
        }
        return sections;
    }

    private String sectionText(Map<String, String> sections, String title) {
        String text = sections.get(title);
        return text == null ? "" : text.strip();
    }

    /**
     * 从章节中提取所有 ```sql ... ``` 代码块，多段用换行拼接。
     */
    private String extractSql(Map<String, String> sections, String title) {
        String sectionContent = sections.get(title);
        if (sectionContent == null || sectionContent.isBlank()) {
            return "";
        }
        Matcher matcher = SQL_BLOCK.matcher(sectionContent);
        StringBuilder sql = new StringBuilder();
        while (matcher.find()) {
            if (!sql.isEmpty()) {
                sql.append("\n");
            }
            sql.append(matcher.group(1).strip());
        }
        return sql.toString();
    }
}
