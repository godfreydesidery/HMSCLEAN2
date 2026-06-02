# ADR-0011: Data Strategy — Greenfield Start, Reference-Data Seeding, and Parity by Golden-Master Tests

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

---

## Context

The legacy Zana HMIS runs on MySQL 5 with Hibernate `ddl-auto=update` and no authoritative DDL. It holds three years of production transaction data and approximately 115 domain entities spanning every bounded context.

The client has ratified a **fresh greenfield build** in `d:/My_Works/HMS/HMSCLEAN2` (package `com.otapp.hmis`). The prior modernisation attempt at `D:\My_Works\HMS\HMSCLEAN\hmis-engine-api` is reference material only; the fresh build is not bound to its technical choices and does not inherit its schema, migrations, or data.

The client decision is unambiguous: **there is no production-data migration.** The new system starts with an empty transactional database. No MySQL-to-PostgreSQL ETL is written, no row-count or financial-totals reconciliation is performed against legacy rows, no identity sequences are seeded from `MAX(legacy_id)`, and no `created_on_day_id` / `Day` FK values are carried forward.

Two distinct data concerns survive this decision and are addressed here:

1. **Reference / master-data seeding.** The system cannot be used operationally without a pre-loaded set of reference records (privileges, roles, item types, insurance plans, ward categories, etc.). These must be delivered by Flyway seed scripts sourced from verified legacy values — this is a controlled, read-only extraction exercise, not a migration.

2. **Exact-process parity.** The business process (workflow states, document-number formats and sequences, pricing and insurance logic, RBAC semantics, report content) must be identical to the legacy. Parity is proven by behavioural golden-master tests that drive identical inputs through both systems and compare outputs at the business level, not by reconciling migrated row counts or financial totals.

The prior modernisation attempt demonstrates concretely what happens when these two concerns are conflated or deferred: DIAG-2 / PHARM-2 (payment gates implemented as queue filters instead of hard state-transition guards), DISCH-1 (no closure worklist for a second approver), and BILL-1 / BILL-2 (no receipts or cash-up reports) all reached QA because no golden-master harness existed early enough to catch process divergence.

---

## Decision

### 1. The system starts empty

The transactional database is provisioned by Flyway DDL-only migrations. No V2 bulk-load migration, no ETL staging table, no `setval` sequence seeding, and no cross-database link to MySQL are ever introduced. On first deployment the only non-empty tables are those populated by the reference-data seed scripts described in section 2.

