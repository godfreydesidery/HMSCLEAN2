package com.otapp.hmis.pharmacy.domain;

/**
 * OTC {@code PharmacySaleOrder} header lifecycle (inc-08a chunk 4; legacy status strings at
 * PatientServiceImpl.java:3019-3026,3341,3079,3138).
 *
 * <p>PENDING (on create) → APPROVED (side effect of paying the linked bills) → ARCHIVED (after all
 * details GIVEN); plus PENDING → CANCELED. These DB values are valid Java identifiers, so they map
 * via {@code @Enumerated(STRING)} directly (unlike the hyphenated detail fulfilment status).
 */
public enum OtcOrderStatus {

    /** Created, awaiting payment. */
    PENDING,

    /** Linked bills paid — ready to dispense. */
    APPROVED,

    /** All details dispensed (GIVEN) and the order closed. Terminal. */
    ARCHIVED,

    /** Cancelled before payment (manual or 24h auto-expiry). Terminal. */
    CANCELED
}
