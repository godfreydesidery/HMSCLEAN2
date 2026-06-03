package com.otapp.hmis.shared.api;

import com.otapp.hmis.shared.application.BusinessDayAdminService;
import com.otapp.hmis.shared.application.dto.BusinessDayDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for business-day lifecycle (build-spec §5, P5).
 *
 * <ul>
 *   <li>{@code POST /api/v1/shared/business-days/open}    — opens a new day (DAY-ACCESS); 409 if already open.</li>
 *   <li>{@code POST /api/v1/shared/business-days/close}   — closes the current day (DAY-ACCESS); 422 if none open.</li>
 *   <li>{@code GET  /api/v1/shared/business-days/current} — returns the open day (authenticated); 422 if none open.</li>
 * </ul>
 *
 * <p>Lives in the {@code shared} module (OPEN type — no new module edges introduced).
 * Delegates to {@link BusinessDayAdminService} which owns the mapper (package-private in
 * {@code shared.application}). The controller never touches the mapper directly.
 *
 * <p>{@code DAY-ACCESS} is code #2 in the 26 live codes (build-spec §3, PrivilegeGateArchTest).
 * No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).
 */
@RestController
@RequestMapping("/api/v1/shared/business-days")
@RequiredArgsConstructor
public class BusinessDayAdminController {

    private final BusinessDayAdminService service;

    @PostMapping("/open")
    @PreAuthorize("hasAnyAuthority('DAY-ACCESS')")
    public ResponseEntity<BusinessDayDto> open() {
        return ResponseEntity.ok(service.openToday());
    }

    @PostMapping("/close")
    @PreAuthorize("hasAnyAuthority('DAY-ACCESS')")
    public BusinessDayDto close() {
        return service.closeCurrentDay();
    }

    @GetMapping("/current")
    public BusinessDayDto current() {
        return service.currentDay();
    }
}
