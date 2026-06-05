/**
 * Inpatient / Nursing bounded context (inc-07, ADR-0008).
 *
 * <p>Owns the full admission lifecycle from bed-claim through discharge/referral/deceased
 * disposition: {@code PatientAdmission} aggregate, bed occupancy, nursing observation,
 * drug-administration chart (via clinical::api), ward charge billing, and the four disposition
 * summary flows (discharge, referral, deceased, sign-out).
 *
 * <p>Module dependencies (ADR-0008 §6; inc-07 ratified decisions):
 * <ul>
 *   <li>{@code shared} — {@code AuditableEntity}, {@code Money}, {@code TxAuditContext},
 *       {@code BusinessDayService}, {@code DocumentNumberService}, error model,
 *       {@code AuditRecorder}.</li>
 *   <li>{@code clinical :: api} — {@code PrescriptionChartPort} (free-text dosing-note seam,
 *       inc-07 SEAM-2 / 07b); {@code MedicationAdministrationPort} (closed-loop MAR seam,
 *       inc-07 07d / CR-07-MAR); {@code NursingChartPort}; consultation/non-consultation read
 *       seams already published. One-directional: {@code inpatient → clinical::api} only.</li>
 *   <li>{@code billing :: api} — {@code BillingCommands} (ward charge + consumable charges,
 *       inc-07 07a/07c); {@code BillingQueries.admissionHasOutstandingBills} (discharge gate,
 *       inc-07 07a). One-directional: {@code inpatient → billing::api} only.</li>
 *   <li>{@code masterdata :: lookup} — {@code WardLookup} (bed/ward-type read seam, inc-07
 *       SEAM-1); {@code WardBedClaim} (mutating bed-status seam, inc-07 SEAM-1 / CR-07-Q3 /
 *       ADR-0017 ratified); {@code PriceLookup} (ward charge pricing, CR-12);
 *       {@code DressingLookup} (07b); {@code ConsumableLookup} (07c-i);
 *       {@code RouteLookup} (administration-route validation, inc-07 07d / CR-07-MAR).</li>
 *   <li>{@code iam :: lookup} — nurse / attending-doctor personnel existence checks.</li>
 * </ul>
 *
 * <p><strong>Added in chunk 07c-i (CR-07-consumable-stock):</strong>
 * {@code pharmacy :: api} is added here for the consumable-stock seam
 * ({@link com.otapp.hmis.pharmacy.api.PharmacyStockDebit}). The dependency edge is
 * one-directional: {@code inpatient → pharmacy :: api}. No reverse edge exists (pharmacy
 * never imports inpatient), so no cycle is introduced. ModularityTest verifies this.
 *
 * <p>Legacy citation: PatientServiceImpl.java (admission lifecycle); PatientResource.java
 * (REST endpoints). inc-07 / ADR-0008 §6 / ADR-0017 ratified.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared",
                "clinical :: api",
                "billing :: api",
                "masterdata :: lookup",
                "iam :: lookup",
                "registration :: lookup",
                "pharmacy :: api"})  // inc-07 07c-i CR-07-consumable-stock: consumable issue debit seam
package com.otapp.hmis.inpatient;
