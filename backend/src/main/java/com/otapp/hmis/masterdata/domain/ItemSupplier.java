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
 * Item-to-supplier relationship with supplier cost prices
 * (legacy {@code com.orbix.api.domain.ItemSupplier}, ItemSupplier.java:31-50).
 *
 * <p>NOTE: Legacy {@code ItemSupplier} has NO manual audit columns (unlike other inventory
 * entities). HMSCLEAN2 still extends {@link AuditableEntity} for target-side consistency
 * (uid + audit cols are a target-side invariant — build-spec §1.2 note).
 *
 * <p>The functional overlap with {@link SupplierItemPrice} (both model supplier prices for an
 * item via different shapes) is a known legacy redundancy. Both are preserved; do not unify
 * (build-spec §1.2 / 02-extract-inventory.md Q5).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "items_suppliers")
public class ItemSupplier extends AuditableEntity {

    /**
     * The item being supplied (legacy {@code @ManyToOne ... optional=false, updatable=false},
     * ItemSupplier.java:36-39). EAGER fetch.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private Item item;

    /**
     * The supplier (legacy {@code @ManyToOne ... optional=false, updatable=false},
     * ItemSupplier.java:41-44). EAGER fetch.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @NotNull
    @Column(name = "cost_price_vat_incl", nullable = false, precision = 19, scale = 2)
    private BigDecimal costPriceVatIncl = BigDecimal.ZERO;

    @NotNull
    @Column(name = "cost_price_vat_excl", nullable = false, precision = 19, scale = 2)
    private BigDecimal costPriceVatExcl = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /** Business constructor — FKs immutable after creation. */
    public ItemSupplier(Item item, Supplier supplier,
                        BigDecimal costPriceVatIncl, BigDecimal costPriceVatExcl,
                        boolean active) {
        this.item = item;
        this.supplier = supplier;
        this.costPriceVatIncl = costPriceVatIncl != null ? costPriceVatIncl : BigDecimal.ZERO;
        this.costPriceVatExcl = costPriceVatExcl != null ? costPriceVatExcl : BigDecimal.ZERO;
        this.active = active;
    }

    /**
     * Mutates the mutable price and active fields.
     * {@code item} and {@code supplier} are {@code updatable=false} and must NOT be changed.
     */
    public void update(BigDecimal costPriceVatIncl, BigDecimal costPriceVatExcl, boolean active) {
        this.costPriceVatIncl = costPriceVatIncl != null ? costPriceVatIncl : BigDecimal.ZERO;
        this.costPriceVatExcl = costPriceVatExcl != null ? costPriceVatExcl : BigDecimal.ZERO;
        this.active = active;
    }
}
