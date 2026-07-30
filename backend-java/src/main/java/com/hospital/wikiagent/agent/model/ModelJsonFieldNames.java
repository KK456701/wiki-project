package com.hospital.wikiagent.agent.model;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 模型输出 JSON 的字段名归一化。
 *
 * <p>提示词按 JSON 惯例声明 snake_case 字段（{@code target_indicator}、{@code raw_name}、
 * {@code start_time}），而 Java 侧的 IR record 是 camelCase。越严格遵守提示词的模型越会
 * 命中这个不一致并被判为无效输出，而不听话输出 camelCase 的模型反而能通过。</p>
 *
 * <p>因此反序列化前把对象键统一转成 camelCase：提示词声明的 snake_case 和模型自发写的
 * camelCase 都能落到同一个字段上。这里只改键的书写，不改任何值，也不新增或丢弃键；
 * 缺字段、多字段和非法取值仍由 record 校验与枚举解析按原规则拒绝。</p>
 *
 * <p>适用前提：目标结构的字段名固定，不含以用户数据为键的 Map。IR 里的
 * {@code RequestPlan} 及其嵌套结构均满足。</p>
 */
final class ModelJsonFieldNames {

    private ModelJsonFieldNames() {
    }

    /**
     * 递归把对象键转为 camelCase，数组逐元素处理，其余节点原样返回。
     */
    static JsonNode toCamelCase(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> property : object.properties()) {
                normalized.set(camelCase(property.getKey()), toCamelCase(property.getValue()));
            }
            return normalized;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : array) {
                normalized.add(toCamelCase(element));
            }
            return normalized;
        }
        return node;
    }

    /**
     * 把 {@code snake_case} 或 {@code kebab-case} 键名转为 camelCase；
     * 不含分隔符的键名原样返回，避免把已经正确的 camelCase 压成全小写。
     */
    private static String camelCase(String name) {
        if (name.indexOf('_') < 0 && name.indexOf('-') < 0) {
            return name;
        }
        StringBuilder normalized = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '_' || character == '-') {
                // 连续分隔符只触发一次首字母大写，且不产生空段。
                upperNext = !normalized.isEmpty();
                continue;
            }
            normalized.append(upperNext ? Character.toUpperCase(character) : character);
            upperNext = false;
        }
        return normalized.toString();
    }
}
