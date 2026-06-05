package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.pharmacy.api.PharmacyStockCredit;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link PharmacyStockCredit} (inc-08b chunk 6). Delegates to
 * {@link StockService#receiveLot} (increment aggregate + create destination PharmacyMedicineBatch +
 * TRANSFER_IN stock-card row). Runs in the caller's (inventory RN) transaction (propagation REQUIRED)
 * so the pharmacy credit and the RN COMPLETED flip are atomic.
 */
@Service
@RequiredArgsConstructor
class PharmacyStockCreditAdapter implements PharmacyStockCredit {

    private final StockService stockService;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void creditTransferLot(String pharmacyUid, String medicineUid, String batchNo,
                                  LocalDate manufacturedDate, LocalDate expiryDate, BigDecimal qty,
                                  String reference, TxAuditContext ctx) {
        stockService.receiveLot(pharmacyUid, medicineUid, batchNo, manufacturedDate, expiryDate,
                qty, reference, ctx);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void creditTransferAggregate(String pharmacyUid, String medicineUid, BigDecimal qty,
                                        String reference, TxAuditContext ctx) {
        // increment aggregate + TRANSFER_IN card, NO batch — the reproduced p2p gap (Q7).
        stockService.increment(pharmacyUid, medicineUid, qty,
                com.otapp.hmis.pharmacy.domain.MovementType.TRANSFER_IN, reference, ctx);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void debitTransferOut(String pharmacyUid, String medicineUid, BigDecimal qty,
                                 String reference, TxAuditContext ctx) {
        // hard negative-stock gate + FEFO + TRANSFER_OUT card (source/delivering pharmacy).
        stockService.decrementFefo(pharmacyUid, medicineUid, qty,
                com.otapp.hmis.pharmacy.domain.MovementType.TRANSFER_OUT, reference, ctx);
    }
}
