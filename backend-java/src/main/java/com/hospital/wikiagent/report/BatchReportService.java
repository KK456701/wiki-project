package com.hospital.wikiagent.report;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchJobSnapshot;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.IndicatorDetailException;

import jakarta.annotation.PostConstruct;

/**
 * 从不可变批次事实创建版本化报告快照，并用同一快照生成 Word、PDF 和 Excel。
 *
 * <p>服务端负责医院与用户隔离、草稿状态判定、版本号递增和下载审计；报告内容只来源于已经落库的
 * 批次结果，不重新执行指标 SQL，也不采信客户端提交的统计值。三种下载格式共享同一个 JSON 快照，
 * 从而避免各格式之间的数字漂移。</p>
 */
@Service
public class BatchReportService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String EXPORT_PERMISSION = "indicator_detail_export";

    private final BatchJobStore jobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BatchReportService(BatchJobStore jobs, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_batch_report_snapshot (
                  report_id VARCHAR(64) PRIMARY KEY,
                  batch_run_id VARCHAR(64) NOT NULL,
                  hospital_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64) NOT NULL,
                  version_no INT NOT NULL,
                  report_status VARCHAR(20) NOT NULL,
                  report_json TEXT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  UNIQUE (batch_run_id, hospital_id, user_id, version_no)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS med_batch_report_download_audit (
                  audit_id VARCHAR(64) PRIMARY KEY,
                  report_id VARCHAR(64) NOT NULL,
                  hospital_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64) NOT NULL,
                  format VARCHAR(12) NOT NULL,
                  created_at TIMESTAMP NOT NULL
                )
                """);
    }

    public Map<String, Object> createSnapshot(
            HospitalPrincipal principal,
            String batchRunId) {
        BatchJobSnapshot job = jobs.loadJob(
                        batchRunId, principal.hospitalId(), principal.userId())
                .orElseThrow(() -> error(
                        "REPORT_BATCH_NOT_FOUND",
                        "批次不存在或无权访问。",
                        HttpStatus.NOT_FOUND));
        if ("RUNNING".equals(job.status())) {
            throw error(
                    "REPORT_BATCH_NOT_READY",
                    "批次尚未完成，不能生成报告快照。",
                    HttpStatus.CONFLICT);
        }
        List<BatchTaskSnapshot> tasks =
                jobs.loadTasks(batchRunId, principal.hospitalId(), principal.userId());
        int version = nextVersion(batchRunId, principal);
        String reportId = "BRPT_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        String status = isDraft(tasks) ? "DRAFT" : "FORMAL";
        Map<String, Object> report = reportMap(reportId, version, status, job, tasks);
        jdbc.update("""
                INSERT INTO med_batch_report_snapshot (
                  report_id, batch_run_id, hospital_id, user_id, version_no,
                  report_status, report_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reportId,
                batchRunId,
                principal.hospitalId(),
                principal.userId(),
                version,
                status,
                json(report),
                Timestamp.from(Instant.now()));
        return java.util.Collections.unmodifiableMap(report);
    }

    public Map<String, Object> load(
            HospitalPrincipal principal,
            String reportId) {
        List<String> values = jdbc.query(
                """
                SELECT report_json FROM med_batch_report_snapshot
                WHERE report_id=? AND hospital_id=? AND user_id=?
                """,
                (result, row) -> result.getString(1),
                reportId,
                principal.hospitalId(),
                principal.userId());
        return values.stream().findFirst().map(this::map)
                .orElseThrow(() -> error(
                        "REPORT_NOT_FOUND",
                        "报告不存在或无权访问。",
                        HttpStatus.NOT_FOUND));
    }

    public Download download(
            HospitalPrincipal principal,
            String reportId,
            String requestedFormat) {
        requireExport(principal);
        Map<String, Object> report = load(principal, reportId);
        String format = requestedFormat == null
                ? ""
                : requestedFormat.strip().toLowerCase(Locale.ROOT);
        byte[] bytes;
        String contentType;
        String extension;
        switch (format) {
            case "docx" -> {
                bytes = word(report);
                contentType =
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                extension = "docx";
            }
            case "pdf" -> {
                bytes = pdf(report);
                contentType = "application/pdf";
                extension = "pdf";
            }
            case "xlsx" -> {
                bytes = excel(report);
                contentType =
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            default -> throw error(
                    "REPORT_FORMAT_INVALID",
                    "format 仅支持 docx、pdf 或 xlsx。",
                    HttpStatus.BAD_REQUEST);
        }
        jdbc.update("""
                INSERT INTO med_batch_report_download_audit (
                  audit_id, report_id, hospital_id, user_id, format, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                "RDA_" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 16),
                reportId,
                principal.hospitalId(),
                principal.userId(),
                format,
                Timestamp.from(Instant.now()));
        String name = "核心指标报告_" + text(report.get("batchRunId"))
                + "_V" + text(report.get("version")) + "." + extension;
        return new Download(name, contentType, bytes);
    }

    private int nextVersion(String batchRunId, HospitalPrincipal principal) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM med_batch_report_snapshot
                WHERE batch_run_id=? AND hospital_id=? AND user_id=?
                """,
                Integer.class,
                batchRunId,
                principal.hospitalId(),
                principal.userId());
        return value == null ? 1 : value;
    }

    private static boolean isDraft(List<BatchTaskSnapshot> tasks) {
        return tasks.stream().anyMatch(task ->
                !"SUCCESS".equals(task.status())
                        || task.targetValue() == null
                        || (task.qualityStatus() != null
                                && !task.qualityStatus().isBlank()
                                && !"normal".equalsIgnoreCase(task.qualityStatus())));
    }

    private static Map<String, Object> reportMap(
            String reportId,
            int version,
            String status,
            BatchJobSnapshot job,
            List<BatchTaskSnapshot> tasks) {
        Map<String, Integer> resultCounts = counts(tasks);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportId", reportId);
        report.put("version", version);
        report.put("reportStatus", status);
        report.put("batchRunId", job.batchRunId());
        report.put("hospitalId", job.hospitalId());
        report.put("statStart", job.statStart());
        report.put("statEnd", job.statEnd());
        report.put("generatedAt", Instant.now().toString());
        report.put("total", tasks.size());
        report.put("counts", resultCounts);
        report.put("tasks", tasks);
        report.put("statement",
                "本报告所有指标值均来自已固化批次；AI 解释不参与数值计算。");
        return report;
    }

    private static Map<String, Integer> counts(List<BatchTaskSnapshot> tasks) {
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of(
                "success", 0,
                "noSample", 0,
                "failed", 0));
        for (BatchTaskSnapshot task : tasks) {
            String key = "FAILED".equals(task.status())
                    ? "failed"
                    : "NO_SAMPLE".equals(task.status()) ? "noSample" : "success";
            counts.put(key, counts.get(key) + 1);
        }
        return java.util.Collections.unmodifiableMap(counts);
    }

    @SuppressWarnings("unchecked")
    private byte[] word(Map<String, Object> report) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configureWordPage(document);
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Title");
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.setFontSize(18);
            titleRun.setText("核心制度指标调研报告");
            addParagraph(document, "报告状态：" + reportStatus(report)
                    + "　版本：V" + text(report.get("version")));
            addParagraph(document, "批次：" + text(report.get("batchRunId")));
            addParagraph(document, "统计周期：" + text(report.get("statStart"))
                    + " 至 " + text(report.get("statEnd")));
            addParagraph(document, text(report.get("statement")));

            List<Map<String, Object>> tasks =
                    (List<Map<String, Object>>) report.getOrDefault("tasks", List.of());
            XWPFTable table = document.createTable(tasks.size() + 1, 8);
            String[] headers = {
                    "指标编码", "指标名称", "口径", "状态",
                    "结果", "分子", "分母", "详情类型"
            };
            for (int index = 0; index < headers.length; index++) {
                table.getRow(0).getCell(index).setText(headers[index]);
            }
            for (int rowIndex = 0; rowIndex < tasks.size(); rowIndex++) {
                Map<String, Object> task = tasks.get(rowIndex);
                String[] values = taskValues(task);
                for (int column = 0; column < values.length; column++) {
                    table.getRow(rowIndex + 1).getCell(column).setText(values[column]);
                }
            }
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw generationFailure("Word", exception);
        }
    }

    private static void configureWordPage(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        BigInteger standardMargin = BigInteger.valueOf(1134);
        margin.setTop(standardMargin);
        margin.setBottom(standardMargin);
        margin.setLeft(standardMargin);
        margin.setRight(standardMargin);
        margin.setHeader(BigInteger.valueOf(567));
        margin.setFooter(BigInteger.valueOf(567));
        margin.setGutter(BigInteger.ZERO);
    }

    @SuppressWarnings("unchecked")
    private byte[] excel(Map<String, Object> report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("核心指标");
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle wrappedStyle = workbook.createCellStyle();
            wrappedStyle.setWrapText(true);
            wrappedStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle centeredStyle = workbook.createCellStyle();
            centeredStyle.setAlignment(HorizontalAlignment.CENTER);
            centeredStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            String[] headers = {
                    "指标编码", "指标名称", "口径", "状态",
                    "结果", "分子", "分母", "详情类型"
            };
            Row header = sheet.createRow(0);
            header.setHeightInPoints(26);
            for (int index = 0; index < headers.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers[index]);
                cell.setCellStyle(headerStyle);
            }
            List<Map<String, Object>> tasks =
                    (List<Map<String, Object>>) report.getOrDefault("tasks", List.of());
            for (int rowIndex = 0; rowIndex < tasks.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                row.setHeightInPoints(30);
                String[] values = taskValues(tasks.get(rowIndex));
                for (int column = 0; column < values.length; column++) {
                    Cell cell = row.createCell(column);
                    cell.setCellValue(values[column]);
                    cell.setCellStyle(column == 1 || column == 2 || column == 7
                            ? wrappedStyle
                            : centeredStyle);
                }
            }
            int[] widths = {18, 42, 22, 16, 18, 10, 10, 24};
            for (int index = 0; index < widths.length; index++) {
                sheet.setColumnWidth(index, widths[index] * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, tasks.size(), 0, headers.length - 1));
            sheet.setDisplayGridlines(false);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw generationFailure("Excel", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] pdf(Map<String, Object> report) {
        Path fontPath = firstExisting(
                Path.of("C:\\Windows\\Fonts\\simhei.ttf"),
                Path.of("C:\\Windows\\Fonts\\simsunb.ttf"));
        if (fontPath == null) {
            throw error(
                    "REPORT_FONT_MISSING",
                    "未找到可嵌入的中文字体，PDF 已停止生成。",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try (PDDocument document = new PDDocument();
                InputStream fontStream = Files.newInputStream(fontPath);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontStream);
            List<String> lines = new ArrayList<>();
            lines.add("核心制度指标调研报告");
            lines.add("报告状态：" + reportStatus(report)
                    + "  版本：V" + text(report.get("version")));
            lines.add("批次：" + text(report.get("batchRunId")));
            lines.add("统计周期：" + text(report.get("statStart"))
                    + " 至 " + text(report.get("statEnd")));
            lines.add(text(report.get("statement")));
            lines.add("");
            List<Map<String, Object>> tasks =
                    (List<Map<String, Object>>) report.getOrDefault("tasks", List.of());
            for (Map<String, Object> task : tasks) {
                String[] values = taskValues(task);
                lines.add(values[0] + "  " + values[1]
                        + "  状态：" + values[3]
                        + "  结果：" + values[4]
                        + "  " + values[5] + "/" + values[6]);
            }
            writePdfLines(document, font, lines);
            document.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw generationFailure("PDF", exception);
        }
    }

    private static void writePdfLines(
            PDDocument document,
            PDType0Font font,
            List<String> lines) throws Exception {
        final float margin = 48f;
        final float lineHeight = 18f;
        final int linesPerPage = 42;
        for (int start = 0; start < lines.size(); start += linesPerPage) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 10);
                content.newLineAtOffset(margin, page.getMediaBox().getHeight() - margin);
                int end = Math.min(lines.size(), start + linesPerPage);
                for (int index = start; index < end; index++) {
                    String line = truncate(lines.get(index), 82);
                    content.showText(line);
                    content.newLineAtOffset(0, -lineHeight);
                }
                content.endText();
            }
        }
    }

    private static String[] taskValues(Map<String, Object> task) {
        return new String[] {
                text(task.get("ruleId")),
                text(task.get("ruleName")),
                first(text(task.get("profileName")), text(task.get("profileId")), "默认口径"),
                text(task.get("status")),
                first(text(task.get("calculationDisplay")),
                        text(task.get("resultValue")), "—"),
                first(text(task.get("numeratorCount")), "—"),
                first(text(task.get("denominatorCount")), "—"),
                text(task.get("detailKind"))
        };
    }

    private static void addParagraph(XWPFDocument document, String text) {
        XWPFRun run = document.createParagraph().createRun();
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(10);
        run.setText(text);
    }

    private static String reportStatus(Map<String, Object> report) {
        return "FORMAL".equals(text(report.get("reportStatus"))) ? "正式" : "草稿";
    }

    private void requireExport(HospitalPrincipal principal) {
        if (!principal.permissions().contains(EXPORT_PERMISSION)) {
            throw error(
                    "REPORT_EXPORT_FORBIDDEN",
                    "当前账号没有报告下载权限。",
                    HttpStatus.FORBIDDEN);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw generationFailure("报告快照", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw generationFailure("报告快照", exception);
        }
    }

    private static Path firstExisting(Path... paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static IndicatorDetailException generationFailure(
            String type,
            Exception exception) {
        return error(
                "REPORT_GENERATION_FAILED",
                type + " 生成失败。",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static IndicatorDetailException error(
            String code,
            String message,
            HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }

    public record Download(String fileName, String contentType, byte[] bytes) {
    }
}
