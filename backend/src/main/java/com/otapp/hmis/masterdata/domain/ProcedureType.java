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
 * Procedure / theatre service type catalog entry
 * (legacy {@code com.orbix.api.domain.ProcedureType}, ProcedureType.java:38-59).
 *
 * <p>Same shape as {@link RadiologyType} (code/name/description/price/uom/active).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "procedure_types")
public class ProcedureType extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "uom", length = 40)
    private String uom;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public ProcedureType(String code, String name, String description,
                         BigDecimal price, String uom, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.active = active;
    }

    public void update(String code, String name, String description,
                       BigDecimal price, String uom, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.price = price != null ? price : BigDecimal.ZERO;
        this.uom = uom;
        this.active = active;
    }
}
