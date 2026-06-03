# Increment 03 (Registration) — Engagement-lead ratification of the §11 open items

Date 2026-06-03. Ratifies the open items the solution-architect routed in `00-build-spec.md` §11.
The full 22-CR register in `00-build-spec.md` §9 stands as written. These are the gating calls.

| Open item | Decision |
|---|---|
| **CR-02 — MRN from `seq_mrno`, EAT year** | **RATIFIED.** `MRNO/{EAT-year}/{nextval seq_mrno}`. The decoupling from the surrogate PK is forced by the already-ratified architecture (ADR-0014 §1 hides `id`; the legacy author flagged the PK-as-MRN as a placeholder). Format string is byte-identical; only the numeric provenance changes. No per-year reset, no zero-pad. Consistent with the pre-approved "data model may change" directive. |
| **CR-12 — registration-fee source** | **RATIFIED.** Amount sourced from `service_prices(kind=REGISTRATION, plan_uid=null)` via `recordClinicalCharge` (the inc-02 matrix), NOT `CompanyProfile.registrationFee`. The specific cash figure is a **data-seed concern** (greenfield catalogs start empty) — not a code blocker; the golden-master parity figure is supplied when the registration price is seeded. Single-row matrix resolves the legacy multi-row last-wins nondeterminism (FIX). |
| **CR-20 — follow-up `NONE` mechanism** | **RATIFIED: extend billing** (architect's recommendation). Add a `followUp` flag to `billing.api.ChargeRequest`; when true for `kind=CONSULTATION`, `recordClinicalCharge` creates the consultation bill with `status=NONE` (already in `ck_patient_bills_status`, V15:71) and skips the payable charge/claim. Keeps all charge creation in one place. Implemented in chunk C6 (a small billing.api addition on this branch). |
| **CR-17 — gender value set** | **RATIFIED: free-text** `VARCHAR(20) NOT NULL`, `@NotBlank` only, no DB CHECK, no app-level enum (legacy is free-text — exact process). |
| **R1/R2 — insurance fall-throughs** | **RATIFIED as PARITY.** Insurance-but-uncovered → silent cash UNPAID; insurance-but-regFee==0 → VERIFIED, no claim. Already reproduced by the billing engine; no new code. |
| **CR-21 — Consultation ownership** | **RATIFIED.** PENDING `Consultation` lives in `registration` for inc-03 (clinical module absent); permanent owner = `clinical` (inc-05). The inc-05 spec carries the ownership-transfer plan; `registration.api` exposes PENDING-consultation read. |

**Net:** no further blockers. Proceed with the C1–C7 chunked build per `00-build-spec.md` §8.
