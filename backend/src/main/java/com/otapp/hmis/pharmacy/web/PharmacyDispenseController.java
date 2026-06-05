package com.otapp.hmis.pharmacy.web;

import com.otapp.hmis.clinical.api.PrescriptionPatientType;
import com.otapp.hmis.clinical.api.PrescriptionView;
import com.otapp.hmis.pharmacy.application.PharmacyDispenseService;
import com.otapp.hmis.pharmacy.application.PharmacyStockQueryService;
import com.otapp.hmis.pharmacy.application.dto.DispenseRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pharmacy dispensing REST surface (inc-08a chunk 3).
 *
 * <p><strong>Authenticated-only (no {@code @PreAuthorize}, AC-RBAC-01/05):</strong> the legacy
 * clinical {@code issue_medicine} terminal and the worklist were UNGATED; the global
 * deny-by-default floor (ADR-0006 hardening) requires a valid JWT. The "177" figure is BANNED — the
 * dispense flow carries no role-level code (Q5). pharmacyUid is the required, server-validated
 * stock-source selector (Q2) — supplied in the request body / query param, NO affiliation check.
 *
 * <p>No {@code @Transactional} on the controller — the service owns the boundary (ADR-0014 §5).
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
@RequiredArgsConstructor
public class PharmacyDispenseController {

    private final PharmacyDispenseService dispenseService;
    private final PharmacyStockQueryService queryService;
    private final BusinessDayService businessDayService;

    /**
     * Dispense a clinical prescription from a pharmacy: NOT-GIVEN → GIVEN, stock decremented,
     * stock-card OUT row, FEFO lot-trace. NO terminal bill-status check (Q1).
     */
    @PostMapping("/prescriptions/uid/{uid}/dispense")
    public PrescriptionView dispense(
            @PathVariable("uid") String prescriptionUid,
            @Valid @RequestBody DispenseRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return dispenseService.dispense(prescriptionUid, request, ctxFrom(jwt));
    }

    /**
     * Pharmacy dispense worklist — NOT-GIVEN prescriptions admitted by the legacy bill-status FILTER
     * for the patient type (PAID|COVERED; +VERIFIED for INPATIENT). A FILTER, never a hard gate (Q1).
     *
     * @param patientType OUTPATIENT / OUTSIDER / INPATIENT (required)
     * @param patientUid  optional scope to a single patient
     */
    @GetMapping("/prescriptions/worklist")
    public List<PrescriptionView> worklist(
            @RequestParam("patientType") PrescriptionPatientType patientType,
            @RequestParam(value = "patientUid", required = false) String patientUid,
            @AuthenticationPrincipal Jwt jwt) {
        return queryService.worklist(patientType, patientUid);
    }

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
