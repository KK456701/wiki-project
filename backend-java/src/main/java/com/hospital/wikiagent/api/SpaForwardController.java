package com.hospital.wikiagent.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 提供 {@code SpaForwardController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@Controller
public class SpaForwardController {
    @GetMapping({"/", "/runs", "/metadata", "/terminology", "/monitoring", "/implementation"})
    public String index() {
        return "forward:/index.html";
    }
}
