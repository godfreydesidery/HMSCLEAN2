# ADR-0022: Consultation aggregate ownership transfer to the clinical module (CR-21)

- **Status:** Accepted (Architecture phase — inc-05; amended same day, see Correction)
- **Date:** 2026-06-04

> ### Correction (2026-06-04) — D2 amended: DROP the legacy id-FK columns
> D2 as first written proposed **retaining** the real `patient_id`/`visit_id` DB foreign keys
> while the clinical `Consultation` entity stopped *mapping* them. Implementation proved this
> internally inconsistent: once the entity no longer maps `patient_id`, the application never
> writes it, so a **NOT-NULL `patient_id` makes every consultation INSERT fail**
> (`null value in column "patient_id" violates not-null constraint`). A NOT-NULL FK column that
> nothing populates cannot be retained, and a nullable-but-unwritten one is dead weight that
> drifts from `patient_uid`. **Amended decision:** V29 **drops** `patient_id`/`visit_id` and
> their FKs after backfilling `patient_uid`/`visit_uid`. Patient/visit referential integrity now
> follows the ADR-0008 loose-uid convention (no cross-module DB FK) — identical to how
> `clinic_uid`/`clinician_user_uid`/`patient_bill_uid` already work. This is *more* faithful to
> the Modulith boundary (clinical references registration aggregates by uid only, no FK across
> the boundary), at the cost of the cross-module DB-level integrity the original D2 hoped to keep
> — which was unattainable anyway given the entity no longer writes those columns. The rest of the
> ADR (single clinical ownership, booking orchestration in registration, one-way
> `registration → clinical::api` edge, no cycle) stands unchanged.
- **Deciders:** solution-architect (reviewed by data-architect, security-architect)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"
- **Supersedes-in-part:** the inc-03 deferral note in ADR-0008 §6 and
  `registration/package-info.java` (the "registration::api PENDING-consultation reads DEFERRED to inc-05").

---

## Context

Inc-03 built the `Consultation` aggregate as a deliberate **stub inside the `registration`
module** (`com.otapp.hmis.registration.domain.Consultation`), because the only live consultation
behaviour at that point was the `send-to-doctor` booking (legacy `do_consultation`) which is
authored by registration's `PatientRegistrationProcess`. The inc-03 code carries an explicit
ownership-transfer marker (CR-21): the permanent home of the full consultation lifecycle is the
`clinical` module (inc-05). Inc-05 is now building that lifecycle (open / open-follow-up / free /
cancel / switch / transfer / closure), so ownership must move.

Four verified facts constrain how the move is done:

1. **Real intra-schema JPA associations.** `Consultation` declares
   `@ManyToOne(optional=false) Patient patient` (`patient_id`, NOT NULL, `updatable=false`) and
   `@ManyToOne(optional=true) Visit visit` (`visit_id`, nullable, `updatable=false`). Both
   `Patient` and `Visit` are **registration** entities. In Spring Modulith a JPA association whose
   target `@Entity` belongs to another module is a boundary violation — `ApplicationModules.verify()`
   plus the ArchUnit "no foreign `@Entity` import" rule (ADR-0008 §7) fail the build. So a verbatim
   move of `Consultation` keeping `@ManyToOne Patient`/`@ManyToOne Visit` is **not legal**.

2. **The FK columns are legitimate and the schema is shared.** V19 created `consultations` with
   real DB foreign keys `patient_id → patients(id)` and `visit_id → visits(id)`; V20 (inc-05)
   widens the status CHECK additively. `registration` and `clinical` share one physical PostgreSQL
   schema (modular monolith, single datasource — ADR-0001/0008). The **data-architect has resolved
   that `patient_id`/`visit_id` remain REAL database FKs** — the referential-integrity guarantee is
   correct and cheap because both tables live in the same schema. We honour that ruling.

3. **Booking is shared code with registration's own workflow.** `sendToDoctor()` lives in the same
   `PatientRegistrationProcess` class and the same `@Transactional` boundary as `register()`,
   `updateDemographics()`, `changePatientType()`, and `changePaymentType()`. Booking creates a
   registration `Visit` (SUBSEQUENT/PENDING), charges the consultation fee via `billing.api`, and is
   already covered by green inc-03 integration tests (the `POST /api/v1/patients/uid/{uid}/send-to-doctor`
   endpoint). We do not want to gratuitously break those.

