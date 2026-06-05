/**
 * Pharmacy bounded context (inc-08, ADR-0008). Owns pharmacy stock (the {@code PharmacyMedicine}
 * scalar aggregate + {@code StockBatch} FEFO lots + the append-only {@code StockMovement} ledger),
 * clinical-prescription dispensing orchestration, the OTC {@code PharmacySaleOrder} lifecycle, and
 * the pharmacy↔pharmacy / pharmacy↔store transfer chains.
 *
 * <p>Module dependencies (ADR-0008 §6; inc-08 ratified decisions):
 * <ul>
 *   <li>{@code shared} — {@code AuditableEntity}, {@code Money}, {@code TxAuditContext},
 *       {@code BusinessDayService}, the shared {@code DocumentNumberService} (Q11), error model,
 *       {@code AuditRecorder}.</li>
 *   <li>{@code clinical :: api} — the prescription read + worklist + dispense seams
 *       ({@code PrescriptionReadPort}, {@code PrescriptionWorklistPort},
 *       {@code PrescriptionDispensePort}). Pharmacy ORCHESTRATES the dispense and calls DOWN into
 *       clinical (inc-08a chunk 1, AC-RX-PRE-01). There is NO {@code clinical → pharmacy} edge.</li>
 *   <li>{@code billing :: api} — {@code BillingCommands} (OTC flat-cash charge is built without the
 *       plan-pricing seam, Q9), {@code BillStatus} / settlement event consumption (OTC
 *       PENDING→APPROVED side effect, AC-OTC-27).</li>
 *   <li>{@code masterdata :: lookup} — pharmacy / store / medicine / item / coefficient / supplier
 *       existence + the {@code item_medicine_coefficients} conversion (08b).</li>
 *   <li>{@code iam :: lookup} — pharmacist personnel existence (no pharmacy affiliation gate — Q2).</li>
 * </ul>
 *
 * <p>{@code pharmacyUid} is a required, server-validated stock-source selector with NO user→pharmacy
 * affiliation check (Q2; the {@code pharmacy_staff} 403 gate is parked as CR-Q2).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared",
                "clinical :: api",
                "billing :: api",
                "masterdata :: lookup",
                "iam :: lookup"})
package com.otapp.hmis.pharmacy;
