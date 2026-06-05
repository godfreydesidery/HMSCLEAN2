package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Store per-lot FEFO inventory (inc-08b; mirror of the pharmacy {@link
 * com.otapp.hmis.pharmacy.domain.StockBatch}). Re-model of legacy {@code StoreItemBatch}: a NEW row
 * per GRN batch (no merge — GoodsReceivedNoteServiceImpl.java:152-163). received_qty/remaining_qty
 * split reproduces the legacy in-place-decremented qty.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_item_batch")
public class StoreItemBatch extends AuditableEntity {

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_item_id", nullable = false, updatable = false)
    private StoreItem storeItem;

    @NotBlank
    @Column(name = "batch_no", length = 100, nullable = false)
    private String batchNo;

    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "received_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @Column(name = "remaining_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal remainingQty = BigDecimal.ZERO;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public StoreItemBatch(StoreItem storeItem, String batchNo, LocalDate manufacturedDate,
                          LocalDate expiryDate, BigDecimal receivedQty, String businessDayUid) {
        this.storeItem = storeItem;
        this.storeUid = storeItem.getStoreUid();
        this.itemUid = storeItem.getItemUid();
        this.batchNo = batchNo;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.receivedQty = receivedQty != null ? receivedQty : BigDecimal.ZERO;
        this.remainingQty = this.receivedQty;
        this.businessDayUid = businessDayUid;
    }

    public BigDecimal draw(BigDecimal qty) {
        this.remainingQty = this.remainingQty.subtract(qty);
        return qty;
    }
}
