package com.otapp.hmis.inventory.domain;

/**
 * Store→Pharmacy RN (PGRN) lifecycle (inc-08b chunk 6). Two states only: PENDING → COMPLETED
 * (legacy StoreToPharmacyRN; verify/approve columns never written — two-state machine). Valid Java
 * identifiers → {@code @Enumerated(STRING)}.
 */
public enum SpRnStatus {
    PENDING, COMPLETED
}
