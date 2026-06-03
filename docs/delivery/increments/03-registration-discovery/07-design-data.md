I now have complete context on the conventions. I have everything needed to produce the schema design. The `audit_logs` table is the security-architect-owned append-only audit (V1) — I will not author independent audit DDL; that is out of scope for this Registration schema task and explicitly owned by security-architect.

Here is the complete deliverable.

---

# Registration Bounded Context — PostgreSQL 16 Schema + Flyway Plan (V19)

## 0. Pre-flight findings (verified against repo)

- **`seq_mrno` ALREADY EXISTS** — created in `V13__masterdata_document_sequences.sql:57-58` (`BIGINT START WITH 1`). **Do NOT recreate it.** V19 must only `nextval()` it (in service code), never `CREATE SEQUENCE`.
- **Billing tables already own `patient_uid` as a loose VARCHAR(26) no-FK ref** (`V15__billing_core.sql:29`, `:104`, `:197`). The new `patients.uid` is the target of those loose refs — confirming the cross-module convention. The registration-fee `PatientBill` is created by billing's `recordClinicalCharge(...)`/registration flow, NOT by the Registration module — so `patients` does **not** own a bill table; `registrations` carries a loose `patient_bill_uid`.
- **Audit DDL is NOT in scope here.** The append-only `audit_logs` table is security-architect's deliverable, already shipped in `V1__schema.sql:15-29`. Per my boundary, I author no independent audit table for `patients`; PHI-field audit coverage for the patient row must come from security-architect before any per-entity audit DDL is written. (Restated phantom-feature fact: legacy has zero `@Audited`/Envers — no audit history to migrate.)
- **Conventions confirmed** (V1:4-9, V6:8-13, V15:4-7): plural snake_case; `id BIGINT GENERATED ALWAYS AS IDENTITY` (hidden, never exposed); `uid VARCHAR(26) NOT NULL UNIQUE` (ULID); `NUMERIC(19,2)` money / `NUMERIC(19,6)` qty; `TIMESTAMPTZ` audit cols `created_at/updated_at/created_by/updated_by/version`; named `pk_/fk_/uq_/ck_/idx_`; cross-module refs are loose VARCHAR(26) uids with NO FK.

## 1. DDL Sketch — `V19__registration_patient.sql`

