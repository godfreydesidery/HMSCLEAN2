# ADR-0009: Monetary, Numeric and Document-Numbering Policy

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context

Every monetary, quantity, stock, and balance field in the legacy Zana HMIS is Java primitive `double` — 96 `private double` declarations across 52 entity files; a codebase-wide grep for `BigDecimal` returns zero matches. Billing (`PatientBill.qty`, `price * prescription.getQty()` at PatientServiceImpl:1534), purchasing (`receivedQty * detail.getPrice()` at GoodsReceivedNoteServiceImpl:174), and stock transfers (`transferedStoreSKUQty * imc.getCoefficient()` at StoreToPharmacyTOServiceImpl:424) all use unrounded binary floating-point. `ItemMedicineCoefficient.coefficient` is a `double`; coefficients such as 1/3 produce repeating decimals that IEEE-754 double preserves to ~15 significant digits. Legacy has no explicit rounding step — money is stored as the raw double product, never rounded before persistence.

Document numbers are a business-process value. Format: `Formater.formatWithCurrentDate(prefix, id)` → `PREFIX{yyyyMMdd}-{id}` where `id = repository.getLastId() + 1` (non-atomic JPQL `SELECT MAX(id)+1`). Confirmed in every document-bearing service. Both `StoreToPharmacyTOServiceImpl:438` and `PharmacyToPharmacyTOServiceImpl:428` call `Formater.formatWithCurrentDate("SPT", id.toString())` — a confirmed prefix collision where the same document number string (e.g. `SPT20260602-5`) could refer to either a store-to-pharmacy transfer order or a pharmacy-to-pharmacy transfer order.

The legacy embeds the table's identity PK in the document number. The GRN pattern (confirmed at GoodsReceivedNoteServiceImpl:84-88,210) saves the row first with `no="NA"`, then calls `getLastId()+1` and calls `save` again. The embedded number is therefore derived from `MAX(id)+1`, not the PK of the just-inserted row. This is a subtle distinction: under the non-atomic `MAX+1` approach the embedded number correlates with but is not always equal to the row's PK.

Timestamps: `DayServiceImpl.getTimeStamp()` returns `LocalDateTime.now().plusHours(3)` (line 87) — a hardcoded EAT (UTC+3) offset applied on the service write path. At least two entities initialise `private LocalDateTime createdAt = LocalDateTime.now()` at field-default time with no +3h adjustment (`PatientBill.java:84`, `ItemMedicineCoefficient.java:59`). The `Day` entity (`days` table) is a managed business-day record with `bussinessDate` (LocalDate), `startedAt`, `endedAt`, and `status` ("STARTED"/"ENDED"). Every transactional entity carries a `createdOn` Long FK to `days.id` in addition to a `createdAt` LocalDateTime. `DayService.endDay()` advances the business date and opens a new Day record. This is a core business-process workflow, not merely an audit annotation.

**This is a fresh greenfield build. There is no production-data migration, no MAX(legacy id) sequence seeding, and no ETL timezone normalization.** All of the cross-ADR precision conflicts (ADR-0011 type table says NUMERIC(19,4) for quantity; ADR-0014 section 7 says `precision=15,scale=6`) are resolved here as the single source of truth.

## Decision

**1. BigDecimal for all money and quantity fields — pre-approved.**
Replace every `double` monetary, quantity, stock, balance, price, and coefficient field with `java.math.BigDecimal`. This is a pre-ratified change; no additional change-request is required.

**2. Money value object: single canonical scale.**
Define an immutable `Money` value object: `BigDecimal amount` + `Currency currency` (default TZS). Persist via JPA `@Embeddable`. Scale at persistence: `NUMERIC(19,2)`, `RoundingMode.HALF_UP`. TZS has no sub-shilling denomination; 2 dp is sufficient for all price, billing, and insurance fields. Display scale is always 2. MapStruct maps `Money` to/from DTO `{ amount: BigDecimal, currency: String }`. This scale is the single authoritative definition; ADR-0003 and ADR-0014 must defer to this ADR for money column precision.

**3. Quantity and coefficient fields: single canonical scale.**
`NUMERIC(19,6)` for all quantity and coefficient columns. Six decimal places accommodates 1/3-derived coefficients (0.333333) and other repeating-decimal conversion factors without truncation artefact. This supersedes NUMERIC(19,4) in any prior ADR draft and the `precision=15,scale=6` in ADR-0014 section 7. ADR-0011 and ADR-0014 must be updated to reference this ADR as the single source. No intermediate rounding during coefficient multiplication: `transferedStoreSKUQty.multiply(coefficient)` carries full `BigDecimal` precision through to the final stored quantity.

**4. Rounding parity definition.**
Legacy has no explicit rounding step. The parity contract for behavioural golden-master tests is: `round(BigDecimal.valueOf(legacyDouble), 2).equals(round(newBigDecimalTotal, 2))`. This is not matching a legacy rounding step — it asserts that converting the same inputs through BigDecimal arithmetic and rounding once to 2 dp at persistence produces the same two-decimal result.

