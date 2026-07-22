package com.hospital.wikiagent.upload;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

/**
 * 实现 {@code XlsxWorkbookReader} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class XlsxWorkbookReader {
    private static final String OFFICE_RELATIONSHIP_NAMESPACE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final UploadProperties properties;

    public XlsxWorkbookReader(UploadProperties properties) {
        this.properties = properties;
    }

    public WorkbookPreview read(UploadStorage.StoredUpload upload) {
        try (ZipFile zip = new ZipFile(upload.path().toFile())) {
            validateArchive(zip);
            List<String> sharedStrings = readSharedStrings(zip);
            Map<String, String> relationships = readRelationships(zip);
            List<SheetReference> sheetReferences = readSheetReferences(zip, relationships);
            if (sheetReferences.isEmpty()) {
                throw new XlsxParseException("Excel 文件没有可读取的工作表。");
            }
            List<SheetPreview> sheets = new ArrayList<>();
            int totalRows = 0;
            for (SheetReference reference : sheetReferences) {
                SheetPreview sheet = readSheet(zip, reference, sharedStrings);
                sheets.add(sheet);
                totalRows += sheet.rowCount();
            }
            return new WorkbookPreview(
                    upload.fileKey(), upload.originalName(), List.copyOf(sheets), totalRows);
        } catch (XlsxParseException exception) {
            throw exception;
        } catch (IOException | XMLStreamException exception) {
            throw new XlsxParseException("Excel 文件无法安全解析。", exception);
        }
    }

    private void validateArchive(ZipFile zip) {
        int entryCount = 0;
        long uncompressed = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            entryCount++;
            if (entryCount > properties.getMaxEntries()) {
                throw new XlsxParseException("Excel 文件包含过多内部对象。");
            }
            String name = entry.getName().replace('\\', '/');
            if (name.startsWith("/") || name.contains("../") || name.contains("://")) {
                throw new XlsxParseException("Excel 文件包含非法内部路径。");
            }
            long size = entry.getSize();
            if (size < 0) {
                throw new XlsxParseException("Excel 文件内部对象大小不可确认。");
            }
            uncompressed += size;
            if (uncompressed > properties.getMaxUncompressedBytes()) {
                throw new XlsxParseException("Excel 解压后的内容超过安全上限。");
            }
        }
        if (zip.getEntry("[Content_Types].xml") == null
                || zip.getEntry("xl/workbook.xml") == null) {
            throw new XlsxParseException("上传内容不是有效的 .xlsx 工作簿。");
        }
    }

    private List<String> readSharedStrings(ZipFile zip)
            throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = xmlFactory().createXMLStreamReader(input);
            StringBuilder current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "si".equals(reader.getLocalName())) {
                    current = new StringBuilder();
                } else if (event == XMLStreamConstants.START_ELEMENT
                        && "t".equals(reader.getLocalName()) && current != null) {
                    current.append(reader.getElementText());
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "si".equals(reader.getLocalName()) && current != null) {
                    values.add(limitText(current.toString()));
                    current = null;
                }
            }
            reader.close();
        }
        return List.copyOf(values);
    }

    private Map<String, String> readRelationships(ZipFile zip)
            throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (entry == null) {
            throw new XlsxParseException("Excel 工作簿缺少工作表关系定义。");
        }
        Map<String, String> values = new LinkedHashMap<>();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = xmlFactory().createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"Relationship".equals(reader.getLocalName())) {
                    continue;
                }
                String targetMode = attribute(reader, null, "TargetMode");
                if ("External".equalsIgnoreCase(targetMode)) {
                    continue;
                }
                String id = attribute(reader, null, "Id");
                String target = attribute(reader, null, "Target");
                if (id != null && target != null) {
                    values.put(id, normalizeWorkbookTarget(target));
                }
            }
            reader.close();
        }
        return Map.copyOf(values);
    }

    private List<SheetReference> readSheetReferences(
            ZipFile zip,
            Map<String, String> relationships) throws IOException, XMLStreamException {
        List<SheetReference> result = new ArrayList<>();
        try (InputStream input = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
            XMLStreamReader reader = xmlFactory().createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"sheet".equals(reader.getLocalName())) {
                    continue;
                }
                String name = limitText(attribute(reader, null, "name"));
                String relationId = attribute(reader, OFFICE_RELATIONSHIP_NAMESPACE, "id");
                if (relationId == null) {
                    relationId = attribute(reader, null, "id");
                }
                String target = relationships.get(relationId);
                if (target != null && target.startsWith("xl/worksheets/")) {
                    result.add(new SheetReference(name == null ? "Sheet" : name, target));
                }
            }
            reader.close();
        }
        return List.copyOf(result);
    }

    private SheetPreview readSheet(
            ZipFile zip,
            SheetReference reference,
            List<String> sharedStrings) throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry(reference.target());
        if (entry == null) {
            throw new XlsxParseException("Excel 工作表对象不存在。");
        }
        List<List<Object>> rows = new ArrayList<>();
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = xmlFactory().createXMLStreamReader(input);
            List<Object> row = null;
            int column = -1;
            String cellType = null;
            String value = null;
            StringBuilder inlineText = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            column = columnIndex(attribute(reader, null, "r"));
                            cellType = attribute(reader, null, "t");
                            value = null;
                            inlineText = null;
                        }
                        case "v" -> value = limitText(reader.getElementText());
                        case "is" -> inlineText = new StringBuilder();
                        case "t" -> {
                            if (inlineText != null) {
                                inlineText.append(reader.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("c".equals(reader.getLocalName()) && row != null
                            && column >= 0 && column < properties.getMaxColumns()) {
                        while (row.size() <= column) {
                            row.add(null);
                        }
                        row.set(column, cellValue(cellType, value, inlineText, sharedStrings));
                    } else if ("row".equals(reader.getLocalName()) && row != null) {
                        rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
                        row = null;
                        if (rows.size() >= properties.getMaxRowsPerSheet()) {
                            break;
                        }
                    }
                }
            }
            reader.close();
        }
        return buildPreview(reference.name(), rows);
    }

    private SheetPreview buildPreview(String name, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return new SheetPreview(name, List.of(), 0, Map.of(), Map.of(), List.of(), false);
        }
        int detailHeader = detailHeaderIndex(rows);
        int headerIndex = detailHeader >= 0 ? detailHeader : 0;
        List<String> headers = rows.get(headerIndex).stream()
                .map(XlsxWorkbookReader::cellText)
                .limit(properties.getMaxColumns())
                .toList();
        List<List<Object>> dataRows = rows.stream()
                .skip(headerIndex + 1L)
                .filter(XlsxWorkbookReader::hasValue)
                .toList();
        Map<String, NumericStats> numeric = numericStats(headers, dataRows);
        Map<String, Object> metadata = detailHeader >= 0
                ? metadata(rows.subList(0, detailHeader))
                : Map.of();
        return new SheetPreview(
                name,
                headers,
                dataRows.size(),
                numeric,
                metadata,
                List.copyOf(dataRows),
                detailHeader >= 0);
    }

    private static Map<String, NumericStats> numericStats(
            List<String> headers,
            List<List<Object>> rows) {
        Map<String, NumericStats> result = new LinkedHashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            String header = headers.get(column);
            if (header.isBlank()) {
                continue;
            }
            List<Double> values = new ArrayList<>();
            for (List<Object> row : rows) {
                if (column < row.size() && row.get(column) instanceof Number number) {
                    values.add(number.doubleValue());
                }
            }
            if (!values.isEmpty()) {
                double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                result.put(header, new NumericStats(
                        values.stream().mapToDouble(Double::doubleValue).min().orElse(0),
                        values.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                        sum,
                        sum / values.size(),
                        values.size()));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> metadata(List<List<Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            if (row.size() < 2) {
                continue;
            }
            String key = cellText(row.get(0));
            if (!key.isBlank()) {
                result.put(key, row.get(1));
            }
        }
        return Map.copyOf(result);
    }

    private static int detailHeaderIndex(List<List<Object>> rows) {
        for (int index = 0; index < rows.size(); index++) {
            List<String> values = rows.get(index).stream()
                    .map(XlsxWorkbookReader::cellText)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (values.size() >= 2 && values.contains("是否达到要求")) {
                return index;
            }
        }
        return -1;
    }

    private static Object cellValue(
            String type,
            String raw,
            StringBuilder inlineText,
            List<String> sharedStrings) {
        if (inlineText != null) {
            return limitText(inlineText.toString());
        }
        if (raw == null) {
            return null;
        }
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw);
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
            } catch (NumberFormatException exception) {
                return "";
            }
        }
        if ("b".equals(type)) {
            return "1".equals(raw);
        }
        if ("str".equals(type) || "e".equals(type)) {
            return raw;
        }
        try {
            BigDecimal decimal = new BigDecimal(raw);
            return decimal.scale() <= 0 ? decimal.longValueExact() : decimal.doubleValue();
        } catch (ArithmeticException | NumberFormatException exception) {
            return raw;
        }
    }

    private static String normalizeWorkbookTarget(String target) {
        String normalized = target.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("xl/")) {
            normalized = "xl/" + normalized;
        }
        Path path = Path.of(normalized).normalize();
        String value = path.toString().replace('\\', '/');
        if (!value.startsWith("xl/") || value.contains("../")) {
            throw new XlsxParseException("Excel 工作表关系包含非法路径。");
        }
        return value;
    }

    private static int columnIndex(String cellReference) {
        if (cellReference == null || cellReference.isBlank()) {
            return 0;
        }
        int value = 0;
        int length = 0;
        for (int index = 0; index < cellReference.length(); index++) {
            char current = Character.toUpperCase(cellReference.charAt(index));
            if (current < 'A' || current > 'Z') {
                break;
            }
            value = value * 26 + current - 'A' + 1;
            length++;
        }
        return length == 0 ? 0 : value - 1;
    }

    private static String attribute(XMLStreamReader reader, String namespace, String name) {
        String value = namespace == null
                ? reader.getAttributeValue(null, name)
                : reader.getAttributeValue(namespace, name);
        if (value != null || namespace != null) {
            return value;
        }
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (name.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    private static boolean hasValue(List<Object> row) {
        return row.stream().anyMatch(value -> value != null && !cellText(value).isBlank());
    }

    private static String cellText(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String limitText(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 4_096 ? value.substring(0, 4_096) : value;
    }

    private record SheetReference(String name, String target) {
    }

    public record WorkbookPreview(
            String fileKey,
            String fileName,
            List<SheetPreview> sheets,
            int totalRows) {
        public WorkbookPreview {
            sheets = List.copyOf(sheets);
        }
    }

    public record SheetPreview(
            String name,
            List<String> headers,
            int rowCount,
            Map<String, NumericStats> numericColumns,
            Map<String, Object> metadata,
            List<List<Object>> rows,
            boolean detailExport) {
        public SheetPreview {
            headers = List.copyOf(headers);
            numericColumns = Collections.unmodifiableMap(new LinkedHashMap<>(numericColumns));
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            rows = List.copyOf(rows);
        }
    }

    public record NumericStats(double min, double max, double sum, double average, int count) {
    }

    public static class XlsxParseException extends RuntimeException {
        public XlsxParseException(String message) {
            super(message);
        }

        public XlsxParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
