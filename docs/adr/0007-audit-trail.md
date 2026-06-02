# ADR-0007: Audit Trail — Explicit Append-Only (Net-New)

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization — "modern design, exact process"

---

## Context

The legacy codebase declares `hibernate-envers` as a Maven dependency but applies `@Audited` to **zero** entities. There are no `_AUD` tables, no `REVINFO` table, and no revision history anywhere in `zana_hmis_db_test`. Audit trail is wholly net-new — this ADR establishes it from scratch; it does not reproduce any legacy behaviour.

The stakes are high. The system handles PHI across 115+ entities spanning all 14 bounded contexts: clinical records (Consultation, LabTest, Radiology, Procedure, Prescription), financial records (PatientBill, PatientInvoice, Collection, Payroll, PayrollDetail), and identity/access mutations (User, Role, Privilege). Every entity stores `createdBy` (Long FK to `users.id`), `createdOn` (Long FK to `days.id`), and `createdAt` (LocalDateTime) directly on the domain object — a rudimentary who/when that is structurally inadequate for compliance audit purposes: it captures creation only, records no before/after state, and cannot detect in-place mutation after the fact.

The 110 files using `@Transactional` routinely span multiple bounded contexts in a single in-process transaction (e.g., `GoodsReceivedNoteServiceImpl.approve()` touches Inventory, Procurement, Stock, and Purchase in one commit; `PatientServiceImpl` crosses every context except HR and Assets). Any audit mechanism must emit records atomically within these same transactions — orphaned audit rows after a rollback are a correctness defect.

**Native query surface in the net-new codebase:** all 22 `createNativeQuery` usages are `SELECT nextval(...)` sequence reads — no DML mutations. Any state-mutating native queries that emerge during implementation must be wrapped with explicit audit calls before merging; this is a hard gate (see Consequences).

HIPAA, national health data regulations, and the client's internal governance require: immutable evidence of who changed what, when; the ability to detect tampering including forged-but-well-formed rows inserted by privileged database users; and a defined retention period. These are forward-compliance requirements, not reproduction of a legacy capability.

---

## Decision

**Adopt an explicit, append-only audit log implemented via JPA entity listeners (`@EntityListeners`) backed by a dedicated `audit_log` table, written within the same ACID transaction as the mutating operation.**

The `audit_log` table is insert-only by application design and enforced by database-level controls (no `UPDATE`/`DELETE` granted to the application role; a separate privileged reader role for compliance queries). Each row captures: `id` (BIGINT identity), `uid` (ULID, `CHAR(26)`), `entity_type` (bounded-context-qualified name, e.g. `billing.PatientBill`), `entity_uid` (the public uid of the mutated entity — never internal id), `action` (CREATE / UPDATE / DELETE), `actor_uid` (user uid from Spring Security context), `actor_username`, `occurred_at` (UTC `Instant`), `before_state` (JSONB, null on CREATE), `after_state` (JSONB, null on DELETE), `checksum` (SHA-256 of the canonical fields), and `chain_checksum` (SHA-256 of this row's checksum concatenated with the previous row's `chain_checksum` for the same `entity_uid`).

**PHI redaction:** PHI fields are redacted before serialisation to `before_state`/`after_state`. The security-architect owns the PHI-field taxonomy and must deliver a `@PiiField` annotation before the audit listener is wired to clinical entities. Until that taxonomy is ratified, all clinical entity fields are replaced by `"[REDACTED]"` tokens; the structural diff (which fields changed) is preserved.

**Before-state capture:** `@PreUpdate` snapshots the entity state before flush via a `ThreadLocal` holder; `@PostUpdate` reads that snapshot. This pair is the only mechanism for reliable before/after diffing under JPA listeners — attempting reflection on the entity inside `@PostUpdate` alone yields post-flush state only.

**Hash chain — mandatory scope:**
- **Identity & Access** (User, Role, Privilege) and **Billing & Cashiering** (PatientBill, PatientInvoice, Collection, PaymentRecord): `chain_checksum` is **mandatory** and enforced non-null at the database level for these entity types. INSERT-only grants prevent deletion of rows but do not prevent a DBA with direct INSERT rights from fabricating well-formed rows with a correct standalone `checksum`. The hash chain closes this gap: a forged or re-ordered row breaks the chain, making tampering detectable without access to application logs.
- All other contexts: `chain_checksum` is populated but stored in a nullable column; chain verification tooling is applied on demand during compliance audits.

