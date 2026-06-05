package com.otapp.hmis.inventory.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
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
 * Store→Pharmacy TO line (inc-08b chunk 6). Carries the store SKU (item) + pharmacy SKU (medicine),
 * the ordered pharmacy-SKU qty, and the running transferred quantities (accumulated via add_batch).
 * Conversion: {@code transferedPharmacySKUQty = transferedStoreSKUQty * coefficient}
 * (InternalOrderResource.java:595; cumulative ≤ ordered guard).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "store_to_pharmacy_to_details")
public class StoreToPharmacyTODetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_to_pharmacy_to_id", nullable = false, updatable = false)
    private StoreToPharmacyTO storeToPharmacyTO;

    /** Store SKU — null until the first add_batch sets it (legacy detail.item set at add_batch). */
    @Column(name = "item_uid", length = 26)
    private String itemUid;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    @NotNull
    @Column(name = "ordered_pharmacy_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedPharmacySkuQty;

    @Column(name = "transfered_store_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal transferedStoreSkuQty = BigDecimal.ZERO;

    @Column(name = "transfered_pharmacy_sku_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal transferedPharmacySkuQty = BigDecimal.ZERO;

    public StoreToPharmacyTODetail(StoreToPharmacyTO to, String medicineUid,
                                   BigDecimal orderedPharmacySkuQty) {
        this.storeToPharmacyTO = to;
        this.medicineUid = medicineUid;
        this.orderedPharmacySkuQty = orderedPharmacySkuQty;
        this.transferedStoreSkuQty = BigDecimal.ZERO;
        this.transferedPharmacySkuQty = BigDecimal.ZERO;
    }

    /**
     * Accumulate a batch's quantities (add_batch): set the store SKU (first batch fixes it; a
     * mismatched SKU on a later batch is rejected — InternalOrderResource.java:581-585), add the
     * store-SKU and the converted pharmacy-SKU ({@code storeSkuQty * coefficient}, full BigDecimal
     * precision). Cumulative pharmacy-SKU must not exceed the ordered pharmacy-SKU (:599-604).
     */
    public void accumulate(String itemUid, BigDecimal storeSkuQty, BigDecimal pharmacySkuQty) {
        if (this.itemUid == null) {
            this.itemUid = itemUid;
        } else if (!this.itemUid.equals(itemUid)) {
            throw new InvalidPatientOperationException("Batch item must match the detail item");
        }
        BigDecimal newPharmacyTotal = this.transferedPharmacySkuQty.add(pharmacySkuQty);
        if (newPharmacyTotal.compareTo(this.orderedPharmacySkuQty) > 0) {
            throw new InvalidPatientOperationException("Transfer qty exceeds the ordered qty");
        }
        this.transferedStoreSkuQty = this.transferedStoreSkuQty.add(storeSkuQty);
        this.transferedPharmacySkuQty = newPharmacyTotal;
    }
}
