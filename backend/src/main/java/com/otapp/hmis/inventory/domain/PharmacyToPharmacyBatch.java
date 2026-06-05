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
 * Pharmacy→Pharmacy transfer trace batch (inc-08b chunk 7; legacy PharmacyToPharmacyBatch). Entered
 * on the TO detail (add_batch), re-parented onto the RN detail at RN create. <strong>NOT promoted to
 * a destination PharmacyMedicineBatch</strong> — display/traceability only (the reproduced p2p gap, Q7).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_to_pharmacy_batches")
public class PharmacyToPharmacyBatch extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_to_detail_id")
    private PharmacyToPharmacyTODetail pharmacyToPharmacyTODetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_to_pharmacy_rn_detail_id")
    private PharmacyToPharmacyRNDetail pharmacyToPharmacyRNDetail;

    @NotBlank
    @Column(name = "batch_no", length = 100, nullable = false)
    private String batchNo;

    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty = BigDecimal.ZERO;

    public PharmacyToPharmacyBatch(PharmacyToPharmacyTODetail toDetail, String batchNo,
                                   LocalDate manufacturedDate, LocalDate expiryDate, BigDecimal qty) {
        this.pharmacyToPharmacyTODetail = toDetail;
        this.batchNo = batchNo;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.qty = qty;
    }

    public void reparentToRn(PharmacyToPharmacyRNDetail rnDetail) {
        this.pharmacyToPharmacyRNDetail = rnDetail;
    }
}
