package com.otapp.hmis.inventory.web;

import com.otapp.hmis.inventory.application.LpoService;
import com.otapp.hmis.inventory.application.dto.LpoDetailRequest;
import com.otapp.hmis.inventory.application.dto.LpoDto;
import com.otapp.hmis.inventory.application.dto.LpoRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
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
 * Local Purchase Order REST surface (inc-08b), base {@code /api/v1/inventory/lpos}.
 *
 * <p>RBAC (Q5 — coarse, verbatim legacy, NO per-transition SoD): create/edit gated
 * {@code LOCAL_PURCHASE_ORDER-CREATE}/{@code -UPDATE}; all transitions gated the single coarse
 * {@code LOCAL_PURCHASE_ORDER-ALL}. No new codes; "177" banned.
 */
@RestController
@RequestMapping("/api/v1/inventory/lpos")
@RequiredArgsConstructor
public class LpoController {

    private final LpoService service;
    private final BusinessDayService businessDayService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-CREATE','LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto create(@Valid @RequestBody LpoRequest req, @AuthenticationPrincipal Jwt jwt) {
        return service.create(req, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/details")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-UPDATE','LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto addDetail(@PathVariable("uid") String uid,
                            @Valid @RequestBody LpoDetailRequest req,
                            @AuthenticationPrincipal Jwt jwt) {
        return service.addDetail(uid, req, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/valid-until")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-UPDATE','LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto editValidUntil(@PathVariable("uid") String uid,
                                 @RequestParam("validUntil") LocalDate validUntil,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.editValidUntil(uid, validUntil, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/verify")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto verify(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.verify(uid, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto approve(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(uid, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/submit")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto submit(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.submit(uid, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/reject")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto reject(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.reject(uid, ctx(jwt));
    }

    @PostMapping("/uid/{uid}/return")
    @PreAuthorize("hasAnyAuthority('LOCAL_PURCHASE_ORDER-ALL')")
    public LpoDto returnForAmendment(@PathVariable("uid") String uid,
                                     @AuthenticationPrincipal Jwt jwt) {
        return service.returnForAmendment(uid, ctx(jwt));
    }

    @GetMapping("/uid/{uid}")
    public LpoDto getByUid(@PathVariable("uid") String uid, @AuthenticationPrincipal Jwt jwt) {
        return service.getByUid(uid);
    }

    private TxAuditContext ctx(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
