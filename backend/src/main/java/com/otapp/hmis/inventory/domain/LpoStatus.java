package com.otapp.hmis.inventory.domain;

/**
 * Local Purchase Order lifecycle (inc-08b; legacy LocalPurchaseOrderServiceImpl.java:84-225).
 * PENDING → VERIFIED → APPROVED → SUBMITTED → (RECEIVED, flipped by GRN approval); REJECTED and
 * RETURNED are reachable only from PENDING or VERIFIED and are terminal. No DRAFT.
 */
public enum LpoStatus {
    PENDING, VERIFIED, APPROVED, SUBMITTED, RECEIVED, REJECTED, RETURNED
}
