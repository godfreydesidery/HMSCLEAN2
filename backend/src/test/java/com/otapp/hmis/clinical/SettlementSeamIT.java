package com.otapp.hmis.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.clinical.domain.LabTest;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for the inc-05 cash-PAID settlement seam (ADR-0022 D5, CR-05, inc-05 §5).
 *
 * <p>Proves the end-to-end seam:
 * <ol>
 *   <li>A CASH consultation (settled=false) blocks the {@code open} transition with 422
 *       PAY_BEFORE_SERVICE BEFORE payment.</li>
 *   <li>After the consultation bill is paid via the cashier payment endpoint
 *       (POST /api/v1/billing/payments), the {@code BillSettledEvent} is published inside the
 *       billing transaction's BEFORE_COMMIT phase, the clinical
 *       {@link com.otapp.hmis.clinical.application.ConsultationSettlementListener} flips
 *       {@code Consultation.settled = true} in that same transaction, and the payment
 *       transaction commits with both the bill PAID and the consultation settled atomically.</li>
 *   <li>After payment, the consultation's local {@code settled} flag is {@code true} and the
 *       {@code open} transition succeeds (PENDING → IN_PROCESS).</li>
 *   <li>Similarly for a CASH lab test: ordered with settled=false, paid via billing, the
 *       lab test's local {@code settled} flag becomes {@code true} after payment.</li>
 * </ol>
 *
 * <p><strong>Why NOT @Transactional on these test methods:</strong>
 * The settlement seam uses {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}.
 * Spring's {@code BEFORE_COMMIT} phase fires as part of the commit sequence — it does NOT fire
 * if the outer transaction is rolled back. If these tests were annotated {@code @Transactional},
 * Spring TestContext Framework would wrap everything in a single transaction and roll it back
 * after each test rather than committing it. The BEFORE_COMMIT listener would never fire,
 * and the seam would appear broken. The correct approach is to let the service methods manage
 * their own transactions (the payment service's {@code @Transactional} starts and commits its
 * own transaction when called from a non-@Transactional test method). The settlement flag is
 * then visible in the committed database state.
 *
 * <p><strong>Data isolation:</strong>
 * Each test uses a unique suffix (from {@link System#nanoTime()}) so entities from different
 * test methods do not interfere. Entities are committed (not rolled back) and remain in the
 * shared Testcontainers database; this is acceptable in the Testcontainers singleton-container
 * pattern where tests are designed to be additive and non-destructive.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>CR-05 settlement: PatientBillResource.java:305-307</li>
 *   <li>PAY_BEFORE_SERVICE gate: PatientResource.java:886</li>
 *   <li>Settlement seam design: ADR-0022 D5; SettlementDispatcher.java (inc-05 seam)</li>
 * </ul>
 */
class SettlementSeamIT extends AbstractIntegrationTest {

    private static final String CLINICAL_URL = "/api/v1/clinical/consultations";
    private static final String PAYMENTS_URL = "/api/v1/billing/payments";

    @Autowired MockMvc                mockMvc;
    @Autowired TestJwtFactory         jwtFactory;
    @Autowired PatientBillRepository  billRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired LabTestRepository      labTestRepository;
    @Autowired BusinessDayService     businessDayService;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
        // NOTE: this IT seeds PatientBill + clinical rows DIRECTLY via repositories (it isolates
        // the event-publish/listener seam from the pricing engine), then pays the seeded bill via
        // the cashier endpoint. It therefore needs NO masterdata service price — neither a
        // REGISTRATION nor a CONSULTATION price is required (and a CONSULTATION price with a null
        // serviceUid is invalid anyway). No price seeding here.
    }

    // =========================================================================
    // Seam test (a): CASH consultation — pay bill → settled=true → open succeeds
    //
    // Steps: seed CASH consultation (settled=false) → assert open blocked (422) →
    // pay bill via cashier endpoint → listener flips consultation settled in same tx →
    // assert settled=true → assert open now succeeds (PENDING → IN_PROCESS).
    // =========================================================================

    @Test
    void cashConsultationBillPaid_flipsConsultationSettledAndAllowsOpen() throws Exception {
        String tag = uniq();

        // Step 1: Seed a real UNPAID PatientBill and a CASH PENDING consultation referencing it.
        // The consultation must reference the REAL bill uid so the listener can match it.
        PatientBill bill = makeCashBill(tag, "2000.00");
        String billUid = bill.getUid();
        String consultationUid = seedCashConsultationWithBill(tag, billUid);

        // Verify initial state: consultation is PENDING and NOT settled
        Consultation beforePayment = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(beforePayment.isSettled()).as("CASH consultation starts unsettled").isFalse();
        assertThat(beforePayment.getStatus()).isEqualTo(ConsultationStatus.PENDING);

        // Step 2: open() must be blocked — 422 PAY_BEFORE_SERVICE
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:pay-before-service"));

        // Step 3: Pay the bill. PaymentService commits its own tx; inside that tx BEFORE_COMMIT
        // fires: BillSettledEvent published, listener flips consultation.settled=true atomically.
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":2000.00,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid);
        mockMvc.perform(post(PAYMENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").exists());

        // Step 4: Reload from DB and assert consultation.settled=true
        Consultation afterPayment = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(afterPayment.isSettled())
                .as("Consultation.settled must be true after bill payment (CR-05 seam)")
                .isTrue();

        // Step 5: open() now succeeds — PENDING → IN_PROCESS
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN-PROCESS"));

        Consultation opened = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(opened.getStatus())
                .as("Consultation must be IN_PROCESS after open succeeds")
                .isEqualTo(ConsultationStatus.IN_PROCESS);
    }

    // =========================================================================
    // Seam test (b): CASH lab test — order (settled=false) → pay bill → settled=true
    //
    // A CASH lab test starts with settled=false and does not appear on the worklist.
    // After the lab bill is paid, the lab test's local settled flag becomes true.
    // =========================================================================

    @Test
    void cashLabTestBillPaid_flipsLabTestSettled() throws Exception {
        String tag = uniq();

        // Seed a CASH lab test with a real UNPAID bill and settled=false.
        // We use the consultation-bound path: seed an IN_PROCESS consultation first,
        // then seed a LabTest directly against it with settled=false and a real bill uid.
        String labBillUid = makeCashBill(tag + "LAB", "3500.00").getUid();
        String consultationUid = seedOpenedCashConsultation(tag);
        String labTestUid = seedCashLabTestWithBill(tag, consultationUid, labBillUid);

        // Verify initial: settled=false
        LabTest beforePayment = labTestRepository.findByUid(labTestUid).orElseThrow();
        assertThat(beforePayment.isSettled()).as("CASH lab test starts unsettled").isFalse();

        // Pay the lab bill via cashier endpoint
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":3500.00,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(labBillUid);
        mockMvc.perform(post(PAYMENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").exists());

        // After payment: LabTest.settled must be true
        LabTest afterPayment = labTestRepository.findByUid(labTestUid).orElseThrow();
        assertThat(afterPayment.isSettled())
                .as("LabTest.settled must be true after bill payment (CR-05 seam)")
                .isTrue();
    }

    // =========================================================================
    // Regression guard: CASH consultation WITHOUT payment stays blocked
    // (Ensures the existing ConsultationLifecycleIT behaviour is preserved —
    // paying in a different test does NOT affect this consultation.)
    // =========================================================================

    @Test
    void cashConsultation_withoutPayment_remainsBlocked() throws Exception {
        String tag = uniq();
        PatientBill bill = makeCashBill(tag + "NP", "1500.00");
        String consultationUid = seedCashConsultationWithBill(tag + "NP", bill.getUid());

        // Without payment, open must stay blocked
        mockMvc.perform(post(CLINICAL_URL + "/uid/" + consultationUid + "/open")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:pay-before-service"));

        // Consultation must still be PENDING and unsettled
        Consultation still = consultationRepository.findByUid(consultationUid).orElseThrow();
        assertThat(still.isSettled()).isFalse();
        assertThat(still.getStatus()).isEqualTo(ConsultationStatus.PENDING);
    }

    // =========================================================================
    // Regression guard: paying a bill that has NO clinical row (e.g. a plain
    // billing-only bill) must not throw — the listener silently no-ops.
    // =========================================================================

    @Test
    void payBillWithNoClinicalRow_succeeds_noException() throws Exception {
        String tag = uniq();
        // Bill with a uid that no clinical entity references
        PatientBill orphanBill = makeCashBill(tag + "ORPH", "500.00");

        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":500.00,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(orphanBill.getUid());
        // Must succeed — no clinical row matched, listener no-ops silently
        mockMvc.perform(post(PAYMENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String uniq() {
        // Produce a short unique suffix (hex nanotime, 10 chars) suitable for tag composition
        return "SS" + Long.toHexString(System.nanoTime()).substring(0, 10);
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

    /**
     * Create a minimal UNPAID CASH PatientBill directly via the repository.
     * This is the billing-module bill that the consultation will reference.
     * Uses {@code ServiceKind.CONSULTATION} to mimic the real charge path.
     *
     * <p>NOTE: This is a committed write (no @Transactional on the test method),
     * so the bill persists in the shared container DB.
     */
    private PatientBill makeCashBill(String tag, String amount) {
        PatientBill bill = new PatientBill(
                fakeUid("PAT", tag),
                com.otapp.hmis.masterdata.lookup.ServiceKind.CONSULTATION,
                "Consultation",
                "Consultation fee (seam test " + tag + ")",
                BigDecimal.ONE,
                Money.of(new BigDecimal(amount)),
                dayUid);
        return billRepository.save(bill);
    }

    /**
     * Seed a PENDING CASH consultation whose {@code patientBillUid} is the provided bill uid.
     * The consultation starts with {@code settled=false} (the CASH gate default).
     *
     * <p>Committed immediately (no @Transactional on test method).
     */
    private String seedCashConsultationWithBill(String tag, String billUid) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag),
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                billUid,                    // reference the REAL bill uid — seam relies on this
                com.otapp.hmis.billing.domain.PaymentMode.CASH,
                false,                      // not a follow-up
                false,                      // settled=false — CASH gate active
                "",
                null,
                dayUid);
        return consultationRepository.save(c).getUid();
    }

    /**
     * Seed an IN_PROCESS CASH consultation (for lab test ordering).
     * Uses a synthetic bill uid (not a real bill) because the consultation itself is not
     * under test — we only need it to exist so the LabTest can be bound to it.
     */
    private String seedOpenedCashConsultation(String tag) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag),
                null,
                fakeUid("CLN", tag),
                fakeUid("DOC", tag),
                fakeUid("CBILL", tag),      // synthetic bill uid (not under test)
                com.otapp.hmis.billing.domain.PaymentMode.CASH,
                false,
                false,
                "",
                null,
                dayUid);
        c.open();   // IN_PROCESS so lab orders can be placed
        return consultationRepository.save(c).getUid();
    }

    /**
     * Seed a PENDING CASH lab test whose {@code patientBillUid} is the provided bill uid.
     * The lab test starts with {@code settled=false}.
     *
     * <p>This bypasses the HTTP lab-order endpoint to avoid needing a real lab test type and
     * price — the seam test only needs a clinical entity with a real bill uid reference.
     * A dummy labTestTypeUid is used (no FK — loose ref per ADR-0008).
     */
    private String seedCashLabTestWithBill(String tag, String consultationUid, String labBillUid) {
        Consultation consultation = consultationRepository.findByUid(consultationUid).orElseThrow();
        LabTest lt = LabTest.forConsultation(
                consultation,
                fakeUid("LTT", tag),        // dummy lab test type uid (no FK — ADR-0008 loose ref)
                labBillUid,                  // reference the REAL lab bill uid — seam relies on this
                false,                       // settled=false — CASH gate active
                "CASH",
                "",
                null,
                null,
                fakeUid("DOC", tag),
                fakeUid("DOC", tag),
                dayUid,
                java.time.Instant.now());
        return labTestRepository.save(lt).getUid();
    }
}
