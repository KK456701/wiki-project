package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathKnowledgeMaterializerTest {
    @TempDir
    Path tempDir;

    @Test
    void materializesSpringBootKnowledgeEntriesWithExactBytes() throws Exception {
        Path archive = tempDir.resolve("application.jar");
        byte[] pointer = "{\"release_id\":\"TEST\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] contract = new byte[] {0, 1, 2, 10, 13, (byte) 255};
        try (OutputStream output = Files.newOutputStream(archive);
                JarOutputStream jar = new JarOutputStream(output)) {
            add(jar,
                    "BOOT-INF/classes/core-rules-wiki/pointers/company-current.json",
                    pointer);
            add(jar,
                    "BOOT-INF/classes/core-rules-wiki/contracts/exact.bin",
                    contract);
        }

        Path materialized = ClasspathKnowledgeMaterializer.materialize(archive);
        try {
            assertThat(materialized).isNotNull();
            assertThat(Files.readAllBytes(
                    materialized.resolve("pointers/company-current.json")))
                    .containsExactly(pointer);
            assertThat(Files.readAllBytes(materialized.resolve("contracts/exact.bin")))
                    .containsExactly(contract);
            assertThat(materialized.resolve(".complete")).isRegularFile();
        } finally {
            if (materialized != null && Files.isDirectory(materialized)) {
                try (var paths = Files.walk(materialized)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    // 临时测试目录的清理失败不掩盖功能断言。
                                }
                            });
                }
            }
        }
    }

    private static void add(JarOutputStream jar, String name, byte[] content)
            throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
