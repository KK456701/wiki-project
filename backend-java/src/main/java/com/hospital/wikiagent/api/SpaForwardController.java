package com.hospital.wikiagent.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
    @GetMapping({"/", "/runs", "/metadata", "/terminology", "/monitoring"})
    public String index() {
        return "forward:/index.html";
    }
}
