package com.otapp.hmis.pharmacy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.pharmacy.application.StockService;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockMovementRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-08a chunk 3 — pharmacy-orchestrated clinical-prescription dispense.
 *
 * <p>Drives the real vertical slice against PostgreSQL 16 (Testcontainers): seed a pharmacy + a
 * medicine + a priced MEDICINE service-price + a lot of stock, prescribe (OUTSIDER/CASH), then
 * dispense via the pharmacy endpoint.
 *
 * <p>Coverage: AC-RX-DSP-04/06/07/08/09/10/11/15/19/23 + RBAC-07.
 */
class PharmacyDispenseIT extends AbstractIntegrationTest {

    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String PHARMACIES_URL = "/api/v1/masterdata/pharmacies";
    private static final String PRICES_URL = "/api/v1/masterdata/service-prices";
    private static final String NC_BASE = "/api/v1/clinical/non-consultations/uid/";
    private static final String RX_BASE = "/api/v1/clinical/prescriptions";
    private static final String PH_BASE = "/api/v1/pharmacy";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired StockService stockService;
    @Autowired NonConsultationRepository nonConsultationRepository;
    @Autowired PharmacyMedicineRepository pharmacyMedicineRepository;
    @Autowired StockMovementRepository stockMovementRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("pharmacist",
                List.of("ADMIN-ACCESS", "MEDICINE-ALL", "PATIENT-ALL", "BILL-A",
                        "SERVICE_PRICE-ALL", "MEDICINE_STOCK-UPDATE"));
        dayUid = ensureDayOpen();
    }

    @Test
    void dispense_happyPath_statusGiven_stockDecremented_oneOutRow_lotTraceMatchesQty() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "1200.00");
        seedLot(pharmacyUid, medicineUid, "LOT-A", LocalDate.now().plusYears(1), new BigDecimal("50"));
        String rxUid = prescribeCash(tag, medicineUid, "30");

        String body = "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"issued\":30}";
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GIVEN"))
                .andExpect(jsonPath("$.issuePharmacyUid").value(pharmacyUid))
                .andExpect(jsonPath("$.balance").value(0));

        assertThat(pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow().getStock())
                .isEqualByComparingTo("20");

        var movements = stockMovementRepository
                .findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(pharmacyUid, medicineUid);
        var dispenseRows = movements.stream()
                .filter(m -> m.getMovementType().name().equals("DISPENSE")).toList();
        assertThat(dispenseRows).hasSize(1);
        var out = dispenseRows.get(0);
        assertThat(out.getQtyOut()).isEqualByComparingTo("30");
        assertThat(out.getRunningBalance()).isEqualByComparingTo("20");
        assertThat(out.getReference()).isEqualTo("Issued in prescription: id " + rxUid);

        MvcResult batchesRes = mockMvc.perform(get(RX_BASE + "/uid/" + rxUid + "/batches")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode batches = objectMapper.readTree(batchesRes.getResponse().getContentAsString());
        BigDecimal traced = BigDecimal.ZERO;
        for (JsonNode b : batches) {
            traced = traced.add(new BigDecimal(b.get("qty").asText()));
        }
        assertThat(traced).isEqualByComparingTo("30");
    }

    @Test
    void dispense_insufficientStock_422_noStateChange() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "800.00");
        seedLot(pharmacyUid, medicineUid, "LOT-B", LocalDate.now().plusYears(1), new BigDecimal("5"));
        String rxUid = prescribeCash(tag, medicineUid, "10");

        String body = "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"issued\":10}";
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:insufficient-stock"));

        assertThat(pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow().getStock())
                .isEqualByComparingTo("5");
        mockMvc.perform(get(RX_BASE + "/uid/" + rxUid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"));
    }

    @Test
    void dispense_underIssue_422_invalidIssueValue() throws Exception {
        // Legacy guard order (Prescription.issue(), AC-RX-DSP-05): under-issue (issued < balance)
        // is rejected with "Invalid issue value" BEFORE the issued==qty check fires.
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "500.00");
        seedLot(pharmacyUid, medicineUid, "LOT-C", LocalDate.now().plusYears(1), new BigDecimal("100"));
        String rxUid = prescribeCash(tag, medicineUid, "20");

        String body = "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"issued\":15}"; // 15 < balance 20
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Invalid issue value"));
    }

    @Test
    void dispense_overIssue_422_allOrNothing() throws Exception {
        // issued > qty passes the (issued >= balance) guard, then trips the all-or-nothing check
        // with "You can only issue the prescribed qty" (AC-RX-DSP-06).
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "500.00");
        seedLot(pharmacyUid, medicineUid, "LOT-C2", LocalDate.now().plusYears(1), new BigDecimal("100"));
        String rxUid = prescribeCash(tag, medicineUid, "20");

        String body = "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"issued\":25}"; // 25 > qty 20
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("You can only issue the prescribed qty"));
    }

    @Test
    void dispense_alreadyGiven_422() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "600.00");
        seedLot(pharmacyUid, medicineUid, "LOT-D", LocalDate.now().plusYears(1), new BigDecimal("100"));
        String rxUid = prescribeCash(tag, medicineUid, "10");

        String body = "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"issued\":10}";
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail",
                        org.hamcrest.Matchers.containsString("not a pending prescription")));
    }

    @Test
    void dispense_unknownPharmacy_404() throws Exception {
        String tag = uniq();
        String medicineUid = createMedicine(tag);
        seedPrice(medicineUid, "900.00");
        String rxUid = prescribeCash(tag, medicineUid, "5");
        String body = "{\"pharmacyUid\":\"01ZZZZZZZZZZZZZZZZZZZZZZZZZ\",\"issued\":5}";
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/" + rxUid + "/dispense")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void dispense_noToken_401() throws Exception {
        mockMvc.perform(post(PH_BASE + "/prescriptions/uid/whatever/dispense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pharmacyUid\":\"x\",\"issued\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Seeding helpers (mirror PrescriptionIT)
    // =========================================================================

    private static String uniq() {
        return "D3" + Long.toHexString(System.nanoTime()).substring(0, 9);
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    private String createPharmacy(String tag) throws Exception {
        String body = """
                {"code":"PH-%s","name":"Pharmacy %s","description":null,"location":null,
                 "category":"RETAIL","active":true}
                """.formatted(tag, tag);
        MvcResult res = mockMvc.perform(post(PHARMACIES_URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createMedicine(String tag) throws Exception {
        String body = """
                {"code":"MED-%s","name":"Medicine %s","description":null,
                 "type":"ORAL","price":500.00,"uom":"TAB","category":"MEDICINE","active":true}
                """.formatted(tag, tag);
        MvcResult res = mockMvc.perform(post(MEDICINES_URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private void seedPrice(String medicineUid, String amount) throws Exception {
        String body = """
                {"planUid":null,"kind":"MEDICINE","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(medicineUid, amount);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Seed pharmacy stock directly via StockService (no GRN/transfer REST yet — 08b). */
    private void seedLot(String pharmacyUid, String medicineUid, String batchNo,
                         LocalDate expiry, BigDecimal qty) {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "seed");
        stockService.receiveLot(pharmacyUid, medicineUid, batchNo, null, expiry, qty, "Seed lot", ctx);
    }

    private String prescribeCash(String tag, String medicineUid, String qty) throws Exception {
        NonConsultation nc = new NonConsultation(
                fakeUid("PAT", tag), fakeUid("VST", tag), "CASH", "", null, dayUid);
        String ncUid = nonConsultationRepository.saveAndFlush(nc).getUid();
        String body = """
                {"medicineUid":"%s","qty":%s,"dosage":"1 OD","frequency":"OD","route":"PO","days":"5"}
                """.formatted(medicineUid, qty);
        MvcResult res = mockMvc.perform(post(NC_BASE + ncUid + "/prescriptions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }
}