```sql
-- =====================================================================================
-- Increment 03 — Registration / Patient bounded context
-- Conventions: identical to V1/V6/V15. Cross-module refs = loose VARCHAR(26) uid, no FK.
-- seq_mrno ALREADY EXISTS (V13:57) — NOT recreated here.
-- Audit trail: owned by security-architect (audit_logs V1:15). No per-entity audit DDL
--   authored here pending security-architect PHI classification.
-- Legacy citations: Patient.java:36-107, Visit.java:33-61, Registration.java:34-62,
--   PatientServiceImpl.java:226-422/739-744, Sanitizer.java:11-17.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- patients — the master demographic record (legacy domain/Patient.java:36-107)
-- gender/type/payment_type are free-text @NotBlank Strings in legacy; modelled as
-- CHECK-constrained VARCHAR here (data-model modernization permitted; values are EXACT).
-- Next-of-kin = 3 flat nullable columns (legacy has NO kin entity, NO @Embedded, ONE kin).
-- NO deceased boolean (legacy: type='DECEASED'). NO updated_* audit on legacy Patient,
--   but we keep the standard audit cols for the modern platform (inert wrt exact process).
-- -------------------------------------------------------------------------------------
CREATE TABLE patients (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)   NOT NULL,

    -- "no" = MRN. Legacy MRNO/{year}/{id}. Nullable here because it is assigned AFTER the
    -- first INSERT (id/seq must exist). UNIQUE once set. (Patient.java:45-47)
    no              VARCHAR(40),

    -- searchKey: no + first + middle + last + phone, sanitized (PatientServiceImpl:739-744).
    -- UNIQUE @NotBlank in legacy (Patient.java:48-50).
    search_key      TEXT          NOT NULL,

    first_name      TEXT          NOT NULL,                 -- @NotBlank (Patient.java:54-55)
    middle_name     TEXT,                                   -- nullable  (Patient.java:56)
    last_name       TEXT          NOT NULL,                 -- @NotBlank (Patient.java:57-58)
    date_of_birth   DATE          NOT NULL,                 -- @NotNull  (Patient.java:59-60)
    gender          VARCHAR(20)   NOT NULL,                 -- @NotBlank (Patient.java:61-62)

    -- patientType vocabulary (Patient.java:63-64; values §EXTRACTION1.4)
    type            VARCHAR(20)   NOT NULL DEFAULT 'OUTPATIENT',

    -- paymentType: legacy ONLY CASH|INSURANCE (Patient.java:68-69; §EXTRACTION1.3)
    payment_type    VARCHAR(20)   NOT NULL DEFAULT 'CASH',

    membership_no   VARCHAR(100)  DEFAULT '',               -- (Patient.java:70)
    phone_no        VARCHAR(40),                            -- (Patient.java:74)
    address         VARCHAR(400),                           -- (Patient.java:75)
    email           VARCHAR(120),                           -- (Patient.java:76)
    nationality     VARCHAR(80),                            -- (Patient.java:77)
    national_id     VARCHAR(60),                            -- (Patient.java:78)
    passport_no     VARCHAR(60),                            -- (Patient.java:79)

    -- next-of-kin (flat, single kin) (Patient.java:83-85)
    kin_full_name     VARCHAR(200),
    kin_relationship  VARCHAR(80),
    kin_phone_no      VARCHAR(40),

    active          BOOLEAN       NOT NULL DEFAULT TRUE,     -- (Patient.java:89)

    -- insurance plan: legacy @ManyToOne nullable, @OnDelete(NO_ACTION) (Patient.java:97-100).
    -- LOOSE cross-module ref (insurance_plans is in masterdata) — NO FK. Resolved by name
    -- at point of care (insurance_plans.uid). NULL for CASH.
    insurance_plan_uid VARCHAR(26),

    -- audit cols (platform standard; legacy stamps only creation, §EXTRACTION1.2)
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT        NOT NULL,

    CONSTRAINT pk_patients              PRIMARY KEY (id),
    CONSTRAINT uq_patients_uid          UNIQUE (uid),
    CONSTRAINT uq_patients_no           UNIQUE (no),         -- legacy UNIQUE; NULL allowed pre-assignment
    CONSTRAINT uq_patients_search_key   UNIQUE (search_key), -- legacy UNIQUE (Patient.java:49)
    CONSTRAINT ck_patients_gender CHECK (gender IN ('MALE','FEMALE')),
    CONSTRAINT ck_patients_type CHECK (
        type IN ('OUTPATIENT','OUTSIDER','INPATIENT','DECEASED')
    ),
    CONSTRAINT ck_patients_payment_type CHECK (
        payment_type IN ('CASH','INSURANCE')
    ),
    -- Business rule: INSURANCE patient must carry a plan + membership_no
    -- (PatientResource.java:296-305, :359-373). CASH must NOT carry a plan.
    CONSTRAINT ck_patients_insurance_consistency CHECK (
        (payment_type = 'INSURANCE'
            AND insurance_plan_uid IS NOT NULL
            AND membership_no IS NOT NULL AND membership_no <> '')
        OR (payment_type = 'CASH' AND insurance_plan_uid IS NULL)
    )
);

-- Search-key indexes (no pagination in legacy; rebuild adds Pageable — these support both)
CREATE INDEX idx_patients_search_key_trgm ON patients USING gin (search_key gin_trgm_ops);
CREATE INDEX idx_patients_no              ON patients (no);
CREATE INDEX idx_patients_phone_no        ON patients (phone_no);
CREATE INDEX idx_patients_last_name       ON patients (last_name);
CREATE INDEX idx_patients_membership_no   ON patients (membership_no);
CREATE INDEX idx_patients_insurance_plan_uid ON patients (insurance_plan_uid);
-- requires:  CREATE EXTENSION IF NOT EXISTS pg_trgm;  (place at top of V19)

-- -------------------------------------------------------------------------------------
-- registrations — thin Patient↔registration-fee-bill join (legacy Registration.java:34-62)
-- OneToOne to patient (one per patient). status='ACTIVE' on create
-- (PatientServiceImpl.java:293-302). patient_bill is in the BILLING module → loose uid.
-- DRIFT NOTE: Registration is NOT Visit. No sequence/encounter fields here (EXTRACTION4.c).
-- -------------------------------------------------------------------------------------
CREATE TABLE registrations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)   NOT NULL,

    patient_id      BIGINT        NOT NULL,                 -- intra-module FK (real)
    -- registration-fee PatientBill lives in billing module → loose uid, NO FK
    patient_bill_uid VARCHAR(26)  NOT NULL,

    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT        NOT NULL,

    CONSTRAINT pk_registrations          PRIMARY KEY (id),
    CONSTRAINT uq_registrations_uid      UNIQUE (uid),
    -- OneToOne: exactly one registration per patient (Registration.java:46-49)
    CONSTRAINT uq_registrations_patient  UNIQUE (patient_id),
    CONSTRAINT fk_registrations_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_registrations_status CHECK (status IN ('ACTIVE'))
);
CREATE INDEX idx_registrations_patient_bill_uid ON registrations (patient_bill_uid);

-- -------------------------------------------------------------------------------------
-- visits — per-encounter log (legacy Visit.java:33-61). ManyToOne to patient (many/patient).
-- Created at register(FIRST), consultation/admission/NC(SUBSEQUENT*). No per-day dedup
-- in legacy (EXTRACTION4.b). status only ever 'PENDING' in legacy. type copied from patient.
-- -------------------------------------------------------------------------------------
CREATE TABLE visits (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)   NOT NULL,

    patient_id      BIGINT        NOT NULL,                 -- intra-module FK (real)

    -- (Visit.java:42-43) FIRST | SUBSEQUENT | SUBSEQUENT-FOR-ADMISSION
    sequence        VARCHAR(30)   NOT NULL DEFAULT '',
    -- (Visit.java:44-45) copied from patient.type (OUTPATIENT|INPATIENT|OUTSIDER)
    type            VARCHAR(20)   NOT NULL DEFAULT '',
    status          VARCHAR(20)   NOT NULL,                 -- (Visit.java:46-47) only 'PENDING' set

    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80),
    version         BIGINT        NOT NULL,

    CONSTRAINT pk_visits          PRIMARY KEY (id),
    CONSTRAINT uq_visits_uid      UNIQUE (uid),
    CONSTRAINT fk_visits_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT ck_visits_sequence CHECK (
        sequence IN ('FIRST','SUBSEQUENT','SUBSEQUENT-FOR-ADMISSION')
    ),
    CONSTRAINT ck_visits_status CHECK (status IN ('PENDING'))
);
-- "last visit" = most recent by created_at (EXTRACTION4.b: legacy relies on insertion order;
-- rebuild does explicit ORDER BY created_at DESC LIMIT 1) — composite supports it directly.
CREATE INDEX idx_visits_patient_created_at ON visits (patient_id, created_at DESC);
```

