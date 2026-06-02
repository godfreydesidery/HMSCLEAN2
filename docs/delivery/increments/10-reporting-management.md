# Increment 10 — Reporting & Management

## Goal

Deliver every one of the 29 legacy reports as typed, JOOQ-backed endpoints in the `reporting` bounded context — row-for-row, cent-for-cent parity against the legacy system proven by a golden-master test suite — plus an operational dashboard surfacing live census, bed occupancy, today's revenue by source, insurance-claims status, and stock-outs; all served through a polished Angular management screen.

## Scope

**Bounded context:** `reporting` (read-only module; no aggregate ownership; queries only via JOOQ against the live PostgreSQL 16 schema). A thin `management-dashboard` module in Angular consumes the same endpoints.

**Key aggregates queried (owned by other increments):** `PatientInvoice`, `PatientPayment`, `ClinicalOrder`, `Prescription`, `PharmacySaleOrder`, `Admission`, `Consultation`, `GoodsReceipt` (GRN), `LocalPurchaseOrder`, `StoreStockBatch`, `StockBatch`, `CashierShift`, `InsuranceClaim`, `BusinessDay`, `Employee` / `ProviderProfile`.

**REST endpoints (all `POST` with typed `ReportFilterRequest` body — date range, optional scoping UIDs):**

| Report | Endpoint |
|---|---|
| Revenue by source (registration, consultation, pharmacy, lab, radiology, procedure, ward) | `POST /reports/revenue-by-source` |
| Collections report (cash collected per cashier, per payment method, per shift) | `POST /reports/collections` |
| Debt tracker (outstanding balances by patient, by invoice age, by payer) | `POST /reports/debt-tracker` |
| Daily production summary (encounters, orders, admissions by day) | `POST /reports/daily-production` |
| Daily sales summary (pharmacy OTC + prescription sales) | `POST /reports/daily-sales` |
| Daily purchase summary (GRN lines approved in period) | `POST /reports/daily-purchases` |
| Daily transaction summary (combined financial activity) | `POST /reports/daily-summary` |
| Fast-moving stock (top-N medicines by dispensing volume) | `POST /reports/fast-moving-stock` |
| Slow-moving stock (medicines below threshold movement over period) | `POST /reports/slow-moving-stock` |
| Inventory valuation (quantity on hand × unit cost per medicine, per location) | `POST /reports/inventory-valuation` |
| Clinician performance (consultations, orders, procedures per clinician) | `POST /reports/clinician-performance` |
| GRN report (approved GRNs with document number, supplier, lines) | `POST /reports/grn` |
| LPO report (purchase orders with status, supplier, lines) | `POST /reports/lpo` |
| Lab collection report (tests ordered vs collected, turnaround time) | `POST /reports/lab-collection` |
| IPD register (admissions with ward, diagnosis, length of stay) | `POST /reports/ipd-register` |
| Bed occupancy | `GET /reports/bed-occupancy` |
| Expiring batches | `GET /reports/expiring-batches?daysAhead=30` |
| Stock-out report | `GET /reports/stock-out?threshold=0` |
| + 11 additional legacy report endpoints (insurance claims ageing, pharmacy sales by item, ward revenue, referral list, deceased list, procedure register, radiology register, consumable usage, supplier price comparison, payroll summary, asset register) | `POST /reports/{report-slug}` |

**Operational dashboard endpoint:** `GET /reports/dashboard` — returns live KPIs: total admitted patients, beds free/occupied, today's revenue (sum of PAID invoice lines created since midnight EAT), open insurance-claim count + total value, stock-out count. Cached with `Cache-Control: max-age=60` at the HTTP layer; data assembled in a single JOOQ multi-CTE query.

**Process flows from PROCESS.md §13 fully implemented:** operational dashboard (§13 ¶1), financial reports (§13 ¶2), clinical reports (§13 ¶3), pharmacy reports (§13 ¶4), quality reports (§13 ¶5).

**Angular screens:** management-dashboard landing page (signals-based live tile refresh every 60 s), report browser with filter form + paginated grid, client-side PDF export (pdfmake) and Excel export (xlsx) consuming the JSON response — consistent with legacy UX and the ADR-0010 decision to keep rendering client-side.

## Dependencies

