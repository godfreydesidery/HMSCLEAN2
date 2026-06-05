package com.otapp.hmis.pharmacy.web;

import com.otapp.hmis.pharmacy.application.PharmacySaleOrderService;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDetailDto;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDetailRequest;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderDto;
import com.otapp.hmis.pharmacy.application.dto.SaleOrderRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTC walk-in sale-order REST surface (inc-08a chunk 4), base {@code /api/v1/pharmacy/sale-orders}.
 *
 * <p><strong>Authenticated-only (no {@code @PreAuthorize}, AC-RBAC-02):</strong> the entire legacy
 * OTC flow was UNGATED (PharmacyResource.java:298 commented). The global deny-by-default floor
 * requires a valid JWT. The "177" figure is BANNED; no new privilege codes (Q5). pharmacyUid is the
 * required, server-validated stock-source selector with NO affiliation check (Q2).
 *
 * <p>PENDING→APPROVED is NOT an endpoint here — it is the bill-payment side effect handled by
 * {@code OtcSettlementListener} (legacy {@code confirm_bills_payment}).
 */
@RestController
@RequestMapping("/api/v1/pharmacy/sale-orders")
@RequiredArgsConstructor
public class PharmacySaleOrderController {

    private final PharmacySaleOrderService service;
    private final BusinessDayService businessDayService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaleOrderDto create(@Valid @RequestBody SaleOrderRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return service.createOrder(request, ctxFrom(jwt));
    }

    @PostMapping("/uid/{uid}/details")
    @ResponseStatus(HttpStatus.CREATED)
    public SaleOrderDetailDto addDetail(@PathVariable("uid") String orderUid,
                                        @Valid @RequestBody SaleOrderDetailRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.addDetail(orderUid, request, ctxFrom(jwt));
    }

    /** Whole-order dispense (APPROVED-only; aggregate decrement, NO FEFO). */
    @PostMapping("/uid/{uid}/dispense")
    public SaleOrderDto dispense(@PathVariable("uid") String orderUid,
                                 @RequestParam("pharmacyUid") String pharmacyUid,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.dispense(orderUid, pharmacyUid, ctxFrom(jwt));
    }

    @PostMapping("/uid/{uid}/cancel")
    public SaleOrderDto cancel(@PathVariable("uid") String orderUid,
                               @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(orderUid, ctxFrom(jwt));
    }

    @PostMapping("/uid/{uid}/archive")
    public SaleOrderDto archive(@PathVariable("uid") String orderUid,
                                @AuthenticationPrincipal Jwt jwt) {
        return service.archive(orderUid, ctxFrom(jwt));
    }

    @DeleteMapping("/details/uid/{detailUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDetail(@PathVariable("detailUid") String detailUid,
                             @AuthenticationPrincipal Jwt jwt) {
        service.deleteDetail(detailUid, ctxFrom(jwt));
    }

    @GetMapping("/uid/{uid}")
    public SaleOrderDto getByUid(@PathVariable("uid") String orderUid,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.getByUid(orderUid);
    }

    /** Worklist FILTER: orders in PENDING or APPROVED. */
    @GetMapping
    public List<SaleOrderDto> worklist(@AuthenticationPrincipal Jwt jwt) {
        return service.worklist();
    }

    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