Flyway configuration:

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
spring.flyway.validate-on-migrate=true
spring.jpa.hibernate.ddl-auto=validate
```

All migrations are DDL or seed scripts. There are no ETL, bulk-copy, or reconciliation callbacks.

### 2. Reference / master-data seeding via Flyway

The following categories of reference data must be present before the system is operationally usable. Each category is delivered as a dedicated Flyway seed migration (`V{n}__seed_{category}.sql`). Values are sourced by a one-time read-only query against the legacy MySQL instance; no writes touch MySQL and no legacy row identifiers are preserved.

| Category | Why required | Sourcing note |
|---|---|---|
| **Privileges (all 177 codes)** | Every `@PreAuthorize` annotation in the codebase references a specific privilege code. The 177 codes confirmed in the legacy (`GOODS_RECEIVED_NOTE-ALL`, `GOODS_RECEIVED_NOTE-CREATE`, etc.) must be present as rows in `iam_privilege` on every fresh deployment. This is non-negotiable and must be in a Flyway migration, never a dev-only seeder. | Read from legacy `privileges` table; preserve `name` values verbatim. |
| **Roles and role-privilege assignments** | The default role set (receptionist, clinician, nurse, pharmacist, cashier, lab tech, radiographer, store keeper, HR manager, system admin, etc.) with their privilege assignments must match legacy RBAC semantics. | Read from legacy `roles` + `role_privileges`; map to new schema. |
| **Clinic types and named clinics** | `md_clinic_type` enum values and the hospital's specific clinic names (General OPD, ANC, Dental, ENT, etc.) are required before any consultation can be booked. | Read from legacy `clinics` / `clinic_types`. |
| **Item / medicine types and units** | Pharmacy stock and store GRN require medicine category types and unit-of-measure definitions. | Read from legacy `medicine_categories`, `units`. |
| **Lab test types and analytes** | Lab order entry requires a populated `md_lab_test_type` catalogue. The fresh build's structured analyte + sex/age-banded reference range model (carried from the prior build's V60 design) requires analyte rows sourced from legacy `lab_test_types`. | Read from legacy `lab_test_types`; map analytes manually if legacy stores them as free text. |
| **Radiology and procedure types** | Required for order entry. | Read from legacy `radiology_types`, `procedure_types`. |
| **Insurance providers and plans** | Every invoice line pricing lookup fails without at least the cash-fallback row. The unified `md_service_price(plan_uid, kind, service_uid, currency, amount)` matrix replaces the legacy's six parallel `*InsurancePlan` tables; per-service pricing must be seeded from those six tables. | Read from legacy `consultation_insurance_plans`, `lab_test_type_insurance_plans`, `radiology_type_insurance_plans`, `procedure_type_insurance_plans`, `medicine_insurance_plans`, `ward_type_insurance_plans`. |
| **Ward types, categories, and named wards** | Admission flow requires ward masterdata. Ward price seeding uses the per-ward-type pricing in `md_service_price`. | Read from legacy `ward_types`, `wards`. |
| **Pharmacies and the central store** | Dispensing and GRN flows require at least one named pharmacy and one store record to be present. | Read from legacy `pharmacies`, `stores`. |
| **Company profile** | System-wide header (hospital name, address, logo path, contact) used on all printed documents. | Read from legacy `company_profile`. |
| **Currencies** | The fresh build's `md_currency` table (with a system default flag) must be seeded with at least the operating currency (TZS). | Read from legacy configuration or hard-code TZS as default. |
| **Dosage forms, routes, frequencies** | Prescription entry picklists. | Read from legacy `dosage_forms`, `routes`, `frequencies`. |

**Extraction is read-only.** A data-architect runs a one-time `SELECT`-only script against the legacy MySQL instance and produces static `.sql` INSERT files that become the Flyway seed migrations. The extraction script is version-controlled under `tools/seed-extraction/`. No ETL pipeline, no staging Parquet files, and no automated sync are introduced.

**`double` values in legacy reference data** (e.g., insurance plan prices) are extracted with `CAST(col AS DECIMAL(19,2))` and stored as `NUMERIC(19,2)` — the ratified money precision (ADR-0003 / ADR-0009: money `NUMERIC(19,2)`, quantity `NUMERIC(19,6)`). This matches the ratified `double → BigDecimal` data-type change and is acceptable at seed time — no financial reconciliation oracle is required because these are configuration values, not transactional balances.

**Document-number sequences** are not seeded from legacy `MAX(id)` values. Each PostgreSQL `SEQUENCE` object starts at 1 on a fresh deployment. The number format strings (e.g., `GRN{yyyyMMdd}-{n}`, `MRNO/{year}/{n}`, `USR-{000000}`) are reproduced exactly; only the counter resets to 1. The legacy's `SELECT MAX(id)+1` race condition (confirmed in legacy-findings.md finding C) is replaced with a per-document-type PostgreSQL sequence from day one.

**The SPT prefix collision** (legacy `StoreToPharmacyTO` and `PharmacyToPharmacyTO` both used `SPT{date}-{id}`) is resolved in **ADR-0009, the single source of truth**: `SPTO` for store-to-pharmacy transfer orders, `PPTO` for pharmacy-to-pharmacy. This ADR defers to those assignments and defines no scheme of its own. No back-fill is required because there are no legacy rows to preserve.

### 3. Exact-process parity via behavioural golden-master tests

Parity with the legacy process is validated by a behavioural golden-master test suite, not by data reconciliation. The harness:

1. **Drives a canonical scenario** through both the legacy system (via its REST API against a test MySQL instance pre-populated with known reference data) and the new system (via its REST API against a Testcontainers PostgreSQL instance seeded from the same reference-data seed migrations).

2. **Compares outputs at the business level:** document number format, workflow state after each transition, invoice line amounts (to 4 decimal places using `BigDecimal` equality, not `double`), report row content and structure, and RBAC enforcement (a request with the wrong privilege is rejected; one with the correct privilege is accepted).

3. **Does not compare internal identifiers.** Legacy `id` values and new `uid` values are not compared. Only business-observable outputs (number strings, amounts, status codes, report columns) are asserted.

4. **Covers all confirmed process-divergence points** from the prior attempt, specifically: the `SendToDoctor` atomicity guarantee (patient → consultation → fee invoice in one unit); payment-gate hard enforcement on `accept()` and `complete()` for CASH orders; the two-phase consultation-transfer lifecycle; the `DischargePlan.approve()` pre-condition on discharge and `markDeceased()`; and the `REQUESTED → ACCEPTED → COMPLETED` lab/radiology lifecycle.

5. **Is a CI gate.** No merge to `main` passes without the golden-master suite green. A failing golden-master test means a process divergence, not a data difference.

The prior build's Pattern: `@TransactionalEventListener(phase = AFTER_COMMIT)` for cross-module event seeding (e.g., consultation-fee invoice seeded after consultation booked) is carried into the fresh build and is one of the behaviours under golden-master coverage.

### 4. One-off reference-data extraction is not a data migration

The read-only extraction of legacy reference values described in section 2 is explicitly not a data migration. It does not touch transactional rows, it does not preserve legacy PKs, it does not require a cutover window, and it does not need reconciliation beyond a manual review of the resulting `.sql` files against the legacy admin screens. It can be re-run at any point before go-live if the legacy reference data changes.

---

## Considered Alternatives

| # | Alternative | Verdict | Why Rejected |
|---|---|---|---|
| A | Full MySQL→PostgreSQL ETL (original ADR-0011 scope) | Rejected — client decision | Client has ratified greenfield start; no production data is migrated. ETL, row-count reconciliation, financial-totals oracle, MAX(id) seeding, and Day-FK carry-forward are all out of scope. |
| B | Seed reference data via an admin "first-run wizard" | Rejected | Privileges and roles must be present for JWT authentication to function at all; a wizard that runs after first login cannot seed them. A Flyway migration is the only reliable pre-startup delivery mechanism. |
| C | Hard-code reference data in application config (YAML/properties) | Rejected | Privileges and insurance pricing change over time; database rows are the correct home. Hard-coding 177 privilege codes in YAML is fragile and bypasses the Flyway version-control contract. |
| D | Generate reference data from the prior build's QA database dump | Rejected as sole source | The prior build used ULID/VARCHAR(26) identifiers and a different schema. The extraction must be sourced from the legacy MySQL instance (canonical source of truth) and mapped to the fresh schema. The prior build's seed data may be consulted as a structural reference but cannot be used directly. |
| E | Prove parity by replaying migrated production data | Rejected — no migrated data exists | Parity is proven behaviourally via golden-master tests with known inputs. This is both sufficient and independent of data-migration risk. |
| F | Accept numeric non-parity for insurance plan prices (keep double precision) | Rejected — pre-approved | `double → BigDecimal` is client-pre-approved. Seed scripts extract legacy prices with `CAST(... AS DECIMAL(19,4))`. No sub-cent rounding analysis is required for reference prices because the business effect is pricing configuration, not historical balance reconciliation. |

---

## Consequences

**Positive:**
- Zero ETL complexity, zero cutover risk from data load failures. The new system can be deployed and functionally tested at any time without a production MySQL connection.
- Reference-data seed migrations are version-controlled, reproducible, and reviewable as ordinary SQL — no opaque ETL pipeline.
- Process parity is enforced continuously by CI-gated golden-master tests, catching divergence at the point of code change rather than at UAT.
- Document-number sequences start clean at 1; no `MAX(id)` seeding means no risk of phantom sequence gaps or seed script failures.
- The `double → BigDecimal` change is applied at seed-extraction time; no financial rounding audit is required.

**Negative / Costs:**
- The hospital operates two systems in parallel after go-live: legacy for historical lookups, new system for all new transactions. A read-only legacy-access window (duration a business decision) is required.
- Reference data sourced from legacy must be manually reviewed before the seed scripts are committed. The data-architect owns this review.
- Golden-master tests require a stable legacy test environment with known reference data. The legacy MySQL test instance must be provisioned and maintained until all golden-master scenarios are green.
- Insurance plan pricing seeded from legacy `double` columns carries sub-cent rounding from the `CAST`. The finance lead must sign off on the resulting seed values during reference-data review.

**Risks and Mitigations:**

| Risk | Likelihood | Mitigation |
|---|---|---|
| Legacy reference data is incomplete or inconsistent (e.g., insurance plan rows missing for some service types) | Medium | Data-architect reviews extraction output against legacy admin screens; gaps are filled manually before first seed migration is committed |
| A privilege code in `@PreAuthorize` does not exist in the seed migration | High if not audited | Run a static analysis pass to enumerate all 177 `@PreAuthorize` values and diff against the seed migration before any authentication test is attempted |
| Golden-master test environment (legacy MySQL) unavailable mid-sprint | Low–Medium | Snapshot the legacy test database as a Docker volume at extraction time; use the snapshot for golden-master CI runs |
| New document-number sequences (starting at 1) conflict with legacy document numbers in dual-operation window | Low | Document numbers are system-scoped; legacy and new systems never share a document-number namespace because they are separate databases |
| SPT prefix collision resurfaces if seed scripts are copied from legacy without fix | Low — known | Assign distinct prefixes at schema design time; the seed extraction script enforces the new prefix mapping |

---

## Exact-Process Impact

The following legacy process elements are reproduced exactly in the fresh build and are under golden-master coverage:

- All 177 `@PreAuthorize` privilege codes preserved verbatim as seed rows and enforced on the same endpoints.
- Document number format strings reproduced exactly: `GRN{yyyyMMdd}-{n}`, `LPO{yyyyMMdd}-{n}`, `PCN{yyyyMMdd}-{n}`, `PRL{yyyyMMdd}-{n}`, `PPRN{yyyyMMdd}-{n}`, `PSR{yyyyMMdd}-{n}`, `PPR{yyyyMMdd}-{n}`, `PGRN{yyyyMMdd}-{n}`, `MRNO/{year}/{n}`, `USR-{000000}` — only the sequence counter changes (PostgreSQL `SEQUENCE` replacing the legacy `SELECT MAX(id)+1` race condition).
- Business-day open/close workflow: the `Day` entity and `DayService.getDayId()` pattern is reproduced. Whether `created_on_day_id` FK columns are retained or replaced with a business-date timestamp is flagged as an open schema decision for the data-architect; the workflow behaviour is non-negotiable.
- Ward-day charge accrual: a `@Scheduled` daily job (not a deferred compute at discharge time) is required. This was a confirmed gap in the prior build (no scheduled job; CASH inpatients discharged with 0 ward-days billed).
- Prescribing duplicate-medicine and unfinished-course advisory alerts (`PrescribingAlertService` pattern) wired at prescription creation time.

The following are **not** reproduced because they were bugs or accidental legacy behaviour, not process:
- `DayServiceImpl.getTimeStamp()` UTC+3 hardcode. Timestamps are stored as UTC; display offset is an application/UI concern.
- `SELECT MAX(id)+1` document-number race condition. PostgreSQL sequences replace it.
- `double` arithmetic in pricing and billing. `BigDecimal` is the replacement (money `NUMERIC(19,2)`, quantity `NUMERIC(19,6)` per ADR-0003 / ADR-0009).
- The SPT prefix collision on transfer-order document numbers.

---

## Implementation Notes

**Reference-data extraction tooling:**
- Location: `tools/seed-extraction/` under the project root.
- Language: Python 3.12 + `mysql-connector-python`. Read-only `SELECT` queries only; no writes to MySQL.
- Output: static `.sql` files with `INSERT INTO ... ON CONFLICT DO NOTHING` statements.
- Each extracted category produces one file (e.g., `privileges.sql`, `insurance_plans.sql`). These become the body of the corresponding Flyway seed migration.
- `double` columns extracted with `CAST(col AS DECIMAL(19,4))`.

**Flyway seed migration naming convention:**
```
V{n}__seed_privileges.sql
V{n+1}__seed_roles_and_assignments.sql
V{n+2}__seed_clinic_types_and_clinics.sql
V{n+3}__seed_item_types_and_units.sql
V{n+4}__seed_lab_test_types_and_analytes.sql
V{n+5}__seed_radiology_and_procedure_types.sql
V{n+6}__seed_insurance_providers_and_plans.sql
V{n+7}__seed_ward_types_and_wards.sql
V{n+8}__seed_pharmacies_and_stores.sql
V{n+9}__seed_company_profile.sql
V{n+10}__seed_currencies.sql
V{n+11}__seed_dosage_picklists.sql
```
Seed migrations run after all DDL migrations. The DDL migration number block and seed block are separated to allow DDL changes to be applied without re-running seed data.

**Privilege-code audit (run before first integration test):**
```bash
# Enumerate all @PreAuthorize values in the codebase:
grep -rh '@PreAuthorize' src/main/java \
  | grep -oP '(?<=hasAuthority\()[^)]+' \
  | tr -d "'" | sort -u > /tmp/code_privileges.txt

