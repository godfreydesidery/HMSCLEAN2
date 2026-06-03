package com.otapp.hmis.masterdata.api;

import com.otapp.hmis.masterdata.application.ServicePriceService;
import com.otapp.hmis.masterdata.application.ServicePriceService.UpsertOutcome;
import com.otapp.hmis.masterdata.application.ServicePriceService.UpsertResult;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the {@code ServicePrice} unified pricing matrix
 * (build-spec §2.1, §endpoints, §3, AC-1, AC-5, RF-1, RF-2).
 *
 * <ul>
 *   <li>{@code POST /api/v1/masterdata/service-prices} — TRUE UPSERT (RF-1):
 *       <ul>
 *         <li>First occurrence of a composite key {@code (plan_uid, kind, service_uid, currency)}
 *             → INSERT; returns 201 Created + Location header.</li>
 *         <li>Subsequent POST with the SAME composite key → UPDATE amount/covered/min/max
 *             in-place; returns 200 OK (idempotent, no 409).</li>
 *       </ul>
 *       Legacy rationale: restores the per-kind {@code update_*_price_by_insurance} /
 *       {@code change_*_coverage} capability from
 *       {@code InsurancePlanResource} (all 7 service kinds).
 *   </li>
 *   <li>{@code DELETE /api/v1/masterdata/service-prices/uid/{uid}} — 204 No Content.
 *       Idempotent: unknown uid → 204 (already gone). Gated {@code ADMIN-ACCESS}.</li>
 *   <li>{@code GET /api/v1/masterdata/service-prices} — list all rows.</li>
 *   <li>{@code GET /api/v1/masterdata/service-prices/resolve} — invoke
 *       {@code PriceLookup.resolve}; 422 when not found (AC-1).</li>
 * </ul>
 *
 * <ul>
 *   <li>Mutations gated {@code ADMIN-ACCESS} (build-spec §3, CR-15 DEVIATION-4).</li>
 *   <li>Reads require a valid JWT; no role gate (legacy-faithful — build-spec §3).</li>
 *   <li>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/masterdata/service-prices")
@RequiredArgsConstructor
public class ServicePriceController {

    private final ServicePriceService service;

    /**
     * True upsert of a single {@code service_prices} row (RF-1).
     *
     * <p>Returns 201 Created + Location on INSERT; 200 OK on UPDATE (same composite key).
     * The RF-2 price/coverage coupling is applied in the service layer before persisting:
     * {@code amount < 0} → 400; {@code amount == 0} → forces {@code covered=false}.
     *
     * @see ServicePriceService#upsert(ServicePriceRequest)
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<ServicePriceDto> upsert(
            @Valid @RequestBody ServicePriceRequest request) {
        UpsertResult result = service.upsert(request);
        if (result.outcome() == UpsertOutcome.CREATED) {
            URI location = URI.create(
                    "/api/v1/masterdata/service-prices/uid/" + result.dto().uid());
            return ResponseEntity.created(location).body(result.dto());
        }
        return ResponseEntity.ok(result.dto());
    }

    /**
     * Delete a {@code service_prices} row by uid (RF-1).
     *
     * <p>Idempotent: returns 204 whether the row existed or not.
     * Gated {@code ADMIN-ACCESS}.
     */
    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ADMIN-ACCESS')")
    public ResponseEntity<Void> delete(@PathVariable("uid") String uid) {
        service.delete(uid);
        return ResponseEntity.noContent().build();
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
