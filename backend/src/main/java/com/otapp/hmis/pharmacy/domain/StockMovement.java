package com.otapp.hmis.pharmacy.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only stock-card ledger row — a re-model of the legacy {@code PharmacyStockCard}
 * (PharmacyStockCard.java:37-57; inc-08a, AC-STK-05/06).
 *
 * <p><strong>Append-only by application contract</strong> — no UPDATE/DELETE repository surface
 * (the precise immutability/role-grant mechanism is BLOCKED on security-architect, AC-STK-07).
 * {@code runningBalance} is a STORED post-movement snapshot of {@link PharmacyMedicine#getStock()},
 * NOT recomputed. The {@link MovementType} is the net-new typed vehicle; the verbatim legacy
 * {@code reference} string is STILL persisted so the stock-card report keeps parity (AC-STK-06).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "stock_movement")
public class StockMovement extends AuditableEntity {

    @NotBlank
    @Column(name = "pharmacy_uid", length = 26, nullable = false, updatable = false)
    private String pharmacyUid;

    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    /** Intra-module link to the aggregate row (@ManyToOne, nullable for safety — V39 FK nullable). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pharmacy_medicine_id", updatable = false)
    private PharmacyMedicine pharmacyMedicine;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", length = 20, nullable = false, updatable = false)
    private MovementType movementType;

    @Column(name = "qty_in", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyIn = BigDecimal.ZERO;

    @Column(name = "qty_out", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyOut = BigDecimal.ZERO;

    /** Stored post-movement snapshot of the aggregate stock (NOT recomputed). */
    @Column(name = "running_balance", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal runningBalance;

    /** Verbatim legacy reference string (the original type-conveying free-text). */
    @Column(name = "reference", columnDefinition = "TEXT", updatable = false)
    private String reference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    /**
     * Append a ledger row against an aggregate stock row (derives pharmacy + medicine from it).
     *
     * @param pharmacyMedicine the owning aggregate row (intra-module FK)
     * @param movementType     the typed classifier
     * @param qtyIn            quantity received (0 for an out movement)
     * @param qtyOut           quantity issued (0 for an in movement)
     * @param runningBalance   the post-movement aggregate balance snapshot
     * @param reference        the verbatim legacy reference string
     * @param occurredAt       the movement timestamp
     * @param businessDayUid   the business day uid
     */
    public StockMovement(PharmacyMedicine pharmacyMedicine,
                         MovementType movementType, BigDecimal qtyIn, BigDecimal qtyOut,
                         BigDecimal runningBalance, String reference, Instant occurredAt,
                         String businessDayUid) {
        this.pharmacyMedicine = pharmacyMedicine;
        this.pharmacyUid = pharmacyMedicine.getPharmacyUid();
        this.medicineUid = pharmacyMedicine.getMedicineUid();
        this.movementType = movementType;
        this.qtyIn = qtyIn != null ? qtyIn : BigDecimal.ZERO;
        this.qtyOut = qtyOut != null ? qtyOut : BigDecimal.ZERO;
        this.runningBalance = runningBalance;
        this.reference = reference;
        this.occurredAt = occurredAt;
        this.businessDayUid = businessDayUid;
    }
}
