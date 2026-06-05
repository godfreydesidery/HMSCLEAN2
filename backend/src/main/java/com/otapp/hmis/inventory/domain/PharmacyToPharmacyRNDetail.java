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
 * Pharmacy→Pharmacy RN line (inc-08b chunk 7). {@code receivedQty} auto-filled from the TO detail's
 * {@code transferedQty} at RN create (1:1, not re-entered). The re-parented trace batches are
 * display-only (NOT promoted to PharmacyMedicineBatch — Q7 gap).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_rn_details")
public class PharmacyToPharmacyRNDetail extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_rn_id", nullable = false, updatable = false)
    private PharmacyToPharmacyRN pharmacyToPharmacyRN;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    @NotNull
    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal orderedQty;

    @Column(name = "received_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @OneToMany(mappedBy = "pharmacyToPharmacyRNDetail", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PharmacyToPharmacyBatch> batches = new ArrayList<>();

    public PharmacyToPharmacyRNDetail(PharmacyToPharmacyRN rn, String medicineUid,
                                      BigDecimal orderedQty, BigDecimal receivedQty) {
        this.pharmacyToPharmacyRN = rn;
        this.medicineUid = medicineUid;
        this.orderedQty = orderedQty;
        this.receivedQty = receivedQty;
    }

    public void attachBatch(PharmacyToPharmacyBatch batch) {
        this.batches.add(batch);
    }
}
