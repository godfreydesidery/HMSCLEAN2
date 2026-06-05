package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLog;
import com.otapp.hmis.shared.audit.AuditLogRepository;
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
 * Integration tests for inc-07 07d — closed-loop Medication Administration Record (MAR), CR-07-MAR.
 *
 * <p>NET-NEW feature (no legacy MAR) — these are net-new acceptance tests, NOT golden-master
 * parity. MAR is additive over the free-text dosing-note path (07b); both coexist. Drives the
 * full vertical slice against PostgreSQL 16 (Testcontainers via {@link AbstractIntegrationTest}).
 *
 * <p>Scenarios:
 * <ul>
 *   <li>HappyPath — IN-PROCESS admission + GIVEN prescription + ACTIVE route + MEDICATION-ADMINISTER
 *       privilege → 201; all fields persisted; GET lists it; a CREATE audit row is written.</li>
 *   <li>PrescriptionNotGiven — NOT-GIVEN prescription → 422 "Prescription not picked from pharmacy".</li>
 *   <li>RouteGuard — unknown/inactive route → 422 "Administration route is not listed or is inactive".</li>
 *   <li>AdmissionGate — PENDING admission → 422 "Could not be done. Admission not verified".</li>
 *   <li>PrivilegeGate — token WITHOUT MEDICATION-ADMINISTER → 403.</li>
 * </ul>
 *
 * <p>NOTE: write tests MUST NOT be {@code @Transactional} — the AdmissionSettlementListener fires
 * at BEFORE_COMMIT inside the billing payment transaction; a wrapping test-tx would block
 * activation (mirrors NursingChartIT).
 */
class MedicationAdministrationIT extends AbstractIntegrationTest {

    private static final String ADMISSIONS = "/api/v1/inpatient/admissions";
    private static final String WARD_CATS  = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES = "/api/v1/masterdata/ward-types";
    private static final String WARDS      = "/api/v1/masterdata/wards";
    private static final String BEDS       = "/api/v1/masterdata/beds";
    private static final String PRICES     = "/api/v1/masterdata/service-prices";
    private static final String PAYMENTS   = "/api/v1/billing/payments";
    private static final String ROUTES     = "/api/v1/masterdata/administration-routes";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired AuditLogRepository auditLogRepository;

