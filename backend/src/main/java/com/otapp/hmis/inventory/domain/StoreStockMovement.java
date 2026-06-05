package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Store append-only stock-card ledger (inc-08b; mirror of the pharmacy {@link
 * com.otapp.hmis.pharmacy.domain.StockMovement}). running_balance is a stored post-movement snapshot
 * of {@link StoreItem#getStock()}; the verbatim legacy reference string is persisted alongside the
 * typed {@link StoreMovementType}. Append-only by application contract.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_stock_movement")
public class StoreStockMovement extends AuditableEntity {

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_item_id", updatable = false)
    private StoreItem storeItem;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", length = 20, nullable = false, updatable = false)
    private StoreMovementType movementType;

    @Column(name = "qty_in", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyIn = BigDecimal.ZERO;

    @Column(name = "qty_out", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyOut = BigDecimal.ZERO;

    @Column(name = "running_balance", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal runningBalance;

    @Column(name = "reference", columnDefinition = "TEXT", updatable = false)
    private String reference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public StoreStockMovement(StoreItem storeItem, StoreMovementType movementType,
                              BigDecimal qtyIn, BigDecimal qtyOut, BigDecimal runningBalance,
                              String reference, Instant occurredAt, String businessDayUid) {
        this.storeItem = storeItem;
        this.storeUid = storeItem.getStoreUid();
        this.itemUid = storeItem.getItemUid();
        this.movementType = movementType;
        this.qtyIn = qtyIn != null ? qtyIn : BigDecimal.ZERO;
        this.qtyOut = qtyOut != null ? qtyOut : BigDecimal.ZERO;
        this.runningBalance = runningBalance;
        this.reference = reference;
        this.occurredAt = occurredAt;
        this.businessDayUid = businessDayUid;
    }
}
