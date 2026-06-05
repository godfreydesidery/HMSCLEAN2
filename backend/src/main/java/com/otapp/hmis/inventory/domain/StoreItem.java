package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InsufficientStockException;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Store aggregate stock — authoritative scalar balance per (store, item) (inc-08b; mirror of the
 * pharmacy {@link com.otapp.hmis.pharmacy.domain.PharmacyMedicine}). Re-model of legacy
 * {@code StoreItem.stock} (double → NUMERIC(19,6), ADR-0009 §3). The hard negative-stock REFUSAL on
 * decrement is the frozen parity gate (StoreToPharmacyTOServiceImpl.java:250-259); the DB CHECK is a
 * net-new backstop.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_item")
public class StoreItem extends AuditableEntity {

    @NotBlank
    @Column(name = "store_uid", length = 26, nullable = false, updatable = false)
    private String storeUid;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @Column(name = "stock", nullable = false, precision = 19, scale = 6)
    private BigDecimal stock = BigDecimal.ZERO;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public StoreItem(String storeUid, String itemUid, String businessDayUid) {
        this.storeUid = storeUid;
        this.itemUid = itemUid;
        this.stock = BigDecimal.ZERO;
        this.businessDayUid = businessDayUid;
    }

    /** Decrement, enforcing the legacy hard negative-stock refusal (→ 422 INSUFFICIENT_STOCK). */
    public BigDecimal decrement(BigDecimal qty) {
        if (this.stock.compareTo(qty) < 0) {
            throw new InsufficientStockException("Available stock is less than the transfer qty");
        }
        this.stock = this.stock.subtract(qty);
        return this.stock;
    }

    public BigDecimal increment(BigDecimal qty) {
        this.stock = this.stock.add(qty);
        return this.stock;
    }

    public BigDecimal overwrite(BigDecimal newStock) {
        if (newStock == null || newStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidPatientOperationException("Stock cannot be negative");
        }
        this.stock = newStock;
        return this.stock;
    }
}