### Constraint-vocabulary decisions requiring engagement-lead / business-analyst sign-off
- **`ck_patients_gender ('MALE','FEMALE')`** — legacy is free-text `@NotBlank` String (Patient.java:61-62); it enumerates no values. The CHECK is an *inferred* tightening. **CONFIRM the exact gender vocabulary with business-analyst** (legacy data may contain other casings/values such as `Male`/`M`); if uncertain, ship gender as plain `VARCHAR(20) NOT NULL` with no CHECK to preserve exact process and avoid blocking the 3-year migration.
- **`ck_patients_insurance_consistency`** — encodes the register/update branch rule. The legacy *insurance-but-regFee==0* and *insurance-but-no-covered-plan* fall-throughs (EXTRACTION2.c edge cases) leave a patient INSURANCE without a resolved coverage but **still with a plan + membership**, so they do NOT violate this CHECK. Confirmed safe, but flag for migration: any legacy CASH row that erroneously carries a stale `insurance_plan_id` would fail the CHECK — **data-migration-engineer must null those out** (see reconciliation §5).
- **`ck_visits_status ('PENDING')` / `ck_registrations_status ('ACTIVE')`** — these are the ONLY values legacy ever writes in this module. If downstream increments (admission/discharge) mutate `visits.status` or `registrations.status`, the CHECK set must be widened by an additive migration then. Flag to solution-architect.

