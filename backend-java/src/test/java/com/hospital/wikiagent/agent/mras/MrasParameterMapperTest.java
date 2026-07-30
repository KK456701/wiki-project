package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * MrasParameterMapper 单元测试：验证参数映射逻辑正确覆盖知识库模板所需的全部命名参数。
 */
class MrasParameterMapperTest {

    private final MrasParameterMapper mapper = new MrasParameterMapper();

    @Test
    void mapsTimeRangeToBothParameterPairs() {
        LocalDateTime start = LocalDateTime.of(2025, 3, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2025, 12, 31, 23, 59, 59);

        Map<String, Object> params = mapper.mapParameters(start, end, null, null);

        assertThat(params.get("marptBeginAt")).isEqualTo("2025-03-01 00:00:00");
        assertThat(params.get("marptEndAt")).isEqualTo("2025-12-31 23:59:59");
        assertThat(params.get("startTime")).isEqualTo("2025-03-01 00:00:00");
        assertThat(params.get("endTime")).isEqualTo("2025-12-31 23:59:59");
    }

    @Test
    void defaultSyncTypeIsOutHosp() {
        Map<String, Object> params = mapper.mapTimeOnly(
                LocalDateTime.of(2025, 1, 1, 0, 0), LocalDateTime.of(2025, 6, 30, 23, 59));

        assertThat(params.get("syncType")).isEqualTo("outHosp");
    }

    @Test
    void deptFilterIncludedWhenPresent() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59),
                "101,102,103",
                null);

        assertThat(params.get("deptIdIn")).isEqualTo("101,102,103");
    }

    @Test
    void deptFilterOmittedWhenNull() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59),
                null,
                null);

        assertThat(params).doesNotContainKey("deptIdIn");
    }

    @Test
    void deptFilterOmittedWhenBlank() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59),
                "   ",
                null);

        assertThat(params).doesNotContainKey("deptIdIn");
    }

    @Test
    void qualifiedFilterMapsToBothKeys() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59),
                null,
                "98175");

        assertThat(params.get("qualified")).isEqualTo("98175");
        assertThat(params.get("status")).isEqualTo("98175");
    }

    @Test
    void qualifiedFilterOmittedWhenNull() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59),
                null,
                null);

        assertThat(params).doesNotContainKey("qualified");
        assertThat(params).doesNotContainKey("status");
    }

    @Test
    void mapTimeOnlyContainsExactlyFiveKeys() {
        Map<String, Object> params = mapper.mapTimeOnly(
                LocalDateTime.of(2025, 7, 1, 0, 0),
                LocalDateTime.of(2025, 7, 31, 23, 59));

        assertThat(params).containsOnlyKeys(
                "marptBeginAt", "marptEndAt", "startTime", "endTime", "syncType");
    }

    @Test
    void allFiltersPresentGivesFullMap() {
        Map<String, Object> params = mapper.mapParameters(
                LocalDateTime.of(2025, 3, 1, 8, 30, 0),
                LocalDateTime.of(2025, 3, 31, 17, 0, 0),
                "201,202",
                "98176");

        assertThat(params).containsOnlyKeys(
                "marptBeginAt", "marptEndAt", "startTime", "endTime",
                "syncType", "deptIdIn", "qualified", "status");
        assertThat(params).hasSize(8);
    }

    @Test
    void timestampFormatIncludesSeconds() {
        Map<String, Object> params = mapper.mapTimeOnly(
                LocalDateTime.of(2025, 1, 15, 9, 5, 3),
                LocalDateTime.of(2025, 1, 15, 9, 5, 3));

        assertThat(params.get("marptBeginAt")).isEqualTo("2025-01-15 09:05:03");
    }
}
