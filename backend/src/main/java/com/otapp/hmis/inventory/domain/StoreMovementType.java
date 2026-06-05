package com.otapp.hmis.inventory.domain;

/**
 * Typed store stock-movement classifier (inc-08b; mirror of the pharmacy {@code MovementType}).
 * Net-new vehicle — legacy {@code StoreStockCard} has no type column (the kind was conveyed only by
 * the free-text reference string, which is still persisted verbatim). No WASTAGE (no legacy disposal).
 */
public enum StoreMovementType {

    /** Opening stock row. */
    OPENING,

    /** Goods received into the store (GRN approve). */
    RECEIPT,

    /** Stock issued out of the store to a pharmacy (transfer goods-issue). */
    TRANSFER_OUT,

    /** Stock received into the store from a pharmacy/transfer. */
    TRANSFER_IN,

    /** Manual absolute stock overwrite. */
    ADJUSTMENT,

    /** Stock issued out on a dispense (unused in store today; kept for symmetry with the enum CHECK). */
    DISPENSE
}
