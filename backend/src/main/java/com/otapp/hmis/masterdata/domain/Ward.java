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
 * Physical ward (legacy {@code com.orbix.api.domain.Ward}, Ward.java:39-63).
 *
 * <p>References both {@link WardCategory} and {@link WardType} as mandatory FKs
 * (Ward.java:49-57, optional=false, @OnDelete NO_ACTION). NO per-ward price — pricing
 * delegates entirely to {@code wardType.price} (CR-12 / legacy: no price on Ward).
 * {@code noOfBeds} is a denormalized counter, not a derived field (Ward.java:46).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "wards")
public class Ward extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "no_of_beds", nullable = false)
    private int noOfBeds = 0;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * Mandatory FK to {@link WardCategory} (Ward.java:49-52, optional=false, updatable=true,
     * @OnDelete NO_ACTION). EAGER fetch mirrors legacy behaviour.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ward_category_id", nullable = false, updatable = true)
    private WardCategory wardCategory;

    /**
     * Mandatory FK to {@link WardType} (Ward.java:54-57, optional=false, updatable=true,
     * @OnDelete NO_ACTION). EAGER fetch mirrors legacy behaviour.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ward_type_id", nullable = false, updatable = true)
    private WardType wardType;

    public Ward(String code, String name, int noOfBeds, boolean active,
                WardCategory wardCategory, WardType wardType) {
        this.code = code;
        this.name = name;
        this.noOfBeds = noOfBeds;
        this.active = active;
        this.wardCategory = wardCategory;
        this.wardType = wardType;
    }

    public void update(String code, String name, int noOfBeds, boolean active,
                       WardCategory wardCategory, WardType wardType) {
        this.code = code;
        this.name = name;
        this.noOfBeds = noOfBeds;
        this.active = active;
        this.wardCategory = wardCategory;
        this.wardType = wardType;
    }
}