# Enumerate privileges in the seed file:
grep -oP "(?<=VALUES \(')[^']*" \
  src/main/resources/db/migration/V{n}__seed_privileges.sql \
  | sort -u > /tmp/seed_privileges.txt

diff /tmp/seed_privileges.txt /tmp/code_privileges.txt
# Any lines in code_privileges.txt not in seed_privileges.txt = missing seed row = broken auth
```

**Golden-master test skeleton (JUnit 5 + Testcontainers):**
```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class SendToDoctorGoldenMasterTest {

    // New system: Testcontainers PostgreSQL, seeded by Flyway
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // Legacy system: pre-existing Docker MySQL snapshot (read-only)
    // LegacyClient wraps the legacy REST API running against that snapshot.

    @Test
    void sendToDoctor_createsConsultationAndFeeInvoiceAtomically() {
        // 1. Register patient on both systems with identical inputs.
        // 2. Call SendToDoctor on both systems.
        // 3. Assert: consultation status = BOOKED on both.
        // 4. Assert: fee invoice exists and amount matches (BigDecimal equality to 4dp).
        // 5. Assert: invoice is UNPAID for CASH patient on both (gate not yet settled).
    }
}
```

**Ownership:**
- Data-architect: owns reference-data extraction scripts, seed migration SQL review, and sign-off on insurance plan pricing values.
- Backend engineers: own Flyway DDL migrations and the privilege-code audit diff.
- QA engineer: owns golden-master test scenarios, legacy snapshot provisioning, and CI gate configuration.
- Solution-architect: reviews seed migration content before the first staging deployment; signs off golden-master suite coverage before UAT is scheduled.
