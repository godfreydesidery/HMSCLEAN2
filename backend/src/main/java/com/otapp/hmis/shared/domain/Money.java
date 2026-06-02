package com.otapp.hmis.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Immutable money value object (ADR-0009, ADR-0014 §7).
 *
 * <p>{@code amount} is {@code NUMERIC(19,2)} normalised with {@link RoundingMode#HALF_UP} at the
 * persistence boundary; {@code currency} defaults to {@code TZS}. Accessors and {@code equals}/
 * {@code hashCode} are Lombok-generated (DIRECTIVE 1) — no hand-written boilerplate. The JPA
 * no-arg constructor and the rounding factory stay hand-authored because Lombok cannot express
 * the scale normalisation or the immutable-with-JPA-proxy shape.
 */
@Getter
@EqualsAndHashCode
@Embeddable
public final class Money implements Serializable {

    public static final String DEFAULT_CURRENCY = "TZS";
    public static final int SCALE = 2;

    @Column(name = "amount", precision = 19, scale = 2)
    private final BigDecimal amount;

    @Column(name = "currency", length = 3)
    private final String currency;

    /** JPA-only no-arg constructor. */
    protected Money() {
        this.amount = null;
        this.currency = null;
    }

    private Money(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, DEFAULT_CURRENCY);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, DEFAULT_CURRENCY);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
