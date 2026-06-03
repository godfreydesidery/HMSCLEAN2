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
 * Lab test type catalog entry (legacy {@code com.orbix.api.domain.LabTestType},
 * LabTestType.java:43-71).
 *
 * <p>The {@code price} is the cash/self-pay rate; insurance prices live in
 * {@code service_prices} (CR-04).
 *
 * <p><b>Code-immutable-on-update quirk (AC-9.4):</b> on update, {@code code} is intentionally
 * NOT changed even if the caller provides a new value. This faithfully reproduces the legacy
 * {@code LabTestTypeServiceImpl.java:47-48} behaviour where the update branch re-derives
 * {@code code} from the already-persisted entity rather than from the incoming payload.
 * See {@link com.otapp.hmis.masterdata.application.LabTestTypeService#update} for the
 * authoritative implementation of this rule.
 *
 * <p>DO NOT implement {@code PUT /lab_test_types/update_by_code} — that endpoint mapping
 * does not exist in legacy (AC-9.5 — dead broken path in Angular).
 *
 * <p>The ranges collection (a named-string-label list) lives in {@link LabTestTypeRange}.
 * No analyte, reference range, or flag model exists in legacy (CR-05 — extraction §Q1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "lab_test_types")
public class LabTestType extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Cash / self-pay price. BigDecimal replaces legacy {@code double} (pre-approved). */
    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "uom", length = 40)
    private String uom;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /** Business constructor — all fields, including code (used only on CREATE). */
    public LabTestType(String code, String name, String description,
                       BigDecimal price, String uom, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.active = active;
    }

    /**
     * Mutates all fields EXCEPT {@code code}.
     *
     * <p><b>Legacy quirk AC-9.4:</b> {@code code} is immutable on update — the legacy
     * {@code LabTestTypeServiceImpl.save} update branch re-derives it from the persisted entity
     * rather than the payload, making {@code code} effectively immutable once set.
     * This method enforces the same invariant by accepting no {@code code} parameter.
     */
    public void update(String name, String description,
                       BigDecimal price, String uom, boolean active) {
        // code intentionally excluded — immutable after creation (AC-9.4 / LabTestTypeServiceImpl:47-48)
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.active = active;
    }
}
