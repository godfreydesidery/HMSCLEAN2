package com.otapp.hmis.pharmacy.application;

import com.otapp.hmis.pharmacy.domain.MovementType;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicine;
import com.otapp.hmis.pharmacy.domain.PharmacyMedicineRepository;
import com.otapp.hmis.pharmacy.domain.StockBatch;
import com.otapp.hmis.pharmacy.domain.StockBatchRepository;
import com.otapp.hmis.pharmacy.domain.StockMovement;
import com.otapp.hmis.pharmacy.domain.StockMovementRepository;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InsufficientStockException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pharmacy stock engine (inc-08a chunk 2) — the single owner of every {@code PharmacyMedicine}
 * aggregate mutation, the FEFO {@code StockBatch} walk, and the append-only {@code StockMovement}
 * ledger write. Used by clinical dispense (chunk 3), OTC dispense (chunk 4), and the
 * transfer/procurement paths (08b).
 *
 * <p><strong>FEFO (verbatim legacy {@code getEarlierBatch}, PatientResource.java:3338-3376; Q8
 * baseline):</strong> if any positive-remaining lot has a non-null expiry, consume earliest-expiry
 * first and SILENTLY EXCLUDE null-expiry lots; else fall back to lowest-id (FIFO) over null-expiry
 * lots. {@code id ASC} pinned secondary sort (N8). NULLS-LAST parked (HDE/Q8).
 *
 * <p><strong>Negative-stock gate (verbatim legacy refusal):</strong> {@link PharmacyMedicine#decrement}
 * runs on the aggregate FIRST, throwing {@link InsufficientStockException} (422) when stock &lt; qty.
 *
 * <p><strong>NO pessimistic lock (Q4 parked):</strong> only the inherited {@code @Version} guards
 * concurrent aggregate writes; the {@code PESSIMISTIC_WRITE} target is CR-08-Q4 + ADR-0017 (AC-STK-10).
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final PharmacyMedicineRepository pharmacyMedicineRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockMovementRepository stockMovementRepository;

    /** A consumed-lot record returned to the caller for clinical lot-traceability. */
    public record ConsumedLot(String batchNo, LocalDate manufacturedDate,
                              LocalDate expiryDate, BigDecimal qty) {
    }

    // =========================================================================
    // Decrement (dispense / transfer-out)
    // =========================================================================

    /**
     * Decrement aggregate stock by {@code qty}, walk FEFO lots, append a stock-card OUT row.
     *
     * @param pharmacyUid  the pharmacy (stock source — already server-validated by the caller)
     * @param medicineUid  the medicine
     * @param qty          the quantity to issue
     * @param movementType the ledger classifier (DISPENSE / TRANSFER_OUT)
     * @param reference     the verbatim legacy reference string for the stock-card row
     * @param ctx          audit context (business day, timestamp)
     * @return the lots consumed (FEFO order), for clinical lot-trace
     * @throws NotFoundException          if no aggregate row exists for (pharmacy, medicine)
     * @throws InsufficientStockException if aggregate stock &lt; qty (verbatim legacy refusal)
     */
    @Transactional
    public List<ConsumedLot> decrementFefo(String pharmacyUid, String medicineUid, BigDecimal qty,
                                           MovementType movementType, String reference,
                                           TxAuditContext ctx) {
        PharmacyMedicine pm = pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid)
                .orElseThrow(() -> new NotFoundException(
                        "No stock record for medicine in this pharmacy"));

        BigDecimal newBalance = pm.decrement(qty);              // hard negative-stock gate first
        List<ConsumedLot> consumed = walkFefo(pm, qty);         // legacy null-expiry exclusion + id-ASC

        stockMovementRepository.save(new StockMovement(
                pm, movementType, BigDecimal.ZERO, qty, newBalance,
                reference, instant(ctx), ctx.dayUid()));
        return consumed;
    }

    // =========================================================================
    // Increment (transfer-in credit, no lot)
    // =========================================================================

    /**
     * Increment aggregate stock by {@code qty} + a stock-card IN row, WITHOUT creating a lot (the
     * reproduced p2p RN gap — 08b). Skips the row entirely when {@code qty <= 0} (N2).
     */
    @Transactional
    public void increment(String pharmacyUid, String medicineUid, BigDecimal qty,
                          MovementType movementType, String reference, TxAuditContext ctx) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        PharmacyMedicine pm = resolveOrCreate(pharmacyUid, medicineUid, ctx);
        BigDecimal newBalance = pm.increment(qty);
        stockMovementRepository.save(new StockMovement(
                pm, movementType, qty, BigDecimal.ZERO, newBalance,
                reference, instant(ctx), ctx.dayUid()));
    }

    /** Receive a new lot (creates a {@link StockBatch} + increments aggregate + IN card). */
    @Transactional
    public void receiveLot(String pharmacyUid, String medicineUid, String batchNo,
                           LocalDate manufacturedDate, LocalDate expiryDate, BigDecimal qty,
                           String reference, TxAuditContext ctx) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        PharmacyMedicine pm = resolveOrCreate(pharmacyUid, medicineUid, ctx);
        BigDecimal newBalance = pm.increment(qty);
        stockBatchRepository.save(new StockBatch(
                pm, batchNo, manufacturedDate, expiryDate, qty, ctx.dayUid()));
        stockMovementRepository.save(new StockMovement(
                pm, MovementType.TRANSFER_IN, qty, BigDecimal.ZERO, newBalance,
                reference, instant(ctx), ctx.dayUid()));
    }

    // =========================================================================
    // Manual overwrite (N16 / AC-STK-13) + opening stock (N15 / AC-STK-12)
    // =========================================================================

    /**
     * Manual stock OVERWRITE (absolute set, not delta; rejects negative; ADJUSTMENT card; NO batch
     * effect) — verbatim legacy {@code update_stock} (PharmacyResource.java:199-231).
     */
    @Transactional
    public void overwriteStock(String pharmacyUid, String medicineUid, BigDecimal newStock,
                               TxAuditContext ctx) {
        PharmacyMedicine pm = resolveOrCreate(pharmacyUid, medicineUid, ctx);
        BigDecimal balance = pm.overwrite(newStock);
        stockMovementRepository.save(new StockMovement(
                pm, MovementType.ADJUSTMENT, balance, BigDecimal.ZERO, balance,
                "Stock Update", instant(ctx), ctx.dayUid()));
    }

    /**
     * Ensure a zero-stock {@link PharmacyMedicine} row + OPENING stock-card exist for
     * (pharmacy, medicine) — verbatim legacy opening-stock eager creation
     * (PharmacyServiceImpl.java:67-91). Idempotent.
     */
    @Transactional
    public void ensureOpeningStock(String pharmacyUid, String medicineUid, TxAuditContext ctx) {
        if (pharmacyMedicineRepository.existsByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid)) {
            return;
        }
        PharmacyMedicine pm = pharmacyMedicineRepository.save(
                new PharmacyMedicine(pharmacyUid, medicineUid, ctx.dayUid()));
        stockMovementRepository.save(new StockMovement(
                pm, MovementType.OPENING, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "Opening stock, pharmacy registration", instant(ctx), ctx.dayUid()));
    }

    // =========================================================================
    // Private — FEFO walk
    // =========================================================================

    private List<ConsumedLot> walkFefo(PharmacyMedicine pm, BigDecimal qty) {
        List<ConsumedLot> consumed = new ArrayList<>();
        BigDecimal remaining = qty;

        // Legacy getEarlierBatch: dated lots first (excludes null-expiry when any dated lot exists);
        // only when NO dated lot exists, fall back to null-expiry lots ordered by id (FIFO).
        List<StockBatch> dated = stockBatchRepository.findDatedForFefo(pm);
        List<StockBatch> pool = !dated.isEmpty()
                ? dated
                : stockBatchRepository.findUndatedForFefo(pm);

        for (StockBatch lot : pool) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal take = lot.getRemainingQty().min(remaining);
            lot.draw(take);
            consumed.add(new ConsumedLot(
                    lot.getBatchNo(), lot.getManufacturedDate(), lot.getExpiryDate(), take));
            remaining = remaining.subtract(take);
        }

        // Defect-correction (AC-RX-DSP-14): legacy swallowed a batch shortfall in an empty catch;
        // here we surface it. On the happy path the aggregate gate guarantees this never trips.
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new InsufficientStockException(
                    "Batch stock is insufficient to satisfy the requested qty");
        }
        return consumed;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private PharmacyMedicine resolveOrCreate(String pharmacyUid, String medicineUid,
                                             TxAuditContext ctx) {
        return pharmacyMedicineRepository
                .findByPharmacyUidAndMedicineUid(pharmacyUid, medicineUid)
                .orElseGet(() -> pharmacyMedicineRepository.save(
                        new PharmacyMedicine(pharmacyUid, medicineUid, ctx.dayUid())));
    }

    private static Instant instant(TxAuditContext ctx) {
        return ctx.timestamp() != null ? ctx.timestamp() : Instant.now();
    }
}
