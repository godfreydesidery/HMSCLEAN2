## EXTRACTION 5 — Insurance Claims + Billing @PreAuthorize Gates + Coverage Eligibility (legacy `com.orbix.api`, read-only)

### Early-discovery findings (mandatory, repeated in every audit/auth-touching spec)
- **Audit trail:** No Hibernate Envers audit trail is effectively active in the legacy system. No `@Audited` annotation exists in `com.orbix.api`. Downstream agents must not assume an Envers audit baseline exists. (Consistent with memory `zana-legacy-phantom-features.md`; not re-scanned here but no `@Audited` encountered in any entity read.)
- **Device-fingerprint / device-binding:** No device-fingerprint or device-binding feature exists. The JWT filters (`filter/CustomAuthenticationFilter.java`, `filter/CustomAuthorizationFilter.java`) only read/write a `privileges` JWT claim; no device hashing/binding. Agents must not treat this as a feature to preserve.

---

### A. INSURANCE CLAIMS — **ABSENT in legacy** (the spec's `InsuranceClaim` ledger is INVENTED / carried from prior-build, not present here)

**There is NO `Claim` or `InsuranceClaim` entity, repository, service, or resource anywhere in `com.orbix.api`.**
- `domain/` directory listing contains no `Claim*.java` (full glob of `domain/*.java` enumerated — no match).
- Glob `**/*laim*.java` across the whole package returns **No files found**.
- Every `[Cc]laim` hit in the codebase is one of two unrelated things:
  - JWT token claims: `filter/CustomAuthorizationFilter.java:77` (`decodedJWT.getClaim("privileges")`), `filter/CustomAuthenticationFilter.java:81` (`.withClaim("privileges", ...)`), `api/UserResource.java:371` (`.withClaim("roles", ...)`).
  - Code comments using "claim" colloquially to mean "add a bill line to an invoice", e.g. `service/PatientServiceImpl.java:355` (`Add registration patientBill claim to patientInvoice`) and ~20 identical comments. These are comments only — no claim object is constructed.

There is **no** claim-reference numbering, **no** SUBMITTED/SETTLED/REJECTED lifecycle, **no** per-payer claim aggregation entity, and **no** remittance/reconciliation logic anywhere.

**How the legacy ACTUALLY handles insured-patient billing (the real mechanism the modern InsuranceClaim must replace, faithfully):**
1. When a covered service is charged, a `PatientBill` is created at cash price, then overridden to the plan-covered row with `status="COVERED"`, `paid=amount`, `balance=0`, `paymentType="INSURANCE"`, `membershipNo`, `insurancePlan` set (`service/PatientServiceImpl.java:841-849` for lab tests; same pattern repeated per service type).
2. The covered `PatientBill` is attached to a **`PatientInvoice`** that is keyed by `(patient, insurancePlan, status="PENDING")`. The system finds the existing PENDING invoice for that patient+plan or creates one (`service/PatientServiceImpl.java:851-911`). Covered bills become `PatientInvoiceDetail` lines on that invoice. **`PatientInvoice` is the de-facto "claim" aggregator** — one open invoice per patient per insurance plan.
3. `PatientInvoice` (`domain/PatientInvoice.java`) has only `status` (defaults `"PENDING"`, lines 50), `amountPaid`, `amountAllocated`, `amountUnallocated`, an `insurancePlan` FK (line 75-78, **non-updatable**), and a list of `PatientInvoiceDetail`. There is no submit/settle/reject state — the only writes observed set status to `"PENDING"`; detail lines flip to `"PAID"` only when a cash bill is paid (`api/PatientBillResource.java:341-349`), which does not apply to COVERED insurance lines.
4. Retrieval of "insured work" is purely a query: `get_patient_insurance_pending_invoices` returns `findAllByInsurancePlanInAndStatus(allPlans, "PENDING")` (`api/PatientResource.java:5224-5235`); `get_patient_direct_pending_invoices` returns `findAllByInsurancePlanAndStatus(null,"PENDING")` for cash (`api/PatientResource.java:5213-5222`). This list is the closest analog to a "claims worklist" but has no settlement step.
5. **Invoice numbering quirk** (carry into migration spec): the invoice `no` is first set to `String.valueOf(Math.random())`, saved, then overwritten with the DB id as a string and saved again (`service/PatientServiceImpl.java:857`, `877-879`). Not a formatted claim/invoice number.
6. Reconciliation of insured amounts is **report-only** via `Collection` rows (see report query below) — but note insured/COVERED bills do **not** create `Collection` rows (Collections are only written on cash payment in `PatientBillResource`), so insurance settlement reconciliation does not exist in the legacy at all.

