package com.hospital.wikiagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.auth")
public class HospitalAuthProperties {
    private int sessionHours = 8;

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }
}
