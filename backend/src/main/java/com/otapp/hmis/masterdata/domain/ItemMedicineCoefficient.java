package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Store-to-pharmacy unit-of-measure conversion factor
 * (legacy {@code com.orbix.api.domain.ItemMedicineCoefficient},
 * ItemMedicineCoefficient.java:34-60).
 *
 * <p>Semantics: {@code coefficient = medicineQty / itemQty} — computed by the service on
 * every create/update (ConversionCoefficientResource.java:95,101 / build-spec §5.3).
 * Conversion at point of use: {@code pharmacySKUQty = storeSKUQty * coefficient}
 * (InternalOrderResource.java:595, StoreToPharmacyTOServiceImpl.java:424).
 *
 * <p>The DB-level {@code UNIQUE(item_id, medicine_id)} constraint (and the CHECK constraints
 * for positive quantities) back the application validation, but the service checks first to
 * produce a human-readable 409/400 before the DB rejects the row.
 *
 * <p>Legacy {@code item} FK is {@code @OneToOne} but we use {@code @ManyToOne} here to preserve
 * the ability to list "all coefficients for a medicine" ({@code findAllByMedicine} —
 * ItemMedicineCoefficientRepository.java:32). The pair-uniqueness constraint encodes
 * the legacy one-per-pair invariant.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "item_medicine_coefficients")
public class ItemMedicineCoefficient extends AuditableEntity {

    /**
     * The computed ratio {@code medicineQty / itemQty}.
     * Set by the service; stored NUMERIC(19,6) (build-spec §5.3).
     */
    @NotNull
    @Column(name = "coefficient", nullable = false, precision = 19, scale = 6)
    private BigDecimal coefficient = BigDecimal.ZERO;

    /**
     * Store-side quantity of the ratio (must be &gt; 0).
     * CHECK constraint in DDL; also validated in service before insert.
     */
    @NotNull
    @Column(name = "item_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal itemQty = BigDecimal.ZERO;

    /**
     * Pharmacy-side quantity of the ratio (must be &gt; 0).
     * CHECK constraint in DDL; also validated in service before insert.
     */
    @NotNull
    @Column(name = "medicine_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal medicineQty = BigDecimal.ZERO;

    /**
     * Store/purchase SKU (legacy {@code @OneToOne ... optional=false, updatable=false},
     * ItemMedicineCoefficient.java:42-45). EAGER fetch to support DTO mapping without extra query.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private Item item;

    /**
     * Pharmacy/dispensing SKU (legacy {@code @ManyToOne ... optional=false, updatable=false},
     * ItemMedicineCoefficient.java:50-53). EAGER fetch to support DTO mapping.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "medicine_id", nullable = false, updatable = false)
    private Medicine medicine;

    /**
     * Business constructor.
     *
     * @param item        the store SKU (immutable after creation)
     * @param medicine    the pharmacy SKU (immutable after creation)
     * @param itemQty     store-side quantity (must be &gt; 0 — caller validates)
     * @param medicineQty pharmacy-side quantity (must be &gt; 0 — caller validates)
     * @param coefficient pre-computed {@code medicineQty / itemQty} (caller computes with scale 6)
     */
    public ItemMedicineCoefficient(Item item, Medicine medicine,
                                   BigDecimal itemQty, BigDecimal medicineQty,
                                   BigDecimal coefficient) {
        this.item = item;
        this.medicine = medicine;
        this.itemQty = itemQty;
        this.medicineQty = medicineQty;
        this.coefficient = coefficient;
    }

    /**
     * Mutates the quantity and coefficient fields.
     * The {@code item} and {@code medicine} FKs are {@code updatable=false} and must NOT be changed.
     *
     * @param itemQty     new store-side quantity (must be &gt; 0 — caller validates)
     * @param medicineQty new pharmacy-side quantity (must be &gt; 0 — caller validates)
     * @param coefficient newly computed {@code medicineQty / itemQty}
     */
    public void update(BigDecimal itemQty, BigDecimal medicineQty, BigDecimal coefficient) {
        this.itemQty = itemQty;
        this.medicineQty = medicineQty;
        this.coefficient = coefficient;
    }
}
