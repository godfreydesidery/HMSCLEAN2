package com.otapp.hmis.pharmacy.domain;

import java.util.stream.Stream;

/**
 * OTC sale-order DETAIL fulfilment status (inc-08a chunk 4). EXACTLY two values, persisted as the
 * verbatim legacy hyphenated DB strings {@code NOT-GIVEN} → {@code GIVEN} (PharmacySaleOrderDetail
 * default 'NOT-GIVEN' on save → 'GIVEN' on dispense; PatientResource.java:6230-6293). Mapped via
 * {@link OtcFulfilmentStatusConverter} (NOT {@code @Enumerated}) because the hyphen is not a valid
 * Java identifier — mirrors the clinical {@code PrescriptionStatusConverter} pattern.
 *
 * <p>This is the line's FULFILMENT status — independent of its {@link OtcPayStatus} payment status.
 */
public enum OtcFulfilmentStatus {

    /** Ordered but not yet dispensed. DB value {@code NOT-GIVEN}. */
    NOT_GIVEN("NOT-GIVEN"),

    /** Dispensed in full. DB value {@code GIVEN}. */
    GIVEN("GIVEN");

    private final String dbValue;

    OtcFulfilmentStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The EXACT string persisted to {@code pharmacy_sale_order_details.status}. */
    public String dbValue() {
        return dbValue;
    }

    public static OtcFulfilmentStatus fromDbValue(String value) {
        return Stream.of(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown OTC fulfilment status: " + value));
    }
}
