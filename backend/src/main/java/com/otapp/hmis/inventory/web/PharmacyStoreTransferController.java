package com.otapp.hmis.inventory.web;

import com.otapp.hmis.inventory.application.PharmacyStoreTransferService;
import com.otapp.hmis.inventory.application.dto.PsRoDetailRequest;
import com.otapp.hmis.inventory.application.dto.PsRoRequest;
import com.otapp.hmis.inventory.application.dto.ToBatchRequest;
import com.otapp.hmis.inventory.application.dto.TransferDto;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pharmacy↔Store transfer REST surface (inc-08b chunk 6), base {@code /api/v1/inventory/ps-transfers}.
 *
 * <p>RBAC (Q5 — verbatim legacy): RO endpoints gated {@code PHARMACY_ORDER-*}; TO endpoints gated
 * {@code STORE_ORDER-ALL}; RN create + complete gated {@code PHARMACY_ORDER-ALL}.
 */
@RestController
@RequestMapping("/api/v1/inventory/ps-transfers")
@RequiredArgsConstructor
public class PharmacyStoreTransferController {

    private final PharmacyStoreTransferService service;
    private final BusinessDayService businessDayService;

    // ---- RO (PSR) ----

    @PostMapping("/ros")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-CREATE','PHARMACY_ORDER-ALL')")
    public TransferDto createRo(@Valid @RequestBody PsRoRequest req, @AuthenticationPrincipal Jwt jwt) {
        return service.createRo(req, ctx(jwt));
    }

    @PostMapping("/ros/uid/{uid}/details")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-UPDATE','PHARMACY_ORDER-ALL')")
    public TransferDto addRoDetail(@PathVariable("uid") String uid,
                                   @Valid @RequestBody PsRoDetailRequest req,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.addRoDetail(uid, req, ctx(jwt));
    }

    @PostMapping("/ros/uid/{uid}/verify")
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-ALL')")
    public TransferDto verifyRo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.verifyRo(uid, ctx(jwt));
    }

    @PostMapping("/ros/uid/{uid}/approve")
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-ALL')")
    public TransferDto approveRo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.approveRo(uid, ctx(jwt));
    }

    @PostMapping("/ros/uid/{uid}/submit")
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-ALL')")
    public TransferDto submitRo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.submitRo(uid, ctx(jwt));
    }

    @GetMapping("/ros/uid/{uid}")
    public TransferDto getRo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.getRo(uid);
    }

    // ---- TO (SPTO) ----

    @PostMapping("/tos/from-ro/uid/{roUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('STORE_ORDER-ALL')")
    public TransferDto createTo(@PathVariable("roUid") String roUid, @AuthenticationPrincipal Jwt jwt) {
        return service.createToFromRo(roUid, ctx(jwt));
    }

    @PostMapping("/to-details/uid/{detailUid}/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('STORE_ORDER-ALL')")
    public TransferDto addToBatch(@PathVariable("detailUid") String detailUid,
                                  @Valid @RequestBody ToBatchRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        return service.addToBatch(detailUid, req, ctx(jwt));
    }

    @PostMapping("/tos/uid/{uid}/verify")
    @PreAuthorize("hasAnyAuthority('STORE_ORDER-ALL')")
    public TransferDto verifyTo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.verifyTo(uid, ctx(jwt));
    }

    @PostMapping("/tos/uid/{uid}/approve")
    @PreAuthorize("hasAnyAuthority('STORE_ORDER-ALL')")
    public TransferDto approveTo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.approveTo(uid, ctx(jwt));
    }

    /** Issue: store stock decrements here (FEFO). */
    @PostMapping("/tos/uid/{uid}/issue")
    @PreAuthorize("hasAnyAuthority('STORE_ORDER-ALL')")
    public TransferDto issueTo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.issueTo(uid, ctx(jwt));
    }

    @GetMapping("/tos/uid/{uid}")
    public TransferDto getTo(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.getTo(uid);
    }

    // ---- RN (PGRN) ----

    @PostMapping("/rns/from-to/uid/{toUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-ALL')")
    public TransferDto createRn(@PathVariable("toUid") String toUid, @AuthenticationPrincipal Jwt jwt) {
        return service.createRnFromTo(toUid, ctx(jwt));
    }

    /** Complete: pharmacy stock increments here (destination batches created). */
    @PostMapping("/rns/uid/{uid}/complete")
    @PreAuthorize("hasAnyAuthority('PHARMACY_ORDER-ALL')")
    public TransferDto completeRn(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.completeRn(uid, ctx(jwt));
    }

    @GetMapping("/rns/uid/{uid}")
    public TransferDto getRn(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.getRn(uid);
    }

    private TxAuditContext ctx(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
