package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Insurance plan (product offered by a provider) (legacy {@code com.orbix.api.domain.InsurancePlan},
 * InsurancePlan.java:37-60).
 *
 * <p>Carries code, name, description, active flag, and a mandatory FK to the provider.
 * NO copay, coverage percentage, or card/membership fields — membership_no lives on Patient
 * (registered at point of care, PatientResource.java:299-301).
 *
 * <p>Name and code uniqueness are load-bearing: plans are resolved by NAME at point of care
 * (PatientServiceImpl.java:530). Any uniqueness violation must surface as 409 Conflict.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "insurance_plans")
public class InsurancePlan extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * Mandatory FK to the parent provider (InsurancePlan.java:50-53, optional=false, EAGER).
     * ON DELETE NO_ACTION mirrors legacy @OnDelete(NO_ACTION).
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "insurance_provider_id", nullable = false, updatable = true)
    private InsuranceProvider insuranceProvider;

    /**
     * Business constructor — all fields (InsurancePlan.java:37-60).
     */
    public InsurancePlan(String code, String name, String description,
                         boolean active, InsuranceProvider insuranceProvider) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.insuranceProvider = insuranceProvider;
    }

    /** Mutates all mutable fields in one call. */
    public void update(String code, String name, String description,
                       boolean active, InsuranceProvider insuranceProvider) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.insuranceProvider = insuranceProvider;
    }
}
