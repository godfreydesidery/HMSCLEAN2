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
 * Physical clinic / outpatient unit (legacy {@code com.orbix.api.domain.Clinic}, Clinic.java:43-67).
 *
 * <p>The cash consultation fee is stored directly on this row
 * ({@code consultationFee double}, Clinic.java:53-54). Insurance fees live in the
 * {@code service_prices} table (CR-04). No {@code ClinicType} field exists in the legacy system
 * (CR-17 — zero grep matches for ClinicType across the whole legacy codebase).
 *
 * <p>Lombok generates accessors and the protected no-args constructor (DIRECTIVE 1).
 * No public setters — mutation via the {@link #update} method only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "clinics")
public class Clinic extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Cash / self-pay consultation fee. BigDecimal replaces legacy {@code double} (pre-approved). */
    @NotNull
    @Column(name = "consultation_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal consultationFee = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * Business constructor — creates a new clinic with all mandatory fields.
     * (Legacy Clinic.java:43-55 — fields mapped directly.)
     */
    public Clinic(String code, String name, String description,
                  BigDecimal consultationFee, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.consultationFee = consultationFee != null ? consultationFee : BigDecimal.ZERO;
        this.active = active;
    }

    /** Mutates all mutable fields in one call (mirrors legacy setter surface). */
    public void update(String code, String name, String description,
                       BigDecimal consultationFee, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.consultationFee = consultationFee != null ? consultationFee : BigDecimal.ZERO;
        this.active = active;
    }
}