All preceding feature increments (03–09) must be done. Every billable entity, stock entity, and clinical order the reports query must be persisted and indexed before the read queries can be validated. Specifically:
- **Increment 04 (Billing, Cashiering & Insurance)** for `PatientInvoice`, `PatientPayment`, `CashierShift`, `Collection`, `InsuranceClaim`.
- **Increment 05 (Clinical / OPD)** for consultations and `ClinicalOrder` data.
- **Increment 06 (Laboratory, Radiology, Procedures & Theatre)** for `ClinicalOrder` results and turnaround data.
- **Increment 07 (Inpatient & Nursing)** for admissions, ward-day charges, occupancy.
- **Increment 08 (Pharmacy, Inventory & Procurement)** for `StockBatch`, `PharmacySaleOrder`, `StoreStockBatch`, `LocalPurchaseOrder`, `GoodsReceipt`.
- **Increment 09 (HR, Payroll & Assets)** for `Employee`/`ProviderProfile` referenced by clinician performance.
- Read-model code generation runs after Flyway migrations in the CI Testcontainers step; devops-engineer wires it post-Flyway (ADR-0010).

## Exact-process fidelity targets

**Revenue arithmetic (ADR-0009 §4):** Each report total is `SUM(NUMERIC(19,2))` using PostgreSQL aggregation, never reconstructed in Java. Parity assertion: `|newTotal - round(legacyDoubleTotal, 2)| ≤ 0.01` (cent tolerance). The legacy stored raw `double` products without rounding; the new system rounds at persistence to `NUMERIC(19,2)` with `HALF_UP`, so sub-cent differences are a documented precision improvement, not a defect.

**Document numbers in report rows (ADR-0009 §5):** GRN rows display `GRN{yyyyMMdd}-{seq}`, LPO rows display `LPO{yyyyMMdd}-{seq}`, transfer orders display `SPTO{yyyyMMdd}-{seq}` or `PPTO{yyyyMMdd}-{seq}` (resolving the legacy SPT collision). Patient MR numbers appear as `MRNO/{year}/{seq}`. These are stored values — no report-time reformatting.

**Collections report fidelity (legacy-findings.md §B):** The legacy performs an O(n×m) in-process nested join (iterating all bills × all collections). The new implementation replaces this with a single JOOQ query using a window-function join between `patient_payment` and `cashier_shift` grouped by cashier, shift, and payment method. The resulting totals must match the legacy row-for-row after the cent-tolerance adjustment.

**Fast/slow-moving stock thresholds:** The "fast-moving" threshold is encoded in the `PrescriptionRepository` native query predicates in the legacy (confirmed: `reports/models` projection interfaces). The legacy-analyst must confirm the exact numeric thresholds before this report is implemented. The JOOQ query must encode identical predicates; the qa-test-engineer's golden-master must drive the same date range through both systems and assert identical medicine lists.

**Clinician performance columns (PROCESS.md §12 §3):** Must include consultations count, procedures count, lab orders count, patient feedback count, and the revenue attributable to the clinician — matching the `ClinicianPerformanceReport` projection interface used in the legacy's native query.

**Insurance dual-pricing in revenue report (PROCESS.md §11 §4):** Revenue by source must separate CASH-collected lines from INSURANCE-covered lines; the insurance total must use the `ServicePrice(planUid, kind, serviceUid)` amounts, not the cash fallback price, for covered lines.

**Business-day scoping (ADR-0009 §7):** All date-range filters apply to `businessDayId` (via join to `business_days.business_date`), not to wall-clock `created_at`, matching the legacy's `Day` FK scoping pattern.

**Dashboard live data (ADR-0016 §3):** Dashboard KPIs are never served from the application-level Caffeine cache. The JOOQ dashboard query runs on every request against the live schema. `Cache-Control: max-age=60` at the HTTP layer (browser/CDN) provides the only caching. Settlement flags and stock balances are always live database reads.

## Prior-attempt pitfalls to avoid

**BILL-2 (open at audit):** The collections report endpoint and Angular screen were missing entirely in the prior build — raw data existed but no endpoint or report screen. This increment must deliver the full vertical slice: JOOQ query + endpoint + Angular report screen + golden-master test for the collections report.

**BILL-5 (medium, open):** The prior build's revenue report broke down by service kind only; there was no breakdown by payment mode and no pharmacy-sales sub-report. This increment must add payment-mode breakdown (CASH / INSURANCE / DEBIT_CARD / CREDIT_CARD / MOBILE) to the revenue-by-source response, and include pharmacy-sales as a distinct line.

**Legacy-findings.md §B (O(n×m) joins):** The prior attempt's `ReportResource` ran in-process nested-loop joins that would OOM on production data volumes. This increment must replace every such join with a JOOQ CTE or window-function query. The devops-engineer must gate CI on the Gatling/k6 performance benchmark (p95 < 3 s on 12 months of synthetic data) before merge.

