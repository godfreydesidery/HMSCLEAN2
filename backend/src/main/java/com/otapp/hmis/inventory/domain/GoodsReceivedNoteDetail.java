package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * GRN line item (inc-08b). orderedQty seeded from the LPO line; receivedQty entered then verified.
 * Verification (NOT-VERIFIED → VERIFIED) requires {@code receivedQty <= orderedQty} AND
 * {@code sum(batch.qty) == receivedQty} — the ONLY quantity reconciliation in the lane
 * (GoodsReceivedNoteResource.java:218-270). NO three-way match (Q3).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "goods_received_note_details")
public class GoodsReceivedNoteDetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_received_note_id", nullable = false, updatable = false)
    private GoodsReceivedNote goodsReceivedNote;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @NotNull
    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedQty;

    @Column(name = "received_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @NotNull
    @Convert(converter = GrnDetailStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private GrnDetailStatus status = GrnDetailStatus.NOT_VERIFIED;

    @OneToMany(mappedBy = "goodsReceivedNoteDetail", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<GoodsReceivedNoteDetailBatch> batches = new ArrayList<>();

    public GoodsReceivedNoteDetail(GoodsReceivedNote grn, String itemUid,
                                   BigDecimal orderedQty, BigDecimal price) {
        this.goodsReceivedNote = grn;
        this.itemUid = itemUid;
        this.orderedQty = orderedQty;
        this.receivedQty = BigDecimal.ZERO;
        this.price = price;
        this.status = GrnDetailStatus.NOT_VERIFIED;
    }

    public void addBatch(GoodsReceivedNoteDetailBatch batch) {
        this.batches.add(batch);
    }

    /** Enter receivedQty (PENDING GRN, not-yet-verified detail). Guards per :176-216. */
    public void setReceivedQty(BigDecimal received) {
        if (this.status == GrnDetailStatus.VERIFIED) {
            throw new InvalidPatientOperationException("Detail already verified");
        }
        if (received == null || received.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidPatientOperationException("Received qty must not be less than zero");
        }
        if (received.compareTo(this.orderedQty) > 0) {
            throw new InvalidPatientOperationException("Received qty must not exceed ordered qty");
        }
        this.receivedQty = received;
    }

    /**
     * Verify: requires sum(batch.qty) == receivedQty (the only reconciliation). Legacy :218-270.
     */
    public void verify() {
        if (this.status == GrnDetailStatus.VERIFIED) {
            throw new InvalidPatientOperationException("Detail already verified");
        }
        BigDecimal batchSum = batches.stream()
                .map(GoodsReceivedNoteDetailBatch::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (batchSum.compareTo(this.receivedQty) != 0) {
            throw new InvalidPatientOperationException(
                    "Batch quantities are not equal to total received quantities");
        }
        this.status = GrnDetailStatus.VERIFIED;
    }
}
