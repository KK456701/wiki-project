package com.hospital.wikiagent.agent.model;

final class ModelJsonExtractor {
    private ModelJsonExtractor() {
    }

    static String firstObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String value = raw.replaceAll("(?s)<think>.*?</think>", "").strip();
        int start = value.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("模型未返回 JSON 对象");
        }
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return value.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("模型 JSON 对象未闭合");
    }
}