**Native query audit — mandatory gate:** any service method that performs state mutation via `entityManager.createNativeQuery()` or a `@Query(nativeQuery = true)` with DML must call `AuditService.recordNativeAudit(entityType, entityUid, action, beforeSnapshot, afterSnapshot)` explicitly within the same transaction, **before the native statement executes for before-state and immediately after for after-state**. A pull-request checklist item and an ArchUnit rule (`no @Modifying @Query with nativeQuery=true without a paired @AuditNativeOp annotation`) enforce this gate. A mutation invisible to audit is a silent integrity hole on PHI/financial rows — there are no exceptions to this requirement.

**Retention:** audit rows are retained for a minimum of **7 years** (aligned to typical healthcare regulatory minima; the engagement-lead must confirm jurisdiction-specific obligations before go-live). Retention enforcement is a PostgreSQL row-level retention policy applied by the data-architect, not application-level deletion.

---

## Considered Alternatives

| # | Approach | Verdict | Reason rejected |
|---|---|---|---|
| 1 | **JPA entity listeners (`@EntityListeners`)** — chosen | **ADOPTED** | Integrates cleanly with Spring Data JPA; fires within the same Hibernate session/transaction (atomicity guaranteed); no Hibernate internals coupling; testable with Testcontainers; survives schema evolution |
| 2 | **Re-enable Hibernate Envers (`@Audited`)** | Rejected | Envers schema (`_AUD` + `REVINFO`) is opaque and hard to query for compliance reports; PHI-field exclusion requires `@NotAudited` per field — error-prone across 115+ entities; no custom checksum or hash-chain support; version coupling to Hibernate 6 internals |
| 3 | **Hibernate `Interceptor` / `EventListener`** | Rejected | Fires below the JPA abstraction; not Spring-managed (poor testability); breaks under Hibernate 6 SessionFactory refactoring; entity listeners achieve the same at a higher abstraction layer |
| 4 | **Spring Modulith application events** | Rejected as sole mechanism | Inherently asynchronous by default; async events do not guarantee audit row atomicity if the event bus fails or the listener is transactionally decoupled; may complement the audit log for downstream streaming but must not replace synchronous persistence of the canonical audit row |
| 5 | **Database triggers (PostgreSQL)** | Rejected | Audit logic lives outside the application; PHI redaction cannot be applied at trigger level; trigger output is not version-controlled; violates Flyway-managed schema-as-code principle |

**Scoring (JPA listener vs Envers — the non-obvious call given Envers is already on the classpath):**

| Criterion | JPA Listener | Envers |
|---|---|---|
| PHI-field redaction | Exclusion list in listener — one place | `@NotAudited` per field across 115+ entities — error-prone |
| Tamper-evident checksum | Custom field, trivial to add | Not supported |
| Per-entity hash chain | Custom column, straightforward | Not supported |
| Schema clarity | Single `audit_log` table | N `_AUD` tables + `REVINFO` — opaque to compliance tools |
| Atomicity | Same Hibernate flush cycle | Same |
| Native query gap | Handled by mandatory `AuditService.recordNativeAudit()` | Same gap exists; no structured wrapper |

JPA listeners win on PHI safety, tamper evidence, and hash-chain support — all non-negotiable requirements.

---

## Consequences

### Positive
- Every clinical, financial, and identity/access mutation produces a durable, tamper-evident audit record in the same database transaction — no window for silent data loss.
- The hash chain on Identity/Access and Billing entities detects forged or re-ordered rows even when the forger has direct database INSERT rights.
- Compliance queries (who changed PatientBill X, before/after) are a single SQL SELECT on `audit_log` filtered by `entity_uid`.
- PHI redaction is implemented once in the listener; new entities inherit it automatically via `@PiiField` annotation scanning.
- The `audit_log` schema is stable and independent of domain model changes — entity renames do not corrupt historical rows.

### Negative
- Every mutating transaction writes at least one additional row. For high-throughput operations (bulk pharmacy dispensing, payroll batch runs) this increases transaction time. Mitigation: `audit_log` INSERT is a simple single-row write with no FK constraints back to domain tables (`entity_uid` stored as text), so overhead is minimal.
- `@PreUpdate` before-state capture uses a `ThreadLocal` snapshot; this must be cleared on `@PostUpdate`/`@PostRemove` to avoid memory leaks in long-lived persistence contexts.
- Chain checksum computation requires a prior-row lookup (`SELECT chain_checksum FROM audit_log WHERE entity_uid = ? ORDER BY id DESC LIMIT 1`) inside each auditable transaction. For Identity/Access and Billing this is a mandatory serialisation point — acceptable given the low write frequency of those entities relative to clinical throughput.

