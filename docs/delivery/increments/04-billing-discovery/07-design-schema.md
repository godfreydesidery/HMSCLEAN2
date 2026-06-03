I have everything I need. The conventions are crystal clear (BIGINT IDENTITY id + VARCHAR(26) uid, money NUMERIC(19,2), qty NUMERIC(19,6), explicit constraint naming, enums as VARCHAR+CHECK, loose-uid refs with no FK across module boundaries, `ck_`/`uq_`/`fk_`/`pk_`/`idx_` prefixes, partial/COALESCE-unique indexes, additive-only after baseline). I have the full legacy extraction findings in memory. The cashiers table already exists (personnel extension), confirming Cashier is iam personnel not a shift.

Here is the complete schema and migration design.

---

# Increment 04 Billing/Insurance — Schema & Migration Design (Flyway V15+)

## 0. Grounding facts (verified against repo + legacy extractions)

- **Existing state:** V1–V14 applied. NEXT = **V15**. Conventions locked by V1 header: `id BIGINT GENERATED ALWAYS AS IDENTITY` (internal, never in DTO), `uid VARCHAR(26) NOT NULL UNIQUE` (ULID), audit columns `created_at/updated_at/created_by/updated_by/version`, money `NUMERIC(19,2)`, qty `NUMERIC(19,6)`, enums as `VARCHAR + CHECK`, named constraints `pk_/fk_/uq_/ck_/idx_`.
- **`cashiers` table already exists** (V4:163, iam personnel extension — Cashier = staff master-data, NOT a shift). Confirmed by Extraction 3. Any cashier-shift concept is a NEW table, never a column on `cashiers`.
- **`seq_pcn_no` exists** (V13:26) — REUSE, do not recreate.
- **`service_prices`** (V9) already carries the `(plan_uid, kind, service_uid, currency)` matrix with loose-uid refs and `covered`. Billing CONSUMES it via masterdata::lookup; billing does NOT recreate it.
- **Patient does not exist yet** (Registration = inc-03, built AFTER billing). Therefore every patient reference in billing is a **loose `patient_uid VARCHAR(26)` with NO FK**. Same for `plan_uid`, `service_uid`, `created_by_user_uid`.

---

## 1. CRITICAL DEVIATION SUMMARY (read before the DDL)

Every billing-spec construct that the legacy extractions proved is INVENTED or different. Each needs engagement-lead ratification before I freeze the DDL. I provide **two DDL variants** where ratification is pending: a **legacy-faithful** baseline and a **spec-hardened** form behind a "IF RATIFIED" gate.

| # | Spec (inc-04) says | Legacy reality (extraction) | Schema impact | Status |
|---|---|---|---|---|
| D-1 | Invoice lifecycle `PENDING→PARTIALLY_PAID→PAID/CANCELLED` | Header only `{PENDING, APPROVED}`; physically DELETED on cancel; no header recompute (Ext 1 §2, Ext 4 §7) | `ck_patient_invoices_status` value set differs | **NEEDS CR** |
| D-2 | Refund via **signed/negative** `PatientPaymentDetail.amount` | `PatientPaymentDetail` has **NO amount column at all** (Ext 1 §1, Ext 4 §6) | Adding `amount` (signed) is net-new | **NEEDS CR** |
| D-3 | Per-line **partial payment** + allocation | No partial pay; bill paid in full; tendered == sum exactly (Ext 1 §4) | `paid`/`balance` arithmetic semantics | **NEEDS CR** |
| D-4 | `CashierShift OPEN→CLOSED` + per-mode snapshot + `NO_OPEN_SHIFT 409` | **No shift entity exists.** Only global `Day` (already `business_days` V1). Payment never gated on open shift (Ext 3 §3, §5) | Entire `cashier_shifts` table is net-new | **NEEDS CR** |
| D-5 | `InsuranceClaim` ledger `SUBMITTED→SETTLED/REJECTED` + claim numbering | **No claim entity exists.** Claim == PENDING `PatientInvoice` per (patient, plan). No settlement state (Ext 5 §A) | Entire `insurance_claims(+lines)` table is net-new | **NEEDS CR** |
| D-6 | `settled` flag on `patient_invoice`, hard pay-before-service gate | **No `settled` field; no hard gate** anywhere — UI filter only (Ext 2 §5) | `settled` column is net-new hardening | **NEEDS CR** |
| D-7 | PaymentType enum CASH/INSURANCE/DEBIT_CARD/CREDIT_CARD/MOBILE | Only CASH + INSURANCE live; card/mobile are dead source comments; `Collection.paymentChannel` hardcoded "Cash" (Ext 2 §4, Ext 3 §2) | `ck_..._payment_type` value set | **NEEDS CR** |
| D-8 | Receipt = `PatientPayment.uid`; PCN `PCN{yyyyMMdd}-{seq}` EAT | Legacy invoice `no` = `id.toString()`; PCN date is UTC, not EAT (Ext 4 §4) | Numbering/timezone semantics | partly ratified (CR-09 seq), EAT vs UTC NEEDS CR |
| D-9 | `coverage_status` (UNPAID/COVERED/VERIFIED) on invoice detail | Legacy: those statuses live on **PatientBill**, not the invoice detail; detail.status only ∈ {PAID, (unset)} (Ext 4 §7) | column placement | design decision below |
| D-10 | `PatientInvoice.amountAllocated/amountUnallocated` | Dead fields, never written (Ext 1 §1) | DROP — do not carry forward | recommend drop |
| D-11 | Invoice-delete-on-cancel `j=j++` always-delete bug | Buggy always-delete (Ext 4 §2) | behavioural, not schema; flag to backend/qa | flag only |

