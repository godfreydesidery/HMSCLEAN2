package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.masterdata.domain.Dressing;
import com.otapp.hmis.masterdata.domain.DressingRepository;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Integration tests for inc-07 07b — Inpatient nursing charts (five chart types).
 *
 * <p>Drives the full vertical slice against PostgreSQL 16 (Testcontainers via
 * {@link AbstractIntegrationTest}). Each scenario exercises a specific guard or happy-path.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>1. Nursing chart save under IN-PROCESS admission → 201; GET list returns it.</li>
 *   <li>2. Nursing chart save under PENDING admission → 422 "Admission not verified".</li>
 *   <li>3. Care plan + progress note save under IN-PROCESS → 201 each.</li>
 *   <li>4a. Dressing chart: procedureType NOT in dressings → 422 "not listed as dressing".</li>
 *   <li>4b. Dressing chart: procedureType registered + PROCEDURE price → 201 + PROCEDURE bill.</li>
 *   <li>5a. Dosing note: prescription NOT GIVEN → 422 "Prescription not picked from pharmacy".</li>
 *   <li>5b. Dosing note: GIVEN prescription → 201; GET list returns it.</li>
 *   <li>6. Delete within 24h → 204 no-content (freshly created chart is within window).</li>
 * </ul>
 *
 * <p>Seed pattern: reuses the ward+bed+patient seeding from {@link DispositionIT};
 * reuses the payWardBill helper to activate admission to IN-PROCESS.
 *
 * <p>NOTE: all chart tests that write MUST NOT be {@code @Transactional} — the
 * AdmissionSettlementListener fires at BEFORE_COMMIT inside the billing payment transaction;
 * a wrapping test-tx would prevent activation.
 *
 * <p>NOTE on the dosing-note bug: {@code PrescriptionChartPortImpl.record()} passes {@code null}
 * for {@code patientUid} when calling {@code PatientPrescriptionChart.createForAdmission}.
 * This violates the V36 NOT NULL constraint on {@code patient_prescription_charts.patient_uid}.
 * The IT reveals this bug; the fix is to pass {@code prescription.getPatientUid()} instead.
 *
 * <p>Legacy citations: PatientServiceImpl.java:2540-2698; PatientResource.java:3135-3138.
 * inc-07 07b.
 */
class NursingChartIT extends AbstractIntegrationTest {

    // REST path constants
    private static final String ADMISSIONS  = "/api/v1/inpatient/admissions";
    private static final String WARD_CATS   = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES  = "/api/v1/masterdata/ward-types";
    private static final String WARDS       = "/api/v1/masterdata/wards";
    private static final String BEDS        = "/api/v1/masterdata/beds";
    private static final String PRICES      = "/api/v1/masterdata/service-prices";
    private static final String PAYMENTS    = "/api/v1/billing/payments";
    private static final String PROC_TYPES  = "/api/v1/masterdata/procedure-types";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired DressingRepository dressingRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired PrescriptionRepository prescriptionRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse-07b",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // 1. Nursing chart save under IN-PROCESS admission → 201; GET list returns it
    // =========================================================================

    @Nested
    class NursingChartSave {

        @Test
        void save_underInProcessAdmission_returns201_and_listedBack() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            String body = """
                    {"nurseUid":"NURSE-UID-001","feeding":"normal","changingPosition":"done",
                     "bedBathing":"sponge","randomBloodSugar":"5.6","fullBloodSugar":"6.1",
                     "drainageOutput":"30ml","fluidIntake":"200ml","urineOutput":"150ml"}
                    """;
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/nursing-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.admissionUid").value(admUid))
                    .andExpect(jsonPath("$.nurseUid").value("NURSE-UID-001"))
                    .andReturn();

            String chartUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();
            assertThat(chartUid).isNotBlank();

