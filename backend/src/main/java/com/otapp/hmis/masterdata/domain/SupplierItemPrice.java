package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Supplier-quoted price for a specific item
 * (legacy {@code com.orbix.api.domain.SupplierItemPrice}, SupplierItemPrice.java:33-60).
 *
 * <p>The {@code SupplierItemPriceList} is a non-persistent response DTO (no table) —
 * see {@link com.otapp.hmis.masterdata.application.dto.SupplierItemPriceListDto}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "supplier_item_prices")
public class SupplierItemPrice extends AuditableEntity {

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "terms", columnDefinition = "TEXT")
    private String terms;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * The supplier (legacy {@code @ManyToOne ... optional=false, updatable=false},
     * SupplierItemPrice.java:45-48). EAGER fetch.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    /**
     * The item (legacy {@code @ManyToOne ... optional=false, updatable=false},
     * SupplierItemPrice.java:50-53). EAGER fetch.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private Item item;

    /** Business constructor — FKs immutable after creation. */
    public SupplierItemPrice(BigDecimal price, String terms, boolean active,
                             Supplier supplier, Item item) {
        this.price = price != null ? price : BigDecimal.ZERO;
        this.terms = terms;
        this.active = active;
        this.supplier = supplier;
        this.item = item;
    }

    /** Mutates the mutable fields. FKs are {@code updatable=false}. */
    public void update(BigDecimal price, String terms, boolean active) {
        this.price = price != null ? price : BigDecimal.ZERO;
        this.terms = terms;
        this.active = active;
    }
}
