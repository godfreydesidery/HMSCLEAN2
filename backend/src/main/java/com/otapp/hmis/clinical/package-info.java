/**
 * Clinical / OPD bounded context (inc-05, ADR-0022, ADR-0008).
 *
 * <p>Owns the {@code Consultation} aggregate (moved from {@code registration} per CR-21,
 * ADR-0022), its full lifecycle state machine (PENDING → IN-PROCESS → SIGNED-OUT / CANCELED /
 * TRANSFERED / HELD), and all associated clinical entities (clinical notes, diagnoses, orders,
 * prescriptions, closures — C3–C12).
 *
 * <p>Module layout (ADR-0014 §2):
 * <ul>
 *   <li>{@code domain/} — JPA entities, repositories, enums, converters.</li>
 *   <li>{@code application/} — lifecycle services, booking/lookup impls, mapper.
 *       All impls are package-private; {@link com.otapp.hmis.clinical.application.ConsultationLifecyclePort}
 *       is public for the {@code web} layer within this module.</li>
 *   <li>{@code api/} — named interface ({@code @NamedInterface("api")}): the cross-module
 *       contracts consumed by {@code registration} ({@code ConsultationBookingService},
 *       {@code ConsultationLookup}, {@code BookConsultationCommand}, {@code ConsultationDto},
 *       {@code ConsultationWorkStatus}).</li>
 *   <li>{@code web/} — REST controllers ({@code ConsultationController}) under
 *       {@code /api/v1/clinical/}.</li>
 * </ul>
 *
 * <p>Module dependencies (ADR-0008 §6, ADR-0022 D5):
 * <ul>
 *   <li>{@code shared} — {@code AuditableEntity}, {@code TxAuditContext},
 *       {@code BusinessDayService}, common exceptions.</li>
 *   <li>{@code billing :: api} — {@code SettlementPolicy}, {@code PayBeforeServiceException},
 *       {@code PaymentMode} (settlement seam — clinical reads local flag, never calls back;
 *       inc-05 §5). {@code BillingCommands} is used by registration only, not by clinical
 *       lifecycle (lifecycle never charges).</li>
 *   <li>{@code iam :: lookup} — clinician affiliation gate (inc-05 C3+).</li>
 *   <li>{@code masterdata :: lookup} — clinic existence / ServiceKind (inc-05 C3+).</li>
 * </ul>
 *
 * <p>There is NO {@code clinical → registration} edge. The one-directional edge is
 * {@code registration → clinical :: api}. {@code ApplicationModules.verify()} + ArchUnit
 * enforce this (ADR-0022 D5).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Consultation entity: domain/Consultation.java:47-110</li>
 *   <li>Lifecycle: PatientServiceImpl.java, PatientResource.java (open/free/cancel/switch)</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "billing :: api", "iam :: lookup", "masterdata :: lookup"})
package com.otapp.hmis.clinical;
