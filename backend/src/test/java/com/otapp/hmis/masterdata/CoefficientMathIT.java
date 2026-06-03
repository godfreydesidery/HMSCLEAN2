package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.masterdata.application.ItemMedicineCoefficientService;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditLogRepository;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Golden-master tests for {@code ItemMedicineCoefficient} math (build-spec §5.3, AC-3).
 *
 * <ul>
 *   <li><b>AC-3a:</b> itemQty=3, medicineQty=1 → stored coefficient reads back EXACTLY
 *       {@code 0.333333} (scale 6, HALF_UP — no truncation to 4dp).
 *   <li><b>AC-3b:</b> lossless {@code convert(3, medicineQty=1, itemQty=3) = 1.000000} (qty ×
 *       medicineQty / itemQty, rounded at the end) — NOT 3 × rounded-coefficient (0.999999).
 *   <li><b>AC-3c:</b> zero itemQty or medicineQty → 400.
 *   <li><b>AC-3d:</b> duplicate (item, medicine) pair → 409.
 *   <li><b>AC-3 gate:</b> POST without token → 401; POST without ADMIN-ACCESS → 403.
 *   <li><b>AC-3 missing:</b> GET unknown uid → 404.
 *   <li><b>AC-3 update:</b> PUT recalculates coefficient; 403 without ADMIN-ACCESS; audit UPDATE row.
 * </ul>
 */
class CoefficientMathIT extends AbstractIntegrationTest {

    private static final String ITEMS        = "/api/v1/masterdata/items";
    private static final String MEDICINES    = "/api/v1/masterdata/medicines";
    private static final String COEFFICIENTS = "/api/v1/masterdata/item-medicine-coefficients";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired AuditLogRepository auditLogRepository;

