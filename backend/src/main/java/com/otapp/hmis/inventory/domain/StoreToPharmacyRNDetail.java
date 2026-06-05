package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
 * Store→Pharmacy RN line (inc-08b chunk 6). Snapshots the TO detail quantities on RN create; the
 * transferred batches are re-parented here (StoreToPharmacyRNServiceImpl.java:88-141). The pharmacy
 * stock credit at RN complete uses {@code receivedPharmacySkuQty} + the re-parented batches.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_to_pharmacy_rn_details")
public class StoreToPharmacyRNDetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_to_pharmacy_rn_id", nullable = false, updatable = false)
    private StoreToPharmacyRN storeToPharmacyRN;

    @NotBlank
    @Column(name = "item_uid", length = 26, nullable = false, updatable = false)
    private String itemUid;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    @NotNull
    @Column(name = "ordered_pharmacy_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedPharmacySkuQty;

    @Column(name = "received_pharmacy_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedPharmacySkuQty = BigDecimal.ZERO;

    @Column(name = "received_store_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedStoreSkuQty = BigDecimal.ZERO;

    /** The re-parented transfer batches (become destination PharmacyMedicineBatch at RN complete). */
    @OneToMany(mappedBy = "storeToPharmacyRNDetail", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StoreToPharmacyBatch> batches = new ArrayList<>();

    public StoreToPharmacyRNDetail(StoreToPharmacyRN rn, String itemUid, String medicineUid,
                                   BigDecimal orderedPharmacySkuQty,
                                   BigDecimal receivedPharmacySkuQty,
                                   BigDecimal receivedStoreSkuQty) {
        this.storeToPharmacyRN = rn;
        this.itemUid = itemUid;
        this.medicineUid = medicineUid;
        this.orderedPharmacySkuQty = orderedPharmacySkuQty;
        this.receivedPharmacySkuQty = receivedPharmacySkuQty;
        this.receivedStoreSkuQty = receivedStoreSkuQty;
    }

    public void attachBatch(StoreToPharmacyBatch batch) {
        this.batches.add(batch);
    }
}
