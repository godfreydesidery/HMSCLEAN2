# Frontend Rebuild — Scaffold Plan (legacy UX × backend-implemented)

**Branch:** `feat/frontend-foundation-bootstrap` · **Date:** 2026-06-05
**Stack:** Angular 18 standalone + signals, Bootstrap 5 + ng-bootstrap (no Material), typed API
client generated from live `/v3/api-docs`. Dev: frontend on **:4300** (proxy → backend :8080),
login `admin`/`password`.

This plan maps the **legacy Angular frontend** (`ZANAHMIS-2-feature/zana-hmis`, 169 components,
role-based) against the **HMSCLEAN2 backend** (60 controllers) and scaffolds only what can be wired.

---

## Backend reality (what we can wire)

| Area | Backend status | Wireable? |
|------|----------------|-----------|
| Registration (`/api/v1/patients`) | FULL — register/search/get/update/changeType/sendToDoctor | ✅ |
| Clinical / OPD (`/api/v1/clinical/**`) | FULL — consultations, lab tests, radiology, procedures, prescriptions, diagnoses, SOAP/exam/vitals, transfers, closure | ✅ |
| Inpatient (`/api/v1/inpatient/**`) | FULL — admission(+read), dispositions, nursing/care-plan/progress/dressing/dosing/**MAR**, consumables, accrual trigger | ✅ (UI started) |
| Pharmacy (`/api/v1/pharmacy/**`) | FULL — dispense, OTC sale orders, stock (FEFO/movements/adjust) | ✅ |
| Billing / Cashier (`/api/v1/billing/**`) | FULL — bills, payments, invoices, credit notes, collections report, receipt | ✅ (UI exists) |
| Inventory / Procurement / Store (`/api/v1/inventory/**`) | FULL — LPO lifecycle, GRN, PS-transfers, PP-transfers | ✅ |
| IAM (`/api/v1/iam/**`, `/auth`) | FULL — users, roles, privileges, token | ✅ |
| Masterdata (`/api/v1/masterdata/**`) | FULL — 26 catalogs (wards/beds/theatres, clinics/pharmacies/stores, lab/rad/procedure/diagnosis/route types, medicines/items/suppliers, insurance, service-prices, currencies, doc-types) | ✅ |

**Important nuance:** lab / radiology / procedures are **embedded in the clinical module** (ordered on a
consultation/non-consultation; worklists at `/clinical/lab-tests/worklist` etc.). The legacy "Laboratory"
and "Radiology" *role* screens wire to these clinical endpoints — there is no separate department backend.

### NOT implemented (do NOT scaffold as functional — stub/hide)
- **HR / Payroll / Employee / Asset** — no backend at all.
- **Theatre scheduling / procedure booking** — only Theatre *masterdata* CRUD; no operational workflow.
- **Advanced reports / analytics** — only EOD **collections** report exists.
- **Appointments / scheduling** — none.

---

## Legacy navigation model → rebuild model

Legacy is **role-based** (menu groups: Reception, Doctor, Nurse, Laboratory, Radiology, Pharmacy,
Cashier, Store, Procurement, Admin, Reports, HR, Management) gated by `*_SERVICE-ACCESS` privileges,
with routes dynamically loaded per role.

Our shell already has a **module-based** RBAC sidebar (`core/nav/nav-items.ts`, `*appCan`). We keep
module-based nav but align labels/privileges to the legacy roles. Worklist screens (outpatient /
inpatient / outsider lists) are the legacy pattern: each clinical role lands on a **worklist**, picks a
patient, and works the encounter.

---

## Scaffold phases (priority = backend-complete × user value × dependency order)

### Phase 0 — DONE
- Foundation: Bootstrap shell, RBAC nav, entity-picker, dev proxy, API client.
- Billing/Cashier (Material→Bootstrap migrated).
- Inpatient feature module (admissions list/admit/detail + MAR/nursing panels) — *needs alignment to
  legacy flow: admission is a DOCTOR action via cascading ward/bed select, not a standalone admit form.*

### Phase 1 — Registration (foundational; everything needs a patient)
- Patient register (form: demographics + next-of-kin + payment type/insurance).
- Patient list / search.
- "Send to doctor" action (creates consultation + fee bill).
- Wires: `PatientController` (full).

### Phase 2 — Masterdata / Admin catalogs (unblocks pricing, wards, medicines, types)
- Generic catalog CRUD pattern → 26 catalogs. Priority subset first: wards/ward-types/ward-categories/
  beds, medicines, items, suppliers, insurance providers/plans, service-prices, lab/rad/procedure/
  diagnosis/route types, clinics/pharmacies/stores, company-profile.
- Wires: 26 `masterdata` controllers (full).

### Phase 3 — Clinical / OPD (the core encounter)
- Doctor reception queue → consultation workspace (SOAP note, exam, vitals, working/final diagnosis).
- Order lab tests / radiology / procedures; prescribe.
- Lab worklist (accept→collect→verify→report), Radiology worklist (accept→verify→report),
  Procedure worklist, Pharmacy dispense worklist.
- Consultation transfer; OPD closure (deceased/referral).
- Wires: `clinical` module (full) — the largest area.

### Phase 4 — Inpatient (align to legacy)
- Move admit to a DOCTOR "send-to-ward" cascading modal (ward category→type→ward→bed) — already have
  read endpoints + nursing/MAR panels. Add the doctor inpatient list + chart.
- Dispositions (discharge/referral/deceased) as approval-gated screens.

### Phase 5 — Pharmacy + Inventory/Procurement/Store
- Pharmacy: dispense worklists (OPD/inpatient), OTC sale orders, stock status (FEFO/movements).
- Inventory: LPO lifecycle, GRN, PS/PP transfers.

### Phase 6 — IAM admin
- Users, roles, privilege assignment.

### Deferred (no backend) — hide or "coming soon"
- HR/Payroll, Assets, Theatre scheduling, advanced reports, appointments.

---

## Conventions (all features follow)
- Standalone components, OnPush, signals; Bootstrap + ng-bootstrap only.
- **No uid typing** — `app-entity-picker` (typeahead) for patient/bed/etc.; uid captured programmatically.
- Lazy feature routes under `features/<area>/<area>.routes.ts`, wired in `app.routes.ts`, nav item in
  `core/nav/nav-items.ts` (`enabled:true` only when the screen is built + wired).
- Reuse `core/error/problem-detail` (RFC 7807), `core/auth` (`*appCan`, guards).
- A generic **masterdata CRUD** component pattern to avoid hand-writing 26 near-identical catalogs.
