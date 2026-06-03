# 13-review-qa

Verdict: REQUEST_CHANGES

The golden-master and AC-1 pricing parity tests for the 7 ServiceKind values, the AC-3 coefficient precision/lossless-path, the AC-4 CLINICIAN gate, and the AC-5 uniqueness quadrant (null-plan, null-service, both-null) are all present and correctly structured. AC-7 sequence assertions and the SPTO/PPTO CR-10 prefix fix are proven. The PrivilegeGateArchTest and IamNoEntityLeakArchTest structural gates are sound.

There are four HIGH-severity gaps that block sign-off:

1. The `active=false` inertness claim for PriceLookup is unproven — no test seeds an inactive row and asserts it resolves (build-spec §2.1).

2. Audit trail completeness is unproven for 7+ entity types: ServicePrice, ItemMedicineCoefficient, InsurancePlan (via helper), Clinician affiliation, SupplierItemPrice, MdCurrency, MdDocumentType write — none assert AuditAction.CREATE rows. The spec treats audit completeness as a hard requirement with no exceptions.

3. Seven controller groups (Store, Supplier, ItemSupplier, WardType, WardCategory, RadiologyType, ProcedureType) have zero IT coverage — no 401/403/201/audit assertions at all.

4. CoefficientMathIT has no 401/403 gate tests and no 404-on-missing assertion, contradicting the explicit AC-3 and AC-8 requirements.

Two MEDIUM gaps require fixes before merge: the UPDATE audit path is unasserted across all entities except CompanyProfile; and the currency-mismatch 422 path is unproven, leaving the 'currency is a lookup discriminator, not a multiplier' contract untested.

## Findings

### [HIGH] AC-1 Pricing Parity — active field inertness unproven
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 
- issue: The spec (§2.1, CR-11) requires that the `active` column is inert in resolve — a row with `active=false` must still be returned. No test seeds a row with `active=false` and asserts it is found by `PriceLookup.resolve`. All current seeds pass `active:true`. This is a golden-master contract gap: if someone accidentally adds a guard on `active` in the query, every test still passes but the billing engine breaks.
- fix: Add a test `ac1_activeInert_falseActiveRowStillResolves`: seed a RADIOLOGY (or any kind) insurance row with `active=false`, then assert `priceLookup.resolve(planUid, RADIOLOGY, svcUid, 'TZS')` returns that row's amount unchanged.

### [HIGH] Audit trail — ServicePrice, Coefficient, Affiliation mutations not covered
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 
- issue: Build-spec §5.5 requires every create/update/delete to write an `audit_logs` row. ServicePriceIT, CoefficientMathIT, InsurancePlanIT (plans created via helper but audit not asserted), ClinicClinicianAffiliationIT, SupplierItemPriceIT, CurrencyIT, DocumentTypeIT — none assert `AuditAction.CREATE` rows for their respective `masterdata.*` entity types. ClinicIT, WardIT, WardBedIT, PharmacyIT, TheatreIT, MedicineIT, ItemIT, LabTestTypeIT, DiagnosisTypeIT do assert audit rows. The gap means the audit completeness requirement is unproven for 7+ entity types. Per operating principle: audit trail completeness is a hard requirement; a release cannot be signed off with this gap.
- fix: In ServicePriceIT.ac1_registration_insuranceHitAndCashFallback (or a dedicated audit test), inject AuditLogRepository and assert `rows.anyMatch(r -> r.getEntityUid().equals(createdUid) && r.getAction() == AuditAction.CREATE)` for entityType 'masterdata.ServicePrice'. Do the same for ItemMedicineCoefficient in CoefficientMathIT, and for InsurancePlan in InsurancePlanIT (plan creation helper). Affiliation add/remove should map to Clinician CREATE/UPDATE events per agreed audit policy — assert those in ClinicClinicianAffiliationIT.

### [HIGH] Missing ITs — Store, Supplier, ItemSupplier, WardType, WardCategory, RadiologyType, ProcedureType controllers have zero negative-auth coverage
- backend/src/test/java/com/otapp/hmis/masterdata/ 
- issue: There are no IT files for: StoreController, SupplierController, ItemSupplierController, WardTypeController, WardCategoryController, RadiologyTypeController, ProcedureTypeController. These controllers exist and have gated endpoints (`ADMIN-ACCESS`), but the test matrix has a complete hole for 401/403 negative-auth, 201+Location, no-id-in-response, and audit-row assertions for these 7 entity groups. The spec's AC-8 gate-coverage requirement covers 'every mutation endpoint group'. PrivilegeGateArchTest confirms the codes are legal but does not substitute for runtime 401/403 assertions.
- fix: Create StoreIT, SupplierIT, ItemSupplierIT, WardTypeIT, WardCategoryIT, RadiologyTypeIT, ProcedureTypeIT — each following the same pattern as ClinicIT/MedicineIT: 401 without token, 403 without ADMIN-ACCESS, 201+Location+no-id with ADMIN-ACCESS, audit row on create.