My recommendation to engagement-lead: ratify D-1, D-2, D-3, D-7 as **approved change requests** (they are coherent modern hardening and the data model can absorb them additively), and ratify D-4, D-5, D-6 **explicitly** as net-new features (they change *process*, not just design — outside "exact process"). I provide DDL for the hardened form so the schema is ready the moment they're signed off, gated as "IF RATIFIED".

---

## 2. Migration file breakdown (V15–V20)

One concern per file; all additive (nothing in V1–V14 is touched). Suffix names follow the established `Vn__<module>_<topic>.sql` style.

| File | Purpose | Gate |
|---|---|---|
| **V15__billing_core.sql** | `patient_bills`, `patient_invoices`, `patient_invoice_details`, `patient_payments`, `patient_payment_details` — the legacy-faithful core (always built) | core |
| **V16__billing_credit_notes.sql** | `patient_credit_notes` (+ PCN number col; reuses `seq_pcn_no`) | core |
| **V17__billing_collections.sql** | `collections` (per-bill cash-receipt ledger; the legacy reconciliation source) | core |
| **V18__billing_settlement_flag.sql** | `settled`/`settled_at` columns on `patient_invoices` + `patient_bills` | IF D-6 ratified |
| **V19__billing_cashier_shifts.sql** | `cashier_shifts` (OPEN/CLOSED + per-mode snapshot columns) | IF D-4 ratified |
| **V20__insurance_claims.sql** | `insurance_claims` + `insurance_claim_lines` (SUBMITTED/SETTLED/REJECTED) | IF D-5 ratified |

Note V18–V20 are **separate files** precisely because they are the contested net-new features: if a CR is rejected, that file is simply not added, and the core (V15–V17) still reproduces legacy faithfully. No SEQUENCE other than `seq_pcn_no` is needed — payment/invoice/collection numbers derive from uid or id; only PCN has a formatted document number. **Claim numbering (if D-5 ratified) needs a new `seq_claim_no`** — added inside V20.

---

## 3. DDL sketches

### V15 — billing core

