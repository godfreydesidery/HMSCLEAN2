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
 * Pharmacy→Pharmacy TO line (inc-08b chunk 7). 1:1 quantity (NO coefficient — both sides pharmacies,
 * D9): {@code transferedQty} accumulated via add_batch, cumulative ≤ orderedQty
 * (InternalOrderResource.java:1255-1260).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_to_details")
public class PharmacyToPharmacyTODetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_to_id", nullable = false, updatable = false)
    private PharmacyToPharmacyTO pharmacyToPharmacyTO;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    @NotNull
    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedQty;

    @Column(name = "transfered_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal transferedQty = BigDecimal.ZERO;

    public PharmacyToPharmacyTODetail(PharmacyToPharmacyTO to, String medicineUid,
                                      BigDecimal orderedQty) {
        this.pharmacyToPharmacyTO = to;
        this.medicineUid = medicineUid;
        this.orderedQty = orderedQty;
        this.transferedQty = BigDecimal.ZERO;
    }

    /** Accumulate a batch's qty (1:1); cumulative ≤ ordered (verbatim legacy guard). */
    public void accumulate(BigDecimal qty) {
        BigDecimal newTotal = this.transferedQty.add(qty);
        if (newTotal.compareTo(this.orderedQty) > 0) {
            throw new InvalidPatientOperationException("Can not transfer more than ordered qty");
        }
        this.transferedQty = newTotal;
    }
}
