package com.hospital.wikiagent.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.hospital.wikiagent.auth.HospitalPrincipal;

@Component
public class UploadStorage {
    private final Path root;
    private final long maxBytes;

    public UploadStorage(UploadProperties properties) {
        this.root = properties.getRoot().toAbsolutePath().normalize();
        this.maxBytes = Math.max(1, properties.getMaxBytes());
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化上传目录", exception);
        }
    }

    public StoredUpload store(MultipartFile file, HospitalPrincipal principal) {
        String originalName = safeOriginalName(file == null ? null : file.getOriginalFilename());
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Java 迁移版当前仅支持 .xlsx 格式的 Excel 文件。");
        }
        long size = file.getSize();
        if (size <= 0) {
            throw new IllegalArgumentException("上传文件不能为空。");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("文件大小不能超过 10MB。");
        }
        String key = principal.hospitalId() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + "_" + originalName;
        Path target = ownedPath(key, principal.hospitalId());
        Path temporary = root.resolve("." + key + ".uploading").normalize();
        try (InputStream input = file.getInputStream()) {
            byte[] signature = input.readNBytes(4);
            if (signature.length != 4 || signature[0] != 'P' || signature[1] != 'K') {
                throw new IllegalArgumentException("上传内容不是有效的 .xlsx 文件。");
            }
            Files.deleteIfExists(temporary);
            try (var output = Files.newOutputStream(temporary)) {
                output.write(signature);
                long copied = input.transferTo(output) + signature.length;
                if (copied > maxBytes) {
                    throw new IllegalArgumentException("文件大小不能超过 10MB。");
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredUpload(key, originalName, Files.size(target), target);
        } catch (IllegalArgumentException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("保存上传文件失败", exception);
        }
    }

    public StoredUpload requireOwned(String fileKey, String hospitalId) {
        Path path = ownedPath(fileKey, hospitalId);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new UploadAccessException("UPLOAD_NOT_FOUND", "未找到已上传的文件，请先上传 Excel 文件。");
        }
        String marker = hospitalId + "_";
        String suffix = fileKey.substring(marker.length());
        int separator = suffix.indexOf('_');
        String originalName = separator >= 0 && separator + 1 < suffix.length()
                ? suffix.substring(separator + 1)
                : fileKey;
        try {
            return new StoredUpload(fileKey, originalName, Files.size(path), path);
        } catch (IOException exception) {
            throw new UploadAccessException("UPLOAD_NOT_FOUND", "无法读取已上传的文件。");
        }
    }

    private Path ownedPath(String fileKey, String hospitalId) {
        String normalized = fileKey == null ? "" : fileKey.strip();
        if (normalized.isEmpty() || normalized.length() > 255
                || normalized.equals(".") || normalized.equals("..")
                || normalized.contains("/") || normalized.contains("\\")) {
            throw new UploadAccessException("UPLOAD_FILE_KEY_INVALID", "上传文件编号不符合安全约束。");
        }
        if (!normalized.startsWith(hospitalId + "_")) {
            throw new UploadAccessException("UPLOAD_ACCESS_DENIED", "无权访问其他医院的上传文件。");
        }
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.getParent().equals(root)) {
            throw new UploadAccessException("UPLOAD_ACCESS_DENIED", "无权访问该上传文件。");
        }
        return candidate;
    }

    private static String safeOriginalName(String value) {
        String candidate = value == null ? "" : value.strip();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("上传文件必须包含文件名。");
        }
        try {
            candidate = Path.of(candidate).getFileName().toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("上传文件名不符合安全约束。");
        }
        candidate = candidate.replaceAll("[\\p{Cntrl}\\\\/]", "_");
        if (candidate.length() > 160) {
            candidate = candidate.substring(candidate.length() - 160);
        }
        if (candidate.isBlank() || candidate.equals(".") || candidate.equals("..")) {
            throw new IllegalArgumentException("上传文件名不符合安全约束。");
        }
        return candidate;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public record StoredUpload(String fileKey, String originalName, long sizeBytes, Path path) {
    }

    public static class UploadAccessException extends RuntimeException {
        private final String code;

        public UploadAccessException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
