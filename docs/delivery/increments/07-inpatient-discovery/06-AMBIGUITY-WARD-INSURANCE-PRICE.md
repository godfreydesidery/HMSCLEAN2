# Inc-07 Ambiguity Register — Ward-bed insurance price selection (BLOCKS 07a-2)

**Raised:** 2026-06-05 (during the 07a-2 ward-billing build) · **By:** legacy-analyst deep extraction (agent `aaeb2953d0963b83f`)
**Status:** OPEN — needs engagement-lead + healthcare-domain-expert decision. **Blocks the insurance-ward-price path of Chunk 07a-2.** The cash ward path (already built in 07a-1) is unaffected.

> **Why this is here and not silently resolved:** this is a newly-discovered **latent legacy defect** with **direct financial impact** (it changes the insurance-covered ward amount), and it **contradicts the ratified Q9 decision + the frozen build spec**. The implementing agent must not pick the money. Per the charter, a deviation-vs-verbatim choice with golden-master consequences is an owner/HDE call.

---

## What the ratified decision (Q9) assumed

[03-DECISIONS-RATIFIED.md](03-DECISIONS-RATIFIED.md) Q9 and [05-INC07-BUILD-SPEC.md](05-INC07-BUILD-SPEC.md) §07a ward-billing both say: reproduce the **active-flag-ignored max-price-OR-exact-plan-match eligibility loop verbatim** AND **build the load-bearing top-up split** (COVERED principal at `eligiblePlan.price` + UNPAID "Ward Bed / Room (Top up)" supplementary). The adversarial review (AC-WP-04) hedged that the top-up branch is "near-unreachable under the query scope — build verbatim, do not expect it to fire."

## What the legacy code ACTUALLY does (file:line verified)

Legacy `doAdmission` INSURANCE branch (`PatientServiceImpl.java:1795-1966`), selection loop `:1801-1809`:

```java
List<WardTypeInsurancePlan> wardTypePricePlans =
    wardTypeInsurancePlanRepository.findByInsurancePlanAndCovered(p.getInsurancePlan(), true); // :1799
double eligiblePrice = 0;
for (WardTypeInsurancePlan plan : wardTypePricePlans) {
    if (plan.getPrice() > eligiblePrice || plan.getInsurancePlan().getId() == p.getInsurancePlan().getId()) {
        eligiblePrice = plan.getPrice();
        eligiblePlan = plan;
        if (plan.getInsurancePlan().getId() == p.getInsurancePlan().getId()) { break; } // :1806-1808
    }
}
```

Three verified facts that overturn the Q9 premise:

1. **The max-price loop is DEAD CODE.** `findByInsurancePlanAndCovered(p.getInsurancePlan(), true)` (`WardTypeInsurancePlanRepository.java:40`, a plain derived query, no `@OrderBy`) returns ONLY rows whose `insurancePlan == p.getInsurancePlan()`. So the inner `plan.getInsurancePlan().getId() == p.getInsurancePlan().getId()` is **true on iteration 0** → `break` fires immediately. **`eligiblePlan` = the FIRST covered row in arbitrary DB order.** The `price > eligiblePrice` max-logic never decides anything.

2. **Selection IGNORES the admitted bed's ward type.** The loop never references `wb.getWard().getWardType()`. So `eligiblePlan.getPrice()` can be the insurance price of a **completely different ward type** than the bed the patient was admitted to — while the cash/top-up math (`:1754`, `:1880-1885`) DOES use the correct admitted ward type. The two are inconsistent by construction. A correctly-keyed repo method `findByInsurancePlanAndWardType(plan, wardType)` **exists at `WardTypeInsurancePlanRepository.java:33` but is NOT used here.**

3. **The top-up branch is UNREACHABLE in `doAdmission`.** Its guard (`:1880`) is `eligiblePlan.getInsurancePlan().getId() != p.getInsurancePlan().getId() && (wardType.price - eligiblePlan.price) > 0`. Since `eligiblePlan` always comes from the patient's OWN plan, the first conjunct is **always false** → the supplementary "Top up" bill is **never created at admission**. Control always falls to the `else` (`:1950-1963`): sign out IN-PROCESS/PENDING consultations → admission `IN-PROCESS` → bed `OCCUPIED` (i.e. **insurance admissions activate at admit**).

