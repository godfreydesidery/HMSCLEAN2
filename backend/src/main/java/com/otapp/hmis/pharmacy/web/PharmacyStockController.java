package com.otapp.hmis.pharmacy.web;

import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import com.otapp.hmis.pharmacy.application.PharmacyStockQueryService;
import com.otapp.hmis.pharmacy.application.StockService;
import com.otapp.hmis.pharmacy.application.dto.StockBatchDto;
import com.otapp.hmis.pharmacy.application.dto.StockMovementDto;
import com.otapp.hmis.pharmacy.application.dto.StockStatusDto;
import com.otapp.hmis.pharmacy.application.dto.StockUpdateRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
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
 * Pharmacy stock REST surface (inc-08a chunk 3): stock status / batches / stock-card reads
 * (authenticated-only — legacy reads were ungated, AC-RBAC-05) + the manual stock OVERWRITE
 * (gated EXACTLY {@code MEDICINE_STOCK-UPDATE} — the single coarse live code, AC-RBAC-03).
 *
 * <p>The "177" figure is BANNED; only the one in-scope code appears (Q5). pharmacyUid is the
 * required, server-validated stock-source selector with NO affiliation check (Q2).
 */
@RestController
@RequestMapping("/api/v1/pharmacy/stock")
@RequiredArgsConstructor
public class PharmacyStockController {

    private final PharmacyStockQueryService queryService;
    private final StockService stockService;
    private final PharmacyLookup pharmacyLookup;
    private final BusinessDayService businessDayService;

    /** Stock status (scalar aggregate balances) for every medicine in a pharmacy. */
    @GetMapping("/uid/{pharmacyUid}/items")
    public List<StockStatusDto> stockStatus(
            @PathVariable("pharmacyUid") String pharmacyUid,
            @AuthenticationPrincipal Jwt jwt) {
        requirePharmacy(pharmacyUid);
        return queryService.stockStatus(pharmacyUid);
    }

    /** FEFO lots for one (pharmacy, medicine). */
    @GetMapping("/uid/{pharmacyUid}/items/uid/{medicineUid}/batches")
    public List<StockBatchDto> batches(
            @PathVariable("pharmacyUid") String pharmacyUid,
            @PathVariable("medicineUid") String medicineUid,
            @AuthenticationPrincipal Jwt jwt) {
        requirePharmacy(pharmacyUid);
        return queryService.batches(pharmacyUid, medicineUid);
    }

    /** Stock-card (movement ledger) for one (pharmacy, medicine), oldest first. */
    @GetMapping("/uid/{pharmacyUid}/items/uid/{medicineUid}/movements")
    public List<StockMovementDto> movements(
            @PathVariable("pharmacyUid") String pharmacyUid,
            @PathVariable("medicineUid") String medicineUid,
            @AuthenticationPrincipal Jwt jwt) {
        requirePharmacy(pharmacyUid);
        return queryService.movements(pharmacyUid, medicineUid);
    }

    /**
     * Manual stock OVERWRITE (absolute set, ADJUSTMENT card, no batch effect — AC-STK-13).
     * Gated EXACTLY {@code MEDICINE_STOCK-UPDATE} (PharmacyResource.java:200).
     */
    @PostMapping("/update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('MEDICINE_STOCK-UPDATE')")
    public void updateStock(
            @Valid @RequestBody StockUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requirePharmacy(request.pharmacyUid());
        stockService.overwriteStock(request.pharmacyUid(), request.medicineUid(),
                request.stock(), ctxFrom(jwt));
    }

    // -------------------------------------------------------------------------

    /** Q2: pharmacyUid required + server-validated to resolve (no affiliation check). */
    private void requirePharmacy(String pharmacyUid) {
        if (!pharmacyLookup.existsByUid(pharmacyUid)) {
            throw new NotFoundException("Pharmacy not found: " + pharmacyUid);
        }
    }

    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
