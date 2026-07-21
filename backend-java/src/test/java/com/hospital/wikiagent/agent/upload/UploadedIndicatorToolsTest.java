package com.hospital.wikiagent.agent.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.upload.UploadProperties;
import com.hospital.wikiagent.upload.UploadStorage;
import com.hospital.wikiagent.upload.XlsxWorkbookReader;

class UploadedIndicatorToolsTest {
    @TempDir
    Path temporary;

    @Test
    void parsesAggregateWorkbookAndComparesOnlyConfirmedValues() throws Exception {
        Fixture fixture = fixture();
        byte[] workbook = workbook(List.of(
                List.of("分子", "分母", "rate_pct"),
                List.of(30, 522, 0.0575)));
        UploadStorage.StoredUpload upload = fixture.storage().store(
                new MockMultipartFile(
                        "file", "测试.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        workbook),
                principal("hospital_001"));
        AgentRunState state = new AgentRunState();
        state.subtaskId("SUB_1");
        state.currentUploadFileKey(upload.fileKey());
        state.lastToolResults().add(ToolResult.success(
                "TRIAL_RUN_COMPLETED", "完成", Map.of(
                        "rule_id", "MQSI2025_001",
                        "stat_start", "2026-01-01 00:00:00",
                        "stat_end", "2026-07-22 00:00:00",
                        "numerator_count", 11,
                        "denominator_count", 389,
                        "result_value", 2.83)));

        ToolResult result = fixture.tools().analyze(
                new UploadedIndicatorTools.Input(upload.fileKey()),
                execution(state, "hospital_001"));

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("UPLOAD_ANALYZED");
        assertThat(result.data())
                .containsEntry("uploaded_numerator", 30.0)
                .containsEntry("uploaded_denominator", 522.0)
                .containsEntry("uploaded_rate", 5.75)
                .containsEntry("system_numerator", 11.0)
                .containsEntry("system_denominator", 389.0)
                .containsEntry("system_rate", 2.83)
                .containsEntry("comparison_level", "aggregate")
                .containsEntry("different_count", 3)
                .containsEntry("row_level_comparison_available", false)
                .containsEntry("cause_analysis_available", false);
        assertThat(result.data().get("cause_analysis_note").toString())
                .contains("不能推测重复记录", "ICU");
        assertThat((List<?>) result.data().get("comparison_metrics")).hasSize(3);
        assertThat(result.data()).doesNotContainKeys("rows", "patient_rows");
    }

    @Test
    void analyzesWorkbookWithoutInventingSystemComparison() throws Exception {
        Fixture fixture = fixture();
        UploadStorage.StoredUpload upload = fixture.storage().store(
                new MockMultipartFile("file", "汇总.xlsx", null, workbook(List.of(
                        List.of("numerator", "denominator", "rate"),
                        List.of(1, 4, 25)))),
                principal("hospital_001"));
        AgentRunState state = new AgentRunState();
        state.subtaskId("SUB_2");
        state.currentUploadFileKey(upload.fileKey());

        ToolResult result = fixture.tools().analyze(
                new UploadedIndicatorTools.Input(upload.fileKey()),
                execution(state, "hospital_001"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .containsEntry("comparison_level", "none")
                .containsEntry("comparison_status", "system_result_missing")
                .doesNotContainKeys("system_numerator", "system_denominator", "system_rate");
    }

    @Test
    void storageRejectsCrossHospitalAndLegacyBinaryExcel() throws Exception {
        Fixture fixture = fixture();
        UploadStorage.StoredUpload upload = fixture.storage().store(
                new MockMultipartFile("file", "安全.xlsx", null, workbook(List.of(
                        List.of("分子", "分母"), List.of(1, 2)))),
                principal("hospital_001"));

        assertThatThrownBy(() -> fixture.storage().requireOwned(upload.fileKey(), "hospital_002"))
                .isInstanceOf(UploadStorage.UploadAccessException.class)
                .hasMessageContaining("其他医院");
        assertThatThrownBy(() -> fixture.storage().store(
                new MockMultipartFile("file", "旧格式.xls", null, new byte[] {1, 2, 3}),
                principal("hospital_001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
    }

    private Fixture fixture() {
        UploadProperties properties = new UploadProperties();
        properties.setRoot(temporary.resolve("uploads"));
        UploadStorage storage = new UploadStorage(properties);
        return new Fixture(
                storage,
                new UploadedIndicatorTools(storage, new XlsxWorkbookReader(properties)));
    }

    private static ToolExecutionContext execution(AgentRunState state, String hospitalId) {
        AgentRuntimeContext context = new AgentRuntimeContext(
                principal(hospitalId), "REQ_1", "TRACE_1", "business_test");
        return new ToolExecutionContext(
                context, state.subtaskId(), state,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOWED", "允许", "test"));
    }

    private static HospitalPrincipal principal(String hospitalId) {
        return new HospitalPrincipal(
                "user_001", "doctor", hospitalId, Set.of(), false, "AUTH_1");
    }

    private static byte[] workbook(List<List<Object>> rows) throws IOException {
        StringBuilder sheet = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                """);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
            List<Object> row = rows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                String reference = columnName(column) + (rowIndex + 1);
                Object value = row.get(column);
                if (value instanceof Number) {
                    sheet.append("<c r=\"").append(reference).append("\"><v>")
                            .append(value).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(reference)
                            .append("\" t=\"inlineStr\"><is><t>")
                            .append(xml(String.valueOf(value))).append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>
                    """);
            add(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            add(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Target="worksheets/sheet1.xml"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>
                    </Relationships>
                    """);
            add(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Fixture(UploadStorage storage, UploadedIndicatorTools tools) {
    }
}
