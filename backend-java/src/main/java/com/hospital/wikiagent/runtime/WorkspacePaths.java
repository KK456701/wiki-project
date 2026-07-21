package com.hospital.wikiagent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

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
