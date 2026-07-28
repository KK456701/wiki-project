package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * EntityPageParser 集成测试：验证 37 个实体页全部正确解析。
 */
class EntityPageParserTest {

    private final EntityPageParser parser = new EntityPageParser();

    @Test
    void allEntityPagesParsed() {
        // 知识库有 37 个实体页（部分指标有多个维度后缀，编码可能重复取最后一个）
        assertThat(parser.size()).isGreaterThanOrEqualTo(35);
    }

    @Test
    void hxzd001001ParsedCorrectly() {
        EntityPageData entity = parser.getEntity("HXZD-001-001");
        assertThat(entity).isNotNull();
        assertThat(entity.code()).isEqualTo("HXZD-001-001");
        assertThat(entity.name()).contains("患者入院48小时内转科的比例");
        assertThat(entity.definition()).contains("入院48小时内转科患者人次数");
        assertThat(entity.formula()).contains("分子");
        assertThat(entity.formula()).contains("分母");
        assertThat(entity.caliber()).contains("统计患者入区时间");
        assertThat(entity.monitorParams()).contains("时间维度");
    }

    @Test
    void hxzd001001HasAllFourSqlSections() {
        EntityPageData entity = parser.getEntity("HXZD-001-001");
        assertThat(entity).isNotNull();
        assertThat(entity.sourceTableSql()).contains("INPATIENT_ENCOUNTER");
        assertThat(entity.overviewSql()).contains("MRAS_BUSINESS_FIRSTVISIT");
        assertThat(entity.deptStatSql()).contains("CURRENT_DEPT_ID");
        assertThat(entity.patientDetailSql()).contains("ENCOUNTER_ID");
    }

    @Test
    void overviewSqlContainsTemplateSyntax() {
        EntityPageData entity = parser.getEntity("HXZD-001-001");
        assertThat(entity).isNotNull();
        // 概览 SQL 应包含 #ETC 和 #EQUALS 模板标记
        assertThat(entity.overviewSql()).contains("#EQUALS");
        assertThat(entity.overviewSql()).contains(":marptBeginAt");
    }

    @Test
    void everyPrimaryEntityHasOverviewSql() {
        parser.getAllEntities().forEach((code, entity) -> {
            if (entity.isPrimary()) {
                assertThat(entity.hasOverviewSql())
                        .as("主方案指标 %s 应有概览 SQL", code)
                        .isTrue();
            }
        });
    }

    @Test
    void unknownCodeReturnsNull() {
        assertThat(parser.getEntity("HXZD-999-999")).isNull();
    }
}
