package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.clinical.api.DispenseConfirmation;
import com.otapp.hmis.clinical.api.PrescriptionDispensePort;
import com.otapp.hmis.clinical.api.PrescriptionReadPort;
import com.otapp.hmis.clinical.api.PrescriptionView;
import com.otapp.hmis.masterdata.lookup.PharmacyLookup;
import com.otapp.hmis.pharmacy.application.dto.DispenseRequest;
import com.otapp.hmis.pharmacy.domain.MovementType;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pharmacy-orchestrated clinical-prescription dispense (inc-08a chunk 3) — closes the stock-decrement
 * TODO deferred since inc-05/06 (Prescription.java:534-537). One {@code @Transactional} runs:
 *
 * <ol>
 *   <li>validate the {@code pharmacyUid} stock-source selector resolves (Q2 — required, server-
 *       validated, NO affiliation check, AC-RX-DSP-15/25);</li>
 *   <li>read the prescription via {@link PrescriptionReadPort} for {@code medicineUid} + {@code qty};</li>
 *   <li>{@link StockService#decrementFefo} — the hard negative-stock gate (verbatim legacy refusal,
 *       AC-RX-DSP-07), the FEFO walk (legacy null-expiry exclusion + id-ASC, AC-RX-DSP-11), and the
 *       append-only stock-card OUT row "Issued in prescription: id ..." (AC-RX-DSP-10);</li>
 *   <li>{@link PrescriptionDispensePort#markDispensed} — flip the clinical prescription
 *       NOT-GIVEN→GIVEN, stamp {@code approved_*}/{@code issuePharmacyUid}, persist lot-trace
 *       (propagation REQUIRED → atomic with the decrement, AC-RX-PRE-07).</li>
 * </ol>
 *
 * <p><strong>NO bill-status check at this terminal (Q1, AC-RX-DSP-16):</strong> payment enforcement
 * is the worklist FILTER only; {@code BillingQueries.getBillStatus} is NOT called here. The legacy
 * order was status-flip-before-stock-check; under the rollback boundary, running the gate before the
 * clinical flip is observable-equivalent (a rollback undoes both regardless of intra-tx ordering).
 */
@Service
@RequiredArgsConstructor
public class PharmacyDispenseService {

    private final PharmacyLookup pharmacyLookup;
    private final PrescriptionReadPort prescriptionReadPort;
    private final PrescriptionDispensePort prescriptionDispensePort;
    private final StockService stockService;

    /**
     * Dispense a clinical prescription from a pharmacy.
     *
     * @param prescriptionUid the prescription to dispense
     * @param request         the dispense request (pharmacyUid + issued qty)
     * @param ctx             audit context
     * @return the updated published prescription view (status=GIVEN)
     * @throws NotFoundException                         if the pharmacy or prescription is unknown
     * @throws com.otapp.hmis.shared.error.InsufficientStockException if pharmacy stock &lt; qty
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if a dispense guard fails
     */
    @Transactional
    public PrescriptionView dispense(String prescriptionUid, DispenseRequest request,
                                     TxAuditContext ctx) {
        // (1) Q2: pharmacyUid required + server-validated to RESOLVE — no affiliation check.
        if (!pharmacyLookup.existsByUid(request.pharmacyUid())) {
            throw new NotFoundException("Pharmacy not found: " + request.pharmacyUid());
        }

        // (2) read the prescription for medicineUid + qty (no clinical entity leak).
        PrescriptionView rx = prescriptionReadPort.getByUid(prescriptionUid);
        BigDecimal qty = request.issued();

        // (3) pharmacy stock decrement: hard gate -> FEFO walk -> stock-card OUT (DISPENSE).
        //     Reference string verbatim-legacy "Issued in prescription: id <correlation>".
        String reference = "Issued in prescription: id " + rx.uid();
        List<StockService.ConsumedLot> lots = stockService.decrementFefo(
                request.pharmacyUid(), rx.medicineUid(), qty, MovementType.DISPENSE, reference, ctx);

        // (4) flip clinical state NOT-GIVEN->GIVEN + persist lot-trace (same tx, REQUIRED).
        List<DispenseConfirmation.LotTrace> trace = lots.stream()
                .map(l -> new DispenseConfirmation.LotTrace(
                        l.batchNo(), l.manufacturedDate(), l.expiryDate(), l.qty()))
                .toList();
        DispenseConfirmation cmd = new DispenseConfirmation(qty, request.pharmacyUid(), trace);
        return prescriptionDispensePort.markDispensed(prescriptionUid, cmd, ctx);
    }
}
