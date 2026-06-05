package com.otapp.hmis.inventory.application;

import com.otapp.hmis.inventory.domain.StoreItem;
import com.otapp.hmis.inventory.domain.StoreItemBatch;
import com.otapp.hmis.inventory.domain.StoreItemBatchRepository;
import com.otapp.hmis.inventory.domain.StoreItemRepository;
import com.otapp.hmis.inventory.domain.StoreMovementType;
import com.otapp.hmis.inventory.domain.StoreStockMovement;
import com.otapp.hmis.inventory.domain.StoreStockMovementRepository;
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
 * Store stock engine (inc-08b) — the single owner of {@link StoreItem} aggregate mutations, the FEFO
 * {@link StoreItemBatch} walk, and the append-only {@link StoreStockMovement} ledger. Mirror of the
 * pharmacy {@code StockService}. Used by GRN approve (credit + lot creation) and the store→pharmacy
 * transfer goods-issue (decrement, chunk 6).
 *
 * <p>FEFO = legacy null-expiry exclusion + id-ASC (Q8 baseline). Hard negative-stock refusal on
 * decrement (verbatim legacy). NO pessimistic lock (Q4 parked) — inherited {@code @Version} only.
 */
@Service
@RequiredArgsConstructor
public class StoreStockService {

    private final StoreItemRepository storeItemRepository;
    private final StoreItemBatchRepository storeItemBatchRepository;
    private final StoreStockMovementRepository storeStockMovementRepository;

    public record ConsumedLot(String batchNo, LocalDate manufacturedDate,
                              LocalDate expiryDate, BigDecimal qty) {
    }

    /**
     * Credit store stock for a received GRN batch: increment aggregate, create a NEW StoreItemBatch
     * (no merge — legacy), and (when qty&gt;0) append a RECEIPT stock-card row. Returns the managed
     * {@link StoreItem} so the caller can chain.
     */
    @Transactional
    public StoreItem receiveBatch(String storeUid, String itemUid, String batchNo,
                                  LocalDate manufacturedDate, LocalDate expiryDate, BigDecimal qty,
                                  String reference, TxAuditContext ctx) {
        StoreItem si = resolveOrCreate(storeUid, itemUid, ctx);
        StoreItemBatch batch = new StoreItemBatch(si, batchNo, manufacturedDate, expiryDate, qty,
                ctx.dayUid());
        storeItemBatchRepository.save(batch);
        if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newBalance = si.increment(qty);
            storeStockMovementRepository.save(new StoreStockMovement(
                    si, StoreMovementType.RECEIPT, qty, BigDecimal.ZERO, newBalance,
                    reference, instant(ctx), ctx.dayUid()));
        }
        return si;
    }

    /** Increment aggregate stock + RECEIPT card without creating a lot. Skips a zero-qty line (N2). */
    @Transactional
    public void creditAggregate(String storeUid, String itemUid, BigDecimal qty,
                                String reference, TxAuditContext ctx) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        StoreItem si = resolveOrCreate(storeUid, itemUid, ctx);
        BigDecimal newBalance = si.increment(qty);
        storeStockMovementRepository.save(new StoreStockMovement(
                si, StoreMovementType.RECEIPT, qty, BigDecimal.ZERO, newBalance,
                reference, instant(ctx), ctx.dayUid()));
    }

    /**
     * Decrement aggregate store stock by {@code qty}, walk FEFO lots, append a TRANSFER_OUT card.
     * Used by the store→pharmacy transfer goods-issue (chunk 6).
     */
    @Transactional
    public List<ConsumedLot> decrementFefo(String storeUid, String itemUid, BigDecimal qty,
                                           StoreMovementType movementType, String reference,
                                           TxAuditContext ctx) {
        StoreItem si = storeItemRepository.findByStoreUidAndItemUid(storeUid, itemUid)
                .orElseThrow(() -> new NotFoundException("No stock record for item in this store"));
        BigDecimal newBalance = si.decrement(qty);          // hard negative-stock gate first
        List<ConsumedLot> consumed = walkFefo(si, qty);
        storeStockMovementRepository.save(new StoreStockMovement(
                si, movementType, BigDecimal.ZERO, qty, newBalance, reference, instant(ctx),
                ctx.dayUid()));
        return consumed;
    }

    @Transactional
    public void ensureOpeningStock(String storeUid, String itemUid, TxAuditContext ctx) {
        if (storeItemRepository.existsByStoreUidAndItemUid(storeUid, itemUid)) {
            return;
        }
        StoreItem si = storeItemRepository.save(new StoreItem(storeUid, itemUid, ctx.dayUid()));
        storeStockMovementRepository.save(new StoreStockMovement(
                si, StoreMovementType.OPENING, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "Opening stock, store registration", instant(ctx), ctx.dayUid()));
    }

    // -------------------------------------------------------------------------

    private List<ConsumedLot> walkFefo(StoreItem si, BigDecimal qty) {
        List<ConsumedLot> consumed = new ArrayList<>();
        BigDecimal remaining = qty;
        List<StoreItemBatch> dated = storeItemBatchRepository.findDatedForFefo(si);
        List<StoreItemBatch> pool = !dated.isEmpty()
                ? dated
                : storeItemBatchRepository.findUndatedForFefo(si);
        for (StoreItemBatch lot : pool) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal take = lot.getRemainingQty().min(remaining);
            lot.draw(take);
            consumed.add(new ConsumedLot(lot.getBatchNo(), lot.getManufacturedDate(),
                    lot.getExpiryDate(), take));
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new InsufficientStockException(
                    "Batch stock is insufficient to satisfy the requested qty");
        }
        return consumed;
    }

    private StoreItem resolveOrCreate(String storeUid, String itemUid, TxAuditContext ctx) {
        return storeItemRepository.findByStoreUidAndItemUid(storeUid, itemUid)
                .orElseGet(() -> storeItemRepository.save(
                        new StoreItem(storeUid, itemUid, ctx.dayUid())));
    }

    private static Instant instant(TxAuditContext ctx) {
        return ctx.timestamp() != null ? ctx.timestamp() : Instant.now();
    }
}
