package com.hospital.wikiagent.details;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.SnapshotPayload;
import com.hospital.wikiagent.details.DetailContracts.SnapshotSummary;
import com.hospital.wikiagent.details.UploadDetailComparator.MatchedRow;
import com.hospital.wikiagent.details.UploadDetailComparator.RowComparison;

@Component
public class XlsxWorkbookWriter {
    public Path writeIndicatorWorkbook(
            Path path,
            SnapshotPayload payload,
            String actorId) {
        SnapshotSummary summary = payload.summary();
        List<Map<String, Object>> rows = payload.rows();
        long numerator = rows.stream().filter(XlsxWorkbookWriter::meets).count();
        if (rows.size() != summary.denominatorCount()
                || numerator != summary.numeratorCount()) {
            throw new IllegalArgumentException("快照明细与聚合数量不一致，不能生成 Excel");
        }
        List<SheetData> sheets = List.of(
                sheet("统计范围_" + rows.size(), "本次指标计算纳入的全部记录", summary, rows, actorId),
                sheet("达到要求_" + numerator, "在本院口径规定时间或条件内达到要求的记录",
                        summary, rows.stream().filter(XlsxWorkbookWriter::meets).toList(), actorId),
                sheet("未达到要求_" + (rows.size() - numerator), "已纳入统计范围、但未达到本院口径要求的记录",
                        summary, rows.stream().filter(row -> !meets(row)).toList(), actorId));
        return write(path, sheets);
    }

