package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.batch.BatchRequestDetector;
import com.hospital.wikiagent.agent.batch.BatchRequestSpec;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryTarget;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.RequestKind;

/**
 * 固化产品验收中的 15 轮连续对话路由契约。这里不访问模型或数据库，只验证最容易
 * 回归的结构态更新、完整名称保护、复数指代、范围替换和批量续接。
 */
class ConversationContinuationAcceptanceTest {
    private static final String START = "2026-05-01 00:00:00";
    private static final String END = "2026-06-01 00:00:00";
    private final BatchRequestDetector batches = new BatchRequestDetector();
    private final CompoundRequestSplitter splitter = new CompoundRequestSplitter();

    @Test
    void fifteenTurnsKeepOperationTargetsTimeAndRateRatioIdentity() {
        List<String> turns = List.of(
                "急会诊及时到位率是什么？",
                "分子和分母呢？",
                "当前用的是什么口径？",
                "概览 SQL 怎么写？",
                "算一下2026年6月份的结果。",
                "时间改成2026年5月份。",
                "换成术中自体血回输率。",
                "我觉得这个指标的分子口径有问题。",
                "计算患者入院48小时内转科的比例、急会诊及时到位率、危急值报告时间。",
                "这三个指标的定义和口径分别是什么？",
                "第三个换成四级手术与三级手术并发症发生率比。",
                "最后这个指标的 SQL 怎么写？",
                "按上次统计时间计算这三个指标。",
                "全部指标。",
                "把时间改成本月。");
        assertThat(turns).hasSize(15);

        // 1～8：单指标操作可以逐轮替换，时间在定义/SQL/诊断之间保持。
        QueryScopeState scope = single(
                "rule_explanation", "R_URGENT", "急会诊及时到位率", null, null);
        assertThat(scope.valid()).isTrue(); // 1
        scope = single("rule_explanation", "R_URGENT", "急会诊及时到位率", null, null); // 2
        scope = single("rule_explanation", "R_URGENT", "急会诊及时到位率", null, null); // 3
        scope = single("indicator_sql_prepare", "R_URGENT", "急会诊及时到位率", null, null); // 4
        scope = single("indicator_trial_run", "R_URGENT", "急会诊及时到位率",
                "2026-06-01 00:00:00", "2026-07-01 00:00:00"); // 5
        scope = single("indicator_trial_run", "R_URGENT", "急会诊及时到位率", START, END); // 6
        scope = single("indicator_trial_run", "R_BLOOD", "术中自体血回输率", START, END); // 7
        scope = single("indicator_diagnosis", "R_BLOOD", "术中自体血回输率", START, END); // 8
        assertThat(scope.operation()).isEqualTo("indicator_diagnosis");
        assertThat(scope.statStart()).isEqualTo(START);

        List<Map<String, String>> catalog = List.of(
                metric("R_TRANSFER", "患者入院48小时内转科的比例"),
                metric("R_URGENT", "急会诊及时到位率"),
                metric("R_CRITICAL", "危急值报告时间"),
                metric("HXZD-012-001", "四级手术与三级手术并发症发生率比"));

        // 9：明确 3 项计算替换原单指标范围，任务顺序保持用户输入顺序。
        BatchRequestSpec selected = batches.detect(turns.get(8), scope, catalog);
        assertThat(selected.targets()).extracting("ruleId")
                .containsExactly("R_TRANSFER", "R_URGENT", "R_CRITICAL");
        scope = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                selected.targets().stream()
                        .map(value -> new QueryTarget(value.ruleId(), value.ruleName()))
                        .toList(),
                START, END);
        List<String> remembered = scope.targets().stream().map(QueryTarget::ruleName).toList();

        // 10：复数指代继承同一批目标，但操作改为定义/口径，不进入计算。
        var explanation = splitter.split(turns.get(9), "", "hospital_001",
                List.of(), remembered);
        assertThat(explanation.kind()).isEqualTo(RequestKind.RULE_EXPLANATION);
        assertThat(explanation.tasks()).hasSize(3);

        // 11：第三项替换后仍是 3 项；最长正式名称保持为一个指标。
        List<HybridIndicatorResolver.ResolvedIndicator> replaced = List.of(
                resolved("R_TRANSFER", "患者入院48小时内转科的比例"),
                resolved("R_URGENT", "急会诊及时到位率"),
                resolved("HXZD-012-001", "四级手术与三级手术并发症发生率比"));
        var replacement = splitter.split(turns.get(10), "", "hospital_001",
                replaced, remembered);
        assertThat(replacement.tasks()).hasSize(3);
        assertThat(replacement.tasks().get(2).target())
                .isEqualTo("四级手术与三级手术并发症发生率比");

        // 12：只引用最后一项生成 SQL 时，完整率比名称不会按“与”拆成两项。
        var ratioSql = splitter.split(
                "四级手术与三级手术并发症发生率比的 SQL 怎么写？",
                "", "hospital_001",
                List.of(resolved("HXZD-012-001", "四级手术与三级手术并发症发生率比")),
                replaced.stream().map(HybridIndicatorResolver.ResolvedIndicator::canonicalName)
                        .toList());
        assertThat(ratioSql.compound()).isFalse();

        // 13：指代式 3 项重算继承上次时间并重新进入批量。
        var rerun = splitter.split(turns.get(12), "", "hospital_001", List.of(),
                replaced.stream().map(HybridIndicatorResolver.ResolvedIndicator::canonicalName)
                        .toList());
        assertThat(rerun.kind()).isEqualTo(RequestKind.TRIAL_RUN);
        assertThat(rerun.tasks()).hasSize(3);

        // 14～15：全部指标与纯时间修改都继承计算意图，不出现选择循环。
        scope = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                replaced.stream()
                        .map(value -> new QueryTarget(value.ruleId(), value.canonicalName()))
                        .toList(),
                START, END);
        BatchRequestSpec all = batches.detect(turns.get(13), scope, catalog);
        assertThat(all.allActive()).isTrue();
        scope = new QueryScopeState("indicator_trial_run", "ALL", List.of(), START, END);
        BatchRequestSpec currentMonth = batches.detect(turns.get(14), scope, catalog);
        assertThat(currentMonth.allActive()).isTrue();
        assertThat(currentMonth.timeText()).isEqualTo(turns.get(14));
    }

    private static QueryScopeState single(
            String operation, String id, String name, String start, String end) {
        return new QueryScopeState(
                operation, "SINGLE", List.of(new QueryTarget(id, name)), start, end);
    }

    private static Map<String, String> metric(String id, String name) {
        return Map.of("rule_id", id, "rule_name", name);
    }

    private static HybridIndicatorResolver.ResolvedIndicator resolved(
            String id, String name) {
        return new HybridIndicatorResolver.ResolvedIndicator(
                name, name, id, "RULE:" + id, "test", 1.0, 0, name.length());
    }
}
