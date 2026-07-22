package com.hospital.wikiagent.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
/**
 * 提供 {@code SpaForwardController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 */
public class SpaForwardController {
    @GetMapping({"/", "/runs", "/metadata", "/terminology", "/monitoring", "/implementation"})
    public String index() {
        return "forward:/index.html";
    }
}
