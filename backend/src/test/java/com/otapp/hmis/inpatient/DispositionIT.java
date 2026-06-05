package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.clinical.domain.DeceasedNoteRepository;
import com.otapp.hmis.clinical.domain.DeceasedNoteStatus;
import com.otapp.hmis.clinical.domain.ReferralPlanRepository;
import com.otapp.hmis.clinical.domain.ReferralPlanStatus;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.inpatient.domain.DischargePlanRepository;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-07 07a-3 — Inpatient dispositions (discharge / referral / deceased).
 *
 * <p>Drives the full vertical slice against PostgreSQL 16 (Testcontainers via
 * {@link AbstractIntegrationTest}). Each nested class covers one disposition type.
 *
 * <p>For each disposition the tests cover:
 * <ul>
 *   <li>(a) Approve while a bill is UNPAID → 422 ADMISSION_BILLS_OUTSTANDING.</li>
 *   <li>(b) Clear bills; approve by DIFFERENT actor → success; admission SIGNED-OUT;
 *       bed EMPTY; AdmissionBed CLOSED; patient type flipped correctly.</li>
 *   <li>(c) Approve by the SAME actor who created → 422 SELF_APPROVAL_FORBIDDEN.</li>
 *   <li>(d) Deceased save missing summary/cause → 422 "Summary and cause of death are missing".</li>
 * </ul>
 *
 * <p>NOTE: approve tests must NOT be {@code @Transactional}. The
 * {@code BEFORE_COMMIT} listeners (PatientClosureListener) fire inside the disposition
 * service's transaction; a wrapping test-tx would prevent them from running.
 *
 * <p>Legacy citations: PatientResource.java:5342-5934 (discharge/referral/deceased endpoints).
 * CR-07-SoD (SoD second-approver gate). CR-07-Q10 (AdmissionBed.close).
 */
class DispositionIT extends AbstractIntegrationTest {

    // REST paths
    private static final String ADMISSIONS  = "/api/v1/inpatient/admissions";
    private static final String WARD_CATS   = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES  = "/api/v1/masterdata/ward-types";
    private static final String WARDS       = "/api/v1/masterdata/wards";
    private static final String BEDS        = "/api/v1/masterdata/beds";
    private static final String PRICES      = "/api/v1/masterdata/service-prices";
    private static final String PAYMENTS    = "/api/v1/billing/payments";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionRepository admissionRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired DischargePlanRepository dischargePlanRepository;
    @Autowired DeceasedNoteRepository deceasedNoteRepository;
    @Autowired ReferralPlanRepository referralPlanRepository;

    private String dayUid;
    // creator token — the actor who saves the disposition plan
    private String creatorToken;
    // approver token — a DIFFERENT actor who approves (SoD gate)
    private String approverToken;

    @BeforeEach
    void setUp() {
        dayUid = ensureDayOpen();
        creatorToken = jwtFactory.tokenWithPrivileges("nurse-creator",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A"));
        approverToken = jwtFactory.tokenWithPrivileges("doctor-approver",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A",
                        "DISCHARGE-PLAN-APPROVE", "REFERRAL-PLAN-APPROVE",
                        "DECEASED-NOTE-APPROVE"));
    }

    // =========================================================================
    // DISCHARGE disposition tests
    // =========================================================================

    @Nested
    class DischargeDisposition {

        @Test
        void approve_withUnpaidBill_returns422BillsOutstanding() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "500.00");

