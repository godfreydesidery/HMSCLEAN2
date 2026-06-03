package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.iam.lookup.UserSummary;
import com.otapp.hmis.masterdata.application.ClinicClinicianService;
import com.otapp.hmis.masterdata.application.dto.AffiliateClinicianRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for clinic–clinician affiliation and the admin CLINICIAN-role listing
 * (CR-08, build-spec §5.2, AC-4).
 *
 * <p>Affiliation mutations ({@code POST}/{@code DELETE}) are gated {@code ADMIN-ACCESS}
 * (build-spec §3 gate map). The CLINICIAN-role check is a SEPARATE business gate (403
 * {@code urn:hmis:error:clinician-role-required}) handled in the service layer, not here.
 *
 * <p>Reads ({@code GET .../clinicians}) require a valid JWT but carry no role gate
 * (legacy: masterdata GETs are role-ungated — build-spec §3).
 *
 * <p>The admin listing ({@code GET /masterdata/clinicians/by-role/CLINICIAN}) is gated
 * {@code ADMIN-ACCESS} (unscoped admin listing per ADR-0020).
 *
 * <ul>
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 *   <li>No class-level {@code @PreAuthorize} (method-level only per build-spec §2).
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ClinicClinicianController {

    private final ClinicClinicianService service;

    /**
     * Affiliates a clinician user with a clinic.
     * Gate: {@code ADMIN-ACCESS}. The CLINICIAN-role business check is in the service layer.
     */
    @PostMapping("/api/v1/masterdata/clinics/uid/{clinicUid}/clinicians")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<Void> affiliateClinician(
            @PathVariable("clinicUid") String clinicUid,
            @Valid @RequestBody AffiliateClinicianRequest request) {
        service.affiliateClinician(clinicUid, request.userUid());
        URI location = URI.create("/api/v1/masterdata/clinics/uid/" + clinicUid + "/clinicians");
        return ResponseEntity.created(location).build();
    }

    /**
     * Removes a clinician–clinic affiliation. Idempotent: 204 even if the affiliation
     * did not exist. Gate: {@code ADMIN-ACCESS}.
     */
    @DeleteMapping("/api/v1/masterdata/clinics/uid/{clinicUid}/clinicians/{userUid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<Void> removeAffiliation(
            @PathVariable("clinicUid") String clinicUid,
            @PathVariable("userUid") String userUid) {
        service.removeAffiliation(clinicUid, userUid);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists all clinician users affiliated with a clinic.
     * Read — valid JWT required; no role gate (masterdata GETs are role-ungated).
     */
    @GetMapping("/api/v1/masterdata/clinics/uid/{clinicUid}/clinicians")
    public List<UserSummary> listClinicians(@PathVariable("clinicUid") String clinicUid) {
        return service.listClinicians(clinicUid);
    }

    /**
     * Unscoped admin listing of all users holding the {@code CLINICIAN} role (ADR-0020).
     * Gate: {@code ADMIN-ACCESS}.
     */
    @GetMapping("/api/v1/masterdata/clinicians/by-role/CLINICIAN")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public List<UserSummary> listAllClinicians() {
        return service.listAllClinicians();
    }
}
