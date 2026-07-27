package com.hospital.wikiagent.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 将 Spring Boot JAR 中的知识包按原始字节物化为只读运行目录。
 *
 * <p>现有知识读取器依赖 {@link Path} 完成指针切换和 SHA-256 校验，本类型只在外置目录
 * 不存在时工作；它不修改 JAR，也不改变发布文件内容或换行。</p>
 */
final class ClasspathKnowledgeMaterializer {
    private static final String PLAIN_PREFIX = "core-rules-wiki/";
    private static final String BOOT_PREFIX = "BOOT-INF/classes/" + PLAIN_PREFIX;

    private ClasspathKnowledgeMaterializer() {
    }

    static Path materialize() {
        try {
            Path artifact = locateArtifact();
            if (artifact == null) {
                return null;
            }
            return materialize(artifact);
        } catch (Exception exception) {
            throw new IllegalStateException("无法从应用 JAR 物化内嵌知识包。", exception);
        }
    }

    static Path materialize(Path artifact) {
        try {
            Path normalizedArtifact = artifact.toAbsolutePath().normalize();
            if (!isKnowledgeArchive(normalizedArtifact)) {
                return null;
            }
            String fingerprint = fingerprint(normalizedArtifact);
            Path target = Path.of(System.getProperty("java.io.tmpdir"),
                    "wiki-agent-knowledge", fingerprint).toAbsolutePath().normalize();
            Path marker = target.resolve(".complete");
            if (Files.isRegularFile(marker)) {
                return target;
            }
            Files.createDirectories(target);
            try (JarFile jar = new JarFile(normalizedArtifact.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String relative = relative(entry.getName());
                    if (relative == null || relative.isBlank()) {
                        continue;
                    }
                    Path destination = target.resolve(relative).normalize();
                    if (!destination.startsWith(target)) {
                        throw new IllegalStateException("知识包包含越界路径。");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        try (InputStream input = jar.getInputStream(entry)) {
                            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            Files.writeString(marker, fingerprint);
            return target;
        } catch (Exception exception) {
            throw new IllegalStateException("无法从应用 JAR 物化内嵌知识包。", exception);
        }
    }

    private static Path locateArtifact() throws Exception {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry).toAbsolutePath().normalize();
                if (isKnowledgeArchive(candidate)) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // 继续检查 CodeSource；classpath 中可能包含非文件条目。
            }
        }

        URI location = WikiRuleKnowledgeSource.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
        if ("file".equalsIgnoreCase(location.getScheme())) {
            Path candidate = Path.of(location).toAbsolutePath().normalize();
            return isKnowledgeArchive(candidate) ? candidate : null;
        }
        String external = URLDecoder.decode(location.toString(), StandardCharsets.UTF_8);
        int jarEnd = external.toLowerCase().indexOf(".jar");
        if (jarEnd < 0) {
            return null;
        }
        String pathText = external.substring(0, jarEnd + 4)
                .replaceFirst("^jar:nested:", "")
                .replaceFirst("^jar:file:", "")
                .replaceFirst("^jar:", "")
                .replaceFirst("^file:", "");
        if (pathText.matches("^/[A-Za-z]:/.*")) {
            pathText = pathText.substring(1);
        }
        Path candidate = Path.of(pathText).toAbsolutePath().normalize();
        return isKnowledgeArchive(candidate) ? candidate : null;
    }

    private static boolean isKnowledgeArchive(Path candidate) {
        if (!Files.isRegularFile(candidate)
                || !candidate.getFileName().toString().toLowerCase().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(candidate.toFile())) {
            return jar.getEntry(BOOT_PREFIX + "pointers/company-current.json") != null
                    || jar.getEntry(PLAIN_PREFIX + "pointers/company-current.json") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String relative(String name) {
        if (name.startsWith(BOOT_PREFIX)) {
            return name.substring(BOOT_PREFIX.length());
        }
        if (name.startsWith(PLAIN_PREFIX)) {
            return name.substring(PLAIN_PREFIX.length());
        }
        return null;
    }

    private static String fingerprint(Path artifact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(artifact.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(Long.toString(Files.size(artifact)).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        digest.update(Long.toString(Files.getLastModifiedTime(artifact).toMillis()).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
    }
}
