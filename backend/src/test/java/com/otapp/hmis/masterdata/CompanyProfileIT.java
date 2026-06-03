package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@code /api/v1/masterdata/company-profile} (build-spec §1.5, CR-14).
 *
 * <h2>Single-row invariant (CR-14)</h2>
 * The V3 seed already inserts one row with name "Zana Health Management Hospital".
 * Therefore:
 * <ul>
 *   <li>GET → 200 returns the seeded row.</li>
 *   <li>POST → 409 COMPANY_PROFILE_EXISTS (row already seeded).</li>
 *   <li>PUT → 200 updates the seeded row; subsequent GET reflects the change.</li>
 * </ul>
 *
 * <h2>Gate coverage</h2>
 * GET/POST/PUT without ADMIN-ACCESS → 403; without token → 401.
 */
class CompanyProfileIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/company-profile";

    @Autowired MockMvc mockMvc;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------------
    // GET — reads seeded row from V3
    // ------------------------------------------------------------------

    @Test
    void get_withAdminAccess_returns200AndSeededRow() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        // The company profile is a mutable singleton; the PUT test in this class updates its name,
        // and the singleton Testcontainer is shared across tests, so assert the row's PRESENCE and
        // SHAPE (uid + non-blank name, no internal id) rather than a specific seed value.
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").exists())
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotExist());

        // Audit row written on READ
        List<AuditLog> rows = auditLogRepository
                .findByEntityTypeOrderByOccurredAtAsc("masterdata.CompanyProfile");
        assertThat(rows).isNotEmpty();
        AuditLog last = rows.get(rows.size() - 1);
        assertThat(last.getAction()).isEqualTo(AuditAction.READ);
        assertThat(last.getActorUsername()).isEqualTo("admin");
        assertThat(last.getChecksum()).isNotBlank();
    }

    @Test
    void get_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // POST — row already seeded by V3 → 409
    // ------------------------------------------------------------------

    @Test
    void post_whenRowAlreadyExists_returns409WithCorrectType() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalProfileJson("Duplicate Hospital")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:company-profile-exists"));
    }

    @Test
    void post_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalProfileJson("Should Fail")))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalProfileJson("Should Fail")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // PUT — updates the seeded row; GET reflects new values
    // ------------------------------------------------------------------

    @Test
    void put_withAdminAccess_updatesRowAndReturns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        String updateBody = fullProfileJson("Updated Hospital Name", "456 New Street",
                "+255 800 000 001", "12345", "EMP2");

        mockMvc.perform(put(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Hospital Name"))
                .andExpect(jsonPath("$.tin").value("12345"))
                .andExpect(jsonPath("$.employeePrefix").value("EMP2"))
                .andExpect(jsonPath("$.registrationFee").value(500.00))
                .andExpect(jsonPath("$.id").doesNotExist());

        // Verify GET reflects the update
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Hospital Name"));

        // Audit UPDATE row must exist
        List<AuditLog> rows = auditLogRepository
                .findByEntityTypeOrderByOccurredAtAsc("masterdata.CompanyProfile");
        assertThat(rows).anyMatch(r -> r.getAction() == AuditAction.UPDATE);
    }

    @Test
    void put_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(put(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalProfileJson("Should Fail")))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_withoutToken_returns401() throws Exception {
        mockMvc.perform(put(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalProfileJson("Should Fail")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Full field-set round-trip: PUT all fields, GET returns them
    // ------------------------------------------------------------------

    @Test
    void put_fullFieldSet_getReturnsAllFields() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        mockMvc.perform(put(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullProfileJson("Full Field Hospital", "1 Main St",
                                "+255 700 000 002", "TIN-001", "EMP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Dr. Contact"))
                .andExpect(jsonPath("$.vrn").value("VRN-001"))
                .andExpect(jsonPath("$.bankName").value("National Bank"))
                .andExpect(jsonPath("$.quotationNotes").value("Standard quotation terms"))
                .andExpect(jsonPath("$.salesInvoiceNotes").value("Thank you for your business"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String minimalProfileJson(String name) {
        return """
                {"name":"%s","address":null,"phone":null,"contactName":null,"tin":null,
                 "vrn":null,"physicalAddress":null,"postCode":null,"postAddress":null,
                 "telephone":null,"mobile":null,"email":null,"fax":null,"website":null,
                 "bankAccountName":null,"bankPhysicalAddress":null,"bankPostCode":null,
                 "bankPostAddress":null,"bankName":null,"bankAccountNo":null,
                 "bankAccountName2":null,"bankPhysicalAddress2":null,"bankPostCode2":null,
                 "bankPostAddress2":null,"bankName2":null,"bankAccountNo2":null,
                 "bankAccountName3":null,"bankPhysicalAddress3":null,"bankPostCode3":null,
                 "bankPostAddress3":null,"bankName3":null,"bankAccountNo3":null,
                 "quotationNotes":null,"salesInvoiceNotes":null,"registrationFee":0,
                 "publicPath":null,"employeePrefix":"EMP"}
                """.formatted(name);
    }

    private String fullProfileJson(String name, String address, String phone,
                                   String tin, String empPrefix) {
        return """
                {"name":"%s","address":"%s","phone":"%s",
                 "contactName":"Dr. Contact","tin":"%s","vrn":"VRN-001",
                 "physicalAddress":"1 Hospital Rd","postCode":"12345","postAddress":"P.O. Box 1",
                 "telephone":"+255 700 000 000","mobile":"+255 750 000 000",
                 "email":"info@hospital.tz","fax":"+255 700 000 001","website":"https://hospital.tz",
                 "bankAccountName":"Hospital Account","bankPhysicalAddress":"Bank St",
                 "bankPostCode":"11111","bankPostAddress":"P.O. Box 2",
                 "bankName":"National Bank","bankAccountNo":"1234567890",
                 "bankAccountName2":null,"bankPhysicalAddress2":null,"bankPostCode2":null,
                 "bankPostAddress2":null,"bankName2":null,"bankAccountNo2":null,
                 "bankAccountName3":null,"bankPhysicalAddress3":null,"bankPostCode3":null,
                 "bankPostAddress3":null,"bankName3":null,"bankAccountNo3":null,
                 "quotationNotes":"Standard quotation terms",
                 "salesInvoiceNotes":"Thank you for your business",
                 "registrationFee":500.00,"publicPath":"/public","employeePrefix":"%s"}
                """.formatted(name, address, phone, tin, empPrefix);
    }
}
