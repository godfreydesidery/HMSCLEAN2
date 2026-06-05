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
 * Purchase ledger row (inc-08b; legacy Purchase.java:40-41). Written by GRN.approve per detail with
 * {@code receivedQty > 0} when the GRN is LPO-linked: qty=receivedQty, amount=receivedQty×price
 * (NUMERIC(19,2), the legacy double product migrated). Feeds the purchase/daily-purchase reports (N1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "purchases")
public class Purchase extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_received_note_id", updatable = false)
    private GoodsReceivedNote goodsReceivedNote;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    public Purchase(GoodsReceivedNote grn, String itemUid, BigDecimal qty, BigDecimal amount,
                    String dayUid) {
        this.goodsReceivedNote = grn;
        this.itemUid = itemUid;
        this.qty = qty;
        this.amount = amount;
        this.businessDayUid = dayUid;
    }
}
