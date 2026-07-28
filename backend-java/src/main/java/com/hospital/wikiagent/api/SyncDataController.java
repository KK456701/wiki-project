package com.hospital.wikiagent.api;

import com.hospital.wikiagent.dto.SyncDataDto;
import com.hospital.wikiagent.service.SyncDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据同步 HTTP 接口：允许外部触发业务数据抽取并写入本地 SQL Server。
 *
 * <p>仅在 {@code wiki.sqlserver.enabled=true} 时注册，依赖 {@link SyncDataService}
 * 完成实际的清库 + 抽取 + 写入操作。请求体必须符合 {@link SyncDataDto} 约束。</p>
 */
@RestController
@RequestMapping("/api/sync")
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class SyncDataController {

    private final SyncDataService syncDataService;

    public SyncDataController(SyncDataService syncDataService) {
        this.syncDataService = syncDataService;
    }

    @PostMapping("/local-db/sync")
    public String syncEventData(@Validated @RequestBody SyncDataDto syncDataDto) {
        return syncDataService.syncEventData(syncDataDto);
    }
}
