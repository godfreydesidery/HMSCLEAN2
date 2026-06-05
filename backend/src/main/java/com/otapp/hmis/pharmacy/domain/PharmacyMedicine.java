package com.otapp.hmis.pharmacy.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InsufficientStockException;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pharmacy aggregate stock — the AUTHORITATIVE scalar balance per (pharmacy, medicine)
 * (inc-08a, AC-STK-01). A 1:1 re-model of the legacy {@code PharmacyMedicine.stock} plain double
 * (PharmacyMedicine.java:38), promoted to {@code NUMERIC(19,6)} (pre-approved double→BigDecimal,
 * ADR-0009 §3).
 *
 * <p><strong>This column IS the legacy aggregate</strong> — the {@code stock_movement} ledger and
 * {@code stock_batch} lots do NOT derive it (D6/D16: the planning-doc separate StockBalance entity
 * is rejected). Reads and writes go through {@link #decrement(BigDecimal)} / {@link #increment} /
 * {@link #overwrite} which enforce the legacy hard negative-stock REFUSAL (the frozen parity gate;
 * the DB {@code CHECK(stock >= 0)} is a net-new backstop).
 *
 * <p>Cross-module refs ({@code pharmacy_uid}, {@code medicine_uid}) are loose uids to masterdata
 * (no FK, ADR-0008 §1).
 *
 * <p>Legacy citations: PharmacyMedicine.java:35-48 (:38 scalar stock); negative-stock refusal
 * PatientResource.java:3243-3250, :6272-6273.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_medicine")
public class PharmacyMedicine extends AuditableEntity {

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    /** Authoritative scalar aggregate balance (legacy double → NUMERIC(19,6)). */
    @Column(name = "stock", nullable = false, precision = 19, scale = 6)
    private BigDecimal stock = BigDecimal.ZERO;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    /**
     * Open a fresh (pharmacy, medicine) stock row at zero (opening-stock eager creation, N15).
     *
     * @param pharmacyUid    the pharmacy this stock belongs to
     * @param medicineUid    the medicine
     * @param businessDayUid the opening business day uid
     */
    public PharmacyMedicine(String pharmacyUid, String medicineUid, String businessDayUid) {
        this.pharmacyUid = pharmacyUid;
        this.medicineUid = medicineUid;
        this.stock = BigDecimal.ZERO;
        this.businessDayUid = businessDayUid;
    }

    /**
     * Decrement the aggregate stock by {@code qty}, enforcing the legacy hard negative-stock refusal.
     *
     * @param qty the quantity to remove (non-negative)
     * @return the new balance
     * @throws InvalidPatientOperationException (mapped to 422 INSUFFICIENT_STOCK) when
     *         {@code stock < qty} — verbatim legacy refusal (PatientResource.java:3243-3250)
     */
    public BigDecimal decrement(BigDecimal qty) {
        if (this.stock.compareTo(qty) < 0) {
            throw new InsufficientStockException(
                    "Available stock is less than the requested qty");
        }
        this.stock = this.stock.subtract(qty);
        return this.stock;
    }

    /** Increment the aggregate stock by {@code qty} (receipt / transfer-in). */
    public BigDecimal increment(BigDecimal qty) {
        this.stock = this.stock.add(qty);
        return this.stock;
    }

    /**
     * Absolute OVERWRITE of the aggregate stock (manual stock-update, N16/AC-STK-13). Legacy SETs
     * the value (not a delta) and rejects negatives (PharmacyResource.java:211-214).
     *
     * @param newStock the absolute new value (must be non-negative)
     * @return the new balance
     */
    public BigDecimal overwrite(BigDecimal newStock) {
        if (newStock == null || newStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidPatientOperationException("Stock cannot be negative");
        }
        this.stock = newStock;
        return this.stock;
    }
}