### Risks and Mitigations
- **Risk:** Spring Security context unavailable inside `@EntityListeners` during background/batch jobs. **Mitigation:** `SystemAuditContext` holder that background jobs set before executing; listener falls back to `SYSTEM` actor if context is empty — never silently omits actor.
- **Risk:** PHI redaction taxonomy not delivered before coding begins. **Mitigation:** listener ships with `@PiiField` marker; all clinical entity fields are redact-by-default until the security-architect confirms inclusions. Conservative safe default.
- **Risk:** State-mutating native query introduced without audit wrapping. **Mitigation:** ArchUnit rule fails the build if a `@Modifying @Query(nativeQuery=true)` method lacks a paired `@AuditNativeOp` annotation; PR checklist gate enforces the same for `createNativeQuery` DML calls in service code.
- **Risk:** `audit_log` table grows unbounded and degrades query performance. **Mitigation:** data-architect partitions `audit_log` by `occurred_at` (PostgreSQL declarative range partitioning, monthly or quarterly); devops-engineer configures automated detachment of partitions older than 7 years to cold storage via `pg_partman` + `pg_cron`.
- **Risk:** DBA with direct INSERT rights fabricates well-formed audit rows. **Mitigation:** per-entity hash chain on Identity/Access and Billing makes any inserted, deleted, or reordered row detectable. Application role retains INSERT-only grants; chain verification is run on demand by the security-architect.

---

## Exact-Process Impact

**Legacy behaviour preserved:** none — audit trail is net-new. The existing `createdBy` / `createdOn` / `createdAt` fields on domain entities are retained as operational denormalisation (used by DayService, business-day scoping, and existing report queries); they are not replaced by the audit log.

**What the legacy-analyst must still confirm (from the migrated legacy source, not the net-new codebase):**
1. Whether any of the 7 active native queries in `CollectionRepository`, `ClinicianPerformanceRepository`, `LabTestRepository`, and `PrescriptionRepository` in the *legacy* source perform DML. If any do, they must be rewritten as JPA operations or wrapped with `AuditService.recordNativeAudit()` before the entity is migrated — not after.
2. Whether any `@Transactional` service method in the legacy source performs mutations via direct JDBC or `entityManager.createNativeQuery()` beyond the 7 identified. These would be invisible to JPA listeners and must be catalogued and wrapped as part of migration acceptance criteria.

Note: in the net-new codebase (this repository), all 22 `createNativeQuery` usages are confirmed `SELECT nextval(...)` reads — zero DML. The mandatory wrapping requirement is a forward gate against regression, not a remediation of existing code.

**Change requests implied:** none for business process. The audit log is an additive, invisible infrastructure layer. No user-facing workflow changes. UI/UX for an audit-viewer interface (if required) is a separate feature request outside this ADR.

---

## Implementation Notes

**Stack:** Spring Boot 3.3, Spring Data JPA / Hibernate 6, PostgreSQL 16.

**Entity listener skeleton:**
```java
// audit.AuditEntityListener (in shared infrastructure module)
@Component  // Spring-managed via AuditListenerBeanLocator bridge
public class AuditEntityListener {
    @PreUpdate   void beforeUpdate(Object entity) { snapshotBefore(entity); }
    @PostPersist void onInsert(Object entity)     { capture(entity, AuditAction.CREATE, null); }
    @PostUpdate  void onUpdate(Object entity)     { capture(entity, AuditAction.UPDATE, consumeSnapshot()); }
    @PostRemove  void onDelete(Object entity)     { capture(entity, AuditAction.DELETE, consumeSnapshot()); }
}
```
`snapshotBefore` / `consumeSnapshot` use a `ThreadLocal<Map<String, Object>>` that is set in `@PreUpdate` and cleared after `@PostUpdate`/`@PostRemove`. Use `AuditListenerBeanLocator` (a static `ApplicationContext` reference) to access `AuditLogRepository` and `SecurityContextHolder` from within the JPA listener.

**Before-state capture note:** `@PostUpdate` fires after flush — the entity already reflects post-flush state. The `ThreadLocal` snapshot set in `@PreUpdate` is the only reliable source of before-state. Reflection-based field diffing in `@PostUpdate` alone does not work.

**Chain checksum computation (Identity/Access and Billing):**
```java
String prevChain = auditLogRepository
    .findTopChainChecksumByEntityUid(entityUid)  // SELECT ... ORDER BY id DESC LIMIT 1
    .orElse("0".repeat(64));
String chainChecksum = sha256(rowChecksum + prevChain);
```
This must execute within the same transaction as the audit INSERT to prevent races. The `audit_log` table's INSERT-only grant prevents concurrent chain-breaking deletes from the application role.