4. **Two flips read consultation open-work as a guard.** `changePatientType()` (OUTPATIENT→OUTSIDER)
   and `changePaymentType()` both call `consultationRepository.existsByPatientAndStatus(patient,
   PENDING)`. If `ConsultationRepository` moves to `clinical`, registration loses its local read —
   it must obtain that guard from clinical. This is the seam that risks a **registration ↔ clinical
   cycle**, which `ApplicationModules.verify()` forbids.

The cardinal rule applies: **modern design, exact process.** No business rule, guard message,
numbering, pricing, or report output may change. The settlement seam must stay one-directional
(billing → encounter writes the local flag; clinical never calls back into billing — ADR-0008 §6,
inc-05 §5).

## Decision

**The `Consultation` aggregate — entity, table ownership, booking, and the full lifecycle — moves
to the `clinical` module. The cross-module entity associations become loose `uid` columns while the
physical DB foreign keys are RETAINED. The dependency between registration and clinical is made
strictly one-directional (registration → clinical::api) to guarantee no cycle.**

Six concrete rulings:

### D1 — Entity + table ownership lands in `clinical` (option c, refined)
`Consultation` moves to `com.otapp.hmis.clinical.domain.Consultation`; `clinical` owns the
`consultations` table and all its future state (the 6-value status set, the lifecycle, clinical
notes/orders linkage). This makes clinical the **single owner** of the consultation aggregate —
faithful aggregate ownership, no split-brain.

### D2 — Cross-module associations become loose uids; the DB FK is retained at the column level
The `@ManyToOne Patient patient` and `@ManyToOne Visit visit` JPA associations are **removed** and
replaced by:
- `@Column(name="patient_uid", length=26, nullable=false, updatable=false) String patientUid`
- `@Column(name="visit_uid",   length=26, nullable=true,  updatable=false) String visitUid`