    public Path writeUploadComparisonWorkbook(
            Path path,
            RowComparison comparison,
            String uploadedFileName,
            String hospitalId,
            String actorId,
            Instant createdAt) {
        if (!comparison.available()) {
            throw new IllegalArgumentException("当前上传文件不支持逐条差异导出");
        }
        LinkedHashSet<String> systemFields = new LinkedHashSet<>(comparison.commonFields());
        systemFields.addAll(comparison.systemOnlyFields());
        comparison.matchedRows().forEach(item -> systemFields.addAll(item.system().keySet()));
        comparison.systemOnlyRows().forEach(row -> systemFields.addAll(row.keySet()));
        LinkedHashSet<String> uploadedFields = new LinkedHashSet<>(comparison.commonFields());
        uploadedFields.addAll(comparison.uploadedOnlyFields());
        comparison.matchedRows().forEach(item -> uploadedFields.addAll(item.uploaded().keySet()));
        comparison.uploadedOnlyRows().forEach(row -> uploadedFields.addAll(row.keySet()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("指标名称", comparison.systemRuleName());
        metadata.put("指标编号", comparison.systemRuleId());
        metadata.put("适用医院", hospitalId);
        metadata.put("系统统计区间", comparison.systemStatPeriod());
        metadata.put("上传文件统计区间", comparison.uploadedStatPeriod());
        metadata.put("上传文件", uploadedFileName);
        metadata.put("对比层级", "逐条记录");
        metadata.put("逐条匹配字段", String.join("、", comparison.matchingFields()));
        metadata.put("已确认差异", String.join("\n", comparison.confirmedFindings()));
        metadata.put("导出人", actorId);
        metadata.put("导出时间", createdAt.toString());
        List<List<Object>> summaryRows = List.of(
                List.of("双方都有", comparison.bothCount()),
                List.of("仅系统有", comparison.systemOnlyCount()),
                List.of("仅上传文件有", comparison.uploadedOnlyCount()),
                List.of("同一记录但字段值不同", comparison.fieldDifferenceCount()),
                List.of("同一记录但达标判定不同", comparison.classificationDifferenceCount()));

        List<String> matchedHeaders = new ArrayList<>(List.of("匹配键", "字段差异"));
        systemFields.forEach(field -> matchedHeaders.add("系统-" + field));
        uploadedFields.forEach(field -> matchedHeaders.add("上传文件-" + field));
        List<List<Object>> matchedRows = new ArrayList<>();
        for (MatchedRow item : comparison.matchedRows()) {
            List<Object> row = new ArrayList<>();
            row.add(item.key());
            row.add(item.differentFields().isEmpty() ? "无" : String.join("、", item.differentFields()));
            systemFields.forEach(field -> row.add(item.system().get(field)));
            uploadedFields.forEach(field -> row.add(item.uploaded().get(field)));
            matchedRows.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        List<List<Object>> systemOnlyRows = comparison.systemOnlyRows().stream()
                .map(row -> systemFields.stream().map(row::get).toList()).toList();
        List<List<Object>> uploadedOnlyRows = comparison.uploadedOnlyRows().stream()
                .map(row -> uploadedFields.stream().map(row::get).toList()).toList();
        List<SheetData> sheets = List.of(
                new SheetData("对比摘要", metadata, List.of("分类", "数量"), summaryRows),
                comparisonSheet("双方都有_" + comparison.bothCount(),
                        "按匹配字段识别为同一业务记录；字段差异列列出值不一致的字段。",
                        matchedHeaders, matchedRows),
                comparisonSheet("仅系统有_" + comparison.systemOnlyCount(),
                        "当前系统试运行明细中存在、上传文件中未匹配到的记录。",
                        List.copyOf(systemFields), systemOnlyRows),
                comparisonSheet("仅上传文件有_" + comparison.uploadedOnlyCount(),
                        "上传文件中存在、当前系统试运行明细中未匹配到的记录。",
                        List.copyOf(uploadedFields), uploadedOnlyRows));
        return write(path, sheets);
    }

    private static SheetData comparisonSheet(
            String name,
            String description,
            List<String> headers,
            List<List<Object>> rows) {
        return new SheetData(
                safeSheetName(name), Map.of("说明", description), List.copyOf(headers), List.copyOf(rows));
    }

    private Path write(Path path, List<SheetData> sheets) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            try (OutputStream output = Files.newOutputStream(path);
                    ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes(sheets.size()));
                entry(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                        </Relationships>
                        """);
                entry(zip, "xl/workbook.xml", workbook(sheets));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
                entry(zip, "xl/styles.xml", styles());
                for (int index = 0; index < sheets.size(); index++) {
                    entry(zip, "xl/worksheets/sheet" + (index + 1) + ".xml", sheetXml(sheets.get(index)));
                }
            }
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("导出文件生成失败", exception);
        }
    }

    private static SheetData sheet(
            String name,
            String description,
            SnapshotSummary summary,
            List<Map<String, Object>> rows,
            String actorId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("指标名称", summary.ruleName());
        metadata.put("指标编号", summary.ruleId());
        metadata.put("适用医院", summary.hospitalId());
        metadata.put("口径来源与版本", version(summary));
        metadata.put("来源数据库", summary.sourceDatabase());
        metadata.put("取数表", String.join("、", summary.sourceTables()));
        metadata.put("统计区间", summary.statStart() + " 至 " + summary.statEnd() + "（不含结束时刻）");
        metadata.put("明细快照时间", summary.createdAt().toString());
        metadata.put("导出人", actorId);
        metadata.put("本表说明", description);
        metadata.put("记录总数", rows.size());
        List<String> headers = new ArrayList<>();
        summary.columns().forEach(column -> headers.add(column.label()));
        headers.add("是否达到要求");
        List<List<Object>> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> cells = new ArrayList<>();
            for (DetailColumn column : summary.columns()) {
                cells.add(row.get(column.field()));
            }
            cells.add(meets(row) ? "是" : "否");
            values.add(List.copyOf(cells));
        }
        return new SheetData(safeSheetName(name), metadata, headers, List.copyOf(values));
    }

    private static String sheetXml(SheetData sheet) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                """);
        int headerRow = sheet.metadata().size() + 2;
        int dataStart = headerRow + 1;
        xml.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"")
                .append(headerRow).append("\" topLeftCell=\"A").append(dataStart)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        xml.append("<sheetData>");
        int row = 1;
        for (Map.Entry<String, Object> item : sheet.metadata().entrySet()) {
            xml.append("<row r=\"").append(row).append("\">")
                    .append(cell(0, row, item.getKey(), 1))
                    .append(cell(1, row, item.getValue(), 0))
                    .append("</row>");
            row++;
        }
        row++;
        xml.append("<row r=\"").append(headerRow).append("\">");
        for (int column = 0; column < sheet.headers().size(); column++) {
            xml.append(cell(column, headerRow, sheet.headers().get(column), 2));
        }
        xml.append("</row>");
        for (List<Object> values : sheet.rows()) {
            xml.append("<row r=\"").append(row).append("\">");
            for (int column = 0; column < values.size(); column++) {
                xml.append(cell(column, row, values.get(column), 0));
            }
            xml.append("</row>");
            row++;
        }
        xml.append("</sheetData>");
        int lastRow = Math.max(headerRow, row - 1);
        xml.append("<autoFilter ref=\"A").append(headerRow).append(":")
                .append(columnName(Math.max(0, sheet.headers().size() - 1)))
                .append(lastRow).append("\"/>");
        xml.append("</worksheet>");
        return xml.toString();
    }

    private static String cell(int column, int row, Object rawValue, int style) {
        Object value = safeValue(rawValue);
        String reference = columnName(column) + row;
        String styleAttribute = style == 0 ? "" : " s=\"" + style + "\"";
        if (value == null) {
            return "<c r=\"" + reference + "\"" + styleAttribute + "/>";
        }
        if (value instanceof Number number && !(value instanceof Long longValue
                && Math.abs(longValue) >= 1_000_000_000_000_000L)) {
            return "<c r=\"" + reference + "\"" + styleAttribute + "><v>"
                    + numberText(number) + "</v></c>";
        }
        if (value instanceof Boolean bool) {
            return "<c r=\"" + reference + "\" t=\"b\"" + styleAttribute + "><v>"
                    + (bool ? "1" : "0") + "</v></c>";
        }
        return "<c r=\"" + reference + "\" t=\"inlineStr\"" + styleAttribute
                + "><is><t xml:space=\"preserve\">" + xml(String.valueOf(value)) + "</t></is></c>";
    }

    private static Object safeValue(Object value) {
        if (value instanceof String text) {
            String safe = text.length() > 32_767 ? text.substring(0, 32_767) : text;
            if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
                return "'" + safe;
            }
            return safe;
        }
        if (value instanceof Long number && Math.abs(number) >= 1_000_000_000_000_000L) {
            return String.valueOf(number);
        }
        return value;
    }

