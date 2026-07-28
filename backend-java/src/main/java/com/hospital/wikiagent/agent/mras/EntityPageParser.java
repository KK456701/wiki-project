package com.hospital.wikiagent.agent.mras;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
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
            "^(HXZD-\\d{3}-\\d{3})_(.+?)(?:_([^_]+))?\\.md$");
    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n(.*?)```", Pattern.DOTALL);

    private final Map<String, EntityPageData> entitiesByCode;

    public EntityPageParser() {
        this.entitiesByCode = loadAll();
    }

    /**
     * 获取所有已解析的实体页（按指标编码索引）。
     */
    public Map<String, EntityPageData> getAllEntities() {
        return Collections.unmodifiableMap(entitiesByCode);
    }

    /**
     * 按指标编码获取实体页数据。
     *
     * @param indicatorCode 如 "HXZD-001-001"
     * @return 实体页数据，不存在时返回 null
     */
    public EntityPageData getEntity(String indicatorCode) {
        return entitiesByCode.get(indicatorCode);
    }

    /**
     * 已解析的实体页数量。
     */
    public int size() {
        return entitiesByCode.size();
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
                        map.merge(data.code(), data, EntityPageParser::preferWithSql);
                    }
                } catch (Exception exception) {
                    log.warn("解析实体页失败 {}: {}", filename, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法扫描 knowledge-index-mras/entities 目录", exception);
        }
        log.info("领导知识库实体页加载完成: {} 个指标", map.size());
        return map;
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
        String name = fileMatcher.group(2);
        String dimension = fileMatcher.group(3) == null ? "" : fileMatcher.group(3);

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

        return new EntityPageData(
                code, name, dimension,
                definition, formula, caliber, dataSource, monitorParams,
                sourceTableSql, overviewSql, deptStatSql, patientDetailSql);
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
