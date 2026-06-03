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
 * Store / inventory location master (legacy {@code com.orbix.api.domain.Store}, Store.java:37-60).
 *
 * <p>Mirrors {@link Pharmacy} field-for-field. {@code category} is free-text (Store.java:48).
 * The store↔storePerson M2M (Store.java:51-54) is owned by the IAM module and is
 * OUT OF SCOPE for P1.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "stores")
public class Store extends AuditableEntity {

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

    /** Free-text category label (legacy Store.java:48 — not an enum). */
    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public Store(String code, String name, String description,
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
