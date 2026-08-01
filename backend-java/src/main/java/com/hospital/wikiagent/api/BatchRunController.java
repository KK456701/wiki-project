package com.hospital.wikiagent.api;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchJobSnapshot;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.IndicatorDetailException;

/**
 * 提供批量指标运行及其逐项卡片快照的安全回读接口。
 *
 * <p>所有查询均通过令牌取得医院和用户作用域，并以两者共同限制 batchRunId；
 * 不存在或越权统一返回不可枚举的未找到错误。响应禁止缓存，供刷新页面和切换会话后
 * 恢复与原卡片绑定的确定性运行上下文。</p>
 */
@RestController
@RequestMapping("/api/agent/batches")
public class BatchRunController {
    private final HospitalAuthService auth;
    private final BatchJobStore jobs;

    public BatchRunController(HospitalAuthService auth, BatchJobStore jobs) {
        this.auth = auth;
        this.jobs = jobs;
    }

    @GetMapping("/{batchRunId}")
    public ResponseEntity<BatchRunResponse> get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable String batchRunId) {
        HospitalPrincipal principal =
                auth.authenticate(BearerTokens.require(authorization));
        BatchJobSnapshot job = jobs.loadJob(
                        batchRunId, principal.hospitalId(), principal.userId())
                .orElseThrow(() -> new IndicatorDetailException(
                        "BATCH_RUN_NOT_FOUND",
                        "批次不存在或无权访问。",
                        HttpStatus.NOT_FOUND));
        List<BatchTaskSnapshot> tasks = jobs.loadTasks(
                batchRunId, principal.hospitalId(), principal.userId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new BatchRunResponse(job, tasks));
    }

    public record BatchRunResponse(
            BatchJobSnapshot job,
            List<BatchTaskSnapshot> tasks) {
    }
}
