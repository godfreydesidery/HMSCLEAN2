/**
 * Registration &amp; Patient bounded context (build-spec inc-03, ADR-0008).
 *
 * <p>Owns the patient demographic lifecycle (register, update, type-flip, payment-flip,
 * search) and the send-to-doctor / consultation-booking workflow (inc-03 PENDING stub;
 * permanent owner = {@code clinical} module, inc-05 — ADR-0008-R1, CR-21).
 *
 * <p>Module layout (ADR-0014 §2):
 * <ul>
 *   <li>{@code domain/} — JPA entities ({@code Patient, Registration, Visit, Consultation}),
 *       repositories, enums.  No public entity references cross module boundaries.</li>
     *   <li>{@code application/} — orchestration ({@code PatientRegistrationProcess}), read queries
 *       ({@code PatientQueryService}), DTO records, MapStruct mappers, and the MR-number /
 *       searchKey infrastructure ({@code MrNumberGenerator} wrapping {@code seq_mrno},
 *       {@code SearchKeyBuilder}).</li>
 *   <li>{@code web/} — REST controllers ({@code PatientController}).</li>
 * </ul>
 *
 * <p>Module dependencies (ADR-0008 §6, build-spec §7):
 * <ul>
 *   <li>{@code shared} — {@code AuditableEntity}, {@code TxAuditContext},
 *       {@code BusinessDayService}, common exceptions.</li>
 *   <li>{@code billing :: api} — {@code BillingCommands.recordClinicalCharge()} (in-process,
 *       caller's transaction, no async, no REQUIRES_NEW — ADR-0008 §4).</li>
 *   <li>{@code masterdata :: lookup} — clinic existence validation.</li>
 *   <li>{@code iam :: lookup} — clinician active status + affiliation check
 *       ({@code ClinicianAffiliationService}).</li>
 * </ul>
 *
 * <p>A {@code registration :: lookup} named interface is published in inc-07 07a (SEAM-B):
 * {@link com.otapp.hmis.registration.lookup.PatientStatusLookup} exposes the deceased guard
 * query for the inpatient module. The implementation is package-private in
 * {@code registration.application}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Patient entity: domain/Patient.java:36-107</li>
 *   <li>Registration/Visit/Consultation: domain/Registration.java, domain/Visit.java,
 *       domain/Consultation.java</li>
 *   <li>Orchestration: PatientServiceImpl.java, PatientResource.java</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "billing :: api", "masterdata :: lookup", "iam :: lookup",
                               "clinical :: api"})
package com.otapp.hmis.registration;