    // =========================================================================
    // AC-3 gate tests (401 / 403)
    // =========================================================================

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(COEFFICIENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"X\",\"medicineUid\":\"Y\",\"itemQty\":1,\"medicineQty\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutAdminAccess_returns403() throws Exception {
        // DAY-ACCESS is not sufficient — gate requires ADMIN-ACCESS (CR-15 DEVIATION-2)
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"X\",\"medicineUid\":\"Y\",\"itemQty\":1,\"medicineQty\":1}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // AC-3: GET unknown uid → 404
    // =========================================================================

    @Test
    void getByUid_unknownUid_returns404() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        mockMvc.perform(get(COEFFICIENTS + "/uid/NONEXISTENT-UID-00000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // AC-3a + AC-3b: coefficient precision golden-master + audit CREATE row
    // =========================================================================

    @Test
    void coefficient_oneThird_storedAndReadBackAsScale6_andMultiplyReturnsExact() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));

        String itemUid = createItem(token, "COEF-ITEM-MATH", "Coef Item Math");
        String medUid  = createMedicine(token, "COEF-MED-MATH", "Coef Medicine Math");

        // Create coefficient: itemQty=3, medicineQty=1  →  coefficient = 1/3
        String body = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":3,"medicineQty":1}
                """.formatted(itemUid, medUid);
        MvcResult result = mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coefficient").isNumber())
                .andReturn();

        String uid = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("uid").asText();

        // Audit: CREATE row must exist
        assertThat(auditLogRepository.findByEntityTypeOrderByOccurredAtAsc("masterdata.ItemMedicineCoefficient"))
                .as("audit_logs must contain a CREATE row for the new coefficient uid")
                .anyMatch(r -> r.getEntityUid().equals(uid) && r.getAction() == AuditAction.CREATE);

        // Read back and verify exact 6-decimal precision
        MvcResult getResult = mockMvc.perform(get(COEFFICIENTS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String coefStr = objectMapper.readTree(getResult.getResponse().getContentAsString())
                .get("coefficient").asText();
        BigDecimal stored = new BigDecimal(coefStr);

        // AC-3a: stored coefficient must be EXACTLY 0.333333 (scale 6, HALF_UP)
        BigDecimal expected = new BigDecimal("1").divide(new BigDecimal("3"), 6, RoundingMode.HALF_UP);
        assertThat(stored.compareTo(expected))
                .as("Stored coefficient must equal 0.333333 (scale 6, HALF_UP)")
                .isZero();
        assertThat(stored.scale())
                .as("Coefficient scale must be exactly 6")
                .isEqualTo(6);

        // AC-3b: converting qty 3 across the unit boundary yields EXACTLY 1.000000 (not 0.999999).
        // This is the LOSSLESS path (qty × medicineQty / itemQty, rounded at the end), NOT
        // multiplication by the pre-rounded coefficient — which would give 0.999999 and break parity
        // with the legacy double arithmetic (3.0 × (1.0/3.0) == 1.0).
        BigDecimal converted = ItemMedicineCoefficientService.convert(
                new BigDecimal("3"), new BigDecimal("1"), new BigDecimal("3"));
        assertThat(converted)
                .as("convert(3, medicineQty=1, itemQty=3) must equal exactly 1.000000, not 0.999999")
                .isEqualByComparingTo("1.000000");
        // And prove the naive rounded-coefficient path does NOT (documents why convert() exists).
        assertThat(new BigDecimal("3").multiply(stored))
                .as("multiplying by the rounded coefficient loses parity (0.999999)")
                .isLessThan(new BigDecimal("1.000000"));
    }

    // =========================================================================
    // Static unit test for the computation method itself (no DB required)
    // =========================================================================

    @Test
    void computeCoefficient_oneThird_returnsExact0point333333() {
        BigDecimal result = ItemMedicineCoefficientService.computeCoefficient(
                new BigDecimal("1"), new BigDecimal("3"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.333333"));
        assertThat(result.scale()).isEqualTo(6);
    }

    @Test
    void computeCoefficient_oneHalf_returnsExact0point500000() {
        BigDecimal result = ItemMedicineCoefficientService.computeCoefficient(
                new BigDecimal("1"), new BigDecimal("2"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.500000"));
    }

    // =========================================================================
    // AC-3c: zero quantities → 400
    // =========================================================================

    @Test
    void create_withZeroItemQty_returns400() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String itemUid = createItem(token, "COEF-ITEM-ZERO-IQ", "Coef Item Zero IQ");
        String medUid  = createMedicine(token, "COEF-MED-ZERO-IQ", "Coef Med Zero IQ");

        String body = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":0,"medicineQty":1}
                """.formatted(itemUid, medUid);
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withZeroMedicineQty_returns400() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String itemUid = createItem(token, "COEF-ITEM-ZERO-MQ", "Coef Item Zero MQ");
        String medUid  = createMedicine(token, "COEF-MED-ZERO-MQ", "Coef Med Zero MQ");

        String body = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":1,"medicineQty":0}
                """.formatted(itemUid, medUid);
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withNegativeItemQty_returns400() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String itemUid = createItem(token, "COEF-ITEM-NEG-IQ", "Coef Item Neg IQ");
        String medUid  = createMedicine(token, "COEF-MED-NEG-IQ", "Coef Med Neg IQ");

        String body = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":-1,"medicineQty":1}
                """.formatted(itemUid, medUid);
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // AC-3d: duplicate (item, medicine) pair → 409
    // =========================================================================

    @Test
    void create_duplicatePair_returns409() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String itemUid = createItem(token, "COEF-ITEM-DUP", "Coef Item Dup");
        String medUid  = createMedicine(token, "COEF-MED-DUP", "Coef Med Dup");

        String body = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":2,"medicineQty":1}
                """.formatted(itemUid, medUid);

        // First create succeeds
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second create with same (item, medicine) → 409
        mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:hmis:error:conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    // =========================================================================
    // AC-3 UPDATE path: coefficient recalculated; 403 without ADMIN-ACCESS; audit UPDATE row
    // =========================================================================

    @Test
    void update_withAdminAccess_recomputesCoefficientAndReturns200() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS"));
        String itemUid = createItem(token, "COEF-ITEM-UPD", "Coef Item Upd");
        String medUid  = createMedicine(token, "COEF-MED-UPD", "Coef Med Upd");

        // Create: itemQty=3, medicineQty=1 → coefficient=0.333333
        String createBody = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":3,"medicineQty":1}
                """.formatted(itemUid, medUid);
        MvcResult created = mockMvc.perform(post(COEFFICIENTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        String uid = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("uid").asText();

        // Update: itemQty=2, medicineQty=1 → coefficient=0.500000
        String updateBody = """
                {"itemUid":"%s","medicineUid":"%s","itemQty":2,"medicineQty":1}
                """.formatted(itemUid, medUid);
        MvcResult updated = mockMvc.perform(put(COEFFICIENTS + "/uid/" + uid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        // Verify recomputed coefficient = 0.500000 using BigDecimal comparison (AC-3b style)
        String updatedCoef = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("coefficient").asText();
        assertThat(new BigDecimal(updatedCoef))
                .isEqualByComparingTo(new BigDecimal("0.500000"));

        // Audit: UPDATE row written
        assertThat(auditLogRepository.findByEntityUidOrderByOccurredAtAsc(uid))
                .as("audit_logs must contain both CREATE and UPDATE rows for the coefficient uid")
                .anyMatch(r -> r.getAction() == AuditAction.CREATE)
                .anyMatch(r -> r.getAction() == AuditAction.UPDATE);
    }

    @Test
    void update_withoutAdminAccess_returns403() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("clerk", List.of("DAY-ACCESS"));
        mockMvc.perform(put(COEFFICIENTS + "/uid/SOMEUID")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemUid\":\"X\",\"medicineUid\":\"Y\",\"itemQty\":1,\"medicineQty\":1}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String createItem(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","barcode":null,"shortName":null,"commonName":null,
                 "vat":0.00,"uom":null,"packSize":1.000000,"category":null,
                 "costPriceVatIncl":0.00,"sellingPriceVatIncl":0.00,
                 "active":true,"ingredients":""}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(ITEMS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createMedicine(String token, String code, String name) throws Exception {
        String body = """
                {"code":"%s","name":"%s","description":null,"type":"ORAL",
                 "price":10.00,"uom":"TABLET","category":"MEDICINE","active":false}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post(MEDICINES)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("uid").asText();
    }
}
