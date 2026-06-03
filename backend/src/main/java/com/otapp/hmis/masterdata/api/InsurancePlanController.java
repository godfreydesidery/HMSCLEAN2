package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.InsurancePlanService;
import com.otapp.hmis.masterdata.application.dto.InsurancePlanDto;
import com.otapp.hmis.masterdata.application.dto.InsurancePlanRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the {@code InsurancePlan} catalog (build-spec §1.4, §3).
 *
 * <p>Plans are nested under their provider in the URL scheme:
 * {@code GET/POST /masterdata/insurance-providers/uid/{uid}/plans}. A flat list endpoint
 * and a direct-uid endpoint are also provided for convenience.
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3, CR-15 DEVIATION-4).</li>
 *   <li>Reads require a valid JWT; no role gate.</li>
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class InsurancePlanController {

    private final InsurancePlanService service;

    // ------------------------------------------------------------------
    // Flat (non-nested) endpoints
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/masterdata/insurance-plans")
    public List<InsurancePlanDto> list() {
        return service.list();
    }

    @GetMapping("/api/v1/masterdata/insurance-plans/uid/{uid}")
    public InsurancePlanDto getByUid(@PathVariable("uid") String uid) {
        return service.get(uid);
    }

    @PutMapping("/api/v1/masterdata/insurance-plans/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public InsurancePlanDto update(@PathVariable("uid") String uid,
                                   @Valid @RequestBody InsurancePlanRequest request) {
        return service.update(uid, request);
    }

    // ------------------------------------------------------------------
    // Nested under provider (build-spec §endpoints)
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/masterdata/insurance-providers/uid/{providerUid}/plans")
    public List<InsurancePlanDto> listByProvider(
            @PathVariable("providerUid") String providerUid) {
        return service.listByProvider(providerUid);
    }

    @PostMapping("/api/v1/masterdata/insurance-providers/uid/{providerUid}/plans")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<InsurancePlanDto> createUnderProvider(
            @PathVariable("providerUid") String providerUid,
            @Valid @RequestBody InsurancePlanRequest request) {
        InsurancePlanDto created = service.create(providerUid, request);
        URI location = URI.create(
                "/api/v1/masterdata/insurance-plans/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }
}
