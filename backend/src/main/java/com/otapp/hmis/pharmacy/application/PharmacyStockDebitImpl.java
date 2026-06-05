package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.pharmacy.api.PharmacyStockDebit;
import com.otapp.hmis.pharmacy.domain.MovementType;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link PharmacyStockDebit} (inc-07 chunk 07c,
 * CR-07-consumable-stock).
 *
 * <p>Delegates to {@link StockService#decrementFefo} with {@link MovementType#CONSUMPTION},
 * which applies the hard negative-stock gate, FEFO lot walk, and appends the stock-card OUT row.
 * Propagation REQUIRED — runs atomically inside the caller's (inpatient) transaction.
 *
 * <p>This class is intentionally package-private in {@code pharmacy.application}. Callers must
 * depend only on the {@link PharmacyStockDebit} interface from {@code pharmacy.api}.
 *
 * <p>Legacy citation: none for stock — this is net-new per CR-07-consumable-stock. The FEFO
 * decrement logic mirrors the existing prescription dispense path (StockService.decrementFefo,
 * inc-08a chunk 2).
 */
@Service
@RequiredArgsConstructor
class PharmacyStockDebitImpl implements PharmacyStockDebit {

    private final StockService stockService;

    /**
     * {@inheritDoc}
     *
     * <p>Propagation REQUIRED: runs inside the caller's (inpatient consumable-chart) transaction.
     * Hard negative-stock gate ({@code InsufficientStockException}) is enforced by
     * {@link StockService#decrementFefo} via the aggregate {@code decrement()} check.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void debitConsumableIssue(String pharmacyUid, String medicineUid, BigDecimal qty,
                                     String reference, TxAuditContext ctx) {
        stockService.decrementFefo(pharmacyUid, medicineUid, qty,
                MovementType.CONSUMPTION, reference, ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link StockService#increment} with {@link MovementType#CONSUMPTION_REVERSAL}.
     * Aggregate-only increment (no batch re-creation). Skips when qty &lt;= 0.
     * Propagation REQUIRED — runs atomically inside the caller's (inpatient delete) transaction.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void restoreConsumableIssue(String pharmacyUid, String medicineUid, BigDecimal qty,
                                       String reference, TxAuditContext ctx) {
        stockService.increment(pharmacyUid, medicineUid, qty,
                MovementType.CONSUMPTION_REVERSAL, reference, ctx);
    }
}
