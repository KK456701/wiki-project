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
 * 启动时扫描 knowledge-index/concepts/*.md，解析指标概念页补充元数据。
 *
 * <p>职责边界：只读解析概念页中的「指标意义」「计量单位」等 entities/ 没有的字段；
 * 不修改知识库文件、不执行 SQL、不访问网络。</p>
 */
@Component
public class ConceptPageParser {

    private static final Logger log = LoggerFactory.getLogger(ConceptPageParser.class);
    private static final String CONCEPTS_PATTERN =
            "classpath:knowledge-index/concepts/*.md";
    private static final Pattern CODE_COMMENT = Pattern.compile(
            "<!--\\s*concept:\\s*(HXZD-\\d{3}-\\d{3})");
    private static final Pattern TITLE_CODE = Pattern.compile(
            "title:\\s*\"(HXZD-\\d{3}-\\d{3})");

    private final Map<String, ConceptPageData> conceptsByCode;

    public ConceptPageParser() {
        this.conceptsByCode = loadAll();
    }

    public Map<String, ConceptPageData> getAllConcepts() {
        return Collections.unmodifiableMap(conceptsByCode);
    }

    public ConceptPageData getConcept(String indicatorCode) {
        return conceptsByCode.get(indicatorCode);
    }

    public int size() {
        return conceptsByCode.size();
    }

    private Map<String, ConceptPageData> loadAll() {
        Map<String, ConceptPageData> map = new LinkedHashMap<>();
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(CONCEPTS_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    ConceptPageData data = parse(content);
                    if (data != null) {
                        map.putIfAbsent(data.code(), data);
                    }
                } catch (Exception exception) {
                    log.warn("解析概念页失败 {}: {}", filename, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法扫描 knowledge-index/concepts 目录", exception);
        }
        log.info("领导知识库概念页加载完成: {} 个指标", map.size());
        return map;
    }

    private ConceptPageData parse(String content) {
        String code = extractCode(content);
        if (code == null) {
            return null;
        }

        Map<String, String> sections = splitSections(content);
        String significance = sectionText(sections, "指标意义");
        String monitorParams = sectionText(sections, "监测参数");
        String unit = extractUnit(monitorParams);
        String definition = sectionText(sections, "指标定义");
        String formula = sectionText(sections, "计算公式");

        return new ConceptPageData(code, definition, formula, significance, unit);
    }

    private String extractCode(String content) {
        Matcher m = CODE_COMMENT.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        Matcher titleMatcher = TITLE_CODE.matcher(content);
        if (titleMatcher.find()) {
            return titleMatcher.group(1);
        }
        return null;
    }

    /**
     * 从监测参数表格中提取计量单位。
     */
    private String extractUnit(String monitorParams) {
        if (monitorParams == null || monitorParams.isBlank()) {
            return "";
        }
        for (String line : monitorParams.split("\n")) {
            if (line.contains("计量单位")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 3) {
                    return parts[2].strip();
                }
            }
        }
        return "";
    }

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
     * 概念页补充数据。
     */
    public record ConceptPageData(
            String code,
            String definition,
            String formula,
            String significance,
            String unit
    ) {}
}
