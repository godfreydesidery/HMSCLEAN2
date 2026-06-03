package com.otapp.hmis.registration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * C5 integration tests — paginated search (no/name/membership), get-by-uid, last-visit
 * (build-spec §6, CR-07/CR-08; reads authenticated-only per CR-04).
 *
 * <p>Uses a unique per-run search token in surnames so pagination/result-count assertions are
 * isolated from other tests' committed patients on the shared singleton container.
 */
class PatientSearchIT extends AbstractIntegrationTest {

    private static final String PATIENTS_URL = "/api/v1/patients";
    private static final String PRICES_URL   = "/api/v1/masterdata/service-prices";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;   // PATIENT-ALL — register/update
    private String readToken;    // authenticated, NO patient privilege — proves reads are ungated

    @BeforeEach
    void setUp() throws Exception {
        adminToken = jwtFactory.tokenWithPrivileges("admin", List.of("ADMIN-ACCESS", "BILL-A", "PATIENT-ALL"));
        readToken  = jwtFactory.tokenWithPrivileges("reader", List.of("DAY-ACCESS"));
        ensureDayOpen();
        ensureRegistrationCashPrice();
    }

    @Test
    void search_byNameSubstring_findsPatient() throws Exception {
        String tok = uniqueToken();
        String uid = registerCash("Alice", "Zephyr" + tok);

        mockMvc.perform(get(PATIENTS_URL + "?query=Zephyr" + tok)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].uid").value(uid));
    }

    @Test
    void search_byMrNo_findsPatient() throws Exception {
        String uid = registerCash("Bob", "Mrno" + uniqueToken());
        String mrn = getPatient(uid).get("no").asText();

        // mrNo is a shared global sequence (substring collisions possible) — assert the mrn is
        // present in the result set rather than at a specific index.
        mockMvc.perform(get(PATIENTS_URL + "?query=" + mrn)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].no", org.hamcrest.Matchers.hasItem(mrn)));
    }

    @Test
    void search_byMembershipNo_findsPatient_reg1() throws Exception {
        // Register CASH, then flip to INSURANCE to set a membershipNo (no plan/price needed for the flip)
        String uid = registerCash("Carol", "Member" + uniqueToken());
        String mem = "MEMSRCH" + uniqueToken();
        mockMvc.perform(patch(PATIENTS_URL + "/uid/" + uid + "/payment-type")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentType\":\"INSURANCE\",\"insurancePlanUid\":\"PLAN00000000000000000099\",\"membershipNo\":\"" + mem + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATIENTS_URL + "?query=" + mem)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].uid").value(uid));
    }

    @Test
    void search_paginated() throws Exception {
        String tok = uniqueToken();
        registerCash("P1", "Page" + tok);
        registerCash("P2", "Page" + tok);
        registerCash("P3", "Page" + tok);

        mockMvc.perform(get(PATIENTS_URL + "?query=Page" + tok + "&page=0&size=2")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void getByUid_200_withLastVisit() throws Exception {
        String uid = registerCash("Dave", "Detail" + uniqueToken());

        mockMvc.perform(get(PATIENTS_URL + "/uid/" + uid)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.lastVisitAt").isNotEmpty())  // FIRST visit created at registration
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void lastVisit_200() throws Exception {
        String uid = registerCash("Eve", "Lastv" + uniqueToken());
        mockMvc.perform(get(PATIENTS_URL + "/uid/" + uid + "/last-visit")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastVisitAt").isNotEmpty());
    }

    @Test
    void reads_areAuthenticatedOnly_ungated() throws Exception {
        // readToken has NO patient privilege, yet reads succeed (CR-04 parity)
        mockMvc.perform(get(PATIENTS_URL + "?query=nobody")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk());
    }

    @Test
    void reads_401_whenNoToken() throws Exception {
        mockMvc.perform(get(PATIENTS_URL + "?query=x")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATIENTS_URL + "/uid/SOMEUID")).andExpect(status().isUnauthorized());
    }

    @Test
    void getByUid_unknown_404() throws Exception {
        mockMvc.perform(get(PATIENTS_URL + "/uid/NONEXISTENTUID000000000099")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private static String uniqueToken() {
        return "S" + Long.toHexString(System.nanoTime());
    }

    private String registerCash(String first, String last) throws Exception {
        String body = """
                {"firstName":"%s","lastName":"%s","dateOfBirth":"1990-06-15","gender":"MALE","paymentType":"CASH"}
                """.formatted(first, last);
        MvcResult r = mockMvc.perform(post(PATIENTS_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode getPatient(String uid) throws Exception {
        MvcResult r = mockMvc.perform(get(PATIENTS_URL + "/uid/" + uid)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private void ensureDayOpen() {
        try {
            businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
        }
    }

    private void ensureRegistrationCashPrice() throws Exception {
        String reqBody = """
                {"planUid":null,"kind":"REGISTRATION","serviceUid":null,"currency":"TZS",
                 "amount":500.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """;
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(reqBody))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }
}
