package com.hospital.wikiagent.details;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.SnapshotSummary;
import com.hospital.wikiagent.details.UploadDetailComparator.SystemDetailDataset;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.SheetPreview;
import com.hospital.wikiagent.upload.XlsxWorkbookReader.WorkbookPreview;

class UploadDetailComparatorTest {
    private final UploadDetailComparator comparator = new UploadDetailComparator();

    @Test
    void comparesDuplicateSafeMultisetsAndClassificationsWithoutExposingRows() {
        SnapshotSummary summary = summary("MQSI2025_005");
        SystemDetailDataset system = new SystemDetailDataset(summary, List.of(
                raw("C-001", "2026-01-02 08:00:00", 10, 1),
                raw("C-002", "2026-01-03 08:00:00", 30, 0),
                raw("C-003", "2026-01-04 08:00:00", 40, 0)));
        SheetPreview sheet = new SheetPreview(
                "统计范围_3",
                List.of("会诊编号", "请求时间", "到位耗时（分钟）", "是否达到要求"),
                3,
                Map.of(),
                Map.of(
                        "指标名称", "急会诊及时到位率",
                        "指标编号", "MQSI2025_005",
                        "适用医院", "hospital_001",
                        "统计区间", "2026-01-01 00:00:00 至 2026-04-01 00:00:00"),
                List.of(
                        List.of("C-001", "2026-01-02 08:00:00", 10, "是"),
                        List.of("C-002", "2026-01-03 08:00:00", 20, "是"),
                        List.of("C-004", "2026-01-05 08:00:00", 12, "否")),
                true);
        WorkbookPreview workbook = new WorkbookPreview(
                "hospital_001_test.xlsx", "test.xlsx", List.of(sheet), 3);

        var result = comparator.compare(workbook, system);

        assertThat(result.available()).isTrue();
        assertThat(result.matchingFields()).containsExactly("会诊编号", "请求时间");
        assertThat(result.bothCount()).isEqualTo(2);
        assertThat(result.systemOnlyCount()).isEqualTo(1);
        assertThat(result.uploadedOnlyCount()).isEqualTo(1);
        assertThat(result.fieldDifferenceCount()).isEqualTo(1);
        assertThat(result.classificationDifferenceCount()).isEqualTo(1);
        assertThat(result.systemNumeratorCount()).isEqualTo(1);
        assertThat(result.uploadedNumeratorCount()).isEqualTo(2);
        assertThat(result.safeData())
                .containsEntry("both_count", 2)
                .doesNotContainKeys("matched_rows", "system_only_rows", "uploaded_only_rows");
    }

    @Test
    void refusesCrossIndicatorDetailComparison() {
        SheetPreview sheet = new SheetPreview(
                "统计范围_1", List.of("会诊编号", "是否达到要求"), 1, Map.of(),
                Map.of("指标编号", "MQSI2025_001", "指标名称", "另一个指标"),
                List.of(List.of("C-001", "是")), true);

        var result = comparator.compare(
                new WorkbookPreview("key", "other.xlsx", List.of(sheet), 1),
                new SystemDetailDataset(summary("MQSI2025_005"),
                        List.of(raw("C-001", "2026-01-02 08:00:00", 10, 1))));

        assertThat(result.available()).isFalse();
        assertThat(result.status()).isEqualTo("indicator_mismatch");
        assertThat(result.message()).contains("两个指标不能进行逐条比较");
    }

    private static SnapshotSummary summary(String ruleId) {
        return new SnapshotSummary(
                "SNAP_1", "RUN_1", "hospital_001", ruleId, "急会诊及时到位率",
                "hospital", "2025", 1, "2026-01-01 00:00:00", "2026-04-01 00:00:00",
                3, 1, 2,
                List.of(
                        new DetailColumn("consult_id", "会诊编号", "none"),
                        new DetailColumn("request_time", "请求时间", "none"),
                        new DetailColumn("arrive_minutes", "到位耗时（分钟）", "none")),
                Instant.parse("2026-07-21T08:00:00Z"),
                Instant.parse("2026-07-22T08:00:00Z"), false,
                "win60_qa_991827", List.of("INPATIENT_CONSULT_APPLY"));
    }

    private static Map<String, Object> raw(
            String consultId, String requestTime, int arriveMinutes, int meets) {
        return Map.of(
                "consult_id", consultId,
                "request_time", requestTime,
                "arrive_minutes", arriveMinutes,
                "__meets_numerator", meets);
    }
}