```sql
-- =====================================================================================
-- V15 — Billing core (Increment 04). LEGACY-FAITHFUL baseline.
-- Legacy: PatientBill/PatientInvoice/PatientInvoiceDetail/PatientPayment/PatientPaymentDetail.
-- Patient is inc-03 (NOT YET BUILT) -> patient_uid is a LOOSE VARCHAR(26) ref, NO FK.
-- plan_uid loose ref to insurance_plans.uid (cross-module, NO FK).
-- Money NUMERIC(19,2); qty NUMERIC(19,6).
-- =====================================================================================

-- ---- patient_bills : the atomic charge line (Ext 1 §1, Ext 2 §1) -------------------
CREATE TABLE patient_bills (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    uid               VARCHAR(26)   NOT NULL,
    patient_uid       VARCHAR(26)   NOT NULL,                 -- LOOSE ref (Patient=inc-03), NO FK
    bill_item         VARCHAR(60)   NOT NULL DEFAULT 'NA',    -- free-text kind label ("Registration","Bed",...)
    description       VARCHAR(400)  NOT NULL,                 -- legacy @NotBlank
    kind              VARCHAR(20)   NOT NULL,                 -- normalized ServiceKind (NEW: replaces free-text inference)
    qty               NUMERIC(19,6) NOT NULL DEFAULT 1,       -- legacy double qty (medicine qty multiplier)
    amount            NUMERIC(19,2) NOT NULL,
    paid              NUMERIC(19,2) NOT NULL DEFAULT 0,
    balance           NUMERIC(19,2) NOT NULL,
    status            VARCHAR(12)   NOT NULL,                 -- UNPAID/VERIFIED/COVERED/PAID/NONE/CANCELED
    payment_type      VARCHAR(12)   NOT NULL DEFAULT 'CASH',  -- CASH/INSURANCE (see D-7)
    membership_no     VARCHAR(60),
    plan_uid          VARCHAR(26),                            -- LOOSE ref to insurance_plans.uid, NO FK
    principal_bill_id      BIGINT,                            -- ward top-up self-link (covered parent)
    supplementary_bill_id  BIGINT,                            -- ward top-up self-link (top-up child)
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(80),
    updated_by        VARCHAR(80),
    version           BIGINT        NOT NULL,
    business_day_id   BIGINT,                                 -- legacy createdOn = day id
    CONSTRAINT pk_patient_bills              PRIMARY KEY (id),
    CONSTRAINT uq_patient_bills_uid          UNIQUE (uid),
    -- legacy status vocabulary, exact (Ext 2 §4, Ext 4 §7). 'CANCELED' = legacy single-L spelling.
    CONSTRAINT ck_patient_bills_status       CHECK (status IN
        ('UNPAID','VERIFIED','COVERED','PAID','NONE','CANCELED')),
    -- D-7: legacy live modes only. Widen to add DEBIT_CARD/CREDIT_CARD/MOBILE IF ratified.
    CONSTRAINT ck_patient_bills_payment_type CHECK (payment_type IN ('CASH','INSURANCE')),
    CONSTRAINT ck_patient_bills_kind         CHECK (kind IN
        ('REGISTRATION','CONSULTATION','LAB_TEST','MEDICINE','PROCEDURE','RADIOLOGY','WARD')),
    -- Money invariants (legacy keeps paid+balance == amount on every non-cancel transition).
    CONSTRAINT ck_patient_bills_amount_nonneg  CHECK (amount  >= 0),
    CONSTRAINT ck_patient_bills_paid_nonneg    CHECK (paid    >= 0),
    -- balance MAY be negative only conceptually never in legacy; guard non-negative for cash gate safety.
    CONSTRAINT ck_patient_bills_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT ck_patient_bills_qty_pos        CHECK (qty > 0),
    -- ward top-up self FKs (intra-table, so real FK is allowed)
    CONSTRAINT fk_patient_bills_principal
        FOREIGN KEY (principal_bill_id)     REFERENCES patient_bills (id),
    CONSTRAINT fk_patient_bills_supplementary
        FOREIGN KEY (supplementary_bill_id) REFERENCES patient_bills (id)
);
-- High-frequency cashier queue: unpaid/verified bills for a patient (Ext 5 §B view endpoints).
CREATE INDEX idx_patient_bills_patient        ON patient_bills (patient_uid);
CREATE INDEX idx_patient_bills_status         ON patient_bills (status);
-- Partial index: the cashier collectable-queue scan (status in collectable set).
CREATE INDEX idx_patient_bills_collectable
    ON patient_bills (patient_uid) WHERE status IN ('UNPAID','VERIFIED');
-- Reconciliation by day (collections cash-up joins).
CREATE INDEX idx_patient_bills_business_day   ON patient_bills (business_day_id);

-- ---- patient_invoices : insurance-claim accumulator header (Ext 1 §1) --------------
CREATE TABLE patient_invoices (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    uid               VARCHAR(26)   NOT NULL,
    no                VARCHAR(40)   NOT NULL,                 -- legacy = id.toString(); modern = uid-anchored
    patient_uid       VARCHAR(26)   NOT NULL,                 -- LOOSE, NO FK
    plan_uid          VARCHAR(26),                            -- NULL = cash invoice; LOOSE, NO FK
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    amount_paid       NUMERIC(19,2) NOT NULL DEFAULT 0,       -- legacy running sum; never decremented (Ext 4 §6)
    -- D-10: amount_allocated / amount_unallocated DROPPED (dead fields, never written).
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(80),
    updated_by        VARCHAR(80),
    version           BIGINT        NOT NULL,
    business_day_id   BIGINT,
    CONSTRAINT pk_patient_invoices         PRIMARY KEY (id),
    CONSTRAINT uq_patient_invoices_uid     UNIQUE (uid),
    CONSTRAINT uq_patient_invoices_no      UNIQUE (no),       -- legacy @Column(unique=true)
    -- D-1: legacy value set. IF ratified, replace with PENDING/PARTIALLY_PAID/PAID/CANCELLED.
    CONSTRAINT ck_patient_invoices_status  CHECK (status IN ('PENDING','APPROVED')),
    CONSTRAINT ck_patient_invoices_amount_paid_nonneg CHECK (amount_paid >= 0)
);
-- Pending-invoice worklist by plan (insurance) and null-plan (cash) — Ext 5 §A.4.
CREATE INDEX idx_patient_invoices_patient ON patient_invoices (patient_uid);
CREATE INDEX idx_patient_invoices_plan_status ON patient_invoices (plan_uid, status);
-- Cash pending worklist (plan_uid IS NULL) — partial index matches get_patient_direct_pending_invoices.
CREATE INDEX idx_patient_invoices_cash_pending
    ON patient_invoices (patient_uid) WHERE plan_uid IS NULL AND status = 'PENDING';

-- ---- patient_invoice_details : one claim line per covered bill (Ext 1 §1) ----------
CREATE TABLE patient_invoice_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)   NOT NULL,
    patient_invoice_id  BIGINT        NOT NULL,
    patient_bill_id     BIGINT        NOT NULL,               -- de-facto nullable=false (Ext 1 §1 inconsistency resolved)
    description         VARCHAR(400)  NOT NULL,
    qty                 NUMERIC(19,6) NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    -- D-9: legacy detail.status only ∈ {PAID, unset}. coverage_status (UNPAID/COVERED/VERIFIED)
    -- is the spec's name; legacy carries that on patient_bills.status. We keep BOTH:
    --   status          = legacy line settlement (PAID / NULL)
    --   coverage_status = denormalized snapshot of the bill's coverage at attach time (spec §12)
    status              VARCHAR(12),
    coverage_status     VARCHAR(12)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT        NOT NULL,
    CONSTRAINT pk_patient_invoice_details     PRIMARY KEY (id),
    CONSTRAINT uq_patient_invoice_details_uid UNIQUE (uid),
    -- one detail per bill (legacy @OneToOne patientBill) — enforce at DB.
    CONSTRAINT uq_patient_invoice_details_bill UNIQUE (patient_bill_id),
    CONSTRAINT ck_patient_invoice_details_status
        CHECK (status IS NULL OR status IN ('PAID')),
    CONSTRAINT ck_patient_invoice_details_coverage
        CHECK (coverage_status IN ('UNPAID','COVERED','VERIFIED')),
    CONSTRAINT ck_patient_invoice_details_amount_nonneg CHECK (amount >= 0),
    -- legacy orphanRemoval=true on invoice->details: cascade delete child when header deleted.
    CONSTRAINT fk_patient_invoice_details_invoice
        FOREIGN KEY (patient_invoice_id) REFERENCES patient_invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_patient_invoice_details_bill
        FOREIGN KEY (patient_bill_id)    REFERENCES patient_bills (id)
);
CREATE INDEX idx_patient_invoice_details_invoice ON patient_invoice_details (patient_invoice_id);

-- ---- patient_payments : payment receipt header (Ext 1 §1) --------------------------
CREATE TABLE patient_payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    uid               VARCHAR(26)   NOT NULL,                 -- doubles as receipt reference (spec §54)
    patient_uid       VARCHAR(26),                            -- NEW: legacy has none; nullable for fidelity
    amount            NUMERIC(19,2) NOT NULL,                 -- legacy = tendered total
    payment_type      VARCHAR(12)   NOT NULL DEFAULT 'CASH',  -- D-7
    status            VARCHAR(12)   NOT NULL,                 -- RECEIVED
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(80),
    updated_by        VARCHAR(80),
    version           BIGINT        NOT NULL,
    business_day_id   BIGINT,
    cashier_shift_id  BIGINT,                                 -- nullable now; FK added in V19 IF ratified
    CONSTRAINT pk_patient_payments         PRIMARY KEY (id),
    CONSTRAINT uq_patient_payments_uid     UNIQUE (uid),
    CONSTRAINT ck_patient_payments_status  CHECK (status IN ('RECEIVED')),
    CONSTRAINT ck_patient_payments_payment_type CHECK (payment_type IN ('CASH','INSURANCE')),
    CONSTRAINT ck_patient_payments_amount_nonneg CHECK (amount >= 0)
);
CREATE INDEX idx_patient_payments_business_day ON patient_payments (business_day_id);

-- ---- patient_payment_details : bill<->payment link (Ext 1 §1) ----------------------
CREATE TABLE patient_payment_details (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                 VARCHAR(26)   NOT NULL,
    patient_payment_id  BIGINT        NOT NULL,
    patient_bill_id     BIGINT        NOT NULL,               -- legacy @OneToOne
    description         VARCHAR(400),
    -- D-2: legacy has NO amount column. 'amount' added ONLY IF signed-refund CR is ratified.
    -- LEGACY-FAITHFUL: no amount column; refund = status flip to REFUNDED (below).
    amount              NUMERIC(19,2),                        -- NULLABLE; NULL = legacy implicit (=bill.amount)
    status              VARCHAR(12)   NOT NULL,               -- RECEIVED / REFUNDED
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(80),
    updated_by          VARCHAR(80),
    version             BIGINT        NOT NULL,
    CONSTRAINT pk_patient_payment_details      PRIMARY KEY (id),
    CONSTRAINT uq_patient_payment_details_uid  UNIQUE (uid),
    CONSTRAINT ck_patient_payment_details_status
        CHECK (status IN ('RECEIVED','REFUNDED')),
    CONSTRAINT fk_patient_payment_details_payment
        FOREIGN KEY (patient_payment_id) REFERENCES patient_payments (id) ON DELETE CASCADE,
    CONSTRAINT fk_patient_payment_details_bill
        FOREIGN KEY (patient_bill_id)    REFERENCES patient_bills (id)
);
CREATE INDEX idx_patient_payment_details_payment ON patient_payment_details (patient_payment_id);
CREATE INDEX idx_patient_payment_details_bill    ON patient_payment_details (patient_bill_id);
```