So the ratified "load-bearing top-up split" is, in `doAdmission`, **provably dead** — not "near-unreachable." It IS reachable in the ward-accrual path (`UpdatePatient.java:340-356`, a byte-for-byte clone) only under the same always-false guard, so it is equally dead there too.

`.active` is confirmed **not filtered** (the derived query has no `Active` predicate) — the CR-07-Q9 "active-flag-ignored" finding stands.

## Why the modern schema can't reproduce it as-is

HMSCLEAN2 unified insurance pricing into `service_prices` with a UNIQUE index on `(COALESCE(plan_uid,''), kind, service_uid, currency)` — so `PriceLookup.resolve(planUid, WARD, wardTypeUid)` returns **exactly one row, keyed on the admitted ward type**. Legacy returns the **first covered row for the plan, ward-type-agnostic**. These produce **identical money ONLY** when the plan covers exactly one ward type AND it equals the admitted bed's ward type. In any tenant where a plan covers ≥2 ward types (the normal case — e.g. "General" + "Private"), they diverge. There is no `WardTypeInsurancePlan` entity in HMSCLEAN2; the legacy multi-row, ward-type-agnostic, first-row-wins selection is **not representable** in `service_prices` without re-introducing a structure.

## The decision needed (engagement-lead + HDE)

| Option | Behaviour | Consequence |
|--------|-----------|-------------|
| **A — Reproduce verbatim (bug-for-bug)** | Insurance ward price = first covered row for the plan, **independent of admitted ward type**; never produce a top-up at admission; insurance admissions activate IN-PROCESS at admit. | Bit-identical golden-master. Requires re-introducing a multi-row ward-insurance structure (the `service_prices` single-row keying cannot express "first row, ward-type-agnostic"). Preserves a latent defect (wrong-ward-type price). |
| **B — Corrected intent (RECOMMENDED by legacy-analyst)** | Insurance ward price = the covered row **matching the admitted bed's ward type** (`PriceLookup.resolve(plan, WARD, wardTypeUid)`); top-up when cash ward price > covered price. | Matches `service_prices` natively. Almost certainly the designer's intent (the unused keyed repo method + the top-up math both point at it). **Makes the top-up split actually load-bearing** (so AC-WP-04/08 become real, not dead). A DEVIATION from verbatim legacy → its own CR + golden-master re-baseline. |
| **C — Hybrid** | Reproduce "first-row" semantics but key it to the admitted ward type only when a matching row exists, else first-row. | Messy; not recommended. |

**This supersedes / refines the ratified Q9.** Q9's "reproduce the active-flag-ignored loop verbatim + build the load-bearing top-up" was written without knowing the loop is dead and the top-up is unreachable at admission. Whichever option is chosen must be applied to BOTH `doAdmission` and the `UpdatePatient` accrual clone (the 07c accrual chunk inherits this).

## Recommendation to the owner

**Option B** (corrected, ward-type-keyed via the existing `PriceLookup`). Rationale: (1) it is the designer's evident intent; (2) it makes the modern unified `service_prices` the single source of ward pricing with no new structure; (3) it turns the top-up split into genuinely exercised code (the ratified spec WANTED a load-bearing top-up — Option B delivers exactly that, Option A makes it dead); (4) reproducing a wrong-ward-type-price defect bit-for-bit has negative clinical/financial value and no upside. Cost: a CR + golden-master baselined to corrected (not legacy-verbatim) ward-insurance amounts, and an HDE confirmation that the corrected money is the intended NHIF/insurance behaviour.

If the owner prefers strict verbatim parity (Option A), the build re-introduces a `ward_type_insurance_plans`-equivalent and reproduces first-row selection — more work, preserves the bug.

## Build status while blocked

- 07a-1 (cash admission lifecycle) is committed green and is **unaffected** (cash ward bill at `WardType.price`).
- 07a-2's **insurance** ward path is **paused at this decision** — it is the one path whose money depends on the answer.
- The 07a-2 work that does NOT depend on the answer (the activate-at-admit branch for the no-top-up case, which is the legacy `else` at `:1950`) can proceed under either option, but is cleaner to build once the insurance-price shape is decided.