    /** Token WITH the MAR privilege (also ADMIN-ACCESS for masterdata seeding + BILL-A for payment). */
    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse-07d",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A", "MEDICATION-ADMINISTER"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    class HappyPath {

        @Test
        void record_inProcess_given_activeRoute_withPrivilege_returns201_listed_andAudited()
                throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String routeUid = seedRoute(tag, "IV", true);
            String prescriptionUid = seedGivenPrescription(tag, patientUid);

            String body = marBody(prescriptionUid, "NURSE-MAR-1", routeUid,
                    "2026-06-05T10:15:00Z", "500 mg", "no adverse reaction");

            MvcResult res = mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.uid").exists())
                    .andExpect(jsonPath("$.prescriptionUid").value(prescriptionUid))
                    .andExpect(jsonPath("$.routeUid").value(routeUid))
                    .andExpect(jsonPath("$.nurseUid").value("NURSE-MAR-1"))
                    .andExpect(jsonPath("$.doseGiven").value("500 mg"))
                    .andExpect(jsonPath("$.patientResponse").value("no adverse reaction"))
                    .andExpect(jsonPath("$.administeredAt").value("2026-06-05T10:15:00Z"))
                    .andReturn();
            String marUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();

            // GET list returns the MAR entry
            mockMvc.perform(get(admMar(admUid)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uid").value(marUid));

            // A CREATE audit row was written for the MAR (PHI posture — write-path audit)
            List<AuditLog> audits = auditLogRepository
                    .findByEntityUidOrderByOccurredAtAsc(marUid);
            assertThat(audits)
                    .as("MAR create must write exactly one CREATE audit row")
                    .hasSize(1);
            assertThat(audits.get(0).getEntityType()).isEqualTo("clinical.MedicationAdministration");
            assertThat(audits.get(0).getAction()).isEqualTo(AuditAction.CREATE);
        }
    }

    // =========================================================================
    // Prescription not GIVEN → 422
    // =========================================================================

    @Nested
    class PrescriptionNotGiven {

        @Test
        void record_notGivenPrescription_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String routeUid = seedRoute(tag, "PO", true);
            String prescriptionUid = seedNotGivenPrescription(tag, patientUid);

            mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(marBody(prescriptionUid, "NURSE-MAR-2", routeUid,
                                    "2026-06-05T11:00:00Z", "1 tab", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value("Prescription not picked from pharmacy"));
        }
    }

    // =========================================================================
    // Unknown / inactive route → 422
    // =========================================================================

    @Nested
    class RouteGuard {

        @Test
        void record_inactiveRoute_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            // Route exists but is INACTIVE → guard rejects
            String inactiveRouteUid = seedRoute(tag, "IM", false);
            String prescriptionUid = seedGivenPrescription(tag, patientUid);

            mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(marBody(prescriptionUid, "NURSE-MAR-3", inactiveRouteUid,
                                    "2026-06-05T12:00:00Z", "1 amp", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail")
                            .value("Administration route is not listed or is inactive"));
        }

        @Test
        void record_unknownRoute_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String prescriptionUid = seedGivenPrescription(tag, patientUid);
            String unknownRouteUid = "ROUTEUNKNOWN00000000000000"; // 26-char, never persisted

            mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(marBody(prescriptionUid, "NURSE-MAR-4", unknownRouteUid,
                                    "2026-06-05T12:30:00Z", "1 amp", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail")
                            .value("Administration route is not listed or is inactive"));
        }
    }

    // =========================================================================
    // Admission not IN-PROCESS (PENDING) → 422
    // =========================================================================

    @Nested
    class AdmissionGate {

        @Test
        void record_pendingAdmission_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            // NOT paying the ward bill → admission stays PENDING

            String routeUid = seedRoute(tag, "SC", true);
            String prescriptionUid = seedGivenPrescription(tag, patientUid);

            mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(marBody(prescriptionUid, "NURSE-MAR-5", routeUid,
                                    "2026-06-05T13:00:00Z", "1 unit", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value("Could not be done. Admission not verified"));
        }
    }

    // =========================================================================
    // Missing MEDICATION-ADMINISTER privilege → 403
    // =========================================================================

    @Nested
    class PrivilegeGate {

        @Test
        void record_withoutMedicationAdministerPrivilege_returns403() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "400.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String routeUid = seedRoute(tag, "SL", true);
            String prescriptionUid = seedGivenPrescription(tag, patientUid);

            // Token WITHOUT MEDICATION-ADMINISTER (still authenticated)
            String noPrivToken = jwtFactory.tokenWithPrivileges("nurse-nopriv",
                    List.of("PATIENT-ALL"));

            mockMvc.perform(post(admMar(admUid))
                            .header("Authorization", "Bearer " + noPrivToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(marBody(prescriptionUid, "NURSE-MAR-6", routeUid,
                                    "2026-06-05T14:00:00Z", "1 unit", null)))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String admMar(String admUid) {
        return ADMISSIONS + "/" + admUid + "/medication-administrations";
    }

    private static String uniq() {
        return "M7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
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
                null, "07dIT" + tag, "Mar07d", tag, "IT",
                LocalDate.of(1987, 5, 12), "M",
                PatientType.OUTPATIENT, PaymentType.CASH,
                null, null, null, dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /** Seed an administration route via the new CRUD REST endpoint; returns its uid. */
    private String seedRoute(String tag, String code, boolean active) throws Exception {
        String body = """
                {"code":"RT-%s-%s","name":"Route %s %s","description":null,"active":%s}
                """.formatted(code, tag, code, tag, active);
        MvcResult r = mockMvc.perform(post(ROUTES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String seedWardWithBed(String tag, String price) throws Exception {
        String catBody = """
                {"code":"WCM7-%s","name":"WCat M07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        String typeBody = """
                {"code":"WTM7-%s","name":"WType M07 %s","description":null,
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
                {"code":"WDM7-%s","name":"Ward M07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        String bedBody = """
                {"no":"BDM7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    private String doAdmission(String patientUid, String wardBedUid) throws Exception {
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

    private static String marBody(String prescriptionUid, String nurseUid, String routeUid,
                                  String administeredAt, String doseGiven, String patientResponse) {
        String response = patientResponse != null ? "\"" + patientResponse + "\"" : "null";
        return """
                {"prescriptionUid":"%s","nurseUid":"%s","routeUid":"%s",
                 "administeredAt":"%s","doseGiven":"%s","patientResponse":%s}
                """.formatted(prescriptionUid, nurseUid, routeUid, administeredAt, doseGiven, response);
    }

    private String seedNotGivenPrescription(String tag, String patientUid) {
        Consultation savedConsult = consultationRepository.saveAndFlush(
                buildThrowawayConsultation(tag, patientUid));
        BigDecimal qty = new BigDecimal("1.000000");
        Prescription rx = Prescription.forConsultation(
                savedConsult, fakeUid("MED", tag), fakeUid("BIL", tag), false, qty,
                "1 tab", "OD", "ORAL", "7", null, null,
                "CASH", "", null, null, "test-seeder", dayUid, Instant.now());
        return prescriptionRepository.saveAndFlush(rx).getUid();
    }

    private String seedGivenPrescription(String tag, String patientUid) {
        Consultation savedConsult = consultationRepository.saveAndFlush(
                buildThrowawayConsultation(tag + "G", patientUid));
        BigDecimal qty = new BigDecimal("1.000000");
        Prescription rx = Prescription.forConsultation(
                savedConsult, fakeUid("MED", tag), fakeUid("BIL", tag), false, qty,
                "1 tab", "OD", "ORAL", "7", null, null,
                "CASH", "", null, null, "test-seeder", dayUid, Instant.now());
        rx.issue(qty, fakeUid("PHM", tag), "test-seeder", dayUid, Instant.now());
        return prescriptionRepository.saveAndFlush(rx).getUid();
    }

    private Consultation buildThrowawayConsultation(String tag, String patientUid) {
        Consultation c = new Consultation(
                patientUid, null, fakeUid("CLN", tag), fakeUid("DOC", tag),
                fakeUid("BIL", tag), PaymentMode.CASH,
                false, false, "", null, dayUid);
        c.open();
        return c;
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }
}
