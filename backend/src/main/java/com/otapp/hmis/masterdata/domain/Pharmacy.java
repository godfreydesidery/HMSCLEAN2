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
 * Pharmacy location master (legacy {@code com.orbix.api.domain.Pharmacy}, Pharmacy.java:35-53).
 *
 * <p>{@code category} is free-text (NOT an enum or FK, Pharmacy.java:46).
 * The store↔storePerson and pharmacy M2M associations are owned by the IAM module and
 * are OUT OF SCOPE for P1.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacies")
public class Pharmacy extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "location", length = 200)
    private String location;

    /** Free-text category label (legacy Pharmacy.java:46 — not an enum). */
    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public Pharmacy(String code, String name, String description,
                    String location, String category, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.location = location;
        this.category = category;
        this.active = active;
    }

    public void update(String code, String name, String description,
                       String location, String category, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.location = location;
        this.category = category;
        this.active = active;
    }
}