**Legacy-findings.md §A (raw domain entity returns):** Several prior-build report endpoints returned `List<Consultation>` or `List<GoodsReceivedNoteDetail>` directly, serialising the full JPA object graph. All 29 report responses must use typed `ReportRowDto` records with only the columns the report requires — no bare entity serialisation.

**M23 (ward-day accrual gap):** The operational dashboard's "today's revenue" tile must include ward-day charges accrued by `WardDayAccrualJob` (ADR-0018 JOB-001). If the ward-day accrual job has not run today, the dashboard total will undercount. The devops-engineer must confirm the `hmis.jobs.ward-accrual.cron` is set to `0 5 0 * * *` in all non-dev profiles before increment 10 goes to QA.

**ADR-0016 cache exclusion:** Report output must never be placed in a Spring Cache entry (ADR-0016 §3 explicit). Any caching of report output would break golden-master parity and return stale financial totals to management — the prior build had no caching at all on reports, which was inadvertently correct.

## Lead & supporting agents

- **Lead:** backend-engineer, qa-test-engineer
- **Supporting:** solution-architect (JOOQ query review), data-architect (index strategy for reporting read path, JOOQ schema drift CI wiring), frontend-engineer (Angular management screens, pdfmake/xlsx export), devops-engineer (JOOQ codegen in CI pipeline, Gatling performance gate), legacy-analyst (confirm fast/slow-moving thresholds and 8 raw-entity report column definitions), security-architect (privilege-code mapping for all 29 report endpoints), engagement-lead (golden-master dataset coordination with legacy-analyst)

## Definition of Done

- [ ] All 29 report endpoints return typed `ReportRowDto` responses under `/reports/*`; no raw domain entities in any response body.
- [ ] JOOQ code generation integrated in the CI Maven lifecycle; JOOQ classes regenerated on every Flyway migration step with no manual intervention required.
- [ ] Golden-master test suite passes: for each of the 29 reports, a Testcontainers JUnit 5 test loads the reference dataset, runs the report via the new endpoint, and asserts row-count equality and every monetary column within cent tolerance (`|delta| ≤ 0.01`) against the captured legacy output.
- [ ] Collections report parity test explicitly covers the O(n×m) case: a dataset with 500 bills × 200 payments confirms the JOOQ window-function query produces the same per-cashier totals as the legacy nested-loop.
- [ ] Gatling/k6 performance benchmark passes in CI: p95 < 3 s for the five heavy aggregation reports (collections, clinician performance, fast/slow-moving stock, IPD summary, pharmacy sales) against a 3-year synthetic dataset loaded in QA.
- [ ] Operational dashboard `GET /reports/dashboard` returns within 500 ms p95 on the same 3-year dataset; verified by the same Gatling suite.
- [ ] Revenue breakdown includes per-`InvoiceLineKind` columns AND per-payment-mode columns; insurance totals use plan-specific `ServicePrice` amounts, not cash fallback.
- [ ] Fast/slow-moving stock thresholds match legacy predicates (legacy-analyst sign-off captured in a code comment referencing the confirmed native query).
- [ ] All report endpoints carry `@PreAuthorize` annotations using the 177 confirmed privilege codes (`DAY-ACCESS`, `ADMIN-ACCESS`, etc.); each report's privilege mapping reviewed and approved by the security-architect.
- [ ] Document numbers (`GRN{yyyyMMdd}-{seq}`, `SPTO{yyyyMMdd}-{seq}`, `MRNO/{year}/{seq}`, etc.) appear verbatim in report rows without report-time reformatting; verified by the golden-master row assertions.
- [ ] Angular management dashboard renders live KPIs with 60-second auto-refresh (signals-based); report browser supports date-range filter, per-report scoping fields (clinician UID, store UID, ward UID), paginated grid, PDF export via pdfmake, and Excel export via xlsx.
- [ ] `Cache-Control: no-store` on all `/reports/*` responses; `Cache-Control: max-age=300, must-revalidate` + ETag on all `/masterdata/*` reference-data GET endpoints (ADR-0016 §5).
- [ ] OpenAPI spec updated with all report filter request schemas and response row schemas; springdoc generates without warnings.
- [ ] Code-reviewer approves PR; `ApplicationModules.verify()` and ArchUnit gates green; Flyway `ddl-auto=validate` green; no cross-module repository injection from the `reporting` module into any other module's JPA repositories.