**Note on D-2 (signed amount):** I included `amount NUMERIC(19,2) NULLABLE` so the table is ready for the signed-refund CR (a negative-amount detail row reverses a prior receipt), while remaining legacy-faithful when NULL (implicit = `bill.amount`). If the CR is rejected, drop the column in a follow-up. I did NOT add a `CHECK (amount >= 0)` precisely because the spec's refund mechanism requires negatives — flag for backend to enforce the recompute invariant in code, not DB.

### V16 — credit notes

```sql
-- =====================================================================================
-- V16 — Patient credit notes (Ext 4 §1, §4). PCN{yyyyMMdd}-{seq} via seq_pcn_no (V13).
-- Legacy PCN is standalone PENDING-only, NO FK to bill/invoice (Ext 4 §1).
-- Concurrency fix already ratified (CR-09): seq_pcn_no replaces MAX(id)+1.
-- =====================================================================================
CREATE TABLE patient_credit_notes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    uid               VARCHAR(26)   NOT NULL,
    no                VARCHAR(40)   NOT NULL,                 -- PCN{yyyyMMdd}-{nextval(seq_pcn_no)}
    patient_uid       VARCHAR(26),                            -- legacy nullable=true (Ext 4 §1); preserve
    amount            NUMERIC(19,2) NOT NULL DEFAULT 0,       -- always full bill amount in legacy
    reference         VARCHAR(200)  NOT NULL DEFAULT '',      -- "Canceled consultation" etc.
    status            VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    -- LEGACY: no link to bill/invoice. patient_bill_uid added as a LOOSE traceability ref (NEW, nullable)
    -- only if backend wants reconciliation; NOT a legacy field. Keep nullable, no FK.
    patient_bill_uid  VARCHAR(26),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(80),
    updated_by        VARCHAR(80),
    version           BIGINT        NOT NULL,
    business_day_id   BIGINT,
    CONSTRAINT pk_patient_credit_notes        PRIMARY KEY (id),
    CONSTRAINT uq_patient_credit_notes_uid    UNIQUE (uid),
    CONSTRAINT uq_patient_credit_notes_no     UNIQUE (no),   -- legacy @Column(unique=true)
    -- legacy only ever writes PENDING; widen IF a PCN approval lifecycle CR is approved.
    CONSTRAINT ck_patient_credit_notes_status CHECK (status IN ('PENDING')),
    CONSTRAINT ck_patient_credit_notes_amount_nonneg CHECK (amount >= 0)
);
CREATE INDEX idx_patient_credit_notes_patient ON patient_credit_notes (patient_uid);
CREATE INDEX idx_patient_credit_notes_business_day ON patient_credit_notes (business_day_id);
```

