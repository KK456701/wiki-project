package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;

class IndicatorDataFlowBuilderTest {

    private static EntityPageParser entities;
    private static IndicatorDataFlowBuilder builder;

    @BeforeAll
    static void loadBackupKnowledgeIndex() {
        String root = Path.of("src/main/resources/knowledge-index_backup_20260801_150233")
                .toAbsolutePath().normalize().toString();
        entities = new EntityPageParser(new KnowledgeIndexResources(root));
        builder = new IndicatorDataFlowBuilder(new MrasSqlLineageAnalyzer());
    }

    @Test
    void backupKnowledgeIndexIsTheExpected43Profiles() {
        assertThat(entities.size()).isEqualTo(43);
        Map<String, Long> counts = entities.getAllEntities().values().stream()
                .map(builder::build)
                .collect(Collectors.groupingBy(
                        flow -> String.valueOf(flow.get("templateType")),
                        Collectors.counting()));
        assertThat(counts).containsEntry("EVENT_TO_TARGET", 13L)
                .containsEntry("DIRECT_TO_TARGET", 26L)
                .containsEntry("DIRECT_REAL_QUERY", 3L)
                .containsEntry("INCOMPLETE", 1L);
    }

    @Test
    void threeKnowledgeCorrectionsAreEffective() {
        EntityPageData direct = entities.getEntity("HXZD-010-001");
        assertThat(direct.sourceTableSql()).isBlank();
        assertThat(direct.overviewSql()).contains("INP_CLI_ORDER");
        assertThat(builder.build(direct).get("templateType")).isEqualTo("DIRECT_REAL_QUERY");

        EntityPageData unavailable = entities.getEntity("HXZD-009-002_002");
        assertThat(unavailable.sourceTableSql()).isBlank();
        assertThat(unavailable.overviewSql()).isBlank();
        assertThat(unavailable.deptStatSql()).isBlank();
        assertThat(unavailable.patientDetailSql()).isBlank();
        assertThat(builder.build(unavailable).get("templateType")).isEqualTo("INCOMPLETE");

        EntityPageData fixedWeek = entities.getEntity("HXZD-009-003_002");
        assertThat(fixedWeek.sourceTableSql()).contains("19000101")
                .doesNotContainIgnoringCase("SET DATEFIRST")
                .doesNotContainIgnoringCase("DATEPART(WEEKDAY");
    }

    @Test
    void configuredMissingDirectoryFailsClosed() {
        String missing = Path.of("target/definitely-missing-knowledge-index")
                .toAbsolutePath().normalize().toString();
        assertThatThrownBy(() -> new KnowledgeIndexResources(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("知识库目录不存在");
    }
}
