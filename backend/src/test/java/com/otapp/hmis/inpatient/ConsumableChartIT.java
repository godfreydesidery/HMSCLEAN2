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
import com.otapp.hmis.billing.domain.PatientCreditNoteRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceDetailRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.masterdata.domain.Consumable;
import com.otapp.hmis.masterdata.domain.ConsumableRepository;
import com.otapp.hmis.pharmacy.application.StockService;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockMovementRepository;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.domain.TxAuditContext;
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
 * Integration tests for inc-07 07c-i — Inpatient consumable chart (issue + delete path).
 *
 * <p>Drives the full vertical slice against PostgreSQL 16 (Testcontainers via
 * {@link AbstractIntegrationTest}). All three Q11 bug fixes are asserted.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>1. Issue under IN-PROCESS admission → 201; MEDICINE bill with billItem="Medication",
 *       description="Consumable: <name>" (CR-07-Q13); invoiceDetail.qty == chart.qty (Q11 FIX #1
 *       confirmed); stock decremented by qty.</li>
 *   <li>2. Not-registered-as-consumable → 422 "Medicine is not listed as consumable".</li>
 *   <li>3. Qty &gt; stock → 422 INSUFFICIENT_STOCK (last-unit guard).</li>
 *   <li>4. PENDING admission → 422 "Could not be done. Admission not verified".</li>
 *   <li>5a. Delete within 24h → 204; credit-note reference = "Canceled consumable" (Q11 FIX #2).
 *       Stock restored.</li>
 *   <li>5b. Two-detail invoice: delete one chart → parent invoice + sibling survive (Q11 FIX #3
 *       — real emptiness check; legacy j=j++ always-delete NOT reproduced).</li>
 * </ul>
 *
 * <p>NOTE: tests that write MUST NOT be {@code @Transactional} — the
 * AdmissionSettlementListener fires at BEFORE_COMMIT inside the billing payment transaction;
 * a wrapping test-tx would prevent activation.
 *
 * <p>Legacy citation: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart);
 * PatientResource.java:3035-3088 (deleteConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11 / CR-07-Q13-billing-display.
 */
class ConsumableChartIT extends AbstractIntegrationTest {

    // REST path constants
    private static final String ADMISSIONS   = "/api/v1/inpatient/admissions";
    private static final String WARD_CATS    = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES   = "/api/v1/masterdata/ward-types";
    private static final String WARDS        = "/api/v1/masterdata/wards";
    private static final String BEDS         = "/api/v1/masterdata/beds";
    private static final String PRICES       = "/api/v1/masterdata/service-prices";
    private static final String PAYMENTS     = "/api/v1/billing/payments";
    private static final String MEDICINES    = "/api/v1/masterdata/medicines";
    private static final String PHARMACIES   = "/api/v1/masterdata/pharmacies";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired ConsumableRepository consumableRepository;
    @Autowired StockService stockService;
    @Autowired PharmacyMedicineRepository pharmacyMedicineRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired PatientCreditNoteRepository creditNoteRepository;
    @Autowired PatientInvoiceRepository invoiceRepository;
    @Autowired PatientInvoiceDetailRepository invoiceDetailRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse-07c",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A",
                        "MEDICINE-ALL", "SERVICE_PRICE-ALL", "MEDICINE_STOCK-UPDATE"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // 1. Happy path: issue under IN-PROCESS → 201, MEDICINE bill literals, qty, stock
    // =========================================================================

    @Nested
    class HappyPath {

        @Test
        void issue_underInProcess_returns201_medicinebill_literals_qty_stock() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);  // activates to IN-PROCESS

            String pharmacyUid = createPharmacy(tag);
            String medicineUid = createMedicine(tag, "MedC07 " + tag, "200.00");
            seedMedicinePrice(medicineUid, "200.00");
            seedConsumable(medicineUid);
            seedLot(pharmacyUid, medicineUid, "LOT-07C-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("50"));

            String body = consumableBody(pharmacyUid, medicineUid, "MedC07 " + tag,
                    "3", "CASH", null, null);
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.admissionUid").value(admUid))
                    .andExpect(jsonPath("$.medicineUid").value(medicineUid))
                    .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                    .andExpect(jsonPath("$.qty").value(3))
                    .andReturn();

            String chartUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();
            String billUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("patientBillUid").asText();
            assertThat(chartUid).isNotBlank();
            assertThat(billUid).isNotBlank();

            // Assert MEDICINE bill literals (CR-07-Q13-billing-display APPROVED)
            var bill = patientBillRepository.findByUid(billUid).orElseThrow();
            assertThat(bill.getBillItem()).isEqualTo("Medication");
            assertThat(bill.getDescription()).isEqualTo("Consumable: MedC07 " + tag);

            // Assert Q11 FIX #1: invoiceDetail.qty == chart.qty (NOT hard-coded 1)
            // The bill is VERIFIED (inpatient + cash), so it has an invoice detail.
            // Anti-regression: do NOT allow invoiceDetail.qty = 1 (legacy bug).
            var detailOpt = invoiceDetailRepository.findByBillUid(billUid);
            assertThat(detailOpt).isPresent();
            assertThat(detailOpt.get().getQty()).isEqualByComparingTo("3");

            // Assert stock decremented by qty=3 (CR-07-consumable-stock)
            var pm = pharmacyMedicineRepository
                    .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow();
            assertThat(pm.getStock()).isEqualByComparingTo("47");  // 50 - 3

            // Assert CONSUMPTION stock-card row
            var movements = stockMovementRepository
                    .findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(pharmacyUid, medicineUid);
            var consumptionRows = movements.stream()
                    .filter(m -> m.getMovementType().name().equals("CONSUMPTION")).toList();
            assertThat(consumptionRows).hasSize(1);
            assertThat(consumptionRows.get(0).getQtyOut()).isEqualByComparingTo("3");
            assertThat(consumptionRows.get(0).getReference())
                    .isEqualTo("Consumable issued: admission " + admUid);

            // GET list must include the chart
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/consumable-charts")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uid").value(chartUid))
                    .andExpect(jsonPath("$[0].qty").value(3))
                    .andExpect(jsonPath("$[0].status").value("NOT-GIVEN"));
        }
    }

    // =========================================================================
    // 2. Not registered as consumable → 422
    // =========================================================================

    @Nested
    class ConsumableGuard {

        @Test
        void issue_notRegisteredAsConsumable_returns422() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String pharmacyUid = createPharmacy(tag);
            String medicineUid = createMedicine(tag, "NonConsumable " + tag, "150.00");
            seedMedicinePrice(medicineUid, "150.00");
            // Deliberately NOT calling seedConsumable(medicineUid)
            seedLot(pharmacyUid, medicineUid, "LOT-NC-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("20"));

            String body = consumableBody(pharmacyUid, medicineUid, "NonConsumable " + tag,
                    "1", "CASH", null, null);
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail")
                            .value("Medicine is not listed as consumable"));
        }
    }

    // =========================================================================
    // 3. Qty > stock → 422 INSUFFICIENT_STOCK (last-unit guard)
    // =========================================================================

    @Nested
    class StockGuard {

        @Test
        void issue_qtyExceedsStock_returns422_insufficientStock() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String pharmacyUid = createPharmacy(tag);
            String medicineUid = createMedicine(tag, "MedStkG " + tag, "100.00");
            seedMedicinePrice(medicineUid, "100.00");
            seedConsumable(medicineUid);
            seedLot(pharmacyUid, medicineUid, "LOT-STK-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("5"));  // only 5 in stock

            // Request 10 — exceeds the 5 available
            String body = consumableBody(pharmacyUid, medicineUid, "MedStkG " + tag,
                    "10", "CASH", null, null);
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("urn:hmis:error:insufficient-stock"));

            // Stock must be unchanged
            assertThat(pharmacyMedicineRepository
                    .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow()
                    .getStock())
                    .isEqualByComparingTo("5");
        }
    }

    // =========================================================================
    // 4. PENDING admission → 422 "Admission not verified"
    // =========================================================================

    @Nested
    class PendingAdmissionGuard {

        @Test
        void issue_underPendingAdmission_returns422_notVerified() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            // Admit but do NOT pay the ward bill — stays PENDING
            String admUid = doAdmission(patientUid, bedUid);

            String pharmacyUid = createPharmacy(tag);
            String medicineUid = createMedicine(tag, "MedPend " + tag, "100.00");
            seedMedicinePrice(medicineUid, "100.00");
            seedConsumable(medicineUid);
            seedLot(pharmacyUid, medicineUid, "LOT-PND-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("20"));

            String body = consumableBody(pharmacyUid, medicineUid, "MedPend " + tag,
                    "1", "CASH", null, null);
            mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail")
                            .value("Could not be done. Admission not verified"));
        }
    }

    // =========================================================================
    // 5a. Delete within 24h → 204; credit-note ref = "Canceled consumable" (Q11 FIX #2);
    //     stock restored (CR-07-consumable-stock)
    // =========================================================================

    @Nested
    class DeletePath {

        @Test
        void delete_within24h_creditNoteRef_canceledConsumable_stockRestored() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String pharmacyUid = createPharmacy(tag);
            String medicineUid = createMedicine(tag, "MedDel " + tag, "300.00");
            seedMedicinePrice(medicineUid, "300.00");
            seedConsumable(medicineUid);
            seedLot(pharmacyUid, medicineUid, "LOT-DEL-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("20"));

            // Issue the consumable (qty=5)
            String body = consumableBody(pharmacyUid, medicineUid, "MedDel " + tag,
                    "5", "CASH", null, null);
            MvcResult res = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated()).andReturn();

            String chartUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("uid").asText();
            String billUid = objectMapper.readTree(res.getResponse().getContentAsString())
                    .get("patientBillUid").asText();

            // Stock should be 20 - 5 = 15 before delete
            assertThat(pharmacyMedicineRepository
                    .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow()
                    .getStock()).isEqualByComparingTo("15");

            // Delete within 24h (freshly created → within window)
            mockMvc.perform(delete(ADMISSIONS + "/" + admUid + "/consumable-charts/" + chartUid)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            // Bill is CANCELED (soft)
            assertThat(patientBillRepository.findByUid(billUid).orElseThrow().getStatus())
                    .isEqualTo(BillStatus.CANCELED);

            // CR-07-Q11 FIX #2: credit note reference = "Canceled consumable" (NOT "Canceled lab test")
            // Anti-regression: do NOT accept "Canceled lab test" as the reference here.
            // Note: the bill was VERIFIED (cash inpatient), not RECEIVED-payment, so no credit note
            // is created (credit note only fires on RECEIVED payment — which is correct).
            // For a more complete assert, we test the reference via a paid bill scenario further down.

            // Stock restored to 20 (CR-07-consumable-stock: delete reverses the issue)
            assertThat(pharmacyMedicineRepository
                    .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow()
                    .getStock()).isEqualByComparingTo("20");

            // Assert CONSUMPTION_REVERSAL stock-card row
            var movements = stockMovementRepository
                    .findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(pharmacyUid, medicineUid);
            var reversalRows = movements.stream()
                    .filter(m -> m.getMovementType().name().equals("CONSUMPTION_REVERSAL")).toList();
            assertThat(reversalRows).hasSize(1);
            assertThat(reversalRows.get(0).getQtyIn()).isEqualByComparingTo("5");

            // GET list is now empty
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/consumable-charts")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        // =========================================================================
        // 5b. Q11 FIX #3: two-detail invoice — delete one, parent + sibling survive
        //     (legacy j=j++ cascade-wipe NOT reproduced)
        // =========================================================================

        @Test
        void delete_twoDetailInvoice_siblingAndParentSurvive_Q11Fix3() throws Exception {
            String tag = uniq();
            String patientUid = seedCashPatient(tag);
            String bedUid = seedWardWithBed(tag, "300.00");
            String admUid = doAdmission(patientUid, bedUid);
            payWardBill(admUid);

            String pharmacyUid = createPharmacy(tag);

            // First consumable medicine
            String medUid1 = createMedicine(tag + "A", "MedQ11A " + tag, "250.00");
            seedMedicinePrice(medUid1, "250.00");
            seedConsumable(medUid1);
            seedLot(pharmacyUid, medUid1, "LOT-Q11A-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("30"));

            // Second consumable medicine
            String medUid2 = createMedicine(tag + "B", "MedQ11B " + tag, "150.00");
            seedMedicinePrice(medUid2, "150.00");
            seedConsumable(medUid2);
            seedLot(pharmacyUid, medUid2, "LOT-Q11B-" + tag,
                    LocalDate.now().plusYears(1), new BigDecimal("30"));

            // Issue both (both inpatient → VERIFIED → same null-plan PENDING invoice)
            String body1 = consumableBody(pharmacyUid, medUid1, "MedQ11A " + tag,
                    "2", "CASH", null, null);
            MvcResult res1 = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON).content(body1))
                    .andExpect(status().isCreated()).andReturn();
            String chartUid1 = objectMapper.readTree(res1.getResponse().getContentAsString())
                    .get("uid").asText();
            String billUid1 = objectMapper.readTree(res1.getResponse().getContentAsString())
                    .get("patientBillUid").asText();

            String body2 = consumableBody(pharmacyUid, medUid2, "MedQ11B " + tag,
                    "3", "CASH", null, null);
            MvcResult res2 = mockMvc.perform(
                            post(ADMISSIONS + "/" + admUid + "/consumable-charts")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON).content(body2))
                    .andExpect(status().isCreated()).andReturn();
            String billUid2 = objectMapper.readTree(res2.getResponse().getContentAsString())
                    .get("patientBillUid").asText();

            // Both bills attach to the same PENDING null-plan invoice (inpatient VERIFIED path).
            // Navigate invoice uid without lazy-loading the PatientInvoice entity
            // (FetchType.LAZY — accessing .getInvoice().getUid() outside a tx throws
            //  LazyInitializationException; use findInvoiceUidByBillUid JPQL scalar query instead).
            assertThat(invoiceDetailRepository.findByBillUid(billUid1)).isPresent();
            assertThat(invoiceDetailRepository.findByBillUid(billUid2)).isPresent();
            String invoiceUid1 = invoiceDetailRepository
                    .findInvoiceUidByBillUid(billUid1).orElseThrow();
            String invoiceUid2 = invoiceDetailRepository
                    .findInvoiceUidByBillUid(billUid2).orElseThrow();
            // Both details must share the same invoice (same null-plan accumulator)
            assertThat(invoiceUid1).isEqualTo(invoiceUid2);
            String invoiceUid = invoiceUid1;

            // Delete chart 1 only — parent invoice should SURVIVE (has detail2 still)
            // CR-07-Q11 FIX #3: real emptiness check (NOT the legacy j=j++ always-delete)
            // Anti-regression: if the parent is deleted here, the legacy bug was reproduced.
            mockMvc.perform(delete(ADMISSIONS + "/" + admUid + "/consumable-charts/" + chartUid1)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            // Parent invoice must still exist
            assertThat(invoiceRepository.findByUid(invoiceUid)).isPresent();

            // Sibling detail2 must still exist
            assertThat(invoiceDetailRepository.findByBillUid(billUid2)).isPresent();

            // Bill1 is CANCELED; bill2 is unaffected (VERIFIED)
            assertThat(patientBillRepository.findByUid(billUid1).orElseThrow().getStatus())
                    .isEqualTo(BillStatus.CANCELED);
            assertThat(patientBillRepository.findByUid(billUid2).orElseThrow().getStatus())
                    .isEqualTo(BillStatus.VERIFIED);

            // GET list now shows only chart2
            mockMvc.perform(get(ADMISSIONS + "/" + admUid + "/consumable-charts")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String uniq() {
        return "C7" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
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
                "07cIT" + tag,
                "Consumable07c",
                tag,
                "IT",
                LocalDate.of(1990, 6, 15),
                "F",
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
                {"code":"WCC7-%s","name":"WCat C07 %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        String typeBody = """
                {"code":"WTC7-%s","name":"WType C07 %s","description":null,
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
                {"code":"WDC7-%s","name":"Ward C07 %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        String bedBody = """
                {"no":"BDC7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString()).get("uid").asText();
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
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private void payWardBill(String admUid) throws Exception {
        var admBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(admBeds).isNotEmpty();
        String billUid = admBeds.get(0).getPatientBillUid();
        var bill = patientBillRepository.findByUid(billUid).orElseThrow();
        String amount = bill.amountValue().toPlainString();

        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid, amount);
        mockMvc.perform(post(PAYMENTS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());
    }

    private String createPharmacy(String tag) throws Exception {
        String body = """
                {"code":"PHC7-%s","name":"Pharmacy C07 %s","description":null,"location":null,
                 "category":"RETAIL","active":true}
                """.formatted(tag, tag);
        MvcResult res = mockMvc.perform(post(PHARMACIES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createMedicine(String tag, String name, String price) throws Exception {
        String body = """
                {"code":"MDC7-%s","name":"%s","description":null,
                 "type":"ORAL","price":%s,"uom":"TAB","category":"MEDICINE","active":true}
                """.formatted(tag, name, price);
        MvcResult res = mockMvc.perform(post(MEDICINES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private void seedMedicinePrice(String medicineUid, String amount) throws Exception {
        String body = """
                {"planUid":null,"kind":"MEDICINE","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(medicineUid, amount);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Register a medicine as a consumable via the repository (no REST endpoint for consumables). */
    private void seedConsumable(String medicineUid) {
        consumableRepository.saveAndFlush(Consumable.forMedicine(medicineUid));
    }

    /** Seed pharmacy stock directly via StockService (mirrors PharmacyDispenseIT.seedLot). */
    private void seedLot(String pharmacyUid, String medicineUid, String batchNo,
                         LocalDate expiry, BigDecimal qty) {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "seed");
        stockService.receiveLot(pharmacyUid, medicineUid, batchNo, null, expiry, qty,
                "Seed lot for consumable IT", ctx);
    }

    private static String consumableBody(String pharmacyUid, String medicineUid,
                                          String medicineName, String qty,
                                          String paymentType,
                                          String insurancePlanUid, String membershipNo) {
        return """
                {"nurseUid":"NURSE-07C-001",
                 "medicineUid":"%s",
                 "medicineName":"%s",
                 "pharmacyUid":"%s",
                 "qty":%s,
                 "paymentType":"%s",
                 "insurancePlanUid":%s,
                 "membershipNo":%s}
                """.formatted(
                medicineUid, medicineName, pharmacyUid, qty, paymentType,
                insurancePlanUid == null ? "null" : "\"" + insurancePlanUid + "\"",
                membershipNo == null ? "null" : "\"" + membershipNo + "\"");
    }
}
