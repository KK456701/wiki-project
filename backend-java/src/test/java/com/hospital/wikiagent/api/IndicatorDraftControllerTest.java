package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.implementation.IndicatorDraftRepository;

class IndicatorDraftControllerTest {
    private HospitalAuthService hospitals;
    private IndicatorDraftRepository drafts;
    private IndicatorDraftController controller;
    private HospitalPrincipal principal;

    @BeforeEach
    void setUp() {
        hospitals = Mockito.mock(HospitalAuthService.class);
        drafts = Mockito.mock(IndicatorDraftRepository.class);
        controller = new IndicatorDraftController(hospitals, drafts);
        principal = new HospitalPrincipal("user_001", "doctor", "hospital_001", Set.of(), false, "SESSION_1");
        when(hospitals.authenticate("token")).thenReturn(principal);
    }

    @Test
    void listsOnlyAuthenticatedHospitalDrafts() {
        when(drafts.list("hospital_001", null)).thenReturn(List.of(Map.of("draft_id", "DRAFT_1")));

        List<Map<String, Object>> result = controller.list("Bearer token", "hospital_001", null);

        assertThat(result).hasSize(1);
        verify(drafts).list("hospital_001", null);
        assertThatThrownBy(() -> controller.list("Bearer token", "hospital_002", null))
                .isInstanceOf(HospitalAuthException.class);
    }

    @Test
    void ignoresClientActorAndUsesAuthenticatedUser() {
        Map<String, Object> changes = Map.of("index_name", "新版指标");
        when(drafts.update("DRAFT_1", "hospital_001", 3, changes, "user_001"))
                .thenReturn(Map.of("current_version", 4));

        Map<String, Object> result = controller.update("Bearer token", "DRAFT_1",
                new IndicatorDraftController.UpdateRequest(3, changes, "forged_admin"));

        assertThat(result).containsEntry("current_version", 4);
        verify(drafts).update("DRAFT_1", "hospital_001", 3, changes, "user_001");
    }
}
