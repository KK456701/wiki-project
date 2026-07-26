package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

class SpaForwardControllerTest {
    @Test
    void forwardsKnownVueRoutesToBundledIndex() {
        assertThat(new SpaForwardController().index()).isEqualTo("forward:/index.html");
    }

    @Test
    void doesNotForwardRemovedMonitoringOrImplementationRoutes() throws Exception {
        GetMapping mapping = SpaForwardController.class.getMethod("index")
                .getAnnotation(GetMapping.class);

        assertThat(mapping.value())
                .containsExactlyInAnyOrder("/", "/runs", "/metadata", "/terminology")
                .doesNotContain("/monitoring", "/implementation");
    }
}
