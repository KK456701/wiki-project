package com.hospital.wikiagent.agent.mras;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 将 Agent 解析出的时间范围与可选过滤条件映射为领导知识库 SQL 模板所需的命名参数 Map。
 *
 * <p>职责边界：纯参数映射（无 IO、无 SQL 执行），输出可直接交给
 * {@link MrasTemplateRenderer#render(String, Map)} 做模板渲染。</p>
 *
 * <p>映射规则：
 * <ul>
 *   <li>开始时间 → {@code :marptBeginAt} + {@code :startTime}（格式 yyyy-MM-dd HH:mm:ss）</li>
 *   <li>结束时间 → {@code :marptEndAt} + {@code :endTime}</li>
 *   <li>科室过滤 → {@code :deptIdIn}（逗号分隔 ID，可选）</li>
 *   <li>达标状态 → {@code :qualified} + {@code :status}（98175=达标 / 98176=未达标，可选）</li>
 *   <li>同步模式 → {@code :syncType}（ETL 用，默认 outHosp）</li>
 * </ul></p>
 */
@Component
public class MrasParameterMapper {

    private static final DateTimeFormatter SQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认同步模式：出院患者。 */
    private static final String DEFAULT_SYNC_TYPE = "outHosp";

    /**
     * 将 Agent 解析出的查询条件映射为领导知识库模板参数。
     *
     * @param start           查询开始时间（必填）
     * @param end             查询结束时间（必填）
     * @param deptFilter      科室 ID 过滤，逗号分隔（可为 null 表示不过滤）
     * @param qualifiedFilter 达标状态过滤："98175"=达标 / "98176"=未达标（可为 null）
     * @return 命名参数 Map，键不含冒号前缀
     */
    public Map<String, Object> mapParameters(
            LocalDateTime start,
            LocalDateTime end,
            String deptFilter,
            String qualifiedFilter) {

        Map<String, Object> params = new LinkedHashMap<>();

        String startStr = start.format(SQL_TIMESTAMP);
        String endStr = end.format(SQL_TIMESTAMP);

        // 概览/科室/明细查询使用 marptBeginAt / marptEndAt
        params.put("marptBeginAt", startStr);
        params.put("marptEndAt", endStr);

        // 源表 ETL 查询使用 startTime / endTime
        params.put("startTime", startStr);
        params.put("endTime", endStr);

        // 同步模式（ETL 抽取用）
        params.put("syncType", DEFAULT_SYNC_TYPE);

        // 可选：科室过滤
        if (deptFilter != null && !deptFilter.isBlank()) {
            params.put("deptIdIn", deptFilter.strip());
        }

        // 可选：达标状态
        if (qualifiedFilter != null && !qualifiedFilter.isBlank()) {
            String qualified = qualifiedFilter.strip();
            params.put("qualified", qualified);
            params.put("status", qualified);
        }

        return params;
    }

    /**
     * 仅映射时间范围（无可选过滤），用于最简概览查询场景。
     *
     * @param start 查询开始时间
     * @param end   查询结束时间
     * @return 命名参数 Map
     */
    public Map<String, Object> mapTimeOnly(LocalDateTime start, LocalDateTime end) {
        return mapParameters(start, end, null, null);
    }
}