**5. Document-number formats reproduced exactly; numbering made concurrency-safe.**
Every legacy `PREFIX{yyyyMMdd}-{id}` format is reproduced verbatim. The `MAX(id)+1` race is replaced with one dedicated PostgreSQL `SEQUENCE` per document type (`seq_grn_no`, `seq_lpo_no`, `seq_pcn_no`, `seq_prl_no`, `seq_pprn_no`, `seq_psr_no`, `seq_ppr_no`, `seq_sto_no`, `seq_ptp_no`, `seq_pgrn_no`). Because this is a fresh build starting empty there is no legacy MAX(id) to seed from — all sequences start at 1.

The generation pattern is: (a) obtain the next sequence value; (b) insert the row with `no = PREFIX + LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).format("yyyyMMdd") + "-" + seqNextVal`; (c) the inserted row carries its `no` value from the first insert — no double-save. This is a clean break from the legacy save-then-assign double-save pattern; the fresh build does not replicate that implementation defect.

**6. SPT prefix collision: single canonical resolution.**
Legacy root cause: `StoreToPharmacyTOServiceImpl:438` and `PharmacyToPharmacyTOServiceImpl:428` both call `formatWithCurrentDate("SPT", id)`. The fresh build assigns:

| Document | Legacy prefix | Fresh prefix |
|---|---|---|
| StoreToPharmacy Transfer Order | SPT (shared) | **SPTO** |
| PharmacyToPharmacy Transfer Order | SPT (shared) | **PPTO** |

**This ADR is the single source of truth for both prefixes.** ADR-0011 must defer to these assignments and must not define its own scheme. Product-owner sign-off on SPTO and PPTO is a release gate before the prefix table is finalised in the Flyway seed migration.

**7. Business-date and timestamp policy.**
The legacy `DayService` business-date workflow is a confirmed business-process artifact. The fresh build reproduces the process semantics: a `BusinessDay` aggregate (table: `business_days`) with `businessDate` (LocalDate), `openedAt`, `closedAt`, and `status` (OPEN/CLOSED). Every transactional entity carries a `businessDayId` (UUID FK to `business_days.uid`) stamped at write time by `BusinessDayService.currentId()`. `closeDay()` / `openDay()` remain explicit operator actions, not automated rollover. This preserves the legacy day-open/close gate semantics.

Wall-clock timestamps: persist as `timestamptz` in PostgreSQL; Java type `Instant`/`OffsetDateTime`; JVM started with `-Duser.timezone=UTC`. The legacy `getTimeStamp()` hardcodes `LocalDateTime.now().plusHours(3)` because the JVM runs on an EAT server with no explicit timezone set, so `now()` returns UTC and the +3h corrects to local wall time. The fresh build instead uses `ZonedDateTime.now(ZoneId.of("Africa/Dar_es_Salaam"))` where a human-readable local timestamp is required and stores `Instant` for ordering and audit. There is no ETL timezone normalization because there is no data migration.

The `Formater.formatWithCurrentDate` uses bare `LocalDateTime.now()` for the date in document numbers. The fresh build uses `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))` so that document numbers reflect the hospital's local calendar date regardless of JVM timezone configuration.

## Considered alternatives

| Option | Verdict |
|---|---|
| NUMERIC(19,4) for all fields | Rejected — silently truncates 1/3-derived coefficients; compounds across stock-card running balances |
| NUMERIC(19,4) money, NUMERIC(19,6) qty | Rejected — two scales without benefit; money at 4 dp is unnecessary for TZS |
| Integer minor units (long, shillings only) | Rejected — TZS has no minor unit but pricing matrices sometimes express fractional TZS; BigDecimal/2dp is cleaner |
| Keep double | Rejected — perpetuates unreconcilable rounding artefacts |
| Separate sequence seeded to MAX(legacy id)+1 | Not applicable — fresh build; sequences start at 1 |
| App-level counter table with row lock | Rejected — reintroduces lock contention; native sequences are simpler and correct |
| Keep SPT for StoreToPharmacy, assign new prefix only to P2P | Rejected — both sides need disambiguation; the collision is in the legacy and both prefixes need to change |
| Reproduce save-then-assign double-save pattern | Rejected — a concurrency defect, not a process requirement; the format is reproduced, not the implementation |
| Keep Day FK on every entity as Long | Rejected — replaced by uuid FK to `business_days.uid`; no data migration means no compatibility constraint |

## Consequences

**Positive:** Stock-card running balances are faithful to full-precision coefficient inputs; no silent truncation; single money scale eliminates cross-ADR drift; concurrency-safe document numbering from day one; SPTO/PPTO unambiguously identify document kind in logs, reports, and API responses; business-date workflow preserved with a clean aggregate model; EAT document-date semantics preserved without a hardcoded +3h offset in every service.

**Negative / risks:**
- Behavioural golden-master tests must use the round-to-2dp comparison, not bit-exact double equality, for any total that was a raw double product in legacy.
- Product-owner must sign off on SPTO and PPTO before the prefix Flyway seed migration is committed; any external integration (e.g., reports, printed documents, external audit exports) that hard-codes "SPT" must be updated.
- Coefficient tests must cover 1/3, 1/7, 5/6 and other repeating-decimal cases to confirm NUMERIC(19,6) storage does not introduce a truncation artefact in the stock ledger.
- Closing `BusinessDay` is a hard gate on certain writes; the operational runbook must document the end-of-day procedure.