**Decision for spec:** the modern `InsuranceClaim` (SUBMITTED/SETTLED/REJECTED + claim numbering + remittance) is NET-NEW and must be flagged as an invented feature requiring an `engagement-lead` change request, OR the modern build must reproduce only the legacy behaviour (PENDING `PatientInvoice` per patient+plan, no settlement state).

---

### B. BILLING @PreAuthorize GATE MAP (exact legacy codes; `//` = present-but-commented = effectively UNGATED)

Authority tokens are derived by splitting the `Object_` constant `OBJECT-AUTH...` strings (`security/Object_.java`); the live tokens seen in `@PreAuthorize` are `OBJECT-AUTHORITY` forms (e.g. `PATIENT-ALL`, `ADMIN-ACCESS`, `BILL-A`).

| Endpoint (method) | File:line | Legacy @PreAuthorize | Effective gate |
|---|---|---|---|
| Confirm reg+consultation payment (record payment) `POST /bills/confirm_registration_and_consultation_payment` | `api/PatientBillResource.java:146-147` | **none** | UNGATED (authenticated only) |
| Confirm bills payment (record payment) `POST /bills/confirm_bills_payment` | `api/PatientBillResource.java:269-270` | `//@PreAuthorize("hasAnyAuthority('BILL-A')")` **commented out** | UNGATED |
| Get registration/consultation/lab/procedure/prescription/radiology/inpatient/pharmacy bills (view) `GET /bills/...` | `api/PatientBillResource.java:116,128,395,422,449,477,504,594` | **none** on any | UNGATED |
| Cashier list/get/active `GET /cashiers...` | `api/CashierResource.java:46,51,56` | **none** | UNGATED |
| Cashier save (master-data) `POST /cashiers/save` | `api/CashierResource.java:63-64` | `hasAnyAuthority('ADMIN-ACCESS')` | **ADMIN-ACCESS** |
| Cashier assign user / load-by-username | `api/CashierResource.java:76,91` | **none** ("to do later") | UNGATED |
| Credit-note creation (inline in cancellations) `POST /patients/cancel_consultation` | `api/PatientResource.java:605-606` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | PATIENT codes |
| Credit-note creation (inline) `POST /patients/delete_lab_test` | `api/PatientResource.java:2912-2913` | **none** | UNGATED |
| Credit-note creation (inline) delete_radiology / delete_procedure / delete_prescription | `api/PatientResource.java` (delete_* methods ~2984,3048,3431,3500,3567) | **none** | UNGATED |
| EOD / collections report `POST /reports/collections_report` | `api/ReportResource.java:661-662` | **none** | UNGATED |
| Per-service collection reports `POST /reports/{lab_test|radiology|procedure}_collection_report` | `api/ReportResource.java:674,720,766` | **none** | UNGATED |
| Pending invoices view `GET /patients/get_patient_{direct|insurance}_pending_invoices`, `get_patient_invoice` | `api/PatientResource.java:5213,5224,5237` | `//` commented out | UNGATED |
| Manage service prices (lab plan prices etc.) | `api/LabTestTypePlanResource.java`, `InsurancePlanResource.java` etc. | NOT inspected in this extraction — flag for follow-up | TBD |

**Privilege-code reality (`security/Object_.java`):**
- `BILL = "BILL-ALL CREATE"` (line 27) → live tokens `BILL-ALL`, `BILL-CREATE`. The code's commented gate uses **`BILL-A`** (line 270) which does NOT match `BILL-ALL` exactly — ambiguity (see decisions). Memory says `BILL-A` is among the 35 live codes.
- **`CASHIER_SERVICE = "CASHIER_SERVICE-ACCESS"` (line 36)** is a real legacy constant. The spec's **`CASHIER-ACCESS` is NOT this string** — it is an invented/misremembered code. If a cashier gate is wanted, map to the real `CASHIER_SERVICE-ACCESS` (note: NOT currently applied to any billing endpoint in code) or to `ADMIN-ACCESS` (the only code actually gating any cashiering endpoint, on cashier save).
- `ADMIN = "ADMIN-ACCESS"` (line 20) is the only code that actually gates a cashiering endpoint.

