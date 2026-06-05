package com.otapp.hmis.inventory.domain;

import java.util.stream.Stream;

/**
 * GRN detail verification status (inc-08b; legacy "NOT VERIFIED" → "VERIFIED"). Stored via the
 * {@link GrnDetailStatusConverter} as the verbatim legacy hyphenated/spaced strings used in the V41
 * CHECK ({@code 'NOT-VERIFIED'}/{@code 'VERIFIED'}). (Legacy used a space "NOT VERIFIED"; the rebuild
 * normalises the DB token to {@code NOT-VERIFIED} for a clean CHECK while preserving the two-state
 * semantics — a cosmetic token change, not a behaviour change.)
 */
public enum GrnDetailStatus {

    NOT_VERIFIED("NOT-VERIFIED"),
    VERIFIED("VERIFIED");

    private final String dbValue;

    GrnDetailStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static GrnDetailStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown GRN detail status: " + value));
    }
}
