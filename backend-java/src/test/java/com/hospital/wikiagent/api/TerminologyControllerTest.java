package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.terminology.TerminologyService;

class TerminologyControllerTest {
    @Test
    void rejectsOtherHospitalMappingAccess() {
        HospitalAuthService auth = mock(HospitalAuthService.class);
        TerminologyService terminology = mock(TerminologyService.class);
        when(auth.authenticate("token")).thenReturn(new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "session_001"));
        TerminologyController controller = new TerminologyController(auth, terminology);

        assertThatThrownBy(() -> controller.concept(
                "Bearer token", "TERM_001", "hospital_002"))
                .isInstanceOf(HospitalAuthException.class)
                .hasMessageContaining("其他医院");
    }
}
