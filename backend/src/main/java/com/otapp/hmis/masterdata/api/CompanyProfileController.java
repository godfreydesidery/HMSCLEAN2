package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.CompanyProfileService;
import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/company-profile} — proves HTTP -&gt; service -&gt; DB round trip
 * (increment-00 spec). Gated to {@code ADMIN-ACCESS} (ADR-0006). Constructor injection is
 * Lombok-generated (DIRECTIVE 1).
 */
@RestController
@RequestMapping("/api/v1/company-profile")
@RequiredArgsConstructor
public class CompanyProfileController {

    private final CompanyProfileService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public CompanyProfileDto current() {
        return service.current();
    }
}
