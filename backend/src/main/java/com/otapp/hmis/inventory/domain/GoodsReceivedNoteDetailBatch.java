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
 * A batch line on a GRN detail (inc-08b; legacy GoodsReceivedNoteDetailBatch). On GRN approve each
 * of these is copied into a NEW {@code StoreItemBatch} (no merge — GoodsReceivedNoteServiceImpl.java:152-163).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "goods_received_note_detail_batches")
public class GoodsReceivedNoteDetailBatch extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_received_note_detail_id", nullable = false, updatable = false)
    private GoodsReceivedNoteDetail goodsReceivedNoteDetail;

    @NotBlank
    @Column(name = "batch_no", length = 100, nullable = false)
    private String batchNo;

    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty;

    public GoodsReceivedNoteDetailBatch(GoodsReceivedNoteDetail detail, String batchNo,
                                        LocalDate manufacturedDate, LocalDate expiryDate,
                                        BigDecimal qty) {
        this.goodsReceivedNoteDetail = detail;
        this.batchNo = batchNo;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.qty = qty;
    }
}
