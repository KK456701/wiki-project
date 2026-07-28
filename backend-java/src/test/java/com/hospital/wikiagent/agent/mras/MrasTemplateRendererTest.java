package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * MrasTemplateRenderer 单元测试：覆盖 #ETC、#EQUALS、参数替换和方言修正。
 */
class MrasTemplateRendererTest {

    private final MrasTemplateRenderer renderer = new MrasTemplateRenderer();

    @Test
    void etcKeepsLineWhenParamPresent() {
        String template = "SELECT * FROM t\n#ETC{ AND event.HOSPITAL_AREA_ID IN (:hospitalAreaList) }\nWHERE 1=1";
        String result = renderer.render(template, Map.of("hospitalAreaList", "1,2,3"));
        assertThat(result).contains("AND event.HOSPITAL_AREA_ID IN");
    }

    @Test
    void etcRemovesLineWhenParamAbsent() {
        String template = "SELECT * FROM t\n#ETC{ AND event.HOSPITAL_AREA_ID IN (:hospitalAreaList) }\nWHERE 1=1";
        String result = renderer.render(template, Map.of());
        assertThat(result).doesNotContain("HOSPITAL_AREA_ID");
        assertThat(result).contains("WHERE 1=1");
    }

    @Test
    void equalsMatchesValueKeepsTrueBranch() {
        String template = "#EQUALS{:syncType; outHosp; AND t1.ENCOUNTER_ID IN (SELECT 1)}";
        String result = renderer.render(template, Map.of("syncType", "outHosp"));
        assertThat(result).contains("AND t1.ENCOUNTER_ID IN (SELECT 1)");
        assertThat(result).doesNotContain("#EQUALS");
    }

    @Test
    void equalsNonMatchRemovesContent() {
        String template = "#EQUALS{:syncType; outHosp; AND t1.ENCOUNTER_ID IN (SELECT 1)}";
        String result = renderer.render(template, Map.of("syncType", "increment"));
        assertThat(result).doesNotContain("ENCOUNTER_ID");
    }

    @Test
    void equalsWithFalseBranchUsesElseOnMismatch() {
        String template = "#EQUALS{:onlySearchFeilds; ONLY_SEARCH_FEILDS; 1 = 0 ; 1=1}";
        String result = renderer.render(template, Map.of("onlySearchFeilds", "NORMAL"));
        assertThat(result).contains("1=1");
        assertThat(result).doesNotContain("1 = 0");
    }

    @Test
    void equalsWithFalseBranchUsesTrueOnMatch() {
        String template = "#EQUALS{:onlySearchFeilds; ONLY_SEARCH_FEILDS; 1 = 0 ; 1=1}";
        String result = renderer.render(template, Map.of("onlySearchFeilds", "ONLY_SEARCH_FEILDS"));
        assertThat(result).contains("1 = 0");
        assertThat(result).doesNotContain("1=1");
    }

    @Test
    void namedParamsReplacedWithQuotedStrings() {
        String template = "WHERE event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt";
        String result = renderer.render(template, Map.of(
                "marptBeginAt", "2025-03-01 00:00:00",
                "marptEndAt", "2025-05-01 00:00:00"));
        assertThat(result).contains("'2025-03-01 00:00:00'");
        assertThat(result).contains("'2025-05-01 00:00:00'");
        assertThat(result).doesNotContain(":marptBeginAt");
    }

    @Test
    void numericParamsNotQuoted() {
        String template = "WHERE dept_id = :deptId";
        String result = renderer.render(template, Map.of("deptId", 12345));
        assertThat(result).contains("= 12345");
        assertThat(result).doesNotContain("'12345'");
    }

    @Test
    void doubleQuoteAliasFixed() {
        String template = "SELECT COUNT(1) AS \"\"分母同期入院患者总人次数\"\"";
        String result = renderer.render(template, Map.of());
        assertThat(result).contains("\"分母同期入院患者总人次数\"");
        assertThat(result).doesNotContain("\"\"");
    }

    @Test
    void nolockFixedWithWithKeyword() {
        String template = "FROM MRAS_BUSINESS_FIRSTVISIT event (NOLOCK)";
        String result = renderer.render(template, Map.of());
        assertThat(result).contains("event WITH (NOLOCK)");
    }

    @Test
    void existingWithNolockNotDuplicated() {
        String template = "FROM MRAS_TARGET_DEFINITION WITH (NOLOCK)";
        String result = renderer.render(template, Map.of());
        assertThat(result).contains("WITH (NOLOCK)");
        assertThat(result).doesNotContain("WITH WITH");
    }

    @Test
    void entityPageWrapperQuotesRemoved() {
        String template = "\"'SELECT 1 AS col'\"";
        String result = renderer.render(template, Map.of());
        assertThat(result).isEqualTo("SELECT 1 AS col");
    }

    @Test
    void combinedScenarioFromRealEntityPage() {
        String template = """
                "'--查询出目标值
                WITH TargetValue AS (
                    SELECT TARGET_COMP_VAL/100.0 AS target_value
                    FROM MRAS_TARGET_DEFINITION (NOLOCK)
                    WHERE TARGET_NO = 'HXZD-001-001'
                )
                SELECT
                event.CURRENT_DEPT_NAME AS ""科室名称"",
                COUNT(1) AS ""分母""
                FROM
                MRAS_BUSINESS_FIRSTVISIT event (NOLOCK)
                WHERE
                #EQUALS{:onlySearchFeilds; ONLY_SEARCH_FEILDS;  1 = 0 ; 1=1}
                AND event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt and :marptEndAt
                #ETC{ AND event.HOSPITAL_AREA_ID IN (:hospitalAreaList) }
                GROUP BY event.CURRENT_DEPT_NAME'\"
                """;
        String result = renderer.render(template, Map.of(
                "marptBeginAt", "2025-03-01 00:00:00",
                "marptEndAt", "2025-05-01 00:00:00",
                "onlySearchFeilds", "NORMAL"));

        // 方言修正
        assertThat(result).doesNotContain("\"'");
        assertThat(result).doesNotContain("'\"");
        assertThat(result).contains("\"科室名称\"");
        assertThat(result).contains("WITH (NOLOCK)");
        // 参数替换
        assertThat(result).contains("'2025-03-01 00:00:00'");
        assertThat(result).doesNotContain(":marptBeginAt");
        // #EQUALS 走 false 分支
        assertThat(result).contains("1=1");
        assertThat(result).doesNotContain("1 = 0");
        // #ETC 无参数被删除
        assertThat(result).doesNotContain("HOSPITAL_AREA_ID");
    }

    @Test
    void emptyAndNullInput() {
        assertThat(renderer.render("", Map.of())).isEmpty();
        assertThat(renderer.render(null, Map.of())).isEmpty();
        assertThat(renderer.render("   ", Map.of())).isEmpty();
    }
}
