package com.otapp.hmis.pharmacy.domain;

/**
 * Typed stock-movement classifier for the {@code stock_movement} ledger (inc-08a, AC-STK-06).
 *
 * <p><strong>Net-new vehicle.</strong> The legacy {@code PharmacyStockCard} has NO type column —
 * the movement kind was conveyed only by the free-text {@code reference} string
 * (PharmacyStockCard.java:37-57). This enum decodes 1:1 onto those legacy reference strings, and the
 * verbatim reference is STILL persisted in {@code stock_movement.reference}, so the stock-card report
 * keeps parity. There is <strong>no WASTAGE</strong> value — legacy has no disposal path.
 *
 * <p>Reference-string mapping (legacy → type):
 * <ul>
 *   <li>{@code OPENING} ← "Opening stock, pharmacy registration" (PharmacyServiceImpl.java:67-91)</li>
 *   <li>{@code DISPENSE} ← "Issued in prescription: id &lt;n&gt;" / "Issued in sale: id &lt;n&gt;"
 *       (PatientResource.java:3252-3264, :6281-6293)</li>
 *   <li>{@code RECEIPT} ← "Medicine received # &lt;no&gt;" (GRN approve / RN receipt)</li>
 *   <li>{@code TRANSFER_OUT} ← "Goods transfered to ..." (transfer goods-issue)</li>
 *   <li>{@code TRANSFER_IN} ← transfer receipt at destination</li>
 *   <li>{@code ADJUSTMENT} ← "Stock Update" (manual overwrite, PharmacyResource.java:217-229)</li>
 * </ul>
 */
public enum MovementType {

    /** Opening stock row written when a pharmacy is registered (qty 0). */
    OPENING,

    /** Goods received into the pharmacy (RECEIPT / transfer-in credit). */
    RECEIPT,

    /** Stock issued out on a prescription or OTC sale dispense. */
    DISPENSE,

    /** Stock issued out of this pharmacy to another location (transfer goods-issue). */
    TRANSFER_OUT,

    /** Stock received into this pharmacy from another location (transfer receipt). */
    TRANSFER_IN,

    /** Manual absolute stock overwrite ("Stock Update"). */
    ADJUSTMENT,

    /**
     * Inpatient consumable issue — stock decremented when a consumable medicine is recorded
     * on a patient's consumable chart during an admission (CR-07-consumable-stock, inc-07 07c).
     * Net-new movement kind: the legacy system billed consumables but did NOT touch pharmacy
     * stock (billing-only path). This value classifies the stock-card OUT row that we add
     * to keep stock consistent when a consumable is issued to an inpatient.
     */
    CONSUMPTION,

    /**
     * Reversal of a consumable issue — stock credited back when a consumable chart is deleted
     * within the 24-hour window (CR-07-consumable-stock, inc-07 07c). Aggregate-only increment;
     * no batch is re-created (the consumed FEFO lots cannot be meaningfully un-walked).
     */
    CONSUMPTION_REVERSAL
}
