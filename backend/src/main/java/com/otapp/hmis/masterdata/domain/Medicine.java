package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pharmacy / dispensing SKU (legacy {@code com.orbix.api.domain.Medicine}, Medicine.java:37-63).
 *
 * <p>The cash dispensing price is stored directly on this row ({@code price double},
 * Medicine.java:51-52). Insurance fees live in the {@code service_prices} table (CR-04).
 *
 * <p>{@code type} is a free-text string (comment "ORAL, ETC" — Medicine.java:49-50).
 * {@code category} defaults to {@code "MEDICINE"} (Medicine.java:56 — hard-coded literal).
 * {@code uom} is a free-text label — NO UoM lookup table (CR-07).
 * No {@code ClinicType}, no dosage/route/frequency entities (CR-07).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "medicines")
public class Medicine extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Free-text medicine type (e.g. ORAL, INJECTION) — Medicine.java:49-50. */
    @NotBlank
    @Column(name = "type", length = 80, nullable = false)
    private String type;

    /** Cash / self-pay dispensing price. BigDecimal replaces legacy {@code double} (pre-approved). */
    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /** Free-text unit of measure (Medicine.java:53). */
    @Column(name = "uom", length = 40)
    private String uom;

    /** Free-text category, defaults to "MEDICINE" (Medicine.java:56). */
    @NotBlank
    @Column(name = "category", length = 80, nullable = false)
    private String category = "MEDICINE";

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /** Business constructor — all mandatory fields. */
    public Medicine(String code, String name, String description,
                    String type, BigDecimal price, String uom,
                    String category, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.type = type;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.category = category != null ? category : "MEDICINE";
        this.active = active;
    }

    /** Mutates all mutable fields in one call. */
    public void update(String code, String name, String description,
                       String type, BigDecimal price, String uom,
                       String category, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.type = type;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.category = category != null ? category : "MEDICINE";
        this.active = active;
    }
}