### V17 — collections (the REAL legacy reconciliation source)

```sql
-- =====================================================================================
-- V17 — Collections (Ext 3 §2). Per-bill cash-receipt ledger, write-once, no status.
-- This is the actual legacy cash-up source; EOD report = SUM(amount) GROUP BY (item_name,
-- payment_channel) over date range (Ext 3 §4). created_by = USER id (attribution is by user,
-- NOT by cashiers table) — Ext 3 §1.
-- =====================================================================================
CREATE TABLE collections (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                  VARCHAR(26)   NOT NULL,
    patient_uid          VARCHAR(26)   NOT NULL,             -- LOOSE, NO FK
    patient_bill_id      BIGINT,                             -- legacy optional/nullable
    amount               NUMERIC(19,2) NOT NULL,             -- legacy double -> NUMERIC
    item_name            VARCHAR(60)   NOT NULL DEFAULT 'NA',-- revenue category (bill_item)
    payment_channel      VARCHAR(20)   NOT NULL DEFAULT 'Cash', -- hardcoded 'Cash' in legacy (D-7)
    payment_reference_no VARCHAR(60)   NOT NULL DEFAULT 'NA',
    created_at           TIMESTAMPTZ   NOT NULL,             -- cash-up groups by this range
    updated_at           TIMESTAMPTZ,
    created_by           VARCHAR(80),                        -- the USER who collected (attribution key)
    updated_by           VARCHAR(80),
    version              BIGINT        NOT NULL,
    business_day_id      BIGINT,                             -- legacy createdOn = day id
    cashier_shift_id     BIGINT,                             -- nullable; FK in V19 IF ratified
    CONSTRAINT pk_collections          PRIMARY KEY (id),
    CONSTRAINT uq_collections_uid      UNIQUE (uid),
    CONSTRAINT ck_collections_amount_nonneg CHECK (amount >= 0),
    CONSTRAINT fk_collections_bill
        FOREIGN KEY (patient_bill_id) REFERENCES patient_bills (id)
);
-- The cash-up report: SUM/GROUP BY over created_at range, by user (per-cashier) + item/channel.
CREATE INDEX idx_collections_created_at ON collections (created_at);
CREATE INDEX idx_collections_created_by_created_at ON collections (created_by, created_at);
CREATE INDEX idx_collections_item_channel ON collections (item_name, payment_channel);
CREATE INDEX idx_collections_business_day ON collections (business_day_id);
```

