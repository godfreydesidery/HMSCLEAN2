package com.otapp.hmis.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.inventory.domain.GoodsReceivedNoteRepository;
import com.otapp.hmis.inventory.domain.LocalPurchaseOrderRepository;
import com.otapp.hmis.inventory.domain.LpoStatus;
import com.otapp.hmis.inventory.domain.PurchaseRepository;
import com.otapp.hmis.inventory.domain.StoreItemRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-08b chunk 5 — LPO → GRN procurement (PostgreSQL 16).
 *
 * <p>Drives: seed store/supplier/item + a (supplier,item) price; LPO create→addDetail→verify→
 * approve→submit; GRN create (from SUBMITTED LPO)→receivedQty→addBatch→verify→approve; asserts the
 * atomic approve effects (store stock credited, Purchase row, LPO→RECEIVED) + the batch-sum guard +
 * the two-way (received≤ordered) guard + state-machine + RBAC.
 */
class ProcurementIT extends AbstractIntegrationTest {

    private static final String STORES_URL = "/api/v1/masterdata/stores";
    private static final String SUPPLIERS_URL = "/api/v1/masterdata/suppliers";
    private static final String ITEMS_URL = "/api/v1/masterdata/items";
    private static final String PRICES_URL = "/api/v1/masterdata/supplier-item-prices";
    private static final String LPO_URL = "/api/v1/inventory/lpos";
    private static final String GRN_URL = "/api/v1/inventory/grns";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired StoreItemRepository storeItemRepository;
    @Autowired LocalPurchaseOrderRepository lpoRepository;
    @Autowired GoodsReceivedNoteRepository grnRepository;
    @Autowired PurchaseRepository purchaseRepository;

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("buyer", List.of(
                "ADMIN-ACCESS", "ITEM-ALL", "SUPPLIER-ALL", "SUPPLIER_PRICE_LIST-ALL",
                "LOCAL_PURCHASE_ORDER-ALL", "GOODS_RECEIVED_NOTE-ALL", "GOODS_RECEIVED_NOTE-CREATE",
                "GOODS_RECEIVED_NOTE-UPDATE", "GOODS_RECEIVED_NOTE-APPROVE"));
        ensureDayOpen();
    }

    @Test
    void fullProcurement_lpoToGrnApprove_creditsStock_writesPurchase_flipsLpoReceived() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String supplierUid = createSupplier(tag);
        String itemUid = createItem(tag);
        createSupplierItemPrice(supplierUid, itemUid, "1000.00");

        // LPO create -> add detail (price copied = 1000) -> verify -> approve -> submit
        String lpoUid = createLpo(storeUid, supplierUid);
        addLpoDetail(lpoUid, itemUid, "10");
        transition(LPO_URL, lpoUid, "verify");
        transition(LPO_URL, lpoUid, "approve");
        transition(LPO_URL, lpoUid, "submit");
        assertThat(lpoRepository.findByUid(lpoUid).orElseThrow().getStatus())
                .isEqualTo(LpoStatus.SUBMITTED);

        // GRN create from SUBMITTED LPO (seeds one NOT-VERIFIED detail, orderedQty=10)
        MvcResult grnRes = mockMvc.perform(post(GRN_URL + "?lpoUid=" + lpoUid + "&storeUid=" + storeUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.details[0].orderedQty").value(10))
                .andReturn();
        JsonNode grn = objectMapper.readTree(grnRes.getResponse().getContentAsString());
        String grnUid = grn.get("uid").asText();
        String detailUid = grn.get("details").get(0).get("uid").asText();

        // enter receivedQty=10, add a batch of 10, verify (batch-sum==received), approve
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/received-qty?receivedQty=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/batches")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-1\",\"qty\":10,\"expiryDate\":\"2030-01-01\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details[0].status").value("VERIFIED"));
        mockMvc.perform(post(GRN_URL + "/uid/" + grnUid + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // atomic approve effects:
        assertThat(storeItemRepository.findByStoreUidAndItemUid(storeUid, itemUid).orElseThrow().getStock())
                .as("store stock credited by receivedQty").isEqualByComparingTo("10");
        assertThat(lpoRepository.findByUid(lpoUid).orElseThrow().getStatus())
                .as("LPO flipped to RECEIVED by GRN approve").isEqualTo(LpoStatus.RECEIVED);
        var grnEntity = grnRepository.findByUid(grnUid).orElseThrow();
        assertThat(purchaseRepository.findByGoodsReceivedNote(grnEntity))
                .as("one Purchase ledger row, amount = received*price = 10*1000")
                .hasSize(1);
        assertThat(purchaseRepository.findByGoodsReceivedNote(grnEntity).get(0).getAmount())
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void grnVerify_batchSumMismatch_422() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String supplierUid = createSupplier(tag);
        String itemUid = createItem(tag);
        createSupplierItemPrice(supplierUid, itemUid, "500.00");
        String lpoUid = createLpo(storeUid, supplierUid);
        addLpoDetail(lpoUid, itemUid, "8");
        transition(LPO_URL, lpoUid, "verify");
        transition(LPO_URL, lpoUid, "approve");
        transition(LPO_URL, lpoUid, "submit");
        MvcResult grnRes = mockMvc.perform(post(GRN_URL + "?lpoUid=" + lpoUid + "&storeUid=" + storeUid)
                        .header("Authorization", "Bearer " + token)).andReturn();
        String detailUid = objectMapper.readTree(grnRes.getResponse().getContentAsString())
                .get("details").get(0).get("uid").asText();
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/received-qty?receivedQty=8")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // batch of 5 != received 8 -> verify 422
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/batches")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"batchNo\":\"B-x\",\"qty\":5}")).andExpect(status().isCreated());
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Batch quantities are not equal to total received quantities"));
    }

    @Test
    void grnReceivedQty_exceedsOrdered_422() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String supplierUid = createSupplier(tag);
        String itemUid = createItem(tag);
        createSupplierItemPrice(supplierUid, itemUid, "300.00");
        String lpoUid = createLpo(storeUid, supplierUid);
        addLpoDetail(lpoUid, itemUid, "6");
        transition(LPO_URL, lpoUid, "verify");
        transition(LPO_URL, lpoUid, "approve");
        transition(LPO_URL, lpoUid, "submit");
        MvcResult grnRes = mockMvc.perform(post(GRN_URL + "?lpoUid=" + lpoUid + "&storeUid=" + storeUid)
                        .header("Authorization", "Bearer " + token)).andReturn();
        String detailUid = objectMapper.readTree(grnRes.getResponse().getContentAsString())
                .get("details").get(0).get("uid").asText();
        mockMvc.perform(post(GRN_URL + "/details/uid/" + detailUid + "/received-qty?receivedQty=9")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Received qty must not exceed ordered qty"));
    }

    @Test
    void lpoVerify_noItems_422() throws Exception {
        String tag = uniq();
        String lpoUid = createLpo(createStore(tag), createSupplier(tag));
        // no detail added -> verify 422
        mockMvc.perform(post(LPO_URL + "/uid/" + lpoUid + "/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not verify. Order has no items"));
    }

    @Test
    void lpoAddDetail_supplierWithoutPrice_422() throws Exception {
        String tag = uniq();
        String supplierUid = createSupplier(tag);
        String itemUid = createItem(tag);
        // NO supplier-item price seeded
        String lpoUid = createLpo(createStore(tag), supplierUid);
        mockMvc.perform(post(LPO_URL + "/uid/" + lpoUid + "/details")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"" + itemUid + "\",\"qty\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Item not valid for this supplier"));
    }

    @Test
    void grnCreate_lpoNotSubmitted_422() throws Exception {
        String tag = uniq();
        String storeUid = createStore(tag);
        String supplierUid = createSupplier(tag);
        String itemUid = createItem(tag);
        createSupplierItemPrice(supplierUid, itemUid, "100.00");
        String lpoUid = createLpo(storeUid, supplierUid);
        addLpoDetail(lpoUid, itemUid, "2");
        // LPO is PENDING (not submitted) -> GRN create 422
        mockMvc.perform(post(GRN_URL + "?lpoUid=" + lpoUid + "&storeUid=" + storeUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not create GRN. Local Purchase Order not submitted"));
    }

    @Test
    void lpoCreate_noToken_401() throws Exception {
        mockMvc.perform(post(LPO_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeUid\":\"x\",\"supplierUid\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String uniq() {
        return "D5" + Long.toHexString(System.nanoTime()).substring(0, 9);
    }

    private String createStore(String tag) throws Exception {
        return postUid(STORES_URL, """
                {"code":"ST-%s","name":"Store %s","category":"MAIN","active":true}
                """.formatted(tag, tag));
    }

    private String createSupplier(String tag) throws Exception {
        return postUid(SUPPLIERS_URL, """
                {"code":"SUP-%s","name":"Supplier %s","contactName":"C","active":true}
                """.formatted(tag, tag));
    }

    private String createItem(String tag) throws Exception {
        return postUid(ITEMS_URL, """
                {"code":"IT-%s","name":"Item %s","shortName":"IT-%s","vat":0,"uom":"EA",
                 "packSize":1,"category":"GENERAL","costPriceVatIncl":0,"sellingPriceVatIncl":0,
                 "active":true}
                """.formatted(tag, tag, tag));
    }

    private void createSupplierItemPrice(String supplierUid, String itemUid, String price) throws Exception {
        mockMvc.perform(post(PRICES_URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierUid\":\"" + supplierUid + "\",\"itemUid\":\"" + itemUid
                                + "\",\"price\":" + price + ",\"terms\":\"NET30\",\"active\":true}"))
                .andExpect(status().isCreated());
    }

    private String createLpo(String storeUid, String supplierUid) throws Exception {
        return postUid(LPO_URL, "{\"storeUid\":\"" + storeUid + "\",\"supplierUid\":\"" + supplierUid + "\"}");
    }

    private void addLpoDetail(String lpoUid, String itemUid, String qty) throws Exception {
        mockMvc.perform(post(LPO_URL + "/uid/" + lpoUid + "/details")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"" + itemUid + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isCreated());
    }

    private void transition(String base, String uid, String action) throws Exception {
        mockMvc.perform(post(base + "/uid/" + uid + "/" + action)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private String postUid(String url, String body) throws Exception {
        MvcResult res = mockMvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }
}