## 2. Canonical legacy → new column mapping

### `patients` (legacy `patients` / `domain/Patient.java`)
| Legacy column (Java) | MySQL type | New column | PG type | Transform / rationale |
|---|---|---|---|---|
| `id` (IDENTITY) | BIGINT AUTO_INC | `id` | BIGINT GEN ALWAYS | hidden surrogate; **not** exposed. New `uid` ULID is the public key. |
| — | — | `uid` | VARCHAR(26) | NET-NEW ULID public id (platform convention). Migration generates one per row. |
| `no` | VARCHAR | `no` | VARCHAR(40) | MRN `MRNO/{year}/{id}`. **DRIFT decision pending**: legacy suffix = patient PK; `seq_mrno` (V13) would decouple. Migration copies legacy `no` verbatim (parity); new registrations use the engagement-lead-approved scheme. |
| `searchKey` | VARCHAR | `search_key` | TEXT | UNIQUE NOT NULL. Recompute via legacy `createSearchKey`+`Sanitizer` (PatientServiceImpl:739-744, Sanitizer:11-17) for byte-identical keys, OR copy legacy value verbatim. |
| `firstName` | VARCHAR | `first_name` | TEXT | rename camel→snake. |
| `middleName` | VARCHAR | `middle_name` | TEXT | nullable. |
| `lastName` | VARCHAR | `last_name` | TEXT | — |
| `dateOfBirth` | DATE | `date_of_birth` | DATE | — |
| `gender` | VARCHAR | `gender` | VARCHAR(20) | free-text→CHECK (see §1 caveat). |
| `type` | VARCHAR | `type` | VARCHAR(20) | OUTPATIENT/OUTSIDER/INPATIENT/DECEASED. |
| `paymentType` | VARCHAR | `payment_type` | VARCHAR(20) | CASH/INSURANCE only. |
| `membershipNo` | VARCHAR | `membership_no` | VARCHAR(100) | default `''`. |
| `phoneNo` | VARCHAR | `phone_no` | VARCHAR(40) | — |
| `address` | VARCHAR | `address` | VARCHAR(400) | — |
| `email` | VARCHAR | `email` | VARCHAR(120) | — |
| `nationality` | VARCHAR | `nationality` | VARCHAR(80) | — |
| `nationalId` | VARCHAR | `national_id` | VARCHAR(60) | — |
| `passportNo` | VARCHAR | `passport_no` | VARCHAR(60) | — |
| `kinFullName` | VARCHAR | `kin_full_name` | VARCHAR(200) | flat kin (no child table). |
| `kinRelationship` | VARCHAR | `kin_relationship` | VARCHAR(80) | — |
| `kinPhoneNo` | VARCHAR | `kin_phone_no` | VARCHAR(40) | — |
| `active` | TINYINT(1) | `active` | BOOLEAN | default TRUE. |
| `insurancePlan` (FK `insurance_plan_id`) | BIGINT FK | `insurance_plan_uid` | VARCHAR(26) | **FK→loose uid**: cross-module (insurance is masterdata). Migration resolves legacy `insurance_plan_id`→`insurance_plans.uid`. |
| `createdBy` (`created_by_user_id`) | BIGINT | `created_by` | VARCHAR(80) | legacy stored user *id*; new stores username string. Migration resolves id→username (or stores id-as-string if unresolved). |
| `createdOn` (`created_on_day_id`) | BIGINT | **DROPPED** | — | legacy business-day *id*. Not carried on patients in new model; the business-day linkage moves to the bill/visit attribution (`business_day_uid` already on billing). Flag if any report reads patient.createdOn. |
| `createdAt` | DATETIME | `created_at` | TIMESTAMPTZ | legacy used `+3h` hack (DayServiceImpl:86-87); migrate raw value, treat as the stored instant. |
| — | — | `updated_at/updated_by` | TIMESTAMPTZ/VARCHAR(80) | NET-NEW; NULL for migrated rows (legacy never stamped updates). |
| — | — | `version` | BIGINT | NET-NEW optimistic lock; seed 0. |
| (commented dead fields Patient.java:93-94) | — | **DROPPED** | — | dead code. |