**Native audit wrapper:**
```java
// Required for any state-mutating native query
auditService.recordNativeAudit(
    entityType, entityUid, AuditAction.UPDATE, beforeSnapshot, afterSnapshot);
// Companion ArchUnit rule:
// no @Modifying @Query(nativeQuery=true) without @AuditNativeOp
```

**PHI redaction:** define `@PiiField` (retention: `RUNTIME`, target: `FIELD`). `AuditSerialiser` scans declared fields via reflection; any `@PiiField`-annotated field is replaced with `"[REDACTED]"` before JSON serialisation. Use Jackson `ObjectMapper` with a custom `PiiRedactionModule`. PHI field list is owned by the security-architect.

**Schema (Flyway migration `V7__create_audit_log.sql`):**
```sql
CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid             UUID         NOT NULL DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(120) NOT NULL,
    entity_uid      VARCHAR(36)  NOT NULL,
    action          VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE')),
    actor_uid       VARCHAR(36)  NOT NULL,
    actor_username  VARCHAR(80)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    before_state    JSONB,
    after_state     JSONB,
    checksum        CHAR(64)     NOT NULL,   -- SHA-256 of canonical fields
    chain_checksum  CHAR(64)                 -- NOT NULL enforced at app level for IAM + Billing
) PARTITION BY RANGE (occurred_at);

-- Identity/Access and Billing: enforce non-null chain at DB level via check constraint
-- applied in V7b migration after entity_type values are confirmed:
-- ALTER TABLE audit_log ADD CONSTRAINT chk_chain_mandatory
--   CHECK (chain_checksum IS NOT NULL OR
--          entity_type NOT IN ('iam.User','iam.Role','iam.Privilege',
--                              'billing.PatientBill','billing.PatientInvoice',
--                              'billing.Collection','billing.PaymentRecord'));

REVOKE UPDATE, DELETE ON audit_log FROM app_role;
```
Partition creation and `pg_partman` configuration are in subsequent migration scripts; coordinate with the data-architect.

**ULID for `uid`:** use `ulid-creator` (`com.github.f4b6a3:ulid-creator`, `UlidCreator.getMonotonicUlid()`), stored as `CHAR(26)` per ADR-0003. UUIDv7 / native `uuid` is the rejected alternative.

**Testing:** each bounded context's integration tests (JUnit 5 + Testcontainers PostgreSQL) must include: (1) assertion that a mutating service call produces exactly one `audit_log` INSERT with correct `action`, `actor_uid`, `entity_uid`, and non-null `checksum`; (2) for Identity/Access and Billing entities, assertion that `chain_checksum` is non-null and correctly chains to the prior row. The qa-test-engineer owns the audit assertion fixture library. Security-architect owns boundary tests for INSERT-only privilege enforcement and chain-verification tooling.

**Scope summary by context (minimum):**

| Context | Entities in scope | Chain checksum |
|---|---|---|
| Registration & Patient | Patient, Registration, Visit | nullable |
| Clinical/OPD | Consultation, ClinicalNote, GeneralExamination, WorkingDiagnosis, FinalDiagnosis | nullable |
| Inpatient/Nursing | Admission, AdmissionBed, DischargePlan, DeceasedNote, all chart entities | nullable |
| Pharmacy | PharmacySaleOrder, PharmacyStockCard, all transfer/RO/RN/TO entities | nullable |
| Inventory/Procurement | GoodsReceivedNote, LocalPurchaseOrder, StoreItem, StoreStockCard | nullable |
| Laboratory | LabTest | nullable |
| Radiology | Radiology | nullable |
| Procedures/Theatre | Procedure | nullable |
| Billing & Cashiering | PatientBill, PatientInvoice, Collection, PaymentRecord, PatientCreditNote | **mandatory non-null** |
| Insurance/Claims | InsurancePlan, all InsurancePlan price tables | nullable |
| HR/Payroll | Payroll, PayrollDetail, Employee | nullable |
| Assets | Asset | nullable |
| Identity & Access | User, Role, Privilege (every mutation — security-architect priority) | **mandatory non-null** |

Reference-data entities (DiagnosisType, Medicine, Item, etc.) are in scope for CREATE and UPDATE only; their audit is lower priority and may be deferred to a follow-on sprint if team capacity is constrained. The security-architect must confirm which reference-data mutations are compliance-critical.
