package com.otapp.hmis.pharmacy.domain;

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
 * Per-lot FEFO inventory — a re-model of the legacy {@code PharmacyMedicineBatch}
 * (PharmacyMedicineBatch.java:35-53; inc-08a, AC-STK-03/04).
 *
 * <p><strong>received_qty / remaining_qty split (net-new derived, AC-STK-04):</strong> legacy holds
 * a single in-place-decremented {@code qty}; this re-model splits it into {@code receivedQty}
 * (immutable intake) and {@code remainingQty} (the parity invariant — reproduces the legacy
 * post-decrement value). Dates are nullable EXACTLY as legacy (a null-expiry lot is permitted, and
 * is EXCLUDED from FEFO selection when any dated lot exists — the Q8 baseline).
 *
 * <p>{@code pharmacy_medicine_id} is an intra-module real FK to {@link PharmacyMedicine} (no cascade
 * — legacy has none). {@code pharmacy_uid}/{@code medicine_uid} duplicate the loose masterdata uids
 * for query convenience.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "stock_batch")
public class StockBatch extends AuditableEntity {

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    /** Intra-module real FK (@ManyToOne) to the owning aggregate row (no cascade — legacy has none). */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_medicine_id", nullable = false, updatable = false)
    private PharmacyMedicine pharmacyMedicine;

    /** Free-text batch number (no generator — supplied on receipt). */
    @NotBlank
    @Column(name = "batch_no", length = 100, nullable = false)
    private String batchNo;

    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    /** Nullable expiry — a null-expiry lot is EXCLUDED from FEFO when any dated lot exists (Q8). */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Immutable intake quantity (net-new). */
    @Column(name = "received_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    /** Remaining quantity — reproduces the legacy in-place-decremented qty (AC-STK-04). */
    @Column(name = "remaining_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal remainingQty = BigDecimal.ZERO;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    /**
     * Receive a new lot into the pharmacy.
     *
     * @param pharmacyMedicine the owning aggregate row (intra-module FK); supplies pharmacy+medicine
     * @param batchNo          free-text batch number
     * @param manufacturedDate manufacture date (nullable)
     * @param expiryDate       expiry date (nullable)
     * @param receivedQty      the intake quantity (also the initial remaining)
     * @param businessDayUid   the receiving business day uid
     */
    public StockBatch(PharmacyMedicine pharmacyMedicine,
                      String batchNo, LocalDate manufacturedDate, LocalDate expiryDate,
                      BigDecimal receivedQty, String businessDayUid) {
        this.pharmacyMedicine = pharmacyMedicine;
        this.pharmacyUid = pharmacyMedicine.getPharmacyUid();
        this.medicineUid = pharmacyMedicine.getMedicineUid();
        this.batchNo = batchNo;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.receivedQty = receivedQty != null ? receivedQty : BigDecimal.ZERO;
        this.remainingQty = this.receivedQty;
        this.businessDayUid = businessDayUid;
    }

    /**
     * Draw {@code qty} from this lot's remaining quantity. The caller (FEFO walk) guarantees
     * {@code qty <= remainingQty}; this method does not re-validate stock (the aggregate gate owns
     * the hard refusal). Returns the amount actually drawn.
     */
    public BigDecimal draw(BigDecimal qty) {
        this.remainingQty = this.remainingQty.subtract(qty);
        return qty;
    }
}
