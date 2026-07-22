package com.hospital.wikiagent.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.migration")
public class MigrationProperties {
    private String authorityRuntime = "python";
    private boolean cutoverApproved;
    private String readinessReportId = "";

    public String getAuthorityRuntime() { return authorityRuntime; }
    public void setAuthorityRuntime(String authorityRuntime) { this.authorityRuntime = authorityRuntime; }
    public boolean isCutoverApproved() { return cutoverApproved; }
    public void setCutoverApproved(boolean cutoverApproved) { this.cutoverApproved = cutoverApproved; }
    public String getReadinessReportId() { return readinessReportId; }
    public void setReadinessReportId(String readinessReportId) { this.readinessReportId = readinessReportId; }

    public boolean javaAuthority() { return "java".equalsIgnoreCase(authorityRuntime); }
    public String mode() { return javaAuthority() ? "authority" : "compatibility_shadow"; }
}
