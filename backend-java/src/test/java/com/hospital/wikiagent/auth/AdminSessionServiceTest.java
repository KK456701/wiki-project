package com.hospital.wikiagent.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminSessionServiceTest {
    @Test
    void issuesAndRevokesAdminToken() {
        AdminSessionService service = new AdminSessionService("secret");
        String token = service.login("secret");
        service.require("Bearer " + token);
        service.logout("Bearer " + token);
        assertThatThrownBy(() -> service.require("Bearer " + token))
                .isInstanceOf(HospitalAuthException.class);
    }

    @Test
    void rejectsWrongPassword() {
        AdminSessionService service = new AdminSessionService("secret");
        assertThatThrownBy(() -> service.login("wrong"))
                .isInstanceOf(HospitalAuthException.class);
    }

    @Test
    void refusesKnownPlaceholderPassword() {
        AdminSessionService service = new AdminSessionService("CHANGE_ME");
        assertThatThrownBy(() -> service.login("CHANGE_ME"))
                .isInstanceOf(HospitalAuthException.class)
                .hasMessageContaining("尚未配置");
    }
}
