package com.hospital.wikiagent.agent.ir;

/**
 * IR 枚举取值的书写归一化。
 *
 * <p>Planner 的 JSON 由大模型生成，小模型常把约定的 snake_case 写成驼峰或连字符
 * （实测 Qwen-Plus 会连续两次输出 {@code trialResult} 而不是 {@code trial_result}）。
 * 这只是同一个已知取值的书写差异，不是新语义，却会让整份计划被判为无效并直接
 * 回 {@code PLANNER_OUTPUT_INVALID}。</p>
 *
 * <p>因此枚举解析先做精确匹配，再按「小写 + 去掉所有非字母数字字符」归一化后匹配。
 * 归一化只消除书写差异：各 IR 枚举的取值归一化后互不相同，不会把一个取值映射到
 * 另一个取值上；真正未知的文本仍然抛错由调用方拒绝，不会被静默当成某个成功状态。</p>
 */
final class IrEnumCodec {

    private IrEnumCodec() {
    }

    /**
     * 归一化枚举取值：转小写并丢弃下划线、连字符、空格等分隔符。
     *
     * @param value 原始取值，可为 {@code null}
     * @return 归一化结果；入参为 {@code null} 时返回 {@code null}
     */
    static String canonical(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    /**
     * 判断候选枚举的取值是否命中输入：精确相等，或归一化后相等。
     *
     * @param candidateValue 枚举自身的规范取值（snake_case，非空）
     * @param rawValue       模型输出的原始取值，可为 {@code null}
     * @return 命中返回 {@code true}
     */
    static boolean matches(String candidateValue, String rawValue) {
        return candidateValue.equals(rawValue)
                || canonical(candidateValue).equals(canonical(rawValue));
    }
}