            // GET list must include the newly saved chart
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/nursing-charts")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uid").value(chartUid))
                    .andExpect(jsonPath("$[0].admissionUid").value(admUid))
                    .andExpect(jsonPath("$[0].feeding").value("normal"));
        }

        // =========================================================================
        // 2. Nursing chart save under PENDING admission → 422 "Admission not verified"
        // =========================================================================

        @Test
        void save_underPendingAdmission_returns422_admissionNotVerified() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            // Admit but do NOT pay the ward bill — stays PENDING
            String admUid = doAdmission(patientUid, bedUid, token);

            String body = """
                    {"nurseUid":"NURSE-UID-001","feeding":"normal","changingPosition":null,
                     "bedBathing":null,"randomBloodSugar":null,"fullBloodSugar":null,
                     "drainageOutput":null,"fluidIntake":null,"urineOutput":null}
                    """;
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/nursing-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            "Could not be done. Admission not verified"));
        }
    }

    // =========================================================================
    // 3. Care plan + progress note save under IN-PROCESS → 201 each
    // =========================================================================

    @Nested
    class CarePlanAndProgressNote {

        @Test
        void saveCareplan_underInProcess_returns201() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            String body = """
                    {"nurseUid":"NURSE-UID-002","nursingDiagnosis":"pain management",
                     "expectedOutcome":"pain controlled","implementation":"analgesics given",
                     "evaluation":"effective"}
                    """;
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/nursing-care-plans")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.admissionUid").value(admUid))
                    .andExpect(jsonPath("$.nurseUid").value("NURSE-UID-002"))
                    .andExpect(jsonPath("$.nursingDiagnosis").value("pain management"));
        }

        @Test
        void saveProgressNote_underInProcess_returns201() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            String body = """
                    {"nurseUid":"NURSE-UID-003","note":"Patient resting comfortably, vitals stable"}
                    """;
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/progress-notes")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.admissionUid").value(admUid))
                    .andExpect(jsonPath("$.nurseUid").value("NURSE-UID-003"))
                    .andExpect(jsonPath("$.note").value(
                            "Patient resting comfortably, vitals stable"));
        }
    }

    // =========================================================================
    // 4. Dressing chart
    // =========================================================================

    @Nested
    class DressingChart {

        @Test
        void save_procedureTypeNotInDressings_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            // Seed a procedure type but do NOT register it as a dressing
            String procTypeUid = seedProcedureType(tag, "2000.00");

            String body = dressingBody(procTypeUid, "Wound Care " + tag, "1", "CASH");
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/dressing-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            "Procedure type is not listed as dressing"));
        }

        @Test
        void save_procedureTypeRegisteredAsDressing_returns201_andCreatesProcedureBill()
                throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            // Seed a procedure type, register it as a dressing, seed a PROCEDURE price
            String procTypeUid = seedProcedureType(tag, "3000.00");
            seedDressingMasterdata(procTypeUid);
            seedProcedurePrice(procTypeUid, "3000.00");

            String body = dressingBody(procTypeUid, "Wound Dressing " + tag, "1", "CASH");
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/dressing-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.admissionUid").value(admUid))
                    .andExpect(jsonPath("$.procedureTypeUid").value(procTypeUid))
                    .andReturn();

            // Verify a PROCEDURE PatientBill is created with the right amount and status.
            // Inpatient CASH → VERIFIED (billing engine inpatient-no-covered fallback).
            String patientBillUid = objectMapper.readTree(
                    res.getResponse().getContentAsString()).get("patientBillUid").asText();
            assertThat(patientBillUid).isNotBlank();

            var bill = patientBillRepository.findByUid(patientBillUid).orElseThrow();
            assertThat(bill.getKind().name()).isEqualTo("PROCEDURE");
            // Inpatient CASH (admissionUid non-null, no insurance) → VERIFIED status
            // (billing engine: PatientServiceImpl.java:917 inpatient fallback)
            assertThat(bill.getStatus())
                    .as("Inpatient CASH dressing bill must be VERIFIED (no insurance coverage)")
                    .isEqualTo(BillStatus.VERIFIED);
            assertThat(bill.amountValue()).isEqualByComparingTo(new BigDecimal("3000.00"));
        }
    }

    // =========================================================================
    // 5. Dosing note
    // =========================================================================

    @Nested
    class DosingNote {

        @Test
        void save_prescriptionNotGiven_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            // Seed a NOT-GIVEN prescription (the default status after forConsultation)
            String prescriptionUid = seedNotGivenPrescription(tag, patientUid);

            String body = dosingBody(prescriptionUid, "NURSE-UID-004");
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/dosing-notes")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            "Prescription not picked from pharmacy"));
        }

        @Test
        void save_givenPrescription_returns201_and_listedBack() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            // Seed a GIVEN prescription
            String prescriptionUid = seedGivenPrescription(tag, patientUid);

            String body = dosingBody(prescriptionUid, "NURSE-UID-005");
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/dosing-notes")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.prescriptionUid").value(prescriptionUid))
                    .andExpect(jsonPath("$.nurseUid").value("NURSE-UID-005"))
                    .andReturn();

            String chartUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();
            assertThat(chartUid).isNotBlank();

            // GET list must return the newly saved dosing note
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/dosing-notes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uid").value(chartUid));
        }
    }

    // =========================================================================
    // 6. Delete within 24h → 204 no-content
    // =========================================================================

    @Nested
    class DeleteWithin24h {

        @Test
        void delete_freshlySavedNursingChart_returns204() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid, token);
            payWardBill(admUid);

            // Save a nursing chart
            String body = """
                    {"nurseUid":"NURSE-UID-DEL","feeding":"normal","changingPosition":null,
                     "bedBathing":null,"randomBloodSugar":null,"fullBloodSugar":null,
                     "drainageOutput":null,"fluidIntake":null,"urineOutput":null}
                    """;
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/nursing-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();
            String chartUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();

            // Delete within 24h (freshly created = 0h elapsed < 24h limit) → 204
            mockMvc.perform(
                            delete(ADMISSIONS + "/" + admUid + "/nursing-charts/" + chartUid)
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            // Verify: GET list is now empty
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/nursing-charts")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    // =========================================================================
    // Seeding helpers (mirrors DispositionIT pattern exactly)
    // =========================================================================

    private static String uniq() {
        return "N7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    /** Seed a CASH OUTPATIENT patient via the repository. */
    private String seedCashPatient(String tag) {
        Patient patient = new Patient(
                null,
                "07bIT" + tag,
                "Nursing07b",
                tag,
                "IT",
                LocalDate.of(1988, 3, 20),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.CASH,
                null,
                null,
                null,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /**
     * Seed a ward category + ward type (with price) + ward + ward bed via REST.
     * Returns the ward bed uid.
     */
    private String seedWardWithBed(String tag, String price) throws Exception {
        String catBody = """
                {"code":"WCN7-%s","name":"WCat N07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        String typeBody = """
                {"code":"WTN7-%s","name":"WType N07 %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, price);
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        String priceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, price);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isCreated());

        String wardBody = """
                {"code":"WDN7-%s","name":"Ward N07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        String bedBody = """
                {"no":"BDN7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** POST to /admissions and return the new admission uid. */
    private String doAdmission(String patientUid, String wardBedUid, String tok)
            throws Exception {
        String body = """
                {"patientUid":"%s","wardBedUid":"%s","paymentType":"CASH",
                 "insurancePlanUid":null,"membershipNo":null}
                """.formatted(patientUid, wardBedUid);
        MvcResult res = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Pay the ward bill for an admission (triggers AdmissionSettlementListener →
     * IN-PROCESS). Mirrors the DispositionIT.payWardBill helper exactly.
     */
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
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());
    }

    /**
     * Seed a ProcedureType via the REST API. Returns its uid.
     *
     * @param tag   unique test suffix
     * @param price unit price as string
     */
    private String seedProcedureType(String tag, String price) throws Exception {
        String body = """
                {"code":"PROC-N7-%s","name":"Proc N07 %s","description":null,
                 "price":%s,"uom":"UNIT","active":true}
                """.formatted(tag, tag, price);
        MvcResult r = mockMvc.perform(post(PROC_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Register a ProcedureType as a dressing via the repository (no REST endpoint for dressings).
     * Noted: the dressings masterdata has no REST controller; direct repository seeding is
     * the only test option.
     */
    private void seedDressingMasterdata(String procedureTypeUid) {
        dressingRepository.saveAndFlush(Dressing.forProcedureType(procedureTypeUid));
    }

    /**
     * Seed a PROCEDURE service_price row for a procedure type via the REST API.
     */
    private void seedProcedurePrice(String procedureTypeUid, String price) throws Exception {
        String body = """
                {"planUid":null,"kind":"PROCEDURE","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(procedureTypeUid, price);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Build a dressing chart POST body.
     */
    private static String dressingBody(String procedureTypeUid, String procedureTypeName,
                                       String qty, String paymentType) {
        return """
                {"nurseUid":"NURSE-UID-DRS","clinicianUid":null,
                 "insurancePlanUid":null,"membershipNo":null,
                 "procedureTypeUid":"%s","procedureTypeName":"%s",
                 "qty":%s,"paymentType":"%s"}
                """.formatted(procedureTypeUid, procedureTypeName, qty, paymentType);
    }

    /**
     * Build a dosing note POST body.
     */
    private static String dosingBody(String prescriptionUid, String nurseUid) {
        return """
                {"prescriptionUid":"%s","nurseUid":"%s",
                 "dosage":"2 tabs","output":"no reaction","remark":"given with water"}
                """.formatted(prescriptionUid, nurseUid);
    }

    /**
     * Seed a NOT-GIVEN prescription bound to a throwaway consultation.
     *
     * <p>The consultation is intra-module (clinical module owns both), seeded directly via
     * repository. A fake patientBillUid is used — this prescription is never actually billed;
     * the guard test only cares that status = NOT_GIVEN.
     *
     * @param tag       unique test suffix
     * @param patientUid the patient uid to bind the prescription to
     * @return the prescription uid
     */
    private String seedNotGivenPrescription(String tag, String patientUid) {
        Consultation seedConsult = buildThrowawayConsultation(tag, patientUid);
        Consultation savedConsult = consultationRepository.saveAndFlush(seedConsult);

        BigDecimal qty = new BigDecimal("1.000000");
        Prescription rx = Prescription.forConsultation(
                savedConsult,
                fakeUid("MED", tag),   // fake medicineUid
                fakeUid("BIL", tag),   // fake patientBillUid
                false,                  // settled
                qty,
                "1 tab", "OD", "ORAL", "7",
                null, null,
                "CASH", "", null, null,
                "test-seeder", dayUid, Instant.now());
        // NOT calling issue() — stays NOT_GIVEN
        return prescriptionRepository.saveAndFlush(rx).getUid();
    }

    /**
     * Seed a GIVEN prescription bound to a throwaway consultation.
     *
     * <p>Mirrors the PrescribingAlertIT.seedGivenPrescription pattern exactly:
     * build NOT-GIVEN via forConsultation, call issue() to flip to GIVEN, save.
     * The prescription's patientUid is set from the consultation context.
     *
     * @param tag        unique test suffix
     * @param patientUid the patient uid (must match the consultation under test)
     * @return the prescription uid
     */
    private String seedGivenPrescription(String tag, String patientUid) {
        Consultation seedConsult = buildThrowawayConsultation(tag + "G", patientUid);
        Consultation savedConsult = consultationRepository.saveAndFlush(seedConsult);

        BigDecimal qty = new BigDecimal("1.000000");
        Prescription rx = Prescription.forConsultation(
                savedConsult,
                fakeUid("MED", tag),
                fakeUid("BIL", tag),
                false,
                qty,
                "1 tab", "OD", "ORAL", "7",
                null, null,
                "CASH", "", null, null,
                "test-seeder", dayUid, Instant.now());
        // Transition to GIVEN
        rx.issue(qty, fakeUid("PHM", tag), "test-seeder", dayUid, Instant.now());
        return prescriptionRepository.saveAndFlush(rx).getUid();
    }

    /** Build a throwaway consultation bound to the given patientUid. */
    private Consultation buildThrowawayConsultation(String tag, String patientUid) {
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
        return c;
    }

    /** Produce a deterministic 26-char uid from a prefix + tag. */
    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }
}
