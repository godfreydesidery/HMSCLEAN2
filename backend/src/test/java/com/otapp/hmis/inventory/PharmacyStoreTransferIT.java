package com.otapp.hmis.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.inventory.application.StoreStockService;
import com.otapp.hmis.inventory.domain.StoreItemRepository;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockBatch;
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
 * Integration test for inc-08b chunk 6 — pharmacy↔store transfer (PSR→SPTO→PGRN) on PostgreSQL 16.
 *
 * <p>Proves the exact stock-posting TIMING: store stock is UNCHANGED through RO + TO create/verify/
 * approve; it decrements ONLY at TO.issue; pharmacy stock + a destination PharmacyMedicineBatch
 * appear ONLY at RN.complete, with the coefficient conversion applied (store-SKU × coefficient).
 */
class PharmacyStoreTransferIT extends AbstractIntegrationTest {

    private static final String STORES_URL = "/api/v1/masterdata/stores";
    private static final String PHARMACIES_URL = "/api/v1/masterdata/pharmacies";
    private static final String ITEMS_URL = "/api/v1/masterdata/items";
    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String COEFF_URL = "/api/v1/masterdata/item-medicine-coefficients";
    private static final String T = "/api/v1/inventory/ps-transfers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired StoreStockService storeStockService;
    @Autowired StoreItemRepository storeItemRepository;
    @Autowired PharmacyMedicineRepository pharmacyMedicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("transfer", List.of(
                "ADMIN-ACCESS", "ITEM-ALL", "MEDICINE-ALL", "CONVERSION_COEFFICIENT-ALL",
                "PHARMACY_ORDER-ALL", "PHARMACY_ORDER-CREATE", "PHARMACY_ORDER-UPDATE",
                "STORE_ORDER-ALL"));
        dayUid = ensureDayOpen();
    }

    @Test
    void psTransfer_storeDebitAtIssue_pharmacyCreditAtComplete_withCoefficient() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String pharmacyUid = createPharmacy(tag);
        String itemUid = createItem(tag);
        String medicineUid = createMedicine(tag);
        // coefficient 2: 1 store SKU (e.g. carton) = 2 pharmacy SKU (e.g. blister)
        createCoefficient(itemUid, medicineUid, "2", "1", "2");
        // seed store stock of 100 (store-SKU), one dated lot
        seedStoreLot(storeUid, itemUid, new BigDecimal("100"));

        // RO: request 20 pharmacy-SKU of the medicine
        String roUid = postUid(T + "/ros",
                "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"storeUid\":\"" + storeUid + "\"}");
        mockMvc.perform(post(T + "/ros/uid/" + roUid + "/details")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid + "\",\"orderedQty\":20}"))
                .andExpect(status().isCreated());
        transition(T + "/ros", roUid, "verify");
        transition(T + "/ros", roUid, "approve");
        transition(T + "/ros", roUid, "submit");

        // TO: store creates from SUBMITTED RO (RO -> IN-PROCESS)
        MvcResult toRes = mockMvc.perform(post(T + "/tos/from-ro/uid/" + roUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn();
        JsonNode to = objectMapper.readTree(toRes.getResponse().getContentAsString());
        String toUid = to.get("uid").asText();
        String toDetailUid = to.get("details").get(0).get("uid").asText();

        // add_batch: 10 store-SKU -> 20 pharmacy-SKU (coefficient 2); store stock STILL 100
        mockMvc.perform(post(T + "/to-details/uid/" + toDetailUid + "/batches")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"" + itemUid + "\",\"medicineUid\":\"" + medicineUid
                                + "\",\"batchNo\":\"TB-1\",\"storeSkuQty\":10,\"expiryDate\":\"2030-06-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.details[0].transferedStoreQty").value(10))
                .andExpect(jsonPath("$.details[0].transferedPharmacyQty").value(20));
        assertThat(storeStock(storeUid, itemUid)).as("store stock unchanged before issue")
                .isEqualByComparingTo("100");

        transition(T + "/tos", toUid, "verify");
        transition(T + "/tos", toUid, "approve");
        assertThat(storeStock(storeUid, itemUid)).as("store stock unchanged through approve")
                .isEqualByComparingTo("100");

        // issue: STORE stock decrements here (100 - 10 = 90)
        transition(T + "/tos", toUid, "issue");
        assertThat(storeStock(storeUid, itemUid)).as("store stock debited at TO.issue")
                .isEqualByComparingTo("90");
        // pharmacy NOT yet credited
        assertThat(pharmacyMedicineRepository.findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid))
                .as("pharmacy not credited until RN complete").isEmpty();

        // RN: pharmacy creates from GOODS-ISSUED TO, then completes -> PHARMACY credited
        String rnUid = postUid(T + "/rns/from-to/uid/" + toUid, null);
        mockMvc.perform(post(T + "/rns/uid/" + rnUid + "/complete")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // pharmacy stock credited by the CONVERTED qty (20 pharmacy-SKU)
        assertThat(pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow().getStock())
                .as("pharmacy credited 20 (= 10 store-SKU * coefficient 2)")
                .isEqualByComparingTo("20");
        // a destination PharmacyMedicineBatch was created (this path DOES create dest batches)
        var pm = pharmacyMedicineRepository.findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow();
        List<StockBatch> destBatches = stockBatchRepository.findByPharmacyMedicine(pm);
        assertThat(destBatches).as("destination pharmacy batch created at RN complete").hasSize(1);
        assertThat(destBatches.get(0).getBatchNo()).isEqualTo("TB-1");
        assertThat(destBatches.get(0).getReceivedQty()).isEqualByComparingTo("20");
    }

    @Test
    void createTo_fromNonSubmittedRo_422() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String pharmacyUid = createPharmacy(tag);
        String roUid = postUid(T + "/ros",
                "{\"pharmacyUid\":\"" + pharmacyUid + "\",\"storeUid\":\"" + storeUid + "\"}");
        // RO is PENDING (not submitted) -> TO create 422
        mockMvc.perform(post(T + "/tos/from-ro/uid/" + roUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRo_noToken_401() throws Exception {
        mockMvc.perform(post(T + "/ros").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pharmacyUid\":\"x\",\"storeUid\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== seeding =====================

    private static String uniq() {
        return "D6" + Long.toHexString(System.nanoTime()).substring(0, 9);
    }

    private String createStore(String tag) throws Exception {
        return postUid(STORES_URL, "{\"code\":\"ST-" + tag + "\",\"name\":\"Store " + tag
                + "\",\"category\":\"MAIN\",\"active\":true}");
    }

    private String createPharmacy(String tag) throws Exception {
        return postUid(PHARMACIES_URL, "{\"code\":\"PH-" + tag + "\",\"name\":\"Pharmacy " + tag
                + "\",\"category\":\"RETAIL\",\"active\":true}");
    }

    private String createItem(String tag) throws Exception {
        return postUid(ITEMS_URL, "{\"code\":\"IT-" + tag + "\",\"name\":\"Item " + tag
                + "\",\"shortName\":\"IT-" + tag + "\",\"vat\":0,\"uom\":\"CTN\",\"packSize\":1,"
                + "\"category\":\"GENERAL\",\"costPriceVatIncl\":0,\"sellingPriceVatIncl\":0,\"active\":true}");
    }

    private String createMedicine(String tag) throws Exception {
        return postUid(MEDICINES_URL, "{\"code\":\"MED-" + tag + "\",\"name\":\"Medicine " + tag
                + "\",\"type\":\"ORAL\",\"price\":100.00,\"uom\":\"BLISTER\",\"category\":\"MEDICINE\",\"active\":true}");
    }

    private void createCoefficient(String itemUid, String medicineUid, String coefficient,
                                   String itemQty, String medicineQty) throws Exception {
        mockMvc.perform(post(COEFF_URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"" + itemUid + "\",\"medicineUid\":\"" + medicineUid
                                + "\",\"itemQty\":" + itemQty + ",\"medicineQty\":" + medicineQty + "}"))
                .andExpect(status().isCreated());
    }

    private void seedStoreLot(String storeUid, String itemUid, BigDecimal qty) {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "seed");
        storeStockService.receiveBatch(storeUid, itemUid, "S-LOT", null,
                LocalDate.now().plusYears(2), qty, "Seed", ctx);
    }

    private BigDecimal storeStock(String storeUid, String itemUid) {
        return storeItemRepository.findByStoreUidAndItemUid(storeUid, itemUid).orElseThrow().getStock();
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
