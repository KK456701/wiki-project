package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchRequestDetectorTest {
    private final BatchRequestDetector detector = new BatchRequestDetector();

    @Test
    void detectsAllActiveBatchRequests() {
        assertThat(detector.detect("计算所有指标的结果").batch()).isTrue();
        assertThat(detector.detect("把全部核心指标都算一遍").batch()).isTrue();
        assertThat(detector.detect("今年全部指标的达标情况").batch()).isTrue();
        assertThat(detector.detect("帮我计算全院指标的结果").batch()).isTrue();
        assertThat(detector.detect("逐一计算每个指标的数值").batch()).isTrue();
        assertThat(detector.detect("把所有重点指标算一下").batch()).isTrue();
    }

    @Test
    void allActiveFlagIsSetForBatchRequests() {
        BatchRequestSpec spec = detector.detect("计算所有指标的结果");
        assertThat(spec.allActive()).isTrue();
        assertThat(spec.rawQuery()).isEqualTo("计算所有指标的结果");
    }

    @Test
    void rejectsDefinitionOrCaliberQuestions() {
        assertThat(detector.detect("所有指标的定义是什么").batch()).isFalse();
        assertThat(detector.detect("所有指标的口径").batch()).isFalse();
        assertThat(detector.detect("全部指标的公式解释").batch()).isFalse();
        assertThat(detector.detect("怎么算所有指标").batch()).isFalse();
    }

    @Test
    void rejectsSingleIndicatorRequests() {
        assertThat(detector.detect("计算急会诊及时到位率").batch()).isFalse();
        assertThat(detector.detect("急会诊及时到位率的结果").batch()).isFalse();
    }

    @Test
    void rejectsRequestsWithoutResultIntent() {
        assertThat(detector.detect("所有指标").batch()).isFalse();
        assertThat(detector.detect("全部指标有哪些").batch()).isFalse();
    }

    @Test
    void rejectsBlankAndIndicatorFreeQueries() {
        assertThat(detector.detect(null).batch()).isFalse();
        assertThat(detector.detect("  ").batch()).isFalse();
        assertThat(detector.detect("计算所有手术的结果").batch()).isFalse();
    }
}
