package com.hospital.wikiagent.api;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.contract.UploadResponse;
import com.hospital.wikiagent.upload.UploadStorage;

@RestController
@RequestMapping("/api/agent")
/**
 * 提供 {@code AgentUploadController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 */
public class AgentUploadController {
    private final HospitalAuthService auth;
    private final UploadStorage storage;

    public AgentUploadController(HospitalAuthService auth, UploadStorage storage) {
        this.auth = auth;
        this.storage = storage;
    }

    @PostMapping("/upload")
    public UploadResponse upload(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestPart("file") MultipartFile file) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        UploadStorage.StoredUpload saved = storage.store(file, principal);
        return new UploadResponse(saved.fileKey(), saved.originalName(), saved.sizeBytes());
    }
}
