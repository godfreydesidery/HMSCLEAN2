package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Transfer batch (inc-08b chunk 6; legacy StoreToPharmacyBatch). Manually entered on the TO detail
 * (add_batch); on RN create it is RE-PARENTED from the TO detail onto the RN detail
 * (StoreToPharmacyRNServiceImpl.java:88-141) and ultimately becomes a destination
 * {@code PharmacyMedicineBatch}. Its identity (no/dates) is independent of the FEFO-consumed
 * {@code StoreItemBatch} (the legacy decoupling — InternalOrderResource.java:595-610).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_to_pharmacy_batches")
public class StoreToPharmacyBatch extends AuditableEntity {

    /** Parent at TO time; null after re-parenting onto the RN detail. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_to_pharmacy_to_detail_id")
    private StoreToPharmacyTODetail storeToPharmacyTODetail;

    /** Parent after RN re-parenting. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_to_pharmacy_rn_detail_id")
    private StoreToPharmacyRNDetail storeToPharmacyRNDetail;

    @NotBlank
    @Column(name = "batch_no", length = 100, nullable = false)
    private String batchNo;

    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "store_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal storeSkuQty = BigDecimal.ZERO;

    @Column(name = "pharmacy_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal pharmacySkuQty = BigDecimal.ZERO;

    public StoreToPharmacyBatch(StoreToPharmacyTODetail toDetail, String batchNo,
                                LocalDate manufacturedDate, LocalDate expiryDate,
                                BigDecimal storeSkuQty, BigDecimal pharmacySkuQty) {
        this.storeToPharmacyTODetail = toDetail;
        this.batchNo = batchNo;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.storeSkuQty = storeSkuQty;
        this.pharmacySkuQty = pharmacySkuQty;
    }

    /** Re-parent this batch from its TO detail onto the RN detail (RN create). */
    public void reparentToRn(StoreToPharmacyRNDetail rnDetail) {
        this.storeToPharmacyRNDetail = rnDetail;
    }
}
