package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpaForwardControllerTest {
    @Test
    void forwardsKnownVueRoutesToBundledIndex() {
        assertThat(new SpaForwardController().index()).isEqualTo("forward:/index.html");
    }
}
