package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelInvoker.ModelCompletion;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.AgentModelUnavailableException;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;

/**
 * MrasDetailSqlSynthesizer 单元测试：验证小模型合成分子/分母明细 SQL 的解析、
 * 只读校验、每次重新生成（不缓存）、分子判定表达式提取与失败回退逻辑。
 */
class MrasDetailSqlSynthesizerTest {

    private static final String INDICATOR = "HXZD-001-001";

    /** 合法 JSON：两条 SQL 均含 :marptBeginAt/:marptEndAt 受控时间参数，可通过只读校验。 */
    private static final String VALID_JSON =
            "{\"denominator_sql\":\"SELECT PATIENT_ID FROM MRAS_BUSINESS_FIRSTVISIT WITH (NOLOCK) "
                    + "WHERE MARPT_BEGIN_AT >= :marptBeginAt AND MARPT_BEGIN_AT <= :marptEndAt\","
                    + "\"numerator_sql\":\"SELECT PATIENT_ID FROM MRAS_BUSINESS_FIRSTVISIT WITH (NOLOCK) "
                    + "WHERE MARPT_BEGIN_AT >= :marptBeginAt AND MARPT_BEGIN_AT <= :marptEndAt "
                    + "AND TRANSFER_WITHIN_TWO_DAY = 98175\"}";

    /** 非法 JSON：SQL 缺少受控时间参数，无法通过只读校验。 */
    private static final String INVALID_JSON =
            "{\"denominator_sql\":\"SELECT PATIENT_ID FROM MRAS_BUSINESS_FIRSTVISIT WITH (NOLOCK)\","
                    + "\"numerator_sql\":\"SELECT PATIENT_ID FROM MRAS_BUSINESS_FIRSTVISIT WITH (NOLOCK)\"}";

    private AgentModelInvoker invoker;
    private AgentModelRegistry registry;
    private MrasDetailSqlSynthesizer synthesizer;

    @BeforeEach
    void setUp() {
        invoker = mock(AgentModelInvoker.class);
        registry = mock(AgentModelRegistry.class);
        when(registry.defaultModelId()).thenReturn("test-model");
        synthesizer = new MrasDetailSqlSynthesizer(
                invoker,
                registry,
                new EntityPageParser(),
                new ReadOnlySqlValidator(),
                new ObjectMapper());
    }

    @Test
    void synthesizeParsesAndValidates() {
        when(invoker.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model", VALID_JSON));

        MrasDetailSqlSynthesizer.DetailSqlPair first = synthesizer.synthesize(INDICATOR);

        assertThat(first).isNotNull();
        assertThat(first.denominatorSql()).contains(":marptBeginAt").contains(":marptEndAt");
        assertThat(first.numeratorSql()).contains("TRANSFER_WITHIN_TWO_DAY");

        // 不缓存：第二次调用必须重新调用模型重新生成（用户明确要求）
        MrasDetailSqlSynthesizer.DetailSqlPair second = synthesizer.synthesize(INDICATOR);
        assertThat(second).isNotNull();
        verify(invoker, times(2)).complete(anyString(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void synthesizeFailureFallsBackAndRegeneratesNextCall() {
        when(invoker.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model", INVALID_JSON))
                .thenReturn(new ModelCompletion("test-model", INVALID_JSON))
                .thenReturn(new ModelCompletion("test-model", VALID_JSON));

        // 首轮：首次 + 重试均失败，返回 null
        assertThat(synthesizer.synthesize(INDICATOR)).isNull();
        // 第二轮：重新调用模型并成功
        assertThat(synthesizer.synthesize(INDICATOR)).isNotNull();
        verify(invoker, times(3)).complete(anyString(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void extractNumeratorConditionFindsFirstCaseWhen() {
        String overview = "SELECT COUNT(CASE WHEN TRANSFER_WITHIN_TWO_DAY = '98175' THEN 1 ELSE NULL END) AS mol,\n"
                + "COUNT(1) AS den,\n"
                + "CASE WHEN COUNT(1) = 0 THEN 0 ELSE 1 END AS rate\n"
                + "FROM MRAS_BUSINESS_FIRSTVISIT event (NOLOCK)";

        assertThat(MrasDetailSqlSynthesizer.extractNumeratorCondition(overview))
                .isEqualTo("TRANSFER_WITHIN_TWO_DAY = '98175'");
    }

    @Test
    void extractNumeratorConditionSupportsSumForm() {
        String overview = "SELECT SUM(CASE WHEN event.UNPLANNED_FLAG = 98175\n"
                + "    AND event.IS_DEL = 0 THEN 1 ELSE 0 END) AS mol FROM T event (NOLOCK)";

        assertThat(MrasDetailSqlSynthesizer.extractNumeratorCondition(overview))
                .isEqualTo("event.UNPLANNED_FLAG = 98175 AND event.IS_DEL = 0");
    }

    @Test
    void extractNumeratorConditionReturnsEmptyWhenAbsent() {
        assertThat(MrasDetailSqlSynthesizer.extractNumeratorCondition(
                "SELECT COUNT(1) FROM T event (NOLOCK)")).isEmpty();
        assertThat(MrasDetailSqlSynthesizer.extractNumeratorCondition(null)).isEmpty();
    }

    @Test
    void synthesizeRetriesThenReturnsNullForInvalidSql() {
        when(invoker.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model", INVALID_JSON));

        MrasDetailSqlSynthesizer.DetailSqlPair result = synthesizer.synthesize(INDICATOR);

        assertThat(result).isNull();
        // 首次失败后带错误信息重试一次，共调用模型两次
        verify(invoker, times(2)).complete(anyString(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void synthesizeReturnsNullWhenModelUnavailable() {
        when(invoker.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new AgentModelUnavailableException("MODEL_NOT_FOUND", "模型不存在"));

        MrasDetailSqlSynthesizer.DetailSqlPair result = synthesizer.synthesize(INDICATOR);

        assertThat(result).isNull();
        // 模型不可用时不重试，只调用一次，避免双倍超时阻塞
        verify(invoker, times(1)).complete(anyString(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void synthesizeReturnsNullForUnknownIndicator() {
        MrasDetailSqlSynthesizer.DetailSqlPair result = synthesizer.synthesize("HXZD-999-999");

        assertThat(result).isNull();
        verify(invoker, times(0)).complete(anyString(), anyString(), anyString(), any(Duration.class));
    }
}