### V18 — settled flag (IF D-6 ratified — net-new hardening)

```sql
-- =====================================================================================
-- V18 — Pay-before-service 'settled' flag. NET-NEW (Ext 2 §5: no legacy settled field/gate).
-- ONLY APPLY IF engagement-lead ratifies the hard gate CR.
-- Flag lives on patient_invoices (invoice-level PAID transition) AND patient_bills
-- (line-level, since legacy bill is the real settlement unit). Downstream CLINICAL tables
-- (lab_orders, radiology_orders, ... — NOT YET BUILT, inc-05/06) will each carry their OWN
-- local 'settled' boolean written by SettlementDispatcher (billing->encounter direction,
-- ADR-0008 §6). Clinical reads only its local flag; never calls billing.api.
-- =====================================================================================
ALTER TABLE patient_invoices
    ADD COLUMN settled    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN settled_at TIMESTAMPTZ;
ALTER TABLE patient_invoices
    ADD CONSTRAINT ck_patient_invoices_settled_consistency
        CHECK ((settled = TRUE AND settled_at IS NOT NULL)
            OR (settled = FALSE AND settled_at IS NULL));

ALTER TABLE patient_bills
    ADD COLUMN settled    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN settled_at TIMESTAMPTZ;
ALTER TABLE patient_bills
    ADD CONSTRAINT ck_patient_bills_settled_consistency
        CHECK ((settled = TRUE AND settled_at IS NOT NULL)
            OR (settled = FALSE AND settled_at IS NULL));
-- Downstream accept() gate reads patient_bills.settled (line-level) via the local copy.
CREATE INDEX idx_patient_bills_settled ON patient_bills (settled) WHERE settled = FALSE;
```

### V19 — cashier shifts (IF D-4 ratified — net-new feature)

```sql
-- =====================================================================================
-- V19 — Cashier shifts. NET-NEW (Ext 3 §3: no shift exists in legacy; closest is global
-- business_days, system-wide, no snapshot). ONLY APPLY IF ratified as approved feature.
-- One shift per cashier per business day; per-mode collected snapshot at close (audit-immutable).
-- cashier_user_uid is the IAM user (attribution by user, per Ext 3 §1) — LOOSE ref to users.uid.
-- =====================================================================================
CREATE TABLE cashier_shifts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                   VARCHAR(26)   NOT NULL,
    cashier_user_uid      VARCHAR(26)   NOT NULL,            -- LOOSE ref to users.uid (iam), NO FK
    business_day_id       BIGINT        NOT NULL,            -- intra-DB, real FK to business_days
    status                VARCHAR(8)    NOT NULL DEFAULT 'OPEN',
    opened_at             TIMESTAMPTZ   NOT NULL,
    closed_at             TIMESTAMPTZ,
    -- per-mode collected snapshot (frozen at close; report projects, never re-aggregates).
    cash_collected        NUMERIC(19,2) NOT NULL DEFAULT 0,
    mobile_collected      NUMERIC(19,2) NOT NULL DEFAULT 0,
    card_collected        NUMERIC(19,2) NOT NULL DEFAULT 0,
    insurance_collected   NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_collected       NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(80),
    updated_by            VARCHAR(80),
    version               BIGINT        NOT NULL,
    CONSTRAINT pk_cashier_shifts         PRIMARY KEY (id),
    CONSTRAINT uq_cashier_shifts_uid     UNIQUE (uid),
    CONSTRAINT ck_cashier_shifts_status  CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_cashier_shifts_close_consistency
        CHECK ((status = 'CLOSED' AND closed_at IS NOT NULL)
            OR (status = 'OPEN'   AND closed_at IS NULL)),
    CONSTRAINT ck_cashier_shifts_collected_nonneg
        CHECK (cash_collected >= 0 AND mobile_collected >= 0 AND card_collected >= 0
           AND insurance_collected >= 0 AND total_collected >= 0),
    CONSTRAINT fk_cashier_shifts_business_day
        FOREIGN KEY (business_day_id) REFERENCES business_days (id)
);
-- Enforce "one OPEN shift per cashier per day" — partial unique index on OPEN rows.
CREATE UNIQUE INDEX uq_cashier_shifts_open_per_cashier_day
    ON cashier_shifts (cashier_user_uid, business_day_id) WHERE status = 'OPEN';
CREATE INDEX idx_cashier_shifts_cashier ON cashier_shifts (cashier_user_uid);

-- Now wire the deferred FKs from V15/V17 (added here only if shifts exist).
ALTER TABLE patient_payments
    ADD CONSTRAINT fk_patient_payments_shift
        FOREIGN KEY (cashier_shift_id) REFERENCES cashier_shifts (id);
ALTER TABLE collections
    ADD CONSTRAINT fk_collections_shift
        FOREIGN KEY (cashier_shift_id) REFERENCES cashier_shifts (id);
```

