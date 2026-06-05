package com.otapp.hmis.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.pharmacy.application.PharmacySaleOrderService;
import com.otapp.hmis.pharmacy.domain.OtcOrderStatus;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.PharmacySaleOrderRepository;
import com.otapp.hmis.pharmacy.domain.StockBatchRepository;
import com.otapp.hmis.pharmacy.domain.StockMovementRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-08a chunk 4 — OTC walk-in PharmacySaleOrder lifecycle.
 *
 * <p>Drives the full vertical slice on PostgreSQL 16: create→PENDING; add detail (flat-CASH bill
 * vs GENERAL); pay bill→APPROVED (BillSettledEvent seam) + detail PAID; whole-order dispense→GIVEN +
 * aggregate stock decrement + DISPENSE stock-card "Issued in sale: id ..." + NO StockBatch touch;
 * dispense-before-APPROVED 422; cancel; archive; 24h auto-sweep.
 */
class PharmacySaleOrderIT extends AbstractIntegrationTest {

    private static final String MEDICINES_URL = "/api/v1/masterdata/medicines";
    private static final String PHARMACIES_URL = "/api/v1/masterdata/pharmacies";
    private static final String SO_BASE = "/api/v1/pharmacy/sale-orders";
    private static final String PAY_URL = "/api/v1/billing/payments";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PharmacySaleOrderService saleOrderService;
    @Autowired PharmacySaleOrderRepository orderRepository;
    @Autowired PharmacyMedicineRepository pharmacyMedicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired com.otapp.hmis.pharmacy.application.StockService stockService;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("pharmacist",
                List.of("ADMIN-ACCESS", "MEDICINE-ALL", "BILL-A", "MEDICINE_STOCK-UPDATE"));
        dayUid = ensureDayOpen();
    }

    @Test
    void otcLifecycle_create_addDetail_pay_approve_dispense() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag, "120.00");
        seedLot(pharmacyUid, medicineUid, new BigDecimal("40"));

        // create order -> PENDING
        String orderUid = createOrder(pharmacyUid, tag);
        assertThat(orderRepository.findByUid(orderUid).orElseThrow().getStatus())
                .isEqualTo(OtcOrderStatus.PENDING);

        // add detail -> bill created flat CASH against GENERAL; detail NOT-GIVEN/UNPAID
        String detailBody = """
                {"medicineUid":"%s","qty":10,"dosage":"1 OD","frequency":"OD","route":"PO","days":"3"}
                """.formatted(medicineUid);
        MvcResult detRes = mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/details")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(detailBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT-GIVEN"))
                .andExpect(jsonPath("$.payStatus").value("UNPAID"))
                .andReturn();
        String billUid = objectMapper.readTree(detRes.getResponse().getContentAsString())
                .get("patientBillUid").asText();
        assertThat(billUid).isNotBlank();

        // pay the bill -> BillSettledEvent -> order APPROVED + detail PAID  (amount = 120 * 10 = 1200)
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":1200.00,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid);
        mockMvc.perform(post(PAY_URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());
        assertThat(orderRepository.findByUid(orderUid).orElseThrow().getStatus())
                .as("order APPROVED after bill paid (event seam)")
                .isEqualTo(OtcOrderStatus.APPROVED);

        // whole-order dispense -> details GIVEN, aggregate stock 40-10=30, DISPENSE card, NO batch touch
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/dispense?pharmacyUid=" + pharmacyUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.details[0].status").value("GIVEN"));

        assertThat(pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow().getStock())
                .isEqualByComparingTo("30");

        var pm = pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid).orElseThrow();
        var movements = stockMovementRepository
                .findByPharmacyUidAndMedicineUidOrderByOccurredAtAsc(pharmacyUid, medicineUid);
        var dispenseRows = movements.stream()
                .filter(m -> m.getMovementType().name().equals("DISPENSE")).toList();
        assertThat(dispenseRows).hasSize(1);
        assertThat(dispenseRows.get(0).getReference()).startsWith("Issued in sale: id ");
        // NO batch consumed by OTC: the seeded lot still has its full 40 (only TRANSFER_IN seeding lot)
        BigDecimal remaining = stockBatchRepository.findByPharmacyMedicine(pm).stream()
                .map(b -> b.getRemainingQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(remaining).as("OTC dispense does NOT consume FEFO batches (Q9)")
                .isEqualByComparingTo("40");
    }

    @Test
    void dispense_beforeApproved_422() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag, "50.00");
        seedLot(pharmacyUid, medicineUid, new BigDecimal("20"));
        String orderUid = createOrder(pharmacyUid, tag);
        // add a detail but DON'T pay -> order stays PENDING
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/details")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid + "\",\"qty\":5}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/dispense?pharmacyUid=" + pharmacyUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Order not approved"));
    }

    @Test
    void addDetail_toNonPending_422() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String orderUid = createOrder(pharmacyUid, tag);
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/cancel")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // adding to a CANCELED order -> 422
        String medicineUid = createMedicine(tag, "50.00");
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/details")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid + "\",\"qty\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Only pending orders can be updated"));
    }

    @Test
    void cancel_pending_ok_then_cancelAgain_422() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String orderUid = createOrder(pharmacyUid, tag);
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Only pending orders can be canceled"));
    }

    @Test
    void autoCancel_stalePendingOrder() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String orderUid = createOrder(pharmacyUid, tag);
        // sweep with now = +25h -> the PENDING order auto-cancels
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "sweeper");
        int swept = saleOrderService.cancelStaleOrders(Instant.now().plus(25, ChronoUnit.HOURS), ctx);
        assertThat(swept).isGreaterThanOrEqualTo(1);
        assertThat(orderRepository.findByUid(orderUid).orElseThrow().getStatus())
                .isEqualTo(OtcOrderStatus.CANCELED);
    }

    @Test
    void deleteDetail_unpaidNotGiven_ok() throws Exception {
        String tag = uniq();
        String pharmacyUid = createPharmacy(tag);
        String medicineUid = createMedicine(tag, "70.00");
        String orderUid = createOrder(pharmacyUid, tag);
        MvcResult detRes = mockMvc.perform(post(SO_BASE + "/uid/" + orderUid + "/details")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicineUid\":\"" + medicineUid + "\",\"qty\":5}"))
                .andExpect(status().isCreated()).andReturn();
        String detailUid = objectMapper.readTree(detRes.getResponse().getContentAsString())
                .get("uid").asText();
        mockMvc.perform(delete(SO_BASE + "/details/uid/" + detailUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void create_noToken_401() throws Exception {
        mockMvc.perform(post(SO_BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pharmacyUid\":\"x\",\"pharmacistUid\":\"y\",\"customerName\":\"Z\"}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private static String uniq() {
        return "D4" + Long.toHexString(System.nanoTime()).substring(0, 9);
    }

    private String createPharmacy(String tag) throws Exception {
        String body = """
                {"code":"PH-%s","name":"Pharmacy %s","category":"RETAIL","active":true}
                """.formatted(tag, tag);
        MvcResult res = mockMvc.perform(post(PHARMACIES_URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createMedicine(String tag, String price) throws Exception {
        String body = """
                {"code":"MED-%s","name":"Medicine %s","type":"ORAL","price":%s,"uom":"TAB",
                 "category":"MEDICINE","active":true}
                """.formatted(tag, tag, price);
        MvcResult res = mockMvc.perform(post(MEDICINES_URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createOrder(String pharmacyUid, String tag) throws Exception {
        String body = """
                {"pharmacyUid":"%s","pharmacistUid":"PHARMACIST%s","customerName":"Walk-in %s"}
                """.formatted(pharmacyUid, tag.substring(0, 6), tag);
        MvcResult res = mockMvc.perform(post(SO_BASE)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uid").asText();
    }

    private void seedLot(String pharmacyUid, String medicineUid, BigDecimal qty) {
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "seed");
        stockService.receiveLot(pharmacyUid, medicineUid, "OTC-LOT", null,
                LocalDate.now().plusYears(1), qty, "Seed lot", ctx);
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
