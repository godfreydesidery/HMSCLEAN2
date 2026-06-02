package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLog;
import com.otapp.hmis.shared.audit.AuditLogRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full HTTP -&gt; service -&gt; DB -&gt; audit_logs slice for {@code GET /api/v1/company-profile}
 * (increment-00 DoD). Asserts 200 with ADMIN-ACCESS, 403 without, 401 with no token, and that one
 * {@code READ} audit row is written on success.
 */
class CompanyProfileIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestJwtFactory jwtFactory;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Test
    void returns200AndProfileWithAdminAccessAndWritesAuditRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        mockMvc.perform(get("/api/v1/company-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").exists())
                .andExpect(jsonPath("$.name").value("Zana Health Management Hospital"))
                .andExpect(jsonPath("$.id").doesNotExist());

        List<AuditLog> rows = auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.CompanyProfile");
        assertThat(rows).isNotEmpty();
        AuditLog last = rows.get(rows.size() - 1);
        assertThat(last.getAction()).isEqualTo(AuditAction.READ);
        assertThat(last.getActorUsername()).isEqualTo("admin");
        assertThat(last.getChecksum()).isNotBlank();
        assertThat(last.getEntityUid()).isNotBlank();
    }

    @Test
    void returns403WithoutAdminAccess() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("PATIENT-CREATE"));

        mockMvc.perform(get("/api/v1/company-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/company-profile"))
                .andExpect(status().isUnauthorized());
    }
}
