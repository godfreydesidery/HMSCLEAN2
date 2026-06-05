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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LPO line item (inc-08b). {@code price} is COPIED from the {@code SupplierItemPrice} row at add-time
 * (NOT from the request — legacy LocalPurchaseOrderServiceImpl.java:231-273). Item is a loose uid.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "local_purchase_order_details")
public class LocalPurchaseOrderDetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_purchase_order_id", nullable = false, updatable = false)
    private LocalPurchaseOrder localPurchaseOrder;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty;

    /** Copied from SupplierItemPrice.price (NOT the request). NUMERIC(19,2). */
    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    public LocalPurchaseOrderDetail(LocalPurchaseOrder order, String itemUid,
                                    BigDecimal qty, BigDecimal price) {
        this.localPurchaseOrder = order;
        this.itemUid = itemUid;
        this.qty = qty;
        this.price = price;
    }
}
