package com.hospital.wikiagent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 实现 {@code WorkspacePaths} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
public final class WorkspacePaths {
    private WorkspacePaths() {
    }

    public static Path resolve(Path configured) {
        if (configured == null) {
            throw new IllegalArgumentException("运行目录配置不能为空");
        }
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path workspace = looksLikeWorkspace(workingDirectory)
                ? workingDirectory
                : looksLikeWorkspace(workingDirectory.getParent())
                        ? workingDirectory.getParent()
                        : workingDirectory;
        return workspace.resolve(configured).normalize();
    }

    private static boolean looksLikeWorkspace(Path candidate) {
        return candidate != null
                && (Files.isDirectory(candidate.resolve(".git"))
                        || Files.isRegularFile(candidate.resolve("config.yaml")))
                && Files.isDirectory(candidate.resolve("app"));
    }
}
