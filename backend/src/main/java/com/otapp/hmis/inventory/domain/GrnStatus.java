package com.otapp.hmis.inventory.domain;

/**
 * GRN header lifecycle (inc-08b). Legacy GRN header is ONLY ever PENDING → APPROVED
 * (GoodsReceivedNoteServiceImpl.java:77,194). The other strings in the legacy worklist filter
 * (VERIFIED/REJECTED/SUBMITTED/RETURNED) are never assigned to a header — copy-paste filter, not
 * reachable states (discovery finding). Detail-level verification lives on the GRN detail.
 */
public enum GrnStatus {
    PENDING, APPROVED
}
