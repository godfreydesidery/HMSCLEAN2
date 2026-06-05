package com.otapp.hmis.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.pharmacy.application.StockService;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicine;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockBatchRepository;
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
 * Integration test for inc-08b chunk 7 — pharmacy↔pharmacy transfer (PPR→PPTO→PPRN) on PG 16.
 *
 * <p>Proves: source (delivering) pharmacy stock decrements ONLY at TO.issue (1:1, no coefficient);
 * destination (requesting) pharmacy stock increments ONLY at RN.complete — AGGREGATE ONLY, with
 * <strong>NO destination batch created</strong> (the reproduced legacy gap, Q7 — the key contrast
 * with the store→pharmacy path which DOES create dest batches).
 */
class PharmacyPharmacyTransferIT extends AbstractIntegrationTest {

    private static final String PHARMACIES_URL = "/api/v1/masterdata/pharmacies";
    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String T = "/api/v1/inventory/pp-transfers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired StockService stockService;
    @Autowired PharmacyMedicineRepository pharmacyMedicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("transfer",
                List.of("ADMIN-ACCESS", "MEDICINE-ALL", "PHARMACY_ORDER-ALL"));
        dayUid = ensureDayOpen();
    }

    @Test
    void ppTransfer_sourceDebitAtIssue_destCreditAtComplete_NOdestBatch() throws Exception {
        String tag = uniq();
        String requesting = createPharmacy(tag + "R");
        String delivering = createPharmacy(tag + "D");
        String medicineUid = createMedicine(tag);
        // seed DELIVERING pharmacy stock of 50 (one dated lot)
        seedPharmacyLot(delivering, medicineUid, new BigDecimal("50"));

        // RO: requesting requests 15 from delivering
        String roUid = postUid(T + "/ros", "{\"requestingPharmacyUid\":\"" + requesting
                + "\",\"deliveringPharmacyUid\":\"" + delivering + "\"}");
        mockMvc.perform(post(T + "/ros/uid/" + roUid + "/details")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid + "\",\"orderedQty\":15}"))
                .andExpect(status().isCreated());
        transition(T + "/ros", roUid, "verify");
        transition(T + "/ros", roUid, "approve");
        transition(T + "/ros", roUid, "submit");

        // TO from SUBMITTED RO
        MvcResult toRes = mockMvc.perform(post(T + "/tos/from-ro/uid/" + roUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        JsonNode to = objectMapper.readTree(toRes.getResponse().getContentAsString());
        String toUid = to.get("uid").asText();
        String toDetailUid = to.get("details").get(0).get("uid").asText();

        // add_batch: 15 (1:1 — no coefficient); delivering stock STILL 50
        mockMvc.perform(post(T + "/to-details/uid/" + toDetailUid + "/batches")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid
                                + "\",\"batchNo\":\"PB-1\",\"qty\":15,\"expiryDate\":\"2030-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.details[0].transferedStoreQty").value(15));
        assertThat(pharmacyStock(delivering, medicineUid)).isEqualByComparingTo("50");

        transition(T + "/tos", toUid, "verify");
        transition(T + "/tos", toUid, "approve");

        // issue: SOURCE (delivering) stock decrements (50 - 15 = 35)
        transition(T + "/tos", toUid, "issue");
        assertThat(pharmacyStock(delivering, medicineUid)).as("delivering debited at TO.issue")
                .isEqualByComparingTo("35");
        // requesting not yet credited
        assertThat(pharmacyMedicineRepository.findByPharmacyUidAndMedicineUid(requesting, medicineUid))
                .isEmpty();

        // RN: create + complete -> requesting credited (aggregate only, NO dest batch)
        String rnUid = postUid(T + "/rns/from-to/uid/" + toUid, null);
        mockMvc.perform(post(T + "/rns/uid/" + rnUid + "/complete")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        PharmacyMedicine reqPm = pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(requesting, medicineUid).orElseThrow();
        assertThat(reqPm.getStock()).as("requesting credited 15 (1:1)").isEqualByComparingTo("15");
        // THE Q7 GAP: NO destination StockBatch created for the requesting pharmacy
        assertThat(stockBatchRepository.findByPharmacyMedicine(reqPm))
                .as("p2p does NOT create destination batches (reproduced legacy gap, Q7)")
                .isEmpty();
    }

    @Test
    void createRo_sameRequestingAndDelivering_422() throws Exception {
        String tag = uniq();
        String p = createPharmacy(tag);
        mockMvc.perform(post(T + "/ros").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestingPharmacyUid\":\"" + p + "\",\"deliveringPharmacyUid\":\"" + p + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Order can not be placed in the same pharmacy"));
    }

    @Test
    void issueTo_insufficientSourceStock_422() throws Exception {
        String tag = uniq();
        String requesting = createPharmacy(tag + "R");
        String delivering = createPharmacy(tag + "D");
        String medicineUid = createMedicine(tag);
        seedPharmacyLot(delivering, medicineUid, new BigDecimal("5"));   // only 5
        String roUid = postUid(T + "/ros", "{\"requestingPharmacyUid\":\"" + requesting
                + "\",\"deliveringPharmacyUid\":\"" + delivering + "\"}");
        mockMvc.perform(post(T + "/ros/uid/" + roUid + "/details")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"medicineUid\":\"" + medicineUid + "\",\"orderedQty\":10}"))
                .andExpect(status().isCreated());
        transition(T + "/ros", roUid, "verify");
        transition(T + "/ros", roUid, "approve");
        transition(T + "/ros", roUid, "submit");
        MvcResult toRes = mockMvc.perform(post(T + "/tos/from-ro/uid/" + roUid)
                .header("Authorization", "Bearer " + token)).andReturn();
        JsonNode to = objectMapper.readTree(toRes.getResponse().getContentAsString());
        String toUid = to.get("uid").asText();
        String toDetailUid = to.get("details").get(0).get("uid").asText();
        mockMvc.perform(post(T + "/to-details/uid/" + toDetailUid + "/batches")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"medicineUid\":\"" + medicineUid + "\",\"batchNo\":\"PB-x\",\"qty\":10}"))
                .andExpect(status().isCreated());
        transition(T + "/tos", toUid, "verify");
        transition(T + "/tos", toUid, "approve");
        // issue wants 10 but source has only 5 -> 422 INSUFFICIENT_STOCK
        mockMvc.perform(post(T + "/tos/uid/" + toUid + "/issue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:insufficient-stock"));
    }

    @Test
    void createRo_noToken_401() throws Exception {
        mockMvc.perform(post(T + "/ros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestingPharmacyUid\":\"x\",\"deliveringPharmacyUid\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== seeding =====================

    private static String uniq() {
        return "D7" + Long.toHexString(System.nanoTime()).substring(0, 8);
    }

    private String createPharmacy(String tag) throws Exception {
        return postUid(PHARMACIES_URL, "{\"code\":\"PH-" + tag + "\",\"name\":\"Pharmacy " + tag
                + "\",\"category\":\"RETAIL\",\"active\":true}");
    }

    private String createMedicine(String tag) throws Exception {
        return postUid(MEDICINES_URL, "{\"code\":\"MED-" + tag + "\",\"name\":\"Medicine " + tag
                + "\",\"type\":\"ORAL\",\"price\":100.00,\"uom\":\"TAB\",\"category\":\"MEDICINE\",\"active\":true}");
    }

    private void seedPharmacyLot(String pharmacyUid, String medicineUid, BigDecimal qty) {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "seed");
        stockService.receiveLot(pharmacyUid, medicineUid, "SRC-LOT", null,
                LocalDate.now().plusYears(2), qty, "Seed", ctx);
    }

    private BigDecimal pharmacyStock(String pharmacyUid, String medicineUid) {
        return pharmacyMedicineRepository.findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid)
                .orElseThrow().getStock();
    }

    private void transition(String base, String uid, String action) throws Exception {
        mockMvc.perform(post(base + "/uid/" + uid + "/" + action)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private String postUid(String url, String body) throws Exception {
        var req = post(url).header("Authorization", "Bearer " + token);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult res = mockMvc.perform(req).andExpect(status().isCreated()).andReturn();
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
