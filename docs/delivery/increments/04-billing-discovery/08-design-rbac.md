Confirmed: zero `@PreAuthorize` annotations in ReportResource.java — the entire reporting/collections surface is ungated. I have everything needed.

# BILLING RBAC GATE MAP + CROSS-MODULE SECURITY (security-architect lens)

## Verified privilege-code facts (legacy `security/Object_.java`, read-only)
- `Object_.BILL = "BILL-ALL CREATE"` → live authority tokens are **`BILL-ALL`** and **`BILL-CREATE`**. There is **no `BILL-A` token** in `Object_`. `BILL-A` exists ONLY as a commented-out string at `PatientBillResource.java:270`. The build-memory claim "BILL-A is one of the 35 live codes" is the seeded `privileges`-table reality (matched literally against the JWT `privileges` claim by `CustomAuthorizationFilter`), NOT an `Object_`-derived token. **The exact live token string must be confirmed against the seeded privileges data by legacy-analyst** — `BILL-A` vs `BILL-ALL` is an unresolved mismatch (DEV-1 below).
- `Object_.DAY = "DAY"` → token `DAY`. But `DayResource.java:50` gates on **`DAY-ACCESS`** — another orphan gate string not derivable from `Object_`. Same pattern as `BILL-A`: a literal `@PreAuthorize` string that depends on the seeded privilege name, not on `Object_`.
- `Object_.CASHIER_SERVICE = "CASHIER_SERVICE-ACCESS"` is the real cashier constant. Spec's **`CASHIER-ACCESS` is INVENTED** (confirms Extraction 5). It is applied to NO endpoint.
- `Object_.ADMIN = "ADMIN-ACCESS"` is the only code that actually gates any cashiering/master-data endpoint (cashier save, plan/price save).
- Privilege seeding (`UserServiceImpl.getObjects()` :538-562) extracts only the object prefix (substring before first `-`); it does NOT mechanically expand `OBJECT-OPERATION` tokens. So the authoritative live authority set is the persisted `privileges` rows (the "35 codes"), and every `@PreAuthorize` string is matched literally. @PreAuthorize in the rebuild may use ONLY confirmed live codes.

## Gate map — billing endpoint group → REAL legacy code

| Billing endpoint group | Legacy reality (verified) | Recommended rebuild gate | Class |
|---|---|---|---|
| View invoices / cashier queue (`GET /bills/get_*_bills`, pending-invoices) | UNGATED (no `@PreAuthorize`) | **`BILL-A`** (view tier) — net-new hardening of an ungated surface; flag as deviation | NET-NEW |
| Record payment (`confirm_bills_payment`, `confirm_registration_and_consultation_payment`) | `BILL-A` **commented out** → effectively UNGATED | **`BILL-A`** (re-activate the intended-but-commented gate) | RESTORE |
| POS receipt (print/issue) | No legacy endpoint exists | **`BILL-A`** | NET-NEW |
| Create credit note (inline in cancellations) | Mixed: consultation-cancel gated `PATIENT-ALL/PATIENT-CREATE/PATIENT-UPDATE`; lab/radiology/procedure/prescription cancel UNGATED | **`BILL-A`** for billing-module PCN endpoints; preserve `PATIENT-*` where PCN is a side-effect of a clinical cancel owned by Registration/Clinical | DEV-2 |
| View credit note | No legacy read/list endpoint (PCN is write-only side-effect) | **`BILL-A`** (read tier) | NET-NEW |
| Open/close cashier shift | **No CashierShift entity exists** (Extraction 3 confirmed) | N/A — net-new feature; if approved, gate `ADMIN-ACCESS` (matches Day-close authority pattern) | INVENTED |
| EOD collections / cash-up (`/reports/collections_report` + 7 per-kind) | UNGATED (zero `@PreAuthorize` in `ReportResource`) | **`BILL-A`** (or `REPORT_SERVICE-ACCESS` if among live 35) | NET-NEW |
| Day close (`/days/end_day`) — the real legacy EOD primitive | Gated `ADMIN-ACCESS`,`DAY-ACCESS` | **`ADMIN-ACCESS`, `DAY-ACCESS`** (preserve exactly) | PRESERVE |
| Manage service-prices / plan-prices (`/lab_test_type_insurance_plans/save`+delete, `/insurance_plans/save`) | Gated `ADMIN-ACCESS` | **`ADMIN-ACCESS`** (preserve exactly) | PRESERVE |
| Cashier master-data (`/cashiers/save`) | Gated `ADMIN-ACCESS` | **`ADMIN-ACCESS`** (preserve exactly); model `Cashier` under iam personnel | PRESERVE |
| Insurance claims ledger (SUBMITTED/SETTLED/REJECTED) | **No InsuranceClaim entity exists** (Extraction 5 confirmed) | N/A — net-new; if approved gate `BILL-A`/`ADMIN-ACCESS` | INVENTED |

Rule applied: where legacy gates exist (`ADMIN-ACCESS`, `DAY-ACCESS`), preserve verbatim. Where legacy is ungated, the modern gate is **net-new hardening** — recommend `BILL-A` for the transactional billing surface and `ADMIN-ACCESS` for master-data, but every such cell is flagged as a deviation, not presented as legacy fact. **No new privilege code is invented**; all proposals reuse `BILL-A`/`ADMIN-ACCESS`/`DAY-ACCESS`.

