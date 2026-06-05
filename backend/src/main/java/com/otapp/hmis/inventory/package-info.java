/**
 * Inventory / Procurement bounded context (inc-08b, ADR-0008). Owns store-side stock
 * ({@code StoreItem} scalar aggregate + {@code StoreItemBatch} FEFO lots + the append-only
 * {@code StoreStockMovement} ledger), the LPO→GRN procurement flow (with the {@code Purchase}
 * ledger), and the store side of the pharmacy↔store transfer chain.
 *
 * <p>Module dependencies (ADR-0008 §6; inc-08 ratified decisions):
 * <ul>
 *   <li>{@code shared} — AuditableEntity, Money, TxAuditContext, BusinessDayService, the shared
 *       DocumentNumberService (GRN/LPO numbering, Q11), error model, AuditRecorder.</li>
 *   <li>{@code masterdata :: lookup} — store / item / supplier existence + the per-(supplier,item)
 *       price for LPO detail lines.</li>
 *   <li>{@code iam :: lookup} — personnel existence where needed.</li>
 * </ul>
 *
 * <p>NO three-way match / SupplierInvoice (Q3 — dropped). The pharmacy↔store transfer (08b) adds a
 * narrow {@code pharmacy :: api} consumption seam for the pharmacy-side credit; declared then.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "masterdata :: lookup", "iam :: lookup"})
package com.otapp.hmis.inventory;
