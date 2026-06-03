package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ServicePriceService;
import com.otapp.hmis.masterdata.application.dto.ServicePriceDto;
import com.otapp.hmis.masterdata.application.dto.ServicePriceRequest;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the {@code ServicePrice} unified pricing matrix
 * (build-spec §2.1, §endpoints, §3, AC-1, AC-5).
 *
 * <ul>
 *   <li>{@code POST /api/v1/masterdata/service-prices} — upsert a single row; 201 on create,
 *       409 on duplicate composite key (AC-5 — handles NULL planUid and NULL serviceUid via
 *       the COALESCE unique index pre-check).</li>
 *   <li>{@code GET /api/v1/masterdata/service-prices} — list all rows.</li>
 *   <li>{@code GET /api/v1/masterdata/service-prices/resolve} — invoke
 *       {@code PriceLookup.resolve}; 422 when not found (AC-1).</li>
 * </ul>
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3, CR-15 DEVIATION-4).</li>
 *   <li>Reads require a valid JWT; no role gate.</li>
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/service-prices")
@RequiredArgsConstructor
public class ServicePriceController {

    private final ServicePriceService service;

    /**
     * Upsert a single {@code service_prices} row.
     *
     * <p>Returns 201 Created with a {@code Location} header on success.
     * Returns 409 Conflict ({@code urn:hmis:error:duplicate-service-price}) when a row with
     * the same (plan_uid, kind, service_uid, currency) composite key already exists (AC-5).
     * NULL plan_uid (cash) and NULL service_uid (REGISTRATION) are handled correctly.
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ServicePriceDto> upsert(
            @Valid @RequestBody ServicePriceRequest request) {
        ServicePriceDto created = service.upsert(request);
        URI location = URI.create(
                "/api/v1/masterdata/service-prices/uid/" + created.uid());
        return ResponseEntity.created(location).body(created);
    }

    /**
     * List all service price rows (ordered by kind, plan_uid).
     */
    @GetMapping
    public List<ServicePriceDto> list() {
        return service.list();
    }

    /**
     * Resolve the effective price for a service charge.
     *
     * <p>Delegates to {@link com.otapp.hmis.masterdata.lookup.PriceLookup#resolve}.
     * Returns 422 ({@code urn:hmis:error:service-price-not-found}) when neither a covered
     * insurance row nor a cash fallback exists (AC-1).
     *
     * <p>{@code planUid} is optional (omit or pass empty for a cash-only lookup).
     * {@code serviceUid} is optional for {@link ServiceKind#REGISTRATION} only.
     * {@code currency} defaults to "TZS" when omitted.
     */
    @GetMapping("/resolve")
    public ServicePriceResult resolve(
            @RequestParam(required = false) String planUid,
            @RequestParam ServiceKind kind,
            @RequestParam(required = false) String serviceUid,
            @RequestParam(required = false, defaultValue = "TZS") String currency) {
        return service.resolve(
                (planUid != null && !planUid.isBlank()) ? planUid : null,
                kind,
                (serviceUid != null && !serviceUid.isBlank()) ? serviceUid : null,
                currency);
    }
}
