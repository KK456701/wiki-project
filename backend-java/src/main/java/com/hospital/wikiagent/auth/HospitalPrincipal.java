package com.hospital.wikiagent.auth;

import java.util.Set;

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
