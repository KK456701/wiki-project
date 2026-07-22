package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供不依赖业务数据库的服务健康与运行时状态接口。 */
@RestController
@RequestMapping("/api")
public class SystemController {
    /** 返回负载均衡器和部署脚本使用的最小健康状态。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "runtime", "java",
                "agent_orchestration", "compiled_plan");
    }

    /** 返回当前唯一生产运行时，替代迁移阶段使用的双栈状态接口。 */
    @GetMapping("/runtime/status")
    public Map<String, Object> runtimeStatus() {
        return Map.of(
                "runtime", "java",
                "frontend", "vue3",
                "rule_source", "wiki",
                "runtime_store", "sqlite",
                "business_database_access", "dbhub");
    }
}
