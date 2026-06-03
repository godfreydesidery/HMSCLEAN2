package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Insurance provider / payer master (legacy {@code com.orbix.api.domain.InsuranceProvider},
 * InsuranceProvider.java:37-59).
 *
 * <p>Carries only contact metadata and the active flag. NO membership or card-scheme fields
 * exist on the provider in legacy — membership_no lives on Patient (registered at point of
 * care). Plans are looked up by name at point of care
 * (PatientServiceImpl.java:530, PatientResource.java:297,360,384).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "insurance_providers")
public class InsuranceProvider extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "telephone", length = 40)
    private String telephone;

    @Column(name = "email", length = 120)
    private String email;

    @Column(name = "fax", length = 40)
    private String fax;

    @Column(name = "website", length = 200)
    private String website;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * Business constructor — all fields (InsuranceProvider.java:37-59).
     */
    public InsuranceProvider(String code, String name, String address, String telephone,
                             String email, String fax, String website, boolean active) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.telephone = telephone;
        this.email = email;
        this.fax = fax;
        this.website = website;
        this.active = active;
    }

    /** Mutates all mutable fields in one call. */
    public void update(String code, String name, String address, String telephone,
                       String email, String fax, String website, boolean active) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.telephone = telephone;
        this.email = email;
        this.fax = fax;
        this.website = website;
        this.active = active;
    }
}
