package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class MrasDetailSqlExtractorTest {
    private final EntityPageParser entities = new EntityPageParser(new KnowledgeIndexResources(
            Path.of("src", "main", "resources", "knowledge-index_backup_20260801_150233")
                    .toAbsolutePath().toString()));
    private final MrasDetailSqlExtractor extractor = new MrasDetailSqlExtractor(entities);

    @Test
    void allKnowledgeProfilesHaveDeterministicDetailClassification() {
        List<MrasDetailSqlExtractor.DetailExtraction> results =
                entities.getAllEntities().values().stream()
                        .map(entity -> extractor.extract(entity.code(), entity.variantCode()))
                        .toList();

        assertThat(results).hasSize(43);
        assertThat(results.stream().filter(
                MrasDetailSqlExtractor.DetailExtraction::supported)).hasSize(34);
        assertThat(results.stream().filter(result -> !result.supported())).hasSize(9);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.detailKind()).isNotNull();
            assertThat(result.contractVersion())
                    .isEqualTo(MrasDetailContractRegistry.CONTRACT_VERSION);
        });
    }

    @Test
    void distinctOverviewKeepsSameSourceAndStableIdentity() {
        var extraction = extractor.extract("HXZD-004-001", null);

        assertThat(extraction.supported()).isTrue();
        assertThat(extraction.detailKind()).isEqualTo(MrasDetailKind.COUNT_RATIO);
        assertThat(extraction.detailSql()).contains("SELECT");
        assertThat(extraction.detailSql()).contains("DISTINCT");
        assertThat(extraction.detailSql()).contains("__meets_numerator");
        assertThat(extraction.overviewSqlHash()).hasSize(64);
    }

    @Test
    void specialIndicatorsAreExplicitlyTypedAndRejectedFromCountPath() {
        assertKind("HXZD-007-001", null, MrasDetailKind.SUM_CONTRIBUTION);
        assertKind("HXZD-014-001", null, MrasDetailKind.MEDIAN_SAMPLE);
        assertKind("HXZD-012-004", "HXZD-012-004_002", MrasDetailKind.DUAL_SOURCE);
        assertKind("HXZD-012-001", "HXZD-012-001_001", MrasDetailKind.RATE_COMPARISON);
    }

    private void assertKind(String indicator, String profile, MrasDetailKind expected) {
        var extraction = extractor.extract(indicator, profile);
        assertThat(extraction.supported()).isFalse();
        assertThat(extraction.detailKind()).isEqualTo(expected);
        assertThat(extraction.unsupportedReason()).isNotBlank();
    }
}