### `registrations` (legacy `registrations` / `domain/Registration.java`)
| Legacy | New | Transform |
|---|---|---|
| `id` | `id` (+`uid` NET-NEW) | surrogate hidden; ULID added. |
| `patient` (`patient_id`, OneToOne) | `patient_id` BIGINT FK + `uq_registrations_patient` | real intra-module FK; OneToOne enforced by UNIQUE. |
| `patientBill` (`patient_bill_id`, OneToOne) | `patient_bill_uid` VARCHAR(26) | **FK→loose uid**: bill is in billing module. Resolve legacy id→`patient_bills.uid`. |
| `status` | `status` | only `ACTIVE`. |
| `createdBy/On/At` | `created_by`/(drop `created_on`)/`created_at` | same rules as patients. |

### `visits` (legacy `visits` / `domain/Visit.java`)
| Legacy | New | Transform |
|---|---|---|
| `id` | `id` (+`uid` NET-NEW) | — |
| `sequence` | `sequence` | FIRST/SUBSEQUENT/SUBSEQUENT-FOR-ADMISSION. |
| `type` | `type` | copied patient type. |
| `status` | `status` | only PENDING. |
| `patient` (`patient_id`, ManyToOne) | `patient_id` BIGINT FK | real intra-module FK. |
| `createdBy/On/At` | `created_by`/(drop `created_on`)/`created_at` | same rules. |

**`DeceasedNote`** (legacy `domain/DeceasedNote.java`) is referenced by the deceased flow but is a **clinical/discharge-adjacent entity, NOT part of the core Registration schema** for V19 — defer to its owning increment. Note here only that the deceased "flag" is `patients.type='DECEASED'` plus this companion entity; do not add a `deceased` boolean.

## 3. Index strategy & rationale

| Index | Table | Justification (query pattern) |
|---|---|---|
| `idx_patients_search_key_trgm` (GIN trigram) | patients | Legacy fuzzy search binds the same term to 5–6 OR `LIKE %v%` keys (`load_patients_like[_and_card]`, PatientRepository:41-51). A trigram GIN on the composite `search_key` (which already concatenates no+names+phone) serves the typeahead `includes()`/`LIKE` substring scan that legacy does in-memory over the whole table. Requires `pg_trgm`. |
| `idx_patients_no` | patients | `findByNo` exact lookup (e.g. `"GENERAL"` walk-in, PatientServiceImpl:3266/3398) and MRN reverse-lookup. Also backs `uq_patients_no` error messages. |
| `idx_patients_phone_no` / `idx_patients_last_name` / `idx_patients_membership_no` | patients | Support the per-field LIKE legs of `load_patients_like_and_card` when the rebuild splits the legacy single-term OR into targeted searches; `membership_no` specifically backs the card-search variant (PatientRepository:50-51). |
| `idx_patients_insurance_plan_uid` | patients | Reconciliation + "patients on plan X" reporting; backs migration null-out validation. |
| `uq_patients_search_key`, `uq_patients_no`, `uq_patients_uid` | patients | Legacy UNIQUE on `searchKey`/`no` (Patient.java:46,49); `uid` per platform convention. |
| `uq_registrations_patient` | registrations | Enforces legacy OneToOne (one registration per patient, Registration.java:46-49). |
| `idx_registrations_patient_bill_uid` | registrations | `findAllByPatientBillIn` reporting join (RegistrationRepository) across the loose bill ref. |
| `idx_visits_patient_created_at (DESC)` | visits | "last visit" = most recent by `created_at` (`last_visit_date_time`, EXTRACTION4.b) and `findAllByPatient`; the `(patient_id, created_at DESC)` composite serves both the per-patient encounter list and the `ORDER BY created_at DESC LIMIT 1` last-visit query without a sort step. |

No index on `gender`/`type`/`active` alone — low cardinality, not a documented query path (legacy has no such filtered search). Add later only if a report demands it (additive migration).

## 4. seq_mrno handling (DRIFT — engagement-lead decision required)

