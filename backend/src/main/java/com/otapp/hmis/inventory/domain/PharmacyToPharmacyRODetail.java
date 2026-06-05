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

/** Pharmacy→Pharmacy RO line (inc-08b chunk 7). Request-only; medicine + qty; moves no stock. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_ro_details")
public class PharmacyToPharmacyRODetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_ro_id", nullable = false, updatable = false)
    private PharmacyToPharmacyRO pharmacyToPharmacyRO;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    @NotNull
    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedQty;

    public PharmacyToPharmacyRODetail(PharmacyToPharmacyRO ro, String medicineUid,
                                      BigDecimal orderedQty) {
        this.pharmacyToPharmacyRO = ro;
        this.medicineUid = medicineUid;
        this.orderedQty = orderedQty;
    }
}