### V20 — insurance claims (IF D-5 ratified — net-new feature)

```sql
-- =====================================================================================
-- V20 — Insurance claims. NET-NEW (Ext 5 §A: NO claim entity in legacy; claim == PENDING
-- PatientInvoice per (patient, plan), no settlement state). ONLY APPLY IF ratified.
-- New sequence for claim numbering (legacy has none).
-- claim lines reference patient_invoice_details (intra-DB FK) — the covered lines aggregated.
-- =====================================================================================
CREATE SEQUENCE IF NOT EXISTS seq_claim_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

CREATE TABLE insurance_claims (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    uid               VARCHAR(26)   NOT NULL,
    no                VARCHAR(40)   NOT NULL,                 -- CLM{yyyyMMdd}-{nextval(seq_claim_no)}
    plan_uid          VARCHAR(26)   NOT NULL,                 -- LOOSE ref to insurance_plans.uid, NO FK
    patient_uid       VARCHAR(26)   NOT NULL,                 -- LOOSE, NO FK
    status            VARCHAR(12)   NOT NULL DEFAULT 'SUBMITTED',
    claimed_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    settled_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    submitted_at      TIMESTAMPTZ,
    settled_at        TIMESTAMPTZ,
    rejected_reason   VARCHAR(400),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(80),
    updated_by        VARCHAR(80),
    version           BIGINT        NOT NULL,
    CONSTRAINT pk_insurance_claims        PRIMARY KEY (id),
    CONSTRAINT uq_insurance_claims_uid    UNIQUE (uid),
    CONSTRAINT uq_insurance_claims_no     UNIQUE (no),
    CONSTRAINT ck_insurance_claims_status CHECK (status IN ('SUBMITTED','SETTLED','REJECTED')),
    CONSTRAINT ck_insurance_claims_amount_nonneg
        CHECK (claimed_amount >= 0 AND settled_amount >= 0)
);
CREATE INDEX idx_insurance_claims_plan_status ON insurance_claims (plan_uid, status);
CREATE INDEX idx_insurance_claims_patient     ON insurance_claims (patient_uid);

CREATE TABLE insurance_claim_lines (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY,
    uid                       VARCHAR(26)   NOT NULL,
    insurance_claim_id        BIGINT        NOT NULL,
    patient_invoice_detail_id BIGINT        NOT NULL,        -- the covered line aggregated
    amount                    NUMERIC(19,2) NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ,
    created_by                VARCHAR(80),
    updated_by                VARCHAR(80),
    version                   BIGINT        NOT NULL,
    CONSTRAINT pk_insurance_claim_lines     PRIMARY KEY (id),
    CONSTRAINT uq_insurance_claim_lines_uid UNIQUE (uid),
    CONSTRAINT uq_insurance_claim_lines_detail UNIQUE (patient_invoice_detail_id),
    CONSTRAINT ck_insurance_claim_lines_amount_nonneg CHECK (amount >= 0),
    CONSTRAINT fk_insurance_claim_lines_claim
        FOREIGN KEY (insurance_claim_id) REFERENCES insurance_claims (id) ON DELETE CASCADE,
    CONSTRAINT fk_insurance_claim_lines_detail
        FOREIGN KEY (patient_invoice_detail_id) REFERENCES patient_invoice_details (id)
);
CREATE INDEX idx_insurance_claim_lines_claim ON insurance_claim_lines (insurance_claim_id);
```

---

## 4. Cross-module loose-uid refs (NO FK — flagged exhaustively)