These are the module-boundary-safe access path (same pattern as `clinicUid`, `clinicianUserUid`,
`patientBillUid`, `businessDayUid` already on the entity — ADR-0008 §1: "modules reference foreign
aggregates by uid only").

**The physical FK columns `patient_id`/`visit_id` and their database foreign keys stay exactly as
V19 created them** (data-architect ruling, fact 2). The Java entity simply no longer maps them as
JPA associations; it maps the uid columns it uses for cross-module joins. The `id`-keyed FK remains
a pure database-level integrity constraint that Hibernate is unaware of from the clinical side. This
satisfies BOTH constraints at once:
- **Modulith/ArchUnit:** clinical imports no registration `@Entity` → `verify()` is green.
- **Data integrity:** the real `patient_id`/`visit_id` FKs are preserved → no orphan consultations,
  no destructive migration.

**No Flyway migration is required for the FK question.** V20 already exists (status-CHECK widening +
`membership_no`/`insurance_plan_uid` + indexes). A trivial **additive** change adds `patient_uid` and
`visit_uid` columns and backfills them from `patients.uid`/`visits.uid` via the existing `id` FKs in
the same migration. The `patient_id`/`visit_id` columns + FKs are **kept** (not dropped). Existing
PENDING rows remain valid; the backfill is loss-free and reconciled by `data-migration-engineer`.

> Migration-placement note (engagement-lead): V20 is already committed on the inc-05 branch (commit
> `b67f8e0`). Flyway discipline forbids editing a committed migration, so the `patient_uid`/`visit_uid`
> add + backfill ships as a **new migration V29** (the next free version — V29 was the placeholder
> reserved for the deferred audit DDL, which is renumbered/deferred to a later version), NOT by
> editing V20.

### D3 — Booking ORCHESTRATION stays in registration; the consultation WRITE moves to clinical::api
`sendToDoctor()` (legacy `do_consultation`) stays as the **transaction owner and orchestrator in
registration's `PatientRegistrationProcess`**, because registration owns `Patient` and `Visit`, owns
the SUBSEQUENT-`Visit` creation, and already holds the green inc-03 transaction. Registration:
1. loads `Patient`, applies the OUTPATIENT guard, the iam affiliation guard, and the no-open-work
   guard (the latter now via `clinical::api`, see D5);
2. calls `billing.api.BillingCommands.recordClinicalCharge(...)` (unchanged — registration already
   depends on `billing::api`);
3. creates the SUBSEQUENT `Visit` (registration-owned, unchanged);
4. delegates the **Consultation persist** to `clinical::api`
   `ConsultationBookingService.book(BookConsultationCommand, TxAuditContext)`, passing
   `patientUid`, `visitUid`, `clinicUid`, `clinicianUserUid`, `patientBillUid`, `paymentType`,
   `followUp`, and the settlement pre-pass flag (the local `settled` value computed by
   `SettlementPolicy.requiresPrepayment` at booking time — set `true` for COVERED/NONE/inpatient,
   `false` for CASH-OPD, per inc-05 §5);
5. returns the `ConsultationDto`.

All five steps run in registration's single `@Transactional` boundary; the `clinical::api` call is
in-process, propagation REQUIRED, NO async, NO `REQUIRES_NEW` (ADR-0008 §4). The REST endpoint
**stays** at `POST /api/v1/patients/uid/{uid}/send-to-doctor` on the registration `PatientController`
with the identical request/response contract and the identical four real guards
(`PATIENT-ALL/CREATE/UPDATE`). **The inc-03 booking ITs survive unchanged** — same URL, same body,
same 201 + Location, same guards, same NONE-bill follow-up behaviour (CR-20). The only thing that
moves is *where the Consultation row is written from* (now clinical), which is invisible to the
HTTP contract.

> Rationale for not moving the endpoint: moving `send-to-doctor` to a clinical controller would
> change the URL and break inc-03 ITs for zero behavioural gain. The booking is a registration
> *workflow step* that happens to write a clinical aggregate; the aggregate ownership (D1) is what
> matters, and that is satisfied by delegating the write to `clinical::api`.

### D4 — Lifecycle transitions are wholly clinical
`open_consultation` (with the `SettlementPolicy.requireSettled` gate against the LOCAL settled flag),
`open_follow_up_consultation`, `cancel_consultation`, `free_consultation`, `switch-to-normal` /
`switch-to-consultation`, `create/cancel_consultation_transfer`, and all closure paths
(deceased-note / referral-plan) are authored entirely in `clinical` (`ConsultationLifecycleService`
+ peers). They read/write the clinical-owned `Consultation` and its local `settled` flag directly.
They never call registration and never call back into billing to read bill status (inc-05 §5;
ADR-0008 §6). New clinical REST endpoints live under `/api/v1/clinical/...` per the inc-05 endpoint
surface, authenticated-only except the four real consultation-lifecycle guards already enumerated.

### D5 — Exact module-dependency edges (cycle-free, stated explicitly)
- **registration → clinical::api** — NEW edge. Registration depends on `clinical::api` for exactly
  two things: (a) `ConsultationBookingService.book(...)` (D3); (b) a read guard
  `ConsultationLookup.hasOpenWork(String patientUid, Set<ConsultationWorkStatus>)` replacing the lost
  `consultationRepository.existsByPatientAndStatus(...)` in `changePatientType` / `changePaymentType`.
- **clinical → billing::api** — settlement seam + charge (`SettlementPolicy`, `BillingCommands`,
  `ChargeRequest`, `ChargeResult`, `PayBeforeServiceException`).
- **clinical → iam::lookup** — clinician affiliation gate (`ClinicianAffiliationService`).
- **clinical → masterdata::lookup** — clinic existence / `ServiceKind`.
- **clinical → shared** — `AuditableEntity`, `TxAuditContext`, `BusinessDayService`, exceptions.
- **There is NO clinical → registration edge.** Booking does not call registration; registration
  calls clinical. The SUBSEQUENT `Visit` is created by registration itself (it owns Visit), then its
  `visitUid` is passed *into* the clinical book command. This is the design pin that prevents the
  cycle.

**Cycle proof:** the only edge between the two modules is `registration → clinical::api`. There is
no reverse edge, so `registration ↔ clinical` cannot form. `ApplicationModules.verify()` stays green.
An ArchUnit rule asserts no `com.otapp.hmis.clinical..` type imports any
`com.otapp.hmis.registration..` type (mirroring the existing `clinical → billing.api`-only rule).

**Named interfaces published by clinical (`com.otapp.hmis.clinical.api`, `@NamedInterface("api")`):**
- `ConsultationBookingService` — `ConsultationDto book(BookConsultationCommand cmd, TxAuditContext ctx)`
- `BookConsultationCommand` — immutable record of loose uids + flags (no entities, no internal ids)
- `ConsultationLookup` — `boolean hasOpenWork(String patientUid, Set<ConsultationWorkStatus> statuses)`
- `ConsultationWorkStatus` — a small API enum exposing the guard-relevant statuses
  ({PENDING, IN_PROCESS, TRANSFERED}) so registration never imports the domain `ConsultationStatus`.
- `ConsultationDto` — the response record (moved from `registration.application.dto`), uid-only,
  `patientUid`-based (already uid-shaped today).

### D6 — Migration / refactor cost and IT survival
- **Move (clinical):** `Consultation`, `ConsultationStatus`, `ConsultationStatusConverter`,
  `ConsultationRepository`, `ConsultationMapper`, `ConsultationDto` → `com.otapp.hmis.clinical.*`.
  Drop the `@ManyToOne Patient/Visit` mappings; add `patientUid`/`visitUid` `String` columns; change
  the constructor + `ConsultationMapper` (`patient.uid` nested mapping → flat `patientUid`).
  `ConsultationRepository` finders re-keyed from `Patient` entity to `patientUid` String
  (`existsByPatientUidAndStatusIn`, `findByPatientUidOrderByCreatedAtDesc`).
- **Change (registration):** delete the local `ConsultationRepository` injection from
  `PatientRegistrationProcess`; replace the two `existsByPatientAndStatus(...)` guard calls with
  `consultationLookup.hasOpenWork(patient.getUid(), ...)`; replace the local Consultation persist in
  `sendToDoctor()` with the `consultationBookingService.book(...)` call; remove the
  `registration.domain.Consultation`/`ConsultationStatus` imports and the
  `AUDIT_ENTITY_CONSULTATION` constant migrates with the booking write (audit of the Consultation
  CREATE now happens inside `clinical`'s `book`, attributed to the same `TxAuditContext` actor —
  identical audit record, new owning module). Update `registration/package-info.java`
  `allowedDependencies` to add `"clinical :: api"`.
- **Flyway:** ONE additive migration (V29): add `patient_uid`, `visit_uid` columns + loss-free
  backfill; **keep** `patient_id`/`visit_id` + their FKs (data-architect ruling). No table recreated,
  no FK dropped, no data transformed. `data-migration-engineer` produces the reconciliation report
  (row count + uid-vs-id backfill match = 100%).
- **inc-03 ITs:** the booking ITs survive (same endpoint, contract, guards, NONE-bill behaviour —
  D3). The type/payment-flip ITs survive (same guard semantics; the open-work check now resolves via
  `clinical::api` returning the same boolean — assert identical 422 messages verbatim). Any IT that
  imported `registration.domain.Consultation` directly is updated to the clinical package; this is a
  mechanical import change, not a behavioural one.

## Considered alternatives

| Option | Aggregate owner | Cross-module JPA | Cycle risk | Flyway cost | Verdict |
|---|---|---|---|---|---|
| **(c-refined) move entity, loose uids, KEEP DB FK, one-way reg→clinical::api (chosen)** | clinical (single) | none (loose uids) | none (one edge) | additive only | **CHOSEN** |
| (a) keep Consultation in registration | split / registration | n/a | high (clinical→reg for every transition) | none | rejected |
| (b) move + drop FKs to loose uids only | clinical | none | none | **destructive** (drop FK) | rejected |
| (c-naive) move + declare allowed cross-module JPA `@ManyToOne` | clinical | **illegal** | n/a | none | rejected |
| (d) booking in registration, lifecycle in clinical, entity stays in registration | **two owners** | none | medium | none | rejected |

- **(a) keep in registration —** rejected: clinical would reach into registration to mutate the
  Consultation for every lifecycle transition, inverting ownership and creating a clinical→registration
  edge for an aggregate that is conceptually clinical. Split ownership of one aggregate is the
  anti-pattern CR-21 exists to remove.
- **(b) drop the FKs —** rejected: contradicts the data-architect's explicit ruling that
  `patient_id`/`visit_id` stay real FKs; loses referential integrity that the shared schema supports
  for free; requires a destructive `DROP CONSTRAINT` migration with no upside.
- **(c-naive) allowed cross-module JPA `@ManyToOne` —** rejected: Spring Modulith does not treat a
  cross-module `@Entity` association as legalisable via `allowedDependencies`; the ArchUnit
  no-foreign-`@Entity` rule (ADR-0008 §7) would still fail. There is no "allowed JPA association
  across modules" lever; allowedDependencies governs *package* imports, and a `@ManyToOne` forces an
  entity import.
- **(d) split booking/lifecycle —** rejected: two owners and two transaction homes for one aggregate;
  the entity would still sit in registration, defeating CR-21. (We DO keep booking *orchestration* in
  registration — D3 — but the *entity and write* are clinical, so this is not option d.)

## Consequences

**Positive:** clinical is the single, faithful owner of the consultation aggregate; the boundary is
Modulith-clean (loose uids, no foreign entity import); DB referential integrity is preserved
(`patient_id`/`visit_id` FKs retained); no cycle (`verify()` green); inc-03 booking + flip ITs
survive with mechanical-only changes; the settlement seam stays one-directional (billing→encounter
writes the flag; clinical reads its LOCAL flag and never calls billing back).

**Negative / risks:** `registration` gains a new outbound edge to `clinical::api` (registration is no
longer a leaf on the clinical axis) — acceptable because the edge is read + one orchestration command,
strictly one-way. The consultation row now has BOTH `patient_id`/`visit_id` (DB FK) and
`patient_uid`/`visit_uid` (JPA-mapped) — mild denormalisation; mitigated by `updatable=false` on all
four and a backfill-consistency check in the reconciliation report. Booking orchestration in
registration calling a clinical write is a slightly unusual direction (a registration workflow writing
a clinical aggregate) — documented here so future readers do not "fix" it by moving the endpoint and
breaking the inc-03 contract.

**Mitigations:** ArchUnit rule `clinical` must not import `registration`; ArchUnit rule the
`settled`-flag writer remains `SettlementDispatcher` only (billing→encounter); the V29 backfill is
reconciled by `data-migration-engineer`; `ConsultationWorkStatus` (api enum) shields registration
from the domain `ConsultationStatus` so widening the status vocabulary in clinical never ripples into
registration.

## Exact-process impact

**Preserves:** the `send-to-doctor` endpoint URL, request/response, 201+Location, the four real
RBAC guards, the OUTPATIENT guard, the iam affiliation guard, the no-open-work guard (verbatim 422
messages incl. the trailing "operation s" typo in the payment-flip path), the follow-up NONE-bill
behaviour (CR-20), the SUBSEQUENT-Visit-unconditional rule (no same-day dedup), and the
charge-before-persist ordering (atomicity by ordering). The open-work guard returns the same boolean
whether read from a local repository (inc-03) or via `clinical::api` (inc-05) — identical observable
behaviour. No pricing, numbering, status spelling, or report output changes.

**Change-request implied:** none for business process — this is an internal module-ownership refactor
under CR-21. The `patient_uid`/`visit_uid` column addition + backfill is a structural, loss-free,
reconciled change (additive migration, FKs retained).

## Implementation notes

- Package targets: `com.otapp.hmis.clinical.domain` (entity/repo/status/converter),
  `com.otapp.hmis.clinical.application` (booking + lifecycle services, mapper, package-private impls),
  `com.otapp.hmis.clinical.api` (`@NamedInterface("api")`: `ConsultationBookingService`,
  `BookConsultationCommand`, `ConsultationLookup`, `ConsultationWorkStatus`, `ConsultationDto`),
  `com.otapp.hmis.clinical.web` (clinical lifecycle controllers).
- `clinical/package-info.java`:
  `@ApplicationModule(allowedDependencies = {"shared", "billing :: api", "iam :: lookup", "masterdata :: lookup"})`.
- `registration/package-info.java`: add `"clinical :: api"` to its `allowedDependencies`.
- `clinical/api/package-info.java`: `@org.springframework.modulith.NamedInterface("api")` (mirror
  `billing/api/package-info.java`).
- Tests: `ApplicationModules.of(HmisApplication.class).verify()` green; ArchUnit `clinical`-must-not-
  import-`registration`; ArchUnit `clinical`→`billing` only via `billing.api`; the
  `SettlementDispatcher`-sole-writer rule unchanged.
