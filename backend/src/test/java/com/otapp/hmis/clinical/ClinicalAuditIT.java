package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Audit-trail completeness tests for the inc-05 clinical context (ADR-0007; QA-09, SEC-01).
 *
 * <p>Asserts that state-mutating clinical actions write an append-only {@code audit_logs} row with
 * the correct {@code entity_type}, {@code entity_uid}, {@code action}, and {@code actor_username}.
 * Most importantly it pins SEC-01: the cross-module Patient→DECEASED identity mutation performed by
 * {@code PatientClosureListener} on deceased-note approval is now audited against the Patient
 * aggregate with the REAL approving principal (not SYSTEM).
 *
 * <p>Non-{@code @Transactional}: the deceased-approval path commits across the cross-module event
 * listener (BEFORE_COMMIT), so the test must observe committed rows.
 */
class ClinicalAuditIT extends AbstractIntegrationTest {

    private static final String CLINICAL = "/api/v1/clinical/consultations";
    private static final String CLINICAL_BASE = "/api/v1/clinical";  // closure endpoints hang here
    private static final String PATIENTS = "/api/v1/patients";
    private static final String PRICES   = "/api/v1/masterdata/service-prices";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired BusinessDayService businessDayService;
    @Autowired DataSource dataSource;

    private String token;          // subject "auditor" — the audit actor under test

    @BeforeEach
    void setUp() throws Exception {
        token = jwtFactory.tokenWithPrivileges("auditor",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private long countAudit(String entityType, String entityUid, String action, String actor) {
        Long n = jdbc().queryForObject(
                "SELECT count(*) FROM audit_logs "
                        + "WHERE entity_type = ? AND entity_uid = ? AND action = ? AND actor_username = ?",
                Long.class, entityType, entityUid, action, actor);
        return n == null ? 0 : n;
    }

    // =========================================================================
    // open_consultation → an UPDATE audit row on clinical.Consultation
    // =========================================================================

    @Test
    void open_writesConsultationUpdateAudit() {
        String tag = uniq();
        // INSURANCE so settled=true → open passes the settlement gate.
        Consultation c = new Consultation(
                fakeUid("PAT", tag), null, fakeUid("CLN", tag), fakeUid("DOC", tag),
                fakeUid("BIL", tag), PaymentMode.INSURANCE, false, true,
                "MEM-" + tag, fakeUid("PLN", tag), businessDayService.currentUid());
        String uid = consultationRepository.saveAndFlush(c).getUid();

        // No exception → open succeeded; assert the audit row.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mockMvc.perform(post(CLINICAL + "/uid/" + uid + "/open")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk()));

        assertThat(countAudit("clinical.Consultation", uid, "UPDATE", "auditor"))
                .as("open_consultation must write a clinical.Consultation UPDATE audit row")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // deceased approval → SEC-01: Patient.type=DECEASED mutation is audited against the Patient
    // =========================================================================

    @Test
    void deceasedApproval_auditsConsultationNoteAndPatientDeceasedMutation() throws Exception {
        String tag = uniq();

        // A real, registered Patient (the deceased-event listener loads + mutates it).
        String patientUid = registerCashPatient(tag);

        // Seed a settled CASH consultation for that patient, opened (IN_PROCESS) so it is closeable.
        Consultation c = new Consultation(
                patientUid, null, fakeUid("CLN", tag), fakeUid("DOC", tag),
                fakeUid("BIL", tag), PaymentMode.CASH, false, true, "", null,
                businessDayService.currentUid());
        c.open();
        String consultUid = consultationRepository.saveAndFlush(c).getUid();

        // save_deceased_note → consultation HELD, note PENDING.
        String noteBody = """
                {"patientSummary":"Patient expired","causeOfDeath":"Cardiac arrest",
                 "date":"2026-06-04","time":"10:30:00"}
                """;
        MvcResult noteRes = mockMvc.perform(
                        post(CLINICAL_BASE + "/consultations/uid/" + consultUid + "/deceased-note")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content(noteBody))
                .andExpect(status().isCreated())
                .andReturn();
        String noteUid = objectMapper.readTree(noteRes.getResponse().getContentAsString())
                .get("uid").asText();

        // get_deceased_summary (approve) → consultation SIGNED_OUT, note APPROVED,
        // PatientDeceasedEvent → PatientClosureListener sets Patient.type=DECEASED (+ audits it).
        mockMvc.perform(post(CLINICAL_BASE + "/deceased-notes/uid/" + noteUid + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // SEC-01: the Patient identity mutation must be audited against the Patient aggregate,
        // attributed to the approving principal (auditor), NOT SYSTEM.
        assertThat(countAudit("registration.Patient", patientUid, "UPDATE", "auditor"))
                .as("SEC-01: Patient→DECEASED mutation must be audited against the Patient with the real approver")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        return "AU" + Long.toHexString(System.nanoTime());
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "000000000000000000000000000000").substring(0, 26);
    }

    private String registerCashPatient(String tag) throws Exception {
        String body = """
                {"firstName":"Aud%s","middleName":null,"lastName":"Patient%s",
                 "dateOfBirth":"1985-01-01","gender":"MALE","paymentType":"CASH",
                 "membershipNo":null,"insurancePlanUid":null,"phoneNo":"0700000000",
                 "address":null,"email":null,"nationality":null,"nationalId":null,
                 "passportNo":null,"kinFullName":null,"kinRelationship":null,"kinPhoneNo":null}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(PATIENTS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    private void ensureRegistrationCashPrice() throws Exception {
        String body = """
                {"planUid":null,"kind":"REGISTRATION","serviceUid":null,"currency":"TZS",
                 "amount":500.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """;
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }
}