Every one of these is a `VARCHAR(26)` string with **no FK constraint** because the target lives in another module (or doesn't exist yet). Backend must validate existence via the owning module's lookup interface, not a DB join.

| Column | Table(s) | Target | Reason no FK |
|---|---|---|---|
| `patient_uid` | patient_bills, patient_invoices, patient_payments, patient_invoice (n/a), collections, patient_credit_notes, insurance_claims | `patients.uid` | **Patient = inc-03, NOT BUILT YET** — table does not exist at V15 |
| `plan_uid` | patient_bills, patient_invoices, insurance_claims | `insurance_plans.uid` (masterdata) | cross-module boundary; billing consumes masterdata::lookup |
| `service_uid` | (resolved upstream in service_prices; billing passes through to charge) | clinic/wardtype/labtesttype.uid | cross-module; resolved at price-lookup time, not stored as FK in billing |
| `cashier_user_uid` | cashier_shifts | `users.uid` (iam) | cross-module; attribution by user (Ext 3 §1), consume iam::lookup |
| `created_by` | all | `users.username` (iam) | already the established loose convention (VARCHAR(80), all V1+ tables) |
| `patient_bill_uid` | patient_credit_notes | local `patient_bills.uid` | intra-module but kept loose (NEW traceability field, nullable) |

Intra-module / intra-DB refs that **DO** get real FKs: `principal_bill_id`/`supplementary_bill_id` (self), `patient_invoice_id`→`patient_invoices`, `patient_bill_id`→`patient_bills`, `patient_payment_id`→`patient_payments`, `cashier_shift_id`→`cashier_shifts`, `business_day_id`→`business_days`, claim-line FKs.

---

## 5. SEQUENCE decision

- **REUSE `seq_pcn_no`** (V13) for credit notes — no new sequence.
- **No new sequence** for invoices, payments, collections (uid/id-anchored, matching legacy which had none).
- **`seq_claim_no` is the ONLY new sequence** — created inside V20, ONLY if D-5 (insurance_claims) is ratified. If D-5 is rejected, no new sequence is added.

---

## 6. 'settled' flag location (explicit answer)

- **Billing side:** `patient_invoices.settled` (invoice PAID transition) AND `patient_bills.settled` (line-level — the legacy bill is the true settlement unit). Both in **V18, IF ratified**.
- **Clinical side (NOT YET BUILT):** each downstream order table (`lab_orders`, `radiology_orders`, `procedure_orders`, `prescriptions`, `admission_beds`) will carry its **own local `settled` boolean**, written by `SettlementDispatcher` in the billing→encounter direction. Those columns are defined in inc-05/06 migrations, not here. Clinical modules read only their local flag and never call `billing.api` (ADR-0008 §6, enforced by ApplicationModules.verify()).
- **Legacy reality reminder:** there is NO settled flag and NO hard gate in legacy (Ext 2 §5). V18 is net-new hardening pending D-6 ratification.

---

## 7. Items to hand to other agents

- **engagement-lead:** ratify/reject D-1…D-8 (esp. D-4 cashier_shifts, D-5 insurance_claims, D-6 settled-gate as net-new *process* changes, not "exact process"). V18/V19/V20 do not ship until signed off.
- **backend-engineer:** enforce in code (DB can't): the `paid + balance = amount` invariant per non-cancel transition; the exact-tender equality (now `BigDecimal.compareTo` on scaled NUMERIC, HALF_UP — replaces legacy `double ==`, Ext 1 §4); the D-2 signed-refund `totalPaid` recompute invariant; the D-11 invoice-delete `j=j++` bug decision (reproduce vs fix — needs CR).
- **security-architect:** I have NOT authored audit DDL for these PHI-bearing billing tables (patient_bills, patient_invoices, payments, collections, credit_notes all carry patient_uid + financial PHI). Per my mandate I am **blocked on your PHI field classification and audit coverage requirements** before writing the per-entity audit tables. The existing `audit_logs` (V1) is the generic channel; per-entity old/new JSONB audit tables await your spec.
- **data-migration-engineer:** reconciliation contract for these tables — row counts per table; financial totals SUM(patient_invoice_details.amount), SUM(collections.amount), SUM(patient_payments.amount); referential spot-check that every patient_invoice_detail.patient_bill_id resolves and every COVERED bill has a detail. Note legacy `amount_allocated/amount_unallocated` are NOT migrated (D-10 dead fields); confirm no report reads them.

**Files I read (all absolute):** `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V1__schema.sql`, `V4__schema_iam_delta.sql`, `V8__masterdata_clinical.sql`, `V9__masterdata_insurance.sql`, `V13__masterdata_document_sequences.sql`; spec `D:\My_Works\HMS\HMSCLEAN2\docs\delivery\increments\04-billing-cashiering-core.md`. **Proposed new files (not yet written):** `D:\My_Works\HMS\HMSCLEAN2\backend\src\main\resources\db\migration\V15__billing_core.sql`, `V16__billing_credit_notes.sql`, `V17__billing_collections.sql`, `V18__billing_settlement_flag.sql` (IF D-6), `V19__billing_cashier_shifts.sql` (IF D-4), `V20__insurance_claims.sql` (IF D-5).