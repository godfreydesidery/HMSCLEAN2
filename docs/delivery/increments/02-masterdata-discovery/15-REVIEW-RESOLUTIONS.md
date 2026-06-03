# Increment 02 — Adversarial Review Resolutions (2026-06-03)

3-lens review verdicts: code-reviewer REQUEST_CHANGES, qa-test-engineer REQUEST_CHANGES,
security-architect APPROVE_WITH_NITS. Full findings: `12-review-code.md`, `13-review-qa.md`,
`14-review-security.md`. The high-risk areas were verified CORRECT (PriceLookup storage-tier deferral
honest; coefficient math; affiliation/boundary; catalog faithfulness). Resolutions (all faithful/clear —
no new sign-off needed):

## Production fixes
- **RF-1 (ServicePrice admin lifecycle, code HIGH + sec NIT):** make `POST /masterdata/service-prices` a
  TRUE UPSERT — when the composite key `(plan_uid,kind,service_uid,currency)` already exists, UPDATE its
  amount/covered/min/max (200) instead of 409; add `DELETE /masterdata/service-prices/uid/{uid}` (204).
  Both ADMIN-ACCESS. Restores the legacy per-kind `update_*_price_by_insurance` / `change_*_coverage`
  capability. (The 409-on-create remains only meaningful if we keep a separate create; simplest =
  idempotent upsert by key. Keep a uniqueness guard so a NEW distinct key still inserts.)
- **RF-2 (price/coverage coupling, code HIGH):** reproduce the legacy write rule
  (InsurancePlanResource.java:274-279, mirrored across all 7 kinds) on the ServicePrice write path:
  `amount < 0` → reject 400 (validation); `amount == 0` → force `covered = false`; `amount > 0` → keep
  the supplied `covered`. This is an unambiguous legacy rule → reproduce (no escalation).
- **RF-3 (CompanyProfile read gate, code HIGH):** REMOVE `@PreAuthorize` from the company-profile GET —
  legacy CompanyProfileResource GET is JWT-only/ungated and build-spec §3 mandates ungated masterdata
  reads (cashiers/clinical read it for receipts/invoices). Fix the wrong Javadoc citation. Update the IT
  (drop `get_withoutAdminAccess_returns403`; assert GET works with any valid token).
- **RF-4 (active default drift, code MED):** `items_suppliers.active` and `supplier_item_prices.active`
  default to **TRUE** (legacy ItemSupplier.java:49, SupplierItemPrice.java:42) — fix the entity Java
  defaults + request defaults, and the V7 DDL `DEFAULT TRUE` (branch not merged; catalogs empty so no
  rows affected).

## Test additions (qa HIGH/MED)
- Negative-auth (403 without code / 401 without token) ITs for the controllers lacking them: Store,
  Supplier, ItemSupplier, WardType, WardCategory, RadiologyType, ProcedureType.
- ServicePrice: upsert-UPDATE path (200 + value changed), DELETE (204 + gone), audit row on
  create/update/delete, and an `active=false` row still resolves (active inert).
- Coefficient: 401/403 gate tests, missing-coefficient NotFound, UPDATE path (+ audit row).
- A few audit-row assertions on UPDATE paths; assert the 409 ProblemDetail carries the error `code`.
- NITs: ServicePriceIT resolve test uses ADMIN-ACCESS (not DAY-ACCESS); PrivilegeGateArchTest Javadoc
  wording (35 distinct / 26 live).

## Not changed (confirmed correct or ratified)
PriceLookup storage-tier + §2.3 deferral; coefficient lossless convert(); affiliation iam-ownership +
CLINICIAN gate; catalog faithfulness; WardType-only pricing; SPTO/PPTO; single-row 409; no-id/no-@Transactional/no-now conventions. Security NITs (audit-action semantics, doc consistency) folded into the test/doc tidies above.