- `seq_mrno` exists (V13:57). **V19 does not touch it.** MRN generation is service-side.
- **Legacy reality** (PatientServiceImpl:250): `no = "MRNO/" + Year.now() + "/" + patient.getId()` — suffix is the **IDENTITY PK**, no padding, no per-year reset, server-TZ year.
- **DRIFT**: using `nextval('seq_mrno')` for the suffix decouples the MRN number from the patient surrogate id (observable change — `MRNO/2026/1457` would no longer imply patient id 1457). The planning doc's `seq_mrno`/EAT-zoned/"gap-free per year" claims are all DRIFT vs legacy.
- **Recommendation to engagement-lead**: ratify ONE contract before backend implements MRN. Either (a) exact parity — suffix = `patients.id` after first save (then `seq_mrno` is unused dead schema for MRN), or (b) approved decoupling — suffix = `nextval('seq_mrno')` with EAT-pinned year. The schema supports both (`no` is nullable-until-assigned VARCHAR(40)); only the service algorithm differs. Do not let backend pick silently.

## 5. Reconciliation strategy (data-migration-engineer must pass before go-live)

| Check | Definition | Tolerance |
|---|---|---|
| **R1 row counts** | `COUNT(*)` legacy `patients`/`registrations`/`visits` = new equivalents. | Exact (0). |
| **R2 MRN parity** | Every migrated `patients.no` matches legacy `no` verbatim AND is UNIQUE. | Exact; 0 NULL `no` for migrated rows. |
| **R3 search_key parity** | Recomputed `search_key` (legacy algorithm) = legacy stored value for 100% sampled rows; UNIQUE holds. | Exact on sample; 0 unique violations. |
| **R4 insurance consistency** | 0 rows violate `ck_patients_insurance_consistency`. Pre-migration: report CASH rows with non-null legacy `insurance_plan_id` (must be nulled) and INSURANCE rows with empty `membership_no`. | 0 violations post-fix; deviations logged + signed off. |
| **R5 FK integrity** | Every `registrations.patient_id`/`visits.patient_id` resolves; every `registrations.patient_bill_uid` resolves to an existing `patient_bills.uid` (cross-module spot-check, no DB FK). | 0 orphans. |
| **R6 OneToOne** | `COUNT(DISTINCT patient_id) = COUNT(*)` in `registrations`. | Exact. |
| **R7 plan-uid resolution** | Every non-null `patients.insurance_plan_uid` resolves to `insurance_plans.uid`. | 0 unresolved. |
| **R8 type/payment vocabulary** | 0 rows with `type`/`payment_type`/`gender`/`status` outside the CHECK sets (catches legacy free-text dirty values BEFORE constraint enforcement). | 0; any out-of-vocab value escalated to business-analyst (may force relaxing the gender CHECK). |

ETL gate: I will not approve any data-migration ETL script that loads `patients`/`registrations`/`visits` without R1–R8 wired in as pre/post-load assertions.

## 6. Open items routed to other roles
- **business-analyst**: exact `gender` vocabulary (legacy free-text) → decides whether `ck_patients_gender` ships or gender stays unconstrained VARCHAR.
- **engagement-lead**: MRN contract (PK-coupled parity vs `seq_mrno` decoupling) — §4.
- **security-architect**: PHI classification + audit coverage for `patients` (PHI-bearing) before any per-entity audit DDL. The platform `audit_logs` (V1:15-29) already exists; whether the patient row needs a dedicated old/new-values JSONB audit table is a security-architect requirement I await — I do **not** self-select it.
- **solution-architect**: confirm `registrations.status`/`visits.status` CHECK sets won't be mutated by later increments (admission/discharge) without an additive widening migration.

## Relevant files (absolute)
- Next migration to author: `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V19__registration_patient.sql` (sketch above; not yet written — pending the §6 decisions before commit).
- Existing (read, conventions/constraints sourced): `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql`, `V6__masterdata_org_units.sql`, `V9__masterdata_insurance.sql`, `V13__masterdata_document_sequences.sql` (owns `seq_mrno`), `V15__billing_core.sql` (owns `patient_uid` loose-ref + registration `PatientBill`), `V18__billing_settlement_flag.sql`.
- Drift source (do not trust for names/codes): `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\03-registration-patient.md`.