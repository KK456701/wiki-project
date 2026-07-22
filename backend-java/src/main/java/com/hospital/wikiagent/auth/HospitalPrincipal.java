package com.hospital.wikiagent.auth;

import java.util.Set;

/**
 * 定义 {@code HospitalPrincipal} 的不可变数据载体。
 */
public record HospitalPrincipal(
        String userId,
        String accountId,
        String hospitalId,
        Set<String> permissions,
        boolean mustChangePassword,
        String sessionId) {

    public HospitalPrincipal {
        permissions = Set.copyOf(permissions);
    }

    public boolean canAccessHospital(String candidateHospitalId) {
        return candidateHospitalId != null && hospitalId.equals(candidateHospitalId);
    }
}
