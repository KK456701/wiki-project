package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.metadata.MetadataSyncService;

class MetadataControllerTest {
    @Test
    void rejectsMetadataAccessForAnotherHospital() {
        HospitalAuthService auth = mock(HospitalAuthService.class);
        MetadataSyncService metadata = mock(MetadataSyncService.class);
        when(auth.authenticate("token")).thenReturn(new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "session_001"));
        MetadataController controller = new MetadataController(auth, metadata);

        assertThatThrownBy(() -> controller.overview(
                "Bearer token", "hospital_002", null))
                .isInstanceOf(HospitalAuthException.class)
                .hasMessageContaining("其他医院");
    }
}
