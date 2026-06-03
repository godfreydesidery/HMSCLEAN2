package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.CompanyProfileService;
import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import com.otapp.hmis.masterdata.application.dto.CompanyProfileRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the singleton company-profile (build-spec §1.5, CR-14).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/masterdata/company-profile} — returns the single row; 404 if absent.
 *       JWT-authenticated only — NO role gate (RF-3: matches legacy
 *       {@code CompanyProfileResource} GET which has no {@code @PreAuthorize}, and build-spec §3
 *       which mandates ungated masterdata reads; cashier and clinical roles read this for
 *       receipt/invoice rendering).</li>
 *   <li>{@code POST /api/v1/masterdata/company-profile} — creates the row if absent → 201;
 *       409 if a row already exists (CR-14 single-row invariant). Gated {@code ADMIN-ACCESS}.</li>
 *   <li>{@code PUT  /api/v1/masterdata/company-profile} — updates the single existing row → 200;
 *       404 if no row exists yet. Gated {@code ADMIN-ACCESS}.</li>
 * </ul>
 *
 * <p>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 * Constructor injection is Lombok-generated (DIRECTIVE 1).
 */
@RestController
@RequestMapping("/api/v1/masterdata/company-profile")
@RequiredArgsConstructor
public class CompanyProfileController {

    private final CompanyProfileService service;

    @GetMapping
    public CompanyProfileDto current() {
        return service.current();
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<CompanyProfileDto> create(@Valid @RequestBody CompanyProfileRequest request) {
        CompanyProfileDto created = service.create(request);
        URI location = URI.create("/api/v1/masterdata/company-profile");
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public CompanyProfileDto update(@Valid @RequestBody CompanyProfileRequest request) {
        return service.update(request);
    }
}
