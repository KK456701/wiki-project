package com.hospital.wikiagent.agent.mras;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 统一读取 classpath 或外部目录中的 knowledge-index。
 *
 * <p>该组件只负责资源定位和只读访问，不解释知识内容；配置了外部目录时，目录缺失会直接终止启动，
 * 以避免生产运行静默回退到另一套口径。未配置时仍使用 classpath 默认目录，供普通部署和既有单元测试使用。
 */
@Component
public class KnowledgeIndexResources {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexResources.class);
    private static final String CLASSPATH_DEFAULT = "classpath:knowledge-index";

    private final String root;
    private final Path externalRoot;

    @Autowired
    public KnowledgeIndexResources(
            @Value("${wiki.knowledge-index-root:classpath:knowledge-index}") String root) {
        String configured = root == null || root.isBlank() ? CLASSPATH_DEFAULT : root.strip();
        this.root = trimTrailingSlash(configured);
        this.externalRoot = this.root.startsWith("classpath:")
                ? null : Path.of(this.root).toAbsolutePath().normalize();
        if (externalRoot != null && !Files.isDirectory(externalRoot)) {
            throw new IllegalStateException("知识库目录不存在: " + externalRoot);
        }
        log.info("知识库根目录: {}", description());
    }

    static KnowledgeIndexResources classpathDefault() {
        return new KnowledgeIndexResources(CLASSPATH_DEFAULT);
    }

    public Resource[] markdownResources(String directory) {
        String safeDirectory = safeRelative(directory);
        if (externalRoot == null) {
            try {
                return new PathMatchingResourcePatternResolver().getResources(
                        root + "/" + safeDirectory + "/*.md");
            } catch (IOException exception) {
                throw new UncheckedIOException("无法扫描知识库目录: " + safeDirectory, exception);
            }
        }
        Path directoryPath = resolveExternal(safeDirectory);
        if (!Files.isDirectory(directoryPath)) {
            throw new IllegalStateException("知识库子目录不存在: " + directoryPath);
        }
        try (var paths = Files.list(directoryPath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(FileSystemResource::new)
                    .toArray(Resource[]::new);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法扫描知识库目录: " + directoryPath, exception);
        }
    }

    public String read(String relativePath) {
        String safePath = safeRelative(relativePath);
        try {
            Resource resource;
            if (externalRoot == null) {
                String classpathPath = root.substring("classpath:".length()) + "/" + safePath;
                resource = new org.springframework.core.io.ClassPathResource(classpathPath);
            } else {
                resource = new FileSystemResource(resolveExternal(safePath));
            }
            return resource.exists()
                    ? resource.getContentAsString(StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取知识库文件: " + safePath, exception);
        }
    }

    public String description() {
        return externalRoot == null ? root : externalRoot.toString();
    }

    private Path resolveExternal(String relativePath) {
        Path resolved = externalRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(externalRoot)) {
            throw new IllegalArgumentException("知识库路径越界: " + relativePath);
        }
        return resolved;
    }

    private static String safeRelative(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/').strip();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalArgumentException("无效知识库相对路径: " + value);
        }
        return normalized;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") || result.endsWith("\\")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