## Exact-process impact

**Preserved:** every document-number format string verbatim; the `PREFIX{yyyyMMdd}-{id}` pattern; the SPT conflict resolved to unambiguous SPTO/PPTO; per-line amount calculation semantics (`price × qty`, `receivedQty × price`); the business-day open/close workflow as an operator-driven gate; EAT (Africa/Dar_es_Salaam) as the local calendar for document dates and business-date display.

**Not reproduced (implementation defects, not process):** the `MAX(id)+1` race; the double-save save-then-assign pattern; the hardcoded `+3h` offset in `getTimeStamp()`; the field-default `LocalDateTime.now()` inconsistency on `PatientBill` and related entities.

**Behavioural tests must cover:** (a) concurrent document creation for each document type produces unique, gap-free `no` values; (b) coefficient multiplication for 1/3-based conversions matches `round(legacy_double_result, 2)` at 2 dp; (c) `BusinessDay.closeDay()` blocks new transactional writes scoped to the closed day; (d) document date embedded in `no` reflects EAT calendar date, not UTC.

## Implementation notes

**Types:**
- `Money`: immutable `@Embeddable`; fields `amount NUMERIC(19,2)`, `currency VARCHAR(3)`. `RoundingMode.HALF_UP` applied at the service layer before passing to the repository. MapStruct: `MoneyMapper` handles `Money` ↔ `MoneyDto { BigDecimal amount, String currency }`.
- Quantity / coefficient: `BigDecimal`, `@Column(precision=19, scale=6)`. No intermediate rounding in multiplication chains.
- Money columns: `@Column(precision=19, scale=2)`.

**Document sequences (Flyway V_sequence migration):**
```sql
CREATE SEQUENCE seq_grn_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_lpo_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_pcn_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_prl_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_pprn_no START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_psr_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_ppr_no  START 1 INCREMENT 1 NO CYCLE;
CREATE SEQUENCE seq_sto_no  START 1 INCREMENT 1 NO CYCLE;  -- StoreToPharmacy TO → SPTO
CREATE SEQUENCE seq_ptp_no  START 1 INCREMENT 1 NO CYCLE;  -- PharmacyToPharmacy TO → PPTO
CREATE SEQUENCE seq_pgrn_no START 1 INCREMENT 1 NO CYCLE;
```

**`DocumentNumberService`:**
```java
public String next(DocumentType type) {
    long seq = entityManager.createNativeQuery(
        "SELECT nextval('" + type.sequenceName() + "')")
        .getSingleResult();
    String date = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))
        .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    return type.prefix() + date + "-" + seq;
}
```
`DocumentType` enum carries `prefix` and `sequenceName`. Patient MR number (`MRNO/{year}/{rawId}`) uses the same sequence mechanism with `seq_mrno` and a year-keyed formatter.

**Prefix table (complete, supersedes all other ADRs):**

| Document type | Fresh prefix | Sequence |
|---|---|---|
| Goods Received Note | GRN | seq_grn_no |
| Local Purchase Order | LPO | seq_lpo_no |
| Patient Credit Note | PCN | seq_pcn_no |
| Payroll | PRL | seq_prl_no |
| Pharmacy-to-Pharmacy RN | PPRN | seq_pprn_no |
| Pharmacy-to-Store RO | PSR | seq_psr_no |
| Pharmacy-to-Pharmacy RO | PPR | seq_ppr_no |
| StoreToPharmacy TO | **SPTO** | seq_sto_no |
| PharmacyToPharmacy TO | **PPTO** | seq_ptp_no |
| Store-to-Pharmacy RN | PGRN | seq_pgrn_no |
| User code | USR-{000000} | seq_usr_no (formatSix, no date) |
| Patient MR | MRNO/{year}/{seq} | seq_mrno |

**BusinessDay aggregate:**
```java
@Entity @Table(name = "business_days")
public class BusinessDay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id; // internal, hidden (BIGINT)
    @Column(unique=true, nullable=false, length=26) String uid; // ULID (CHAR(26)), public
    LocalDate businessDate;
    Instant openedAt;
    Instant closedAt;
    @Enumerated(STRING) BusinessDayStatus status; // OPEN, CLOSED
}
```
`BusinessDayService.currentUid()` returns the uid of the single OPEN day; throws `NoDayOpenException` (→ RFC 7807 `ProblemDetail`, `type = "urn:hmis:error:no-day-open"`) if none. Every transactional write stamps `businessDayId = BusinessDayService.currentUid()`.

**Cross-ADR ownership:** ADR-0003 owns identifier strategy (ULID, `CHAR(26)`); ADR-0011 owns module boundaries and the `settled`-flag pattern; ADR-0014 owns MapStruct mapping. Where those ADRs mention column precision for money or quantity, they must cite this ADR (0009) as the authoritative source and not repeat the numbers.
