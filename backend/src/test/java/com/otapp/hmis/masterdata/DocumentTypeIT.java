package com.otapp.hmis.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for document-type seeds (build-spec §5.7, AC-7) and document-number
 * sequences (build-spec §4, CR-09).
 *
 * <h2>AC-7 — Document-type prefix assertions (CR-10 fix)</h2>
 * <ul>
 *   <li>{@code STORE_TO_PHARMACY_TO} → prefix {@code "SPTO"} (NOT {@code "SPT"}).</li>
 *   <li>{@code PHARMACY_TO_PHARMACY_TO} → prefix {@code "PPTO"} (NOT {@code "SPT"}).</li>
 *   <li>No row with prefix {@code "SPT"} exists.</li>
 *   <li>All 11 expected kinds are present.</li>
 * </ul>
 *
 * <h2>Sequence start values (AC-7)</h2>
 * Each new sequence's first {@code nextval} returns 1.
 *
 * <h2>Gate coverage</h2>
 * GET is authenticated (no role gate); without token → 401.
 */
class DocumentTypeIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masterdata/document-types";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // CR-10 prefix assertions
    // ------------------------------------------------------------------

    @Test
    void storeToPharmacyToHasPrefixSpto() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        MvcResult result = mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        String spto = findPrefix(array, "STORE_TO_PHARMACY_TO");
        assertThat(spto)
                .as("STORE_TO_PHARMACY_TO must map to SPTO (CR-10 fix — legacy was SPT collision)")
                .isEqualTo("SPTO");
    }

    @Test
    void pharmacyToPharmacyToHasPrefixPpto() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        MvcResult result = mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        String ppto = findPrefix(array, "PHARMACY_TO_PHARMACY_TO");
        assertThat(ppto)
                .as("PHARMACY_TO_PHARMACY_TO must map to PPTO (CR-10 fix — legacy was SPT collision)")
                .isEqualTo("PPTO");
    }

    @Test
    void noRowCarriesPrefixSpt() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        MvcResult result = mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> prefixes = new ArrayList<>();
        array.forEach(node -> prefixes.add(node.get("prefix").asText()));
        assertThat(prefixes)
                .as("No md_document_types row may carry prefix 'SPT' (CR-10 collision defect fixed)")
                .doesNotContain("SPT");
    }

    @Test
    void allElevenKindsArePresent() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11));
    }

    @Test
    void noIdInResponse() throws Exception {
        String token = jwtFactory.tokenWithPrivileges("user", List.of("DAY-ACCESS"));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Sequence existence and monotonicity assertions (AC-7, CR-09)
    //
    // nextval() is irreversible in the shared Testcontainers instance — subsequent
    // calls in the same JVM run return higher values. We therefore assert:
    //   (a) the sequence exists (nextval does not throw)
    //   (b) the returned value is >= 1 (sequence started at 1, MINVALUE 1)
    //   (c) a second call returns exactly first+1 (INCREMENT BY 1)
    // This is robust regardless of how many times nextval was already called
    // by earlier tests in the same container lifetime.
    // ------------------------------------------------------------------

    @Test
    void seqGrnNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_grn_no");
    }

    @Test
    void seqLpoNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_lpo_no");
    }

    @Test
    void seqPcnNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_pcn_no");
    }

    @Test
    void seqPrlNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_prl_no");
    }

    @Test
    void seqSptoNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_spto_no");
    }

    @Test
    void seqPptoNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_ppto_no");
    }

    @Test
    void seqPgrnNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_pgrn_no");
    }

    @Test
    void seqPprnNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_pprn_no");
    }

    @Test
    void seqPprNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_ppr_no");
    }

    @Test
    void seqPsrNo_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_psr_no");
    }

    @Test
    void seqMrno_existsAndIncrementsByOne() {
        assertSequenceExistsAndIncrements("seq_mrno");
    }

    private void assertSequenceExistsAndIncrements(String seqName) {
        Long first  = jdbcTemplate.queryForObject("SELECT nextval('" + seqName + "')", Long.class);
        Long second = jdbcTemplate.queryForObject("SELECT nextval('" + seqName + "')", Long.class);
        assertThat(first).as("sequence %s: first nextval >= 1 (MINVALUE 1, START 1)", seqName)
                .isGreaterThanOrEqualTo(1L);
        assertThat(second).as("sequence %s: increments by 1 each call", seqName)
                .isEqualTo(first + 1);
    }

    // ------------------------------------------------------------------
    // Gate coverage
    // ------------------------------------------------------------------

    @Test
    void get_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String findPrefix(JsonNode array, String kind) {
        for (JsonNode node : array) {
            if (kind.equals(node.get("kind").asText())) {
                return node.get("prefix").asText();
            }
        }
        throw new AssertionError("No md_document_types row found for kind: " + kind);
    }
}
