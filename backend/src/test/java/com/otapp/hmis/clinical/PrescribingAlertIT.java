package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-05 C11: Prescribing alerts (advisory, non-blocking).
 *
 * <p>Verifies:
 * <ol>
 *   <li>alertsEmpty_forBrandNewPatientMedicine — no prior GIVEN → alerts[] empty.</li>
 *   <li>sameMedicine30Days_fires — prior GIVEN dispensed ≤ 30 days ago → "Has Drugs this month."</li>
 *   <li>sameMedicine_over30Days_noFire — prior GIVEN dispensed > 30 days ago → no same-medicine alert.</li>
 *   <li>unfinishedCourse_fires — prior GIVEN with days='30' dispensed 5 days ago → unfinished alert.</li>
 *   <li>unfinishedCourse_completed_noFire — prior GIVEN with days='3' dispensed 10 days ago → no alert.</li>
 *   <li>unfinishedCourse_nonNumericDays_swallowed — days='as directed' → no crash, no alert.</li>
 *   <li>alerts_neverBlock — prescription is still created (201) regardless of alerts firing.</li>
 *   <li>sameMedicine_sameDay_fires — prior GIVEN dispensed today (days=0) → alert fires.</li>
 * </ol>
 *
 * <p><strong>Clock strategy:</strong>
 * The controller constructs the {@code TxAuditContext} with {@code Instant.now()} as the
 * timestamp (the "now" used by alert computation). To test past-approval scenarios we seed
 * GIVEN prescriptions directly via the repository with a controlled {@code approvedAt}
 * set to a past instant (e.g. 10 days ago). The "now" in the alert service is the
 * {@code ctx.timestamp()} which equals the real wall-clock time at the moment the POST hits
 * the controller — effectively "today". Since seeded {@code approvedAt} is in the past,
 * {@code ChronoUnit.DAYS.between(approvedAt, now)} gives us the expected elapsed days.
 *
 * <p><strong>Seeding GIVEN prescriptions:</strong>
 * We use {@code prescriptionRepository.saveAndFlush()} with a Prescription built via the
 * package-visible factory then mutated via the {@code issue()} domain method and an
 * after-the-fact {@code approvedAt} override via a direct JDBC update. Because
 * {@code Prescription.approvedAt} has no setter (immutable after issue()), we update it via
 * the repository's save mechanism by using reflection-free SQL via a dedicated seeding helper.
 *
 * <p><strong>ApplicationModules.verify() compatibility:</strong>
 * This test lives in {@code com.otapp.hmis.clinical} (the test source tree mirrors the module
 * boundary). It only accesses {@code clinical.domain} and {@code clinical.application} types
 * that are visible within the module's own test scope. No cross-module rule violations.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Alert 1 (DUPLICATE_MEDICINE): PatientResource.java:4480-4521</li>
 *   <li>Alert 2 (UNFINISHED_COURSE): PatientResource.java:4528-4580</li>
 *   <li>Deterministic MAX(approvedAt): CR-INC05-12 (11-DECISIONS-RATIFIED.md §2)</li>
 * </ul>
 */
class PrescribingAlertIT extends AbstractIntegrationTest {

    private static final String BASE         = "/api/v1/clinical";
    private static final String CONSULT_BASE = BASE + "/consultations/uid/";
    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String PRICES_URL    = "/api/v1/masterdata/service-prices";

    // Alert message constants (exact strings from PrescribingAlertService)
    private static final String ALERT_HAS_DRUGS = "Has Drugs this month.";
    private static final String ALERT_UNFINISHED_PREFIX =
            "The patient has not completed the last prescription.";

    @Autowired MockMvc                   mockMvc;
    @Autowired ObjectMapper              objectMapper;
    @Autowired TestJwtFactory            jwtFactory;
    @Autowired ConsultationRepository    consultationRepository;
    @Autowired PrescriptionRepository    prescriptionRepository;
    @Autowired BusinessDayService        businessDayService;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // 1. Brand-new patient+medicine → alerts[] empty
    // =========================================================================

