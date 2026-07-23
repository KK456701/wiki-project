package com.hospital.wikiagent.agent.upload;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.upload.UploadStorage;
import com.hospital.wikiagent.upload.XlsxWorkbookReader;

/**
 * 在业务计划校验前，从当前医院拥有的上传文件中读取可验证的计划上下文。
 *
 * <p>这个组件只读取 Excel 顶部元数据，不读取或暴露患者级数据。它的用途是落实差异
 * 诊断的上下文优先级：本轮用户明确提供的指标和时间始终优先；本轮缺失时使用文件中的
 * 指标编号、指标名称和统计区间；文件也没有时，调用方才回退到结构化会话状态。</p>
 *
 * <p>文件解析失败时返回空上下文，由后续差异 Workflow 给出稳定的文件错误，不允许
 * 在规划阶段吞掉权限异常后猜测指标或时间。</p>
 */
@Component
public class UploadedFilePlanningContext {
    private static final Pattern DATE_TIME = Pattern.compile(
            "(20\\d{2}-\\d{2}-\\d{2})(?:[ T](\\d{2}:\\d{2}(?::\\d{2})?))?");

    private final UploadStorage storage;
    private final XlsxWorkbookReader reader;

    public UploadedFilePlanningContext(
            UploadStorage storage,
            XlsxWorkbookReader reader) {
        this.storage = storage;
        this.reader = reader;
    }

    /**
     * 读取文件首个包含元数据的工作表。返回值只含规划需要的非患者字段。
     */
    public PlanningContext resolve(String fileKey, String hospitalId) {
        if (fileKey == null || fileKey.isBlank()) return PlanningContext.empty();
        var upload = storage.requireOwned(fileKey, hospitalId);
        var workbook = reader.read(upload);
        for (var sheet : workbook.sheets()) {
            if (sheet.metadata().isEmpty()) continue;
            Map<String, Object> metadata = sheet.metadata();
            String period = firstText(
                    metadata, "统计区间", "系统统计区间", "上传文件统计区间");
            List<String> bounds = dateTimes(period);
            return new PlanningContext(
                    firstText(metadata, "指标编号", "指标编码"),
                    firstText(metadata, "指标名称"),
                    period,
                    bounds.size() >= 2 ? bounds.get(0) : null,
                    bounds.size() >= 2 ? bounds.get(1) : null);
        }
        return PlanningContext.empty();
    }

    private static List<String> dateTimes(String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        Matcher matcher = DATE_TIME.matcher(value == null ? "" : value);
        while (matcher.find() && result.size() < 2) {
            String time = matcher.group(2);
            if (time == null) {
                result.add(matcher.group(1) + " 00:00:00");
            } else if (time.length() == 5) {
                result.add(matcher.group(1) + " " + time + ":00");
            } else {
                result.add(matcher.group(1) + " " + time);
            }
        }
        return List.copyOf(result);
    }

    private static String firstText(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).strip();
            }
        }
        return "";
    }

    public record PlanningContext(
            String ruleId,
            String ruleName,
            String rawPeriod,
            String statStart,
            String statEnd) {
        public PlanningContext {
            ruleId = blankToNull(ruleId);
            ruleName = blankToNull(ruleName);
            rawPeriod = rawPeriod == null ? "" : rawPeriod.strip();
            statStart = blankToNull(statStart);
            statEnd = blankToNull(statEnd);
        }

        public static PlanningContext empty() {
            return new PlanningContext(null, null, "", null, null);
        }

        public boolean hasTimeRange() {
            return statStart != null && statEnd != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
