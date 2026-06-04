package com.otapp.hmis.clinical;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration test for the inc-06A C7 attachment size cap (ITEM5).
 *
 * <p>Stand-alone IT with a TINY configured cap ({@code hmis.attachments.max-file-size-bytes=10})
 * so an 11-byte upload trips the cap without allocating a 10 MiB test fixture. A {@code @TestPropertySource}
 * is used because the cap is a bound {@link com.otapp.hmis.shared.storage.AttachmentStorageProperties}
 * value — overriding it here keeps the main {@link LabTestIT}/{@link RadiologyIT} suites at the
 * realistic 10 MiB default.
 *
 * <p>Legacy cap: PatientServiceImpl.java:2842-2844 (10485760 bytes).
 */
@TestPropertySource(properties = "hmis.attachments.max-file-size-bytes=10")
class AttachmentUploadCapIT extends AbstractIntegrationTest {

    private static final String BASE         = "/api/v1/clinical";
    private static final String CONSULT_BASE = BASE + "/consultations/uid/";
    private static final String LAB_BASE     = BASE + "/lab-tests";
    private static final String RAD_BASE     = BASE + "/radiologies";
    private static final String PRICES_URL   = "/api/v1/masterdata/service-prices";
    private static final String LAB_TYPES    = "/api/v1/masterdata/lab-test-types";
    private static final String RAD_TYPES    = "/api/v1/masterdata/radiology-types";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired ConsultationRepository consultationRepository;
    @Autowired BusinessDayService businessDayService;

    private String adminToken;
    private String dayUid;

    @BeforeEach
    void setUp() {
        adminToken = jwtFactory.tokenWithPrivileges("admin",
                List.of("ADMIN-ACCESS", "USER-ALL", "PATIENT-ALL", "BILL-A"));
        try {
            dayUid = businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            dayUid = businessDayService.currentUid();
        }
    }

    private static String uniq() {
        return "CAP" + Long.toHexString(System.nanoTime()).substring(0, 9);
    }

    private static String fakeUid(String prefix, String tag) {
        String base = (prefix + tag).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    @Test
    void uploadAttachment_overCap_422() throws Exception {
        String tag = uniq();

        // Lab type + cash price.
        String labTypeUid = createLabTestType(tag);
        seedPrice(labTypeUid);

        // Seed an INSURANCE (settled) consultation directly, drive a lab order to COLLECTED.
        String consultUid = seedConsultation(tag);
        // Order via endpoint.
        MvcResult orderResult = mockMvc.perform(post(CONSULT_BASE + consultUid + "/lab-tests")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labTestTypeUid\":\"" + labTypeUid + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String labUid = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .get("uid").asText();
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(post(LAB_BASE + "/uid/" + labUid + "/collect")
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

        // 11 bytes > 10-byte cap → 422 verbatim.
        byte[] tooBig = "12345678901".getBytes(StandardCharsets.UTF_8); // 11 bytes
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooBig);
        mockMvc.perform(multipart(LAB_BASE + "/uid/" + labUid + "/attachments/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("File exceeds maximum file size allowed"));
    }

    @Test
    void uploadRadiologyAttachment_overCap_422() throws Exception {
        String tag = uniq();
        String radTypeUid = createRadiologyType(tag);
        seedPrice(radTypeUid, "RADIOLOGY");

        String consultUid = seedConsultation(tag);
        MvcResult orderResult = mockMvc.perform(post(CONSULT_BASE + consultUid + "/radiologies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"radiologyTypeUid\":\"" + radTypeUid + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String radUid = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .get("uid").asText();
        // Radiology attach-gate is ACCEPTED.
        mockMvc.perform(post(RAD_BASE + "/uid/" + radUid + "/accept")
                        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

        byte[] tooBig = "12345678901".getBytes(StandardCharsets.UTF_8); // 11 bytes > 10-byte cap
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooBig);
        mockMvc.perform(multipart(RAD_BASE + "/uid/" + radUid + "/attachments/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("File exceeds maximum file size allowed"));
    }

    private String createRadiologyType(String tag) throws Exception {
        String body = """
                {"code":"RT-%s","name":"RadType %s","description":null,"price":8000.00,"active":true}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(RAD_TYPES)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private String createLabTestType(String tag) throws Exception {
        String body = """
                {"code":"LTT-%s","name":"Lab Type %s","description":null,"range":null,
                 "uom":null,"price":1000.00,"active":false}
                """.formatted(tag, tag);
        MvcResult r = mockMvc.perform(post(LAB_TYPES)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("uid").asText();
    }

    private void seedPrice(String serviceUid) throws Exception {
        seedPrice(serviceUid, "LAB_TEST");
    }

    private void seedPrice(String serviceUid, String kind) throws Exception {
        String body = """
                {"planUid":null,"kind":"%s","serviceUid":"%s","currency":"TZS",
                 "amount":1000.00,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(kind, serviceUid);
        mockMvc.perform(post(PRICES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(201),
                        org.hamcrest.Matchers.is(409))));
    }

    /** Seed an INSURANCE (settled) consultation directly so the lab order is settled. */
    private String seedConsultation(String tag) {
        Consultation c = new Consultation(
                fakeUid("PAT", tag), null, fakeUid("CLN", tag), fakeUid("DOC", tag),
                fakeUid("BIL", tag), PaymentMode.INSURANCE, false, true,
                "MEM-" + tag, fakeUid("PLN", tag), dayUid);
        c.open();  // PENDING → IN_PROCESS so lab orders can be placed
        return consultationRepository.save(c).getUid();
    }
}
