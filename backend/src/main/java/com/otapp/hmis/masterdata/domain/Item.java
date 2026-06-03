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
 * Store / procurement SKU (legacy {@code com.orbix.api.domain.Item}, Item.java:34-64).
 *
 * <p>The {@code Item} is the purchased/stored SKU (procurement/store side); a {@link Medicine}
 * is the dispensed/pharmacy SKU. They are linked via {@link ItemMedicineCoefficient}.
 *
 * <p>{@code shortName} is unique (Item.java:46-48, {@code @Column(unique=true)}).
 * {@code active} defaults to {@code true} (Item.java:56).
 * {@code ingredients} defaults to {@code ""} (Item.java:57).
 * {@code category} and {@code uom} are free-text strings — NO lookup tables (CR-07).
 * {@code packSize} is a quantity field — mapped to NUMERIC(19,6).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "items")
public class Item extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @Column(name = "barcode", length = 80)
    private String barcode;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    /** Unique short label (Item.java:46-48, @Column(unique=true)). */
    @Column(name = "short_name", length = 120, unique = true)
    private String shortName;

    @Column(name = "common_name", length = 200)
    private String commonName;

    /** VAT rate or amount. BigDecimal replaces legacy {@code double} (pre-approved). */
    @NotNull
    @Column(name = "vat", nullable = false, precision = 19, scale = 2)
    private BigDecimal vat = BigDecimal.ZERO;

    /** Free-text unit of measure (Item.java:50). */
    @Column(name = "uom", length = 40)
    private String uom;

    /** Pack / conversion size — NUMERIC(19,6) (Item.java:51, quantity field). */
    @NotNull
    @Column(name = "pack_size", nullable = false, precision = 19, scale = 6)
    private BigDecimal packSize = BigDecimal.ONE;

    /** Free-text category (Item.java:52). */
    @Column(name = "category", length = 80)
    private String category;

    @NotNull
    @Column(name = "cost_price_vat_incl", nullable = false, precision = 19, scale = 2)
    private BigDecimal costPriceVatIncl = BigDecimal.ZERO;

    @NotNull
    @Column(name = "selling_price_vat_incl", nullable = false, precision = 19, scale = 2)
    private BigDecimal sellingPriceVatIncl = BigDecimal.ZERO;

    /** Defaults to {@code true} (Item.java:56). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "ingredients", columnDefinition = "TEXT")
    private String ingredients = "";

    /** Business constructor — all fields. */
    public Item(String code, String barcode, String name, String shortName,
                String commonName, BigDecimal vat, String uom, BigDecimal packSize,
                String category, BigDecimal costPriceVatIncl,
                BigDecimal sellingPriceVatIncl, boolean active, String ingredients) {
        this.code = code;
        this.barcode = barcode;
        this.name = name;
        this.shortName = shortName;
        this.commonName = commonName;
        this.vat = vat != null ? vat : BigDecimal.ZERO;
        this.uom = uom;
        this.packSize = packSize != null ? packSize : BigDecimal.ONE;
        this.category = category;
        this.costPriceVatIncl = costPriceVatIncl != null ? costPriceVatIncl : BigDecimal.ZERO;
        this.sellingPriceVatIncl = sellingPriceVatIncl != null ? sellingPriceVatIncl : BigDecimal.ZERO;
        this.active = active;
        this.ingredients = ingredients != null ? ingredients : "";
    }

    /** Mutates all mutable fields in one call. */
    public void update(String code, String barcode, String name, String shortName,
                       String commonName, BigDecimal vat, String uom, BigDecimal packSize,
                       String category, BigDecimal costPriceVatIncl,
                       BigDecimal sellingPriceVatIncl, boolean active, String ingredients) {
        this.code = code;
        this.barcode = barcode;
        this.name = name;
        this.shortName = shortName;
        this.commonName = commonName;
        this.vat = vat != null ? vat : BigDecimal.ZERO;
        this.uom = uom;
        this.packSize = packSize != null ? packSize : BigDecimal.ONE;
        this.category = category;
        this.costPriceVatIncl = costPriceVatIncl != null ? costPriceVatIncl : BigDecimal.ZERO;
        this.sellingPriceVatIncl = sellingPriceVatIncl != null ? sellingPriceVatIncl : BigDecimal.ZERO;
        this.active = active;
        this.ingredients = ingredients != null ? ingredients : "";
    }
}