**Bottom line:** essentially the entire payment/cashiering/collections surface is **UNGATED** in the legacy (commented-out or absent `@PreAuthorize`). The modern hard pay-before-service gate and any cashier-role gate are NET-NEW hardening, not legacy behaviour — must be flagged as such.

---

### C. COVERAGE ELIGIBILITY — how a line routes to PLAN vs CASH

The decision is made per service line in `service/PatientServiceImpl.java` (lab-test exemplar at lines 837-918; same shape repeated for radiology/procedure/medicine/registration/consultation):

1. Always build the `PatientBill` at **cash price first** (`labTestType.getPrice()`), status `UNPAID` (`service/PatientServiceImpl.java:822-835`).
2. Coverage is attempted only if `patient.getPaymentType().equals("INSURANCE") || admission.isPresent()` (`:837`).
3. Routing key: `labTestTypeInsurancePlanRepository.findByLabTestTypeAndInsurancePlanAndCovered(labTestType, patient.getInsurancePlan(), true)` (`:839`). **A line routes to the plan iff a join-entity row exists matching (serviceType, patient's insurancePlan) AND its `covered` boolean == true.**
   - The `covered` flag lives on the join entity `domain/LabTestTypeInsurancePlan.java:44` (`private boolean covered = false;`), alongside `price` (`:42`) and `active` (`:43`). Plan price = `LabTestTypeInsurancePlan.price`, NOT the cash `LabTestType.price`.
   - NOTE: a separate legacy table `lab_test_plan_prices` (`domain/LabTestPlanPrice.java`) has **no `covered` flag** and is **not** the one used by the routing query — the active mechanism is `lab_test_type_insurance_plans`. Flag for data-migration: two parallel plan-price tables exist; only `*_insurance_plans` carries `covered` and drives routing.
4. If the covered row is present → override bill to COVERED (plan price, paid=amount, balance=0, status `COVERED`) and attach to the patient+plan PENDING `PatientInvoice` (`:841-911`).
5. If NO covered row but patient is admitted (`a.isPresent()`) → cash-fallback bill with status `VERIFIED` attached to a **null-plan** PENDING invoice (`:912-948`). (Matches the memory's per-service asymmetry: LAB/RAD/PROC/MED cash-fallback VERIFIED only when inpatient.)
6. If neither → the cash `UNPAID` bill stands (non-admitted insured patient pays cash). CONSULTATION is the documented hard-fail exception (`PatientServiceImpl:597-601`, per memory).

So: **coverage = existence of a matching `*InsurancePlan` join row with `covered=true` for the patient's `insurancePlan`** — there is no percentage/co-pay field; the only co-pay mechanism is the WARD top-up second bill (per memory). Co-pay/waiver percentages are NOT modelled.

---

### Cross-reference index (legacy artefact → topic)
- `service/PatientServiceImpl.java` — coverage routing, COVERED override, invoice aggregation, invoice numbering.
- `domain/PatientInvoice.java`, `domain/PatientInvoiceDetail.java` — de-facto insurance "claim" aggregator (no settlement lifecycle).
- `domain/LabTestTypeInsurancePlan.java` (+ ProcedureTypeInsurancePlan, RadiologyTypeInsurancePlan, MedicineInsurancePlan, ConsultationInsurancePlan, RegistrationInsurancePlan) — coverage `covered` flag + plan price.
- `api/PatientBillResource.java` — payment confirmation (UNGATED), Collection writes.
- `api/CashierResource.java` — cashier master-data (ADMIN-ACCESS on save only; no shift).
- `service/PatientCreditNoteServiceImpl.java:33-41` — PCN numbering (`Formater.formatWithCurrentDate("PCN", id)`).
- `repositories/CollectionRepository.java:21-59` — collections report SQL (the only "cashier reconciliation").
- `security/Object_.java` — canonical privilege constants (BILL-ALL CREATE; CASHIER_SERVICE-ACCESS; ADMIN-ACCESS).