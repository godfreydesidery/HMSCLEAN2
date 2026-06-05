package com.otapp.hmis.inventory.web;

import com.otapp.hmis.inventory.application.GrnService;
import com.otapp.hmis.inventory.application.dto.GrnBatchRequest;
import com.otapp.hmis.inventory.application.dto.GrnDto;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goods Received Note REST surface (inc-08b), base {@code /api/v1/inventory/grns}.
 *
 * <p>RBAC (Q5 — coarse, verbatim legacy): create gated {@code GOODS_RECEIVED_NOTE-CREATE};
 * receivedQty/batch/verify gated {@code GOODS_RECEIVED_NOTE-UPDATE}; approve gated
 * {@code GOODS_RECEIVED_NOTE-APPROVE} (each {@code -ALL} also admitted). No new codes.
 */
@RestController
@RequestMapping("/api/v1/inventory/grns")
@RequiredArgsConstructor
public class GrnController {

    private final GrnService service;
    private final BusinessDayService businessDayService;

    /** Create a GRN from a SUBMITTED LPO (store-match guard in the service). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('GOODS_RECEIVED_NOTE-CREATE','GOODS_RECEIVED_NOTE-ALL')")
    public GrnDto create(@RequestParam("lpoUid") String lpoUid,
                         @RequestParam("storeUid") String storeUid,
                         @AuthenticationPrincipal Jwt jwt) {
        return service.createFromLpo(lpoUid, storeUid, ctx(jwt));
    }

    @PostMapping("/details/uid/{detailUid}/received-qty")
    @PreAuthorize("hasAnyAuthority('GOODS_RECEIVED_NOTE-UPDATE','GOODS_RECEIVED_NOTE-ALL')")
    public GrnDto setReceivedQty(@PathVariable("detailUid") String detailUid,
                                 @RequestParam("receivedQty") BigDecimal receivedQty,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.setReceivedQty(detailUid, receivedQty, ctx(jwt));
    }

    @PostMapping("/details/uid/{detailUid}/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('GOODS_RECEIVED_NOTE-UPDATE','GOODS_RECEIVED_NOTE-ALL')")
    public GrnDto addBatch(@PathVariable("detailUid") String detailUid,
                           @Valid @RequestBody GrnBatchRequest req,
                           @AuthenticationPrincipal Jwt jwt) {
        return service.addBatch(detailUid, req, ctx(jwt));
    }

    @PostMapping("/details/uid/{detailUid}/verify")
    @PreAuthorize("hasAnyAuthority('GOODS_RECEIVED_NOTE-UPDATE','GOODS_RECEIVED_NOTE-ALL')")
    public GrnDto verifyDetail(@PathVariable("detailUid") String detailUid,
                               @AuthenticationPrincipal Jwt jwt) {
        return service.verifyDetail(detailUid, ctx(jwt));
    }

    /** Approve: one atomic tx — credit store stock, copy batches, Purchase ledger, LPO→RECEIVED. */
    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("hasAnyAuthority('GOODS_RECEIVED_NOTE-APPROVE','GOODS_RECEIVED_NOTE-ALL')")
    public GrnDto approve(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(uid, ctx(jwt));
    }

    @GetMapping("/uid/{uid}")
    public GrnDto getByUid(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.getByUid(uid);
    }

    private TxAuditContext ctx(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