## Cross-module security — `BillingCommands.recordClinicalCharge`

**Recommendation: INTERNAL module API, NOT a REST endpoint, NOT `@PreAuthorize`-gated.**
- `recordClinicalCharge` is exposed on the `billing.api` named interface and called by Registration(03)/Clinical(05) **in the caller's transaction** (REQUIRED propagation, no async, no REQUIRES_NEW per the build memo). It is a trusted in-process Spring Modulith call, not an HTTP boundary.
- Authorization is enforced ONCE at the REST edge the caller already crossed (the clinical/registration controller's own `@PreAuthorize`, e.g. `CONSULTATION-*`/`LAB_TEST-*`). Adding `@PreAuthorize` on the internal command would (a) re-evaluate the `SecurityContext` of whoever happens to be on the thread — fragile for any future system/scheduled caller — and (b) duplicate/contradict the caller's gate. This matches legacy: charge creation lives inside `PatientServiceImpl` (service layer, ungated); only the REST resources carry `@PreAuthorize`.
- **Controls to specify instead:** (1) keep `recordClinicalCharge` package-private to the `billing.api` named interface; Modulith verification tests must prove no other module and no controller calls it directly except the sanctioned `iam`-authenticated clinical flows; (2) the command must record `createdBy` from the propagated authenticated principal (carried via the caller's tx/security context), preserving the legacy `createdBy = userService.getUser(request).getId()` attribution — no PHI beyond the patient identifier in the command payload; (3) no audit-log suppression — every charge mutation is an audit event (clinical+financial), captured at the billing write path regardless of which module invoked it.

## Settled-flag write direction + downstream read

Confirms ADR-0008 §6 and is a clean security posture:
- **Write direction billing → encounter only:** the `SettlementDispatcher` (in billing) writes the local `settled` flag onto the encounter/clinical projection after a payment is recorded. No reverse edge.
- **Downstream clinical modules read ONLY their local `settled` flag** — they must never call `billing.api` to check payment state. This keeps billing as the single source of truth and prevents a clinical module from holding a billing-authorization capability.
- **Security note / deviation:** in legacy there is **no `settled` field and no pay-before-service precondition** (Extraction 2 §5 — zero matches for `settled|isSettled|payBeforeService`; no status/balance check in `PatientServiceImpl`/`PharmacyServiceImpl` before result entry or dispensing). The legacy enforces pay-before-service only as a UI list filter, not a code gate. Therefore the `settled`-flag **hard gate is NET-NEW HARDENING, not exact-process**, and requires an explicit engagement-lead change request before it is enforced as a block. If approved, the flag is a defense-in-depth control and must itself be audited on each write.

## Deviations / decisions requiring sign-off

- **DEV-1 (privilege token mismatch — must resolve before wiring):** `BILL-A` (commented gate string, and the build-memory "live code") does NOT match the `Object_`-derived tokens `BILL-ALL`/`BILL-CREATE`. `DAY-ACCESS` (live gate at `DayResource.java:50`) does NOT match `Object_.DAY`→`DAY`. Both gate strings are matched literally against seeded `privilege.name` values. **legacy-analyst must confirm the exact seeded privilege strings in the `privileges`/`role_privileges` data** before any `@PreAuthorize` is wired. Do NOT finalize the RBAC model on `Object_` constants alone. (Guardrail: RBAC model not final without legacy-analyst confirmation it matches extracted data.)
- **DEV-2 (CASHIER-ACCESS invented):** map any cashier gate to `CASHIER_SERVICE-ACCESS` (if among the live 35) or `ADMIN-ACCESS`. Do not introduce `CASHIER-ACCESS`.
- **DEV-3 (entire payment/cashiering/collections surface ungated in legacy):** every billing gate except `ADMIN-ACCESS` on master-data/cashier-save and `DAY-ACCESS`/`ADMIN-ACCESS` on day-close is net-new. Re-activating the commented `BILL-A` and gating the EOD report surface is recommended hardening but is a behavioural change requiring engagement-lead approval.
- **DEV-4 (CashierShift + InsuranceClaim ledger invented):** no legacy entity; if approved as net-new, gate shift open/close and claim transitions with `ADMIN-ACCESS` (claim view/submit may use `BILL-A`). No new code.
- **DEV-5 (settled hard gate net-new):** see above — needs change request; preserving legacy = no hard block at service time.

## Relevant file paths (absolute)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\security\Object_.java` (privilege constants; `BILL`:27, `ADMIN`:20, `CASHIER_SERVICE`:36, `DAY`:75)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\api\PatientBillResource.java:270` (commented `BILL-A` gate; rest ungated)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\api\CashierResource.java:64` (`ADMIN-ACCESS` on save)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\api\DayResource.java:50` (`ADMIN-ACCESS`,`DAY-ACCESS`)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\api\LabTestTypePlanResource.java:69,103` and `InsurancePlanResource.java:143` (`ADMIN-ACCESS` on plan/price save)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\api\ReportResource.java` (collections/EOD reports — all ungated)
- `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\service\UserServiceImpl.java:538-562` (privilege/object derivation — proves tokens come from seeded data, not `Object_` expansion)