### [HIGH] AC-3 Coefficient — missing 401/403 gate tests and missing-coefficient NotFound
- backend/src/test/java/com/otapp/hmis/masterdata/CoefficientMathIT.java 
- issue: CoefficientMathIT tests math precision and 400/409, but: (1) No 401 (no token) test; (2) No 403 (missing ADMIN-ACCESS) test — the DEVIATION-2 gate must be runtime-verified, not just ArchUnit; (3) No test for GET /item-medicine-coefficients/uid/{unknownUid} → 404 (missing-coefficient NotFound as called out explicitly in AC-3). All three are in scope per spec §5.7 AC-3 and the gate-coverage requirement AC-8.
- fix: Add to CoefficientMathIT: `create_withoutToken_returns401`, `create_withoutAdminAccess_returns403` (use DAY-ACCESS token), and `getByUid_unknownUid_returns404`.

### [MEDIUM] AC-1 Pricing — `currency` inertness unproven at resolve layer
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 
- issue: The spec (CR-11) states `currency` is inert in resolve behaviour. The existing tests all pass `currency='TZS'` into both seed and resolve, which proves lookup works for TZS but does NOT prove the field is inert (i.e., that it is a lookup discriminator only, not a pricing multiplier). The inert-fields test only covers min/max. There is no test that seeds a row in a non-default currency and asserts the resolve returns the stored amount without any currency conversion — but more critically, there is no test that proves a resolve miss due to currency mismatch raises 422 rather than silently returning a wrong-currency row. This gap could mask a future bug where currency is treated as a pricing multiplier.
- fix: Add `ac1_currencyMismatch_returns422`: seed a LAB_TEST row for planUid + currency='USD', then call `resolve(planUid, LAB_TEST, svcUid, 'TZS')` and assert 422 `service-price-not-found`. This proves currency IS a lookup discriminator and is NOT a conversion multiplier — satisfying both sides of the inert-but-meaningful contract.

### [MEDIUM] AC-5 Uniqueness — DELETE not tested on ServicePrice; no audit on delete
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 
- issue: The build-spec §3 gate map states 'All 7 per-service plan-price write/delete' are gated with ADMIN-ACCESS. ServicePriceController has no DELETE endpoint in the current code (confirmed by grep), which means the gate map's delete coverage is not implemented and therefore not tested. If a DELETE is added in a later phase, the 401/403/204 tests will be missing. Additionally, ServicePriceIT has no 401 test for GET /resolve — the test `ac1_missingBoth_resolve_returns422` uses `DAY-ACCESS` but never tests the unauthenticated path.
- fix: Add `resolve_withoutToken_returns401`. If a DELETE endpoint is added to ServicePriceController, add the corresponding 401/403/204 tests in the same increment, not later.

### [MEDIUM] AC-3 Coefficient — UPDATE path not tested (401, 403, 200, audit row)
- backend/src/test/java/com/otapp/hmis/masterdata/CoefficientMathIT.java 
- issue: ItemMedicineCoefficientController has a PUT endpoint gated ADMIN-ACCESS. CoefficientMathIT only exercises POST (create) paths. No test exercises PUT to verify: (a) an update returns 200 with recalculated coefficient, (b) update with zero quantities still returns 400, (c) 403 without ADMIN-ACCESS, (d) audit row of AuditAction.UPDATE written. The spec §5.3 states `coefficient = medicineQty/itemQty` computed in service on save — this should apply to updates too.
- fix: Add an update test: create a coefficient (3,1 → 0.333333), then PUT with (2,1 → 0.500000) and assert the stored value changes. Also assert 403 without ADMIN-ACCESS on PUT.

### [MEDIUM] AC-5 Uniqueness — ServicePrice 409 response body asserts problem type but not error code field
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 261, 283, 306, 328
- issue: The 409 assertions only check `jsonPath('$.type')`. For consistency with the BusinessDayIT pattern (which asserts both `$.type` and `$.code`) and to prevent a future change that emits the right type but wrong HTTP body shape, these assertions should also verify `$.status` equals 409 and optionally the `$.title`. This is a NIT-level quality gap, not a functional one.
- fix: Chain `.andExpect(jsonPath('$.status').value(409))` after each 409 conflict assertion in ac5_* tests.

### [MEDIUM] PrivilegeGateArchTest — counts 26 live codes in Javadoc but build-spec §3 lists 35 distinct / 26 live
- backend/src/test/java/com/otapp/hmis/arch/PrivilegeGateArchTest.java 28-55
- issue: The PrivilegeGateArchTest comment says '26 live codes (build-spec §1)' but the build-spec §3 states '35 distinct (26 live)'. The set in the test appears correct (26 entries), but does NOT include `DAY-ACCESS` — yet `DAY-ACCESS` appears in BusinessDayIT as the gate for open/close, and in BusinessDayAdminController. If `@PreAuthorize('hasAnyAuthority("DAY-ACCESS")')` exists in production code, the ArchTest would correctly pass it (DAY-ACCESS IS present in the set — line 29). This is a documentation inconsistency only. However, the more critical issue: the test's pattern `'([A-Z][A-Z0-9_\\-]*)'` does not match lowercase codes, but all 26 codes are uppercase, so this is safe. Verify the test's LIVE_CODES set matches exactly the 26 live codes from build-spec §1 — specifically, confirm whether `STORE_ORDER-ALL` and the `PHARMACY_ORDER-*` triple are all in the set.
- fix: Add a comment referencing the authoritative list source (build-spec §1, ADR-0006) and verify LIVE_CODES matches the 26 codes listed there exactly.

### [MEDIUM] Audit — UPDATE path not asserted in any test for any entity
- backend/src/test/java/com/otapp/hmis/masterdata/ 
- issue: Every IT that checks audit logs only asserts `AuditAction.CREATE`. No IT asserts an `AuditAction.UPDATE` row is written after a PUT call, except CompanyProfileIT which asserts UPDATE for the singleton profile. For all other entities (Clinic, Ward, WardBed, Medicine, Item, LabTestType, DiagnosisType, InsuranceProvider, InsurancePlan) the UPDATE path through `AuditRecorder.record(entityType, uid, UPDATE)` is untested. A regression that drops the UPDATE call would not be caught.
- fix: In at least one representative IT per entity group, extend the existing update test to also assert `auditLogRepository` contains an UPDATE row for the entity's uid after the PUT call. ClinicIT's `update_withAdminAccess_returns200WithUpdatedFields` is the obvious anchor — add the audit assertion there as a model.

### [MEDIUM] CompanyProfileIT — GET asserts AuditAction.READ but no other entity asserts READ audit
- backend/src/test/java/com/otapp/hmis/masterdata/CompanyProfileIT.java 64-70
- issue: CompanyProfileIT.get_withAdminAccess_returns200AndSeededRow asserts `AuditAction.READ` on GET. This READ-on-GET instrumentation is not mentioned in build-spec §5.5 (which covers create/update/delete only). If CompanyProfile GET deliberately instruments a READ event as a sensitivity measure, that is a net-new behaviour. If it is an accident, it will cause every other entity's GET to appear audit-deficient by contrast. The assertThat on the `last` row (not `anyMatch`) is also fragile — if any audit event fires between the GET and the assertion, the last row may not be the READ. Additionally, `assertThat(last.getChecksum()).isNotBlank()` is checked only here; there is no equivalent checksum assertion in any other entity test.
- fix: Decide whether READ events are in scope for CompanyProfile specifically or all entities. If READ is intentional only for CompanyProfile, add a Javadoc explaining why. Replace the `rows.get(rows.size()-1)` fragile access with `anyMatch` for the READ+actor assertion. If READ is NOT part of the agreed audit scope, remove the assertion and move the `checksum` coverage to a dedicated audit-integrity test.

### [NIT] DocumentTypeIT — sequence start-at-1 assertion weakened by shared container
- backend/src/test/java/com/otapp/hmis/masterdata/DocumentTypeIT.java 185-189
- issue: The Javadoc correctly acknowledges that in a shared Testcontainers singleton, `nextval` returns values >= 1, not exactly 1. The assertions are therefore `isGreaterThanOrEqualTo(1L)` rather than `isEqualTo(1L)`. This is correct and pragmatic, but the spec AC-7 says 'first nextval returns 1'. The test as written does not fail if a migration already consumed some sequence values (e.g., if seed data calls `nextval` during V7). The increment-by-1 assertion does cover monotonicity. Consider adding a `SELECT last_value, start_value FROM pg_sequences WHERE sequencename = ?` query to assert `start_value = 1` without consuming nextval.
- fix: Add a helper `assertSequenceStartValue(String seqName)` that queries `pg_sequences.start_value = 1` and `pg_sequences.minimum_value = 1` without calling nextval. This is non-destructive and exactly proves AC-7.

### [NIT] ServicePriceIT — resolve GET test uses DAY-ACCESS (undocumented in spec) for read-ungated endpoint
- backend/src/test/java/com/otapp/hmis/masterdata/ServicePriceIT.java 189
- issue: The 422 miss test uses a `DAY-ACCESS` token. The spec says reads are ungated by role (require only a valid JWT). Using `DAY-ACCESS` is not wrong, but inconsistent with how list tests in other ITs use `DAY-ACCESS` as the 'any valid user' token. There is no corresponding test asserting `resolve` without a token returns 401, which is a minor gap — any authenticated user should be able to call resolve, but unauthenticated callers should get 401.
- fix: Add `resolve_withoutToken_returns401` test (one line).

### [NIT] CoefficientMathIT — naïve multiplication documented as 0.999999 but not explicitly asserted to scale 6
- backend/src/test/java/com/otapp/hmis/masterdata/CoefficientMathIT.java 100-102
- issue: The assertion `assertThat(new BigDecimal('3').multiply(stored)).isLessThan(new BigDecimal('1.000000'))` proves the rounded-coefficient path loses parity but does not assert the exact value 0.999999. If a future change stores coefficient at scale 5 (0.33333), this assertion would still pass (3 × 0.33333 = 0.99999 < 1.000000), but the scale-6 invariant would be broken. The scale-6 assertion on line 87 covers this, so this is cosmetic.
- fix: Optionally, make the naïve path assertion exact: `isEqualByComparingTo('0.999999')` instead of `isLessThan('1.000000')`.