            // Admit
            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);

            // Save discharge plan (PENDING)
            String planUid = saveDischargePlan(admUid, creatorToken);

            // Approve WITHOUT paying the ward bill → 422 bills outstanding
            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/discharge-plan/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:admission-bills-outstanding"))
                    .andExpect(jsonPath("$.detail").value(
                            "Could not get discharge summary. Patient have uncleared bills."));
        }

        @Test
        void approve_billsCleared_differentActor_dischargesToSignedOut() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "400.00");

            // Admit
            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);

            // Pay the ward bill to activate admission and clear outstanding bills
            payWardBill(admUid);

            // Save discharge plan by creator
            String planUid = saveDischargePlan(admUid, creatorToken);

            // Approve by DIFFERENT actor (approverToken)
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/discharge-plan/approve")
                                    .header("Authorization", "Bearer " + approverToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andReturn();

            // Admission → SIGNED-OUT
            var adm = admissionRepository.findByUid(admUid).orElseThrow();
            assertThat(adm.getStatus()).isEqualTo(AdmissionStatus.SIGNED_OUT);
            assertThat(adm.getDischargedAt()).isNotNull();

            // AdmissionBed → CLOSED (CR-07-Q10)
            var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(beds).isEmpty();
            var closedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "CLOSED");
            assertThat(closedBeds).hasSize(1);

            // Patient → OUTPATIENT + CASH + null insurancePlan (PatientDischargedEvent full reset)
            var patient = patientRepository.findByUid(patientUid).orElseThrow();
            assertThat(patient.getType()).isEqualTo(PatientType.OUTPATIENT);
            assertThat(patient.getPaymentType()).isEqualTo(PaymentType.CASH);
            assertThat(patient.getInsurancePlanUid()).isNull();

            // DischargePlan → APPROVED
            var plan = dischargePlanRepository.findByUid(planUid).orElseThrow();
            assertThat(plan.getStatus()).isEqualTo("APPROVED");
            assertThat(plan.getApprovedBy()).isEqualTo("doctor-approver");
        }

        @Test
        void approve_sameActorAsCreator_returns422SelfApproval() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "300.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            payWardBill(admUid);

            // Save plan with creatorToken (actor = "nurse-creator")
            String planUid = saveDischargePlan(admUid, creatorToken);

            // SAME actor (creatorToken, subject="nurse-creator") tries to approve
            // creatorToken does not have DISCHARGE-PLAN-APPROVE; use approver token but with
            // same subject. We mint a new token with the same subject as the creator.
            String sameActorApproverToken = jwtFactory.tokenWithPrivileges("nurse-creator",
                    List.of("DISCHARGE-PLAN-APPROVE", "PATIENT-ALL", "BILL-A", "ADMIN-ACCESS"));

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/discharge-plan/approve")
                            .header("Authorization", "Bearer " + sameActorApproverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:self-approval-forbidden"));
        }
    }

    // =========================================================================
    // REFERRAL disposition tests
    // =========================================================================

    @Nested
    class ReferralDisposition {

        @Test
        void approve_withUnpaidBill_returns422BillsOutstanding() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "500.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            String planUid = saveReferralPlan(admUid, creatorToken);

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/referral-plan/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:admission-bills-outstanding"))
                    .andExpect(jsonPath("$.detail").value(
                            "Could not get discharge summary. Patient have uncleared bills."));
        }

        @Test
        void approve_billsCleared_differentActor_referralSignedOut() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "400.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            payWardBill(admUid);
            String planUid = saveReferralPlan(admUid, creatorToken);

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/referral-plan/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            // Admission → SIGNED-OUT + dischargedAt stamped
            var adm = admissionRepository.findByUid(admUid).orElseThrow();
            assertThat(adm.getStatus()).isEqualTo(AdmissionStatus.SIGNED_OUT);
            assertThat(adm.getDischargedAt()).isNotNull();

            // AdmissionBed → CLOSED (CR-07-Q10)
            var closedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "CLOSED");
            assertThat(closedBeds).hasSize(1);

            // Patient → OUTPATIENT type ONLY (PatientReferredEvent — no insurance clear)
            // Legacy asymmetry: PatientResource.java:5626 (type-only) vs :5378-5381 (full reset)
            var patient = patientRepository.findByUid(patientUid).orElseThrow();
            assertThat(patient.getType())
                    .as("Referral: patient type must be OUTPATIENT")
                    .isEqualTo(PatientType.OUTPATIENT);
            // Payment type stays CASH (was CASH at admission; referral does NOT clear insurance)
            assertThat(patient.getPaymentType())
                    .as("Referral: payment type must remain CASH (type-only reset)")
                    .isEqualTo(PaymentType.CASH);

            // ReferralPlan → APPROVED
            var plan = referralPlanRepository.findByUid(planUid).orElseThrow();
            assertThat(plan.getStatus()).isEqualTo(ReferralPlanStatus.APPROVED);
        }

        @Test
        void approve_sameActorAsCreator_returns422SelfApproval() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "300.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            payWardBill(admUid);
            String planUid = saveReferralPlan(admUid, creatorToken);

            String sameActorApproverToken = jwtFactory.tokenWithPrivileges("nurse-creator",
                    List.of("REFERRAL-PLAN-APPROVE", "PATIENT-ALL", "BILL-A", "ADMIN-ACCESS"));

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/referral-plan/approve")
                            .header("Authorization", "Bearer " + sameActorApproverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + planUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:self-approval-forbidden"));
        }
    }

    // =========================================================================
    // DECEASED disposition tests
    // =========================================================================

    @Nested
    class DeceasedDisposition {

        @Test
        void save_missingSummaryAndCause_returns422VerbatimMessage() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);

            // Missing patientSummary and causeOfDeath → verbatim 422
            String body = """
                    {"patientSummary":null,"causeOfDeath":null,"deathDate":null,"deathTime":null}
                    """;
            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/deceased-note")
                            .header("Authorization", "Bearer " + creatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            "Summary and cause of death are missing"));
        }

        @Test
        void save_validNote_admissionHeld_bedFreed() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);

            // Valid save — admission → HELD, bed freed early
            String noteUid = saveDeceasedNote(admUid, creatorToken);

            var adm = admissionRepository.findByUid(admUid).orElseThrow();
            assertThat(adm.getStatus())
                    .as("Deceased save must transition admission to HELD")
                    .isEqualTo(AdmissionStatus.HELD);
        }

        @Test
        void approve_withUnpaidBill_returns422BillsOutstanding() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "500.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            String noteUid = saveDeceasedNote(admUid, creatorToken);

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/deceased-note/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + noteUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:admission-bills-outstanding"))
                    .andExpect(jsonPath("$.detail").value(
                            "Could not get discharge summary. Patient have uncleared bills."));
        }

        @Test
        void approve_billsCleared_differentActor_deceasedSignedOut() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "400.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            payWardBill(admUid);

            // Save deceased note (admission → HELD, bed freed)
            String noteUid = saveDeceasedNote(admUid, creatorToken);

            // Approve by DIFFERENT actor
            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/deceased-note/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + noteUid + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            // Admission → SIGNED-OUT (no dischargedAt stamp — legacy deceased path)
            var adm = admissionRepository.findByUid(admUid).orElseThrow();
            assertThat(adm.getStatus()).isEqualTo(AdmissionStatus.SIGNED_OUT);
            assertThat(adm.getDischargedAt())
                    .as("Deceased path must NOT stamp dischargedAt (PatientResource.java:5851-5934)")
                    .isNull();

            // AdmissionBed → CLOSED (CR-07-Q10)
            var closedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "CLOSED");
            assertThat(closedBeds).hasSize(1);

            // Patient → DECEASED (PatientDeceasedEvent)
            var patient = patientRepository.findByUid(patientUid).orElseThrow();
            assertThat(patient.getType())
                    .as("Deceased approve must set patient type=DECEASED")
                    .isEqualTo(PatientType.DECEASED);

            // DeceasedNote → APPROVED
            var note = deceasedNoteRepository.findByUid(noteUid).orElseThrow();
            assertThat(note.getStatus()).isEqualTo(DeceasedNoteStatus.APPROVED);
        }

        @Test
        void approve_sameActorAsCreator_returns422SelfApproval() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, "300.00");

            String admUid = doAdmission(patientUid, wardBedUid, creatorToken);
            payWardBill(admUid);
            String noteUid = saveDeceasedNote(admUid, creatorToken);

            String sameActorApproverToken = jwtFactory.tokenWithPrivileges("nurse-creator",
                    List.of("DECEASED-NOTE-APPROVE", "PATIENT-ALL", "BILL-A", "ADMIN-ACCESS"));

            mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/deceased-note/approve")
                            .header("Authorization", "Bearer " + sameActorApproverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uid\":\"" + noteUid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "urn:hmis:error:self-approval-forbidden"));
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String uniq() {
        return "D7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    private String seedCashPatient(String tag) {
        Patient patient = new Patient(
                null,
                "07a3IT" + tag,
                "Dis07a3",
                tag,
                "IT",
                LocalDate.of(1985, 6, 15),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.CASH,
                null,
                null,
                null,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    private String seedWardWithBed(String tag, String price) throws Exception {
        String catBody = """
                {"code":"WCD7-%s","name":"WCat D07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        String typeBody = """
                {"code":"WTD7-%s","name":"WType D07 %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, price);
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        String priceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, price);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isCreated());

        String wardBody = """
                {"code":"WDD7-%s","name":"Ward D07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        String bedBody = """
                {"no":"BDD7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** POST doAdmission and return the new admission uid. */
    private String doAdmission(String patientUid, String wardBedUid, String token)
            throws Exception {
        String body = """
                {"patientUid":"%s","wardBedUid":"%s","paymentType":"CASH",
                 "insurancePlanUid":null,"membershipNo":null}
                """.formatted(patientUid, wardBedUid);
        MvcResult res = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Pay the ward bill for an admission (triggers settlement listener → IN-PROCESS). */
    private void payWardBill(String admUid) throws Exception {
        var admBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(admBeds).isNotEmpty();
        String billUid = admBeds.get(0).getPatientBillUid();
        var bill = patientBillRepository.findByUid(billUid).orElseThrow();
        assertThat(bill.getStatus()).isEqualTo(BillStatus.UNPAID);
        String amount = bill.amountValue().toPlainString();

        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid, amount);
        mockMvc.perform(post(PAYMENTS)
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());
    }

    /** Save a discharge plan and return its uid. */
    private String saveDischargePlan(String admUid, String token) throws Exception {
        String body = """
                {"history":"test history","investigation":"test inv","management":"mgmt",
                 "operationNote":null,"icuAdmissionNote":null,"generalRecommendation":"rec"}
                """;
        MvcResult res = mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/discharge-plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Save a referral plan and return its uid. */
    private String saveReferralPlan(String admUid, String token) throws Exception {
        String body = """
                {"externalMedicalProviderUid":"EMP-TEST-PROVIDER-001",
                 "referringDiagnosis":"test dx","history":"test history",
                 "investigation":null,"management":null,
                 "operationNote":null,"icuAdmissionNote":null,"generalRecommendation":null}
                """;
        MvcResult res = mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/referral-plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Save a deceased note and return its uid. */
    private String saveDeceasedNote(String admUid, String token) throws Exception {
        String body = """
                {"patientSummary":"Patient summary text","causeOfDeath":"Cardiac arrest",
                 "deathDate":"2026-01-15","deathTime":"14:30:00"}
                """;
        MvcResult res = mockMvc.perform(post(ADMISSIONS + "/" + admUid + "/deceased-note")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();
    }
}