    private static String numberText(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    private static String workbook(List<SheetData> sheets) {
        StringBuilder value = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
                """);
        for (int index = 0; index < sheets.size(); index++) {
            value.append("<sheet name=\"").append(xmlAttribute(sheets.get(index).name()))
                    .append("\" sheetId=\"").append(index + 1).append("\" r:id=\"rId")
                    .append(index + 1).append("\"/>");
        }
        return value.append("</sheets></workbook>").toString();
    }

    private static String workbookRelationships(int count) {
        StringBuilder value = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                """);
        for (int index = 1; index <= count; index++) {
            value.append("<Relationship Id=\"rId").append(index)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(index).append(".xml\"/>");
        }
        value.append("<Relationship Id=\"rId").append(count + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return value.append("</Relationships>").toString();
    }

    private static String contentTypes(int count) {
        StringBuilder value = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                """);
        for (int index = 1; index <= count; index++) {
            value.append("<Override PartName=\"/xl/worksheets/sheet").append(index)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return value.append("</Types>").toString();
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF087F78"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static boolean meets(Map<String, Object> row) {
        Object value = row.get("__meets_numerator");
        return value instanceof Number number
                ? number.intValue() == 1
                : "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String version(SnapshotSummary summary) {
        if ("hospital".equals(summary.effectiveLevel()) && summary.hospitalVersion() != null) {
            return "本院口径 v" + summary.hospitalVersion()
                    + (summary.nationalVersion() == null ? "" : "；标准版本 v" + summary.nationalVersion());
        }
        return "标准口径 v" + (summary.nationalVersion() == null ? "-" : summary.nationalVersion());
    }

    private static String safeSheetName(String value) {
        String safe = value.replaceAll("[\\[\\]:*?/\\\\]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private static String columnName(int value) {
        StringBuilder result = new StringBuilder();
        int current = value + 1;
        while (current > 0) {
            result.insert(0, (char) ('A' + (current - 1) % 26));
            current = (current - 1) / 26;
        }
        return result.toString();
    }

    private static String xml(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD
                    || codePoint >= 0x10000 && codePoint <= 0x10FFFF) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlAttribute(String value) {
        return xml(value).replace("\"", "&quot;").replace("'", "&apos;");
    }

    private record SheetData(
            String name,
            Map<String, Object> metadata,
            List<String> headers,
            List<List<Object>> rows) {
    }
}
