package com.hospital.wikiagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class DocumentationConventionTest {

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^[\\t ]*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)[\\t ]+)*"
                    + "(?:class|record|interface|enum)[\\t ]+[A-Za-z0-9_]+");

    @Test
    void everyProductionPackageHasChineseResponsibilityDocumentation() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> undocumented;
        try (var paths = Files.walk(sourceRoot)) {
            undocumented = paths
                    .filter(Files::isDirectory)
                    .filter(this::containsJavaSource)
                    .filter(path -> !Files.isRegularFile(path.resolve("package-info.java")))
                    .toList();
        }

        assertThat(undocumented)
                .as("每个生产 Java 包都必须用 package-info.java 说明职责和边界")
                .isEmpty();
    }

    @Test
    void everyProductionTypeHasJavadoc() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> undocumented;
        try (var paths = Files.walk(sourceRoot)) {
            undocumented = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .filter(this::hasNoTypeJavadoc)
                    .toList();
        }

        assertThat(undocumented)
                .as("每个生产 Java 顶层类型都必须说明职责；复杂分支和安全边界还应在实现处补充原因注释")
                .isEmpty();
    }

    private boolean containsJavaSource(Path directory) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".java")
                    && !path.getFileName().toString().equals("package-info.java"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法检查 Java 注释约束：" + directory, exception);
        }
    }

    private boolean hasNoTypeJavadoc(Path source) {
        try {
            String text = Files.readString(source);
            var matcher = TOP_LEVEL_TYPE.matcher(text);
            if (!matcher.find()) {
                return false;
            }
            String prefix = text.substring(0, matcher.start());
            int commentStart = prefix.lastIndexOf("/**");
            int commentEnd = prefix.lastIndexOf("*/");
            return commentStart < 0 || commentEnd < commentStart;
        } catch (IOException exception) {
            throw new IllegalStateException("无法检查 Java 类型注释：" + source, exception);
        }
    }
}