    @Test
    void alertsEmpty_forBrandNewPatientMedicine() throws Exception {
        String tag = uniq();
        String medUid = createMedicine(tag);
        seedPrice(medUid);
        // Fresh consultation for a patient who has never had this medicine before
        String consultUid = seedConsultation(tag, fakeUid("PAT", tag));

        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "5", "1.0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts.length()").value(0))
                .andReturn();

        // Confirm prescription created (alerts did not block)
        String rxUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();
        assertThat(rxUid).isNotBlank();
    }

    // =========================================================================
    // 2. Same medicine dispensed <= 30 days ago → "Has Drugs this month." fires
    // =========================================================================

    @Test
    void sameMedicine30Days_fires() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Seed a prior GIVEN prescription dispensed 10 days ago
        seedGivenPrescription(patUid, medUid, "7", daysAgo(10));

        // New prescription for the same patient+medicine
        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "7", "1.0")))
                .andExpect(status().isCreated())  // alert never blocks (C11 spec)
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("same-medicine alert must fire when prior GIVEN was dispensed 10 days ago")
                .anyMatch(a -> a.contains(ALERT_HAS_DRUGS));
    }

    // =========================================================================
    // 3. Same medicine dispensed > 30 days ago → no same-medicine alert
    // =========================================================================

    @Test
    void sameMedicine_over30Days_noFire() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Seed a prior GIVEN prescription dispensed 45 days ago (> 30 day window)
        seedGivenPrescription(patUid, medUid, "7", daysAgo(45));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "7", "1.0")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("same-medicine alert must NOT fire when prior GIVEN was dispensed 45 days ago")
                .noneMatch(a -> a.contains(ALERT_HAS_DRUGS));
    }

    // =========================================================================
    // 4. Unfinished course fires — days='30', dispensed 5 days ago → alert
    // =========================================================================

    @Test
    void unfinishedCourse_fires() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Prior GIVEN with days='30' dispensed only 5 days ago → 25 days left
        seedGivenPrescription(patUid, medUid, "30", daysAgo(5));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "30", "1.0")))
                .andExpect(status().isCreated())  // alert never blocks
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("unfinished-course alert must fire: days='30', elapsed=5 → 25 days remain")
                .anyMatch(a -> a.startsWith(ALERT_UNFINISHED_PREFIX));
    }

    // =========================================================================
    // 5. Unfinished course does NOT fire when course is completed
    // =========================================================================

    @Test
    void unfinishedCourse_completed_noFire() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Prior GIVEN with days='3' dispensed 10 days ago → elapsed(10) >= prescribed(3) → done
        seedGivenPrescription(patUid, medUid, "3", daysAgo(10));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "3", "1.0")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("unfinished-course alert must NOT fire: days='3', elapsed=10 → course done")
                .noneMatch(a -> a.startsWith(ALERT_UNFINISHED_PREFIX));
    }

    // =========================================================================
    // 6. Non-numeric days → exception swallowed, no alert, no crash
    // =========================================================================

    @Test
    void unfinishedCourse_nonNumericDays_swallowed() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Prior GIVEN with days='as directed' (non-numeric) dispensed 3 days ago
        // The NumberFormatException must be swallowed → no alert, no 500
        // (PatientResource.java:4556-4558 exact try/catch pattern)
        seedGivenPrescription(patUid, medUid, "as directed", daysAgo(3));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "7", "1.0")))
                .andExpect(status().isCreated())  // must not crash (201 not 500)
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("unfinished-course alert must NOT fire when days is non-numeric")
                .noneMatch(a -> a.startsWith(ALERT_UNFINISHED_PREFIX));
        // Response is 201 confirms no exception propagated
    }

    // =========================================================================
    // 7. Alerts never block — prescription IS created even when both alerts fire
    // =========================================================================

    @Test
    void alerts_neverBlock_prescriptionCreated201() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Seed a prior GIVEN designed to trigger BOTH alerts:
        // dispensed 5 days ago with days='30' → same-medicine alert (5<=30) + unfinished alert
        seedGivenPrescription(patUid, medUid, "30", daysAgo(5));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "30", "2.0")))
                .andExpect(status().isCreated())  // 201 — alerts never block
                .andExpect(jsonPath("$.uid").isNotEmpty())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        // Both alerts fire
        assertThat(alerts)
                .anyMatch(a -> a.contains(ALERT_HAS_DRUGS))
                .anyMatch(a -> a.startsWith(ALERT_UNFINISHED_PREFIX));
        // Prescription is persisted despite alerts
        String rxUid = dto.get("uid").asText();
        assertThat(prescriptionRepository.findByUid(rxUid)).isPresent();
    }

    // =========================================================================
    // 8. Same-day dispense (days=0) → "Has Drugs this month." fires
    // =========================================================================

    @Test
    void sameMedicine_sameDay_fires() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Prior GIVEN dispensed just 30 seconds ago (days=0 between them)
        seedGivenPrescription(patUid, medUid, "7", Instant.now().minusSeconds(30));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "7", "1.0")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("same-medicine alert must fire even on same-day dispense (days=0)")
                .anyMatch(a -> a.contains(ALERT_HAS_DRUGS));
    }

    // =========================================================================
    // 9. MAX(approvedAt) determinism — two prior GIVEN; alert uses the newest one
    // =========================================================================

    @Test
    void maxApprovedAt_deterministic_usesNewest() throws Exception {
        String tag = uniq();
        String patUid = fakeUid("PAT", tag);
        String medUid = createMedicine(tag);
        seedPrice(medUid);

        // Seed two prior GIVEN prescriptions: one 40 days ago, one 5 days ago.
        // MAX(approvedAt) = 5 days ago → same-medicine alert should fire (5 <= 30).
        // If we incorrectly picked the 40-day one → no alert (incorrect).
        seedGivenPrescription(patUid, medUid, "7", daysAgo(40));
        seedGivenPrescription(patUid, medUid, "7", daysAgo(5));

        String consultUid = seedConsultation(tag + "B", patUid);
        MvcResult result = mockMvc.perform(
                        post(CONSULT_BASE + consultUid + "/prescriptions")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prescribeBody(medUid, "7", "1.0")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> alerts = alertsFrom(dto);
        assertThat(alerts)
                .as("MAX(approvedAt) must be used: newest prior GIVEN is 5 days ago → alert fires")
                .anyMatch(a -> a.contains(ALERT_HAS_DRUGS));
    }

    // =========================================================================
    // Helpers — seeds
    // =========================================================================

    private static String uniq() {
        return "C11" + Long.toHexString(System.nanoTime()).substring(0, 8).toUpperCase();
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    private static Instant daysAgo(long days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    /** Create a Medicine via the masterdata REST API. Returns its uid. */
    private String createMedicine(String tag) throws Exception {
        String body = """
                {"code":"MED-%s","name":"Medicine %s","description":null,
                 "type":"ORAL","price":500.00,"uom":"TAB","category":"MEDICINE","active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(MEDICINES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    /** Seed a default CASH service price for the medicine. */
    private void seedPrice(String medicineUid) throws Exception {
        String body = """
                {"planUid":null,"kind":"MEDICINE","serviceUid":"%s","currency":"TZS",
                 "amount":1000.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(medicineUid);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());
    }

    /** Seed a consultation bound to the given patientUid. */
    private String seedConsultation(String tag, String patientUid) {
        Consultation c = new Consultation(
                patientUid,
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("BIL", tag),
                PaymentMode.CASH,
                false,
                false,
                "",
                null,
                dayUid);
        c.open();
        return consultationRepository.saveAndFlush(c).getUid();
    }

    /**
     * Seed a GIVEN prescription with a controlled {@code approvedAt} directly via the
     * repository. This bypasses the HTTP issue endpoint (which stamps approvedAt=Instant.now())
     * so we can place the dispense in the past for alert-window testing.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Build a NOT-GIVEN prescription via {@code Prescription.forConsultation} on a
     *       fresh consultation owned by the same patient.</li>
     *   <li>Call {@code issue()} to transition to GIVEN (stamps approvedAt=now).</li>
     *   <li>Save via {@code saveAndFlush}.</li>
     *   <li>Use a Spring Data custom save with an explicit approvedAt override via a separate
     *       JPQL update query. Because the entity is immutable after issue(), we use a
     *       native SQL UPDATE to set approved_at to the desired past instant.</li>
     * </ol>
     *
     * <p>The seeded prescription is in a throwaway consultation (separate from the one used
     * for the new prescription under test) so the duplicate-drug guard does not fire.
     *
     * @param patientUid  patient uid (must match the consultation used in the main test)
     * @param medicineUid medicine uid (same as new prescription under test)
     * @param days        the {@code days} free-text field value
     * @param approvedAt  the target dispense timestamp (used for alert-window computation)
     */
    private void seedGivenPrescription(String patientUid, String medicineUid,
                                        String days, Instant approvedAt) {
        // Create a throwaway consultation for the seeded prior prescription.
        // A distinct tag ensures no collision with the test's main consultation.
        String seedTag = "S" + Long.toHexString(System.nanoTime()).substring(0, 7).toUpperCase();
        Consultation seedConsult = new Consultation(
                patientUid,
                null,
                fakeUid("CLN", seedTag),
                fakeUid("DOC", seedTag),
                fakeUid("BIL", seedTag),
                PaymentMode.CASH,
                false,
                false,
                "",
                null,
                dayUid);
        seedConsult.open();
        Consultation savedConsult = consultationRepository.saveAndFlush(seedConsult);

        // Build a NOT-GIVEN prescription. We use a minimal billUid (not a real bill — the
        // seeded prescription is never charged; the patientBillUid constraint is NOT NULL but
        // we put a fake uid since this is read-only test data for alert purposes).
        BigDecimal qty = new BigDecimal("1.000000");
        Prescription rx = Prescription.forConsultation(
                savedConsult,
                medicineUid,
                fakeUid("BIL", seedTag),   // fake patientBillUid (alert read-path only)
                true,                       // settled = true (INSURANCE-like; no gate check)
                qty,
                "1 tab",                    // dosage
                "OD",                       // frequency
                "ORAL",                     // route
                days,                       // the days field under test
                null,                       // reference
                null,                       // instructions
                "CASH",                     // paymentType
                "",                         // membershipNo
                null,                       // insurancePlanUid
                null,                       // clinicianUserUid
                "test-seeder",              // actorUserUid
                dayUid,
                Instant.now());

        // Transition to GIVEN via the domain method (stamps approvedAt = Instant.now())
        rx.issue(qty, null, "test-seeder", dayUid, Instant.now());

        // Save — at this point approvedAt = Instant.now() (not the desired past instant)
        Prescription saved = prescriptionRepository.saveAndFlush(rx);

        // Override approved_at to the desired past instant via a JPQL @Modifying update.
        // Keyed on the public uid (the internal id has no getter — ADR-0014 §1).
        // A @Modifying query needs an active transaction; this IT is not @Transactional,
        // so wrap the update in an explicit programmatic transaction.
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(s ->
                        prescriptionRepository.overrideApprovedAt(saved.getUid(), approvedAt));
    }

    // =========================================================================
    // Request body builders
    // =========================================================================

    private static String prescribeBody(String medicineUid, String days, String qty) {
        return """
                {"medicineUid":"%s","qty":%s,"dosage":"1 tablet","frequency":"OD",
                 "route":"ORAL","days":"%s"}
                """.formatted(medicineUid, qty, days);
    }

    // =========================================================================
    // Response helpers
    // =========================================================================

    private List<String> alertsFrom(JsonNode dto) {
        JsonNode alertsNode = dto.get("alerts");
        if (alertsNode == null || alertsNode.isNull() || !alertsNode.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        alertsNode.forEach(n -> result.add(n.asText()));
        return result;
    }
}
