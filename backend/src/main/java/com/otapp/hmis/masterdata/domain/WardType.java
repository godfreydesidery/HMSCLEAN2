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
 * Ward-type master — the pricing anchor for wards
 * (legacy {@code com.orbix.api.domain.WardType}, WardType.java:31-46).
 *
 * <p>The cash per-stay ward charge is stored as {@code price} on this row
 * (WardType.java:39-40). Insurance overrides live in {@code service_prices} (CR-04).
 * There is NO per-ward price; {@code Ward} itself carries no price field (CR-12).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "ward_types")
public class WardType extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Cash per-stay ward charge. BigDecimal replaces legacy {@code double} (pre-approved). */
    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public WardType(String code, String name, String description,
                    BigDecimal price, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.active = active;
    }

    public void update(String code, String name, String description,
                       BigDecimal price, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.active = active;
    }
}
