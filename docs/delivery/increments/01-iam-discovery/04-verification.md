# Adversarial code verification

isExactly35: false
confirmedActiveCount: 26

## Discrepancies (dead/commented-only codes)
- BILL-A — DEAD (commented-only): sole occurrence is //@PreAuthorize at PatientBillResource.java:270. Never an active gate. Listed in the 35 but not a real authorization gate.
- GOO-ALL — DEAD (commented-only): sole occurrence //@PreAuthorize at GoodsReceivedNoteResource.java:71. Active gate at GRN:98 uses GOODS_RECEIVED_NOTE-* instead. Not a real gate.
- PATIENT-A — DEAD (commented-only): appears only in //@PreAuthorize('PATIENT-A','PATIENT-C','PATIENT-U') at PatientResource.java:572,595,1975,5184,5214,5225,5238,5254,5293,5313,5330,5393,5537,5563,5579,5694,5778,5822,5838. Active patient gates use PATIENT-ALL/PATIENT-CREATE/PATIENT-UPDATE. Not a real gate.
- PATIENT-C — DEAD (commented-only): same commented lines as PATIENT-A in PatientResource.java. Not a real gate.
- PATIENT-U — DEAD (commented-only): same commented lines as PATIENT-A in PatientResource.java. Not a real gate.
- PRODUCT-CREATE — DEAD (commented-only): appears only in //@PreAuthorize at PatientResource.java:1932,5942,5961,6011,6075,6094,6144. Not a real gate.
- ROLE-CREATE — DEAD (commented-only): sole occurrence //@PreAuthorize at ConversionCoefficientResource.java:70. Not a real gate.
- ROLE-U — DEAD (commented-only): sole occurrence //@PreAuthorize at UserResource.java:445. Active role gates use ROLE-ALL. Not a real gate.
- PROCUREMENT-ACCESS — DEAD (commented-only): sole occurrence //@PreAuthorize at ItemResource.java:187. Not a real gate.

## Verdict
## Verdict: REFUTED — the set is NOT "exactly 35 active distinct gate codes"

**Conclusion.** The string-literal *membership* of the claimed 35 is accurate (I found no code outside the list, and no listed code is wholly absent from the source). BUT the figure **35 conflates active authorization gates with commented-out dead code**. Counting only codes that gate at least one endpoint via a *live* `@PreAuthorize`, the correct number is **26**. The remaining **9** codes appear EXCLUSIVELY inside `//`-commented `@PreAuthorize` lines and gate nothing.

**The 9 dead (commented-only) codes** — must NOT be seeded/implemented as gates: `BILL-A`, `GOO-ALL`, `PATIENT-A`, `PATIENT-C`, `PATIENT-U`, `PRODUCT-CREATE`, `ROLE-CREATE`, `ROLE-U`, `PROCUREMENT-ACCESS`. (See extraOrMissingCodes for file:line.)

**The 26 genuinely-active gate codes** (each cited to a live annotation):
`ADMIN-ACCESS` (ClinicResource.java:72 +~90 more), `DAY-ACCESS` (DayResource.java:50), `EMPLOYEE-ALL` (AssetResource.java:60; EmployeeResource.java:83), `GOODS_RECEIVED_NOTE-ALL`/`-CREATE` (GoodsReceivedNoteResource.java:98), `GOODS_RECEIVED_NOTE-UPDATE` (GRN:177), `GOODS_RECEIVED_NOTE-APPROVE` (GRN:274), `LOCAL_PURCHASE_ORDER-ALL` (LocalPurchaseOrderResource.java:60), `LOCAL_PURCHASE_ORDER-CREATE`/`-UPDATE` (LPO:83), `PHARMACY_ORDER-ALL` (InternalOrderResource.java:135), `PHARMACY_ORDER-CREATE`/`-UPDATE` (InternalOrder:125), `STORE_ORDER-ALL` (InternalOrder:445), `PATIENT-ALL`/`PATIENT-CREATE` (PatientResource.java:289), `PATIENT-UPDATE` (Patient:379), `PAYROLL-ALL` (PayrollResource.java:48), `PAYROLL-CREATE`/`-UPDATE` (Payroll:71), `SUPPLIER_PRICE_LIST-ALL` (SupplierItemPriceResource.java:100), `ITEM_STOCK-UPDATE` (StoreResource.java:173), `MEDICINE_STOCK-UPDATE` (PharmacyResource.java:200), `USER-ALL` (UserResource.java:146), `USER-UPDATE` (UserResource.java:336), `ROLE-ALL` (UserResource.java:213).

**Method (independent, multi-strategy).**
1. Counted authority expressions: `hasAuthority|hasAnyAuthority|hasRole|hasAnyRole` = 178 occurrences across 46 files; `@PreAuthorize|@Secured|@RolesAllowed|@PostAuthorize` = 177 across 45 files. The 1-line delta is a single COMMENTED `hasAnyAuthority("dfgh")` in SecurityConfig.java:91 (dead test string, not a gate).
2. Read every one of the 178 lines verbatim and split active vs. `//`-commented.
3. Verified the 9 suspect codes occur ONLY in commented lines via a dedicated grep — confirmed: zero active occurrences for all 9.
4. Checked alternative gate vectors: `@Secured`/`@RolesAllowed` = none; SecurityConfig `antMatchers` only declare `permitAll()` (swagger, login, token/refresh, /wms/**) — no `.hasAuthority` in live config; `CustomAuthorizationFilter` builds authorities dynamically from the JWT `privileges` claim via `new SimpleGrantedAuthority(role)` (no hardcoded codes); `UserServiceImpl` maps `privilege.getName()` from DB (no literals). The `controllers/service/*` `Authorize.authorize(Authority.getAuthority(Objects.X, Actions.Y))` scheme is entirely commented out and its support types are not even live classes.
5. Multiline grep for split/concatenated SpEL and constant/variable arguments: every live `@PreAuthorize` argument is a single-line string literal `hasAnyAuthority('CODE'...)` — no constants, no concatenation, no hidden construction.
6. `hasRole`/`hasAnyRole` and ROLE_ prefixes: none present anywhere.

**Recommendation for increment 01 (exact-process).** Seed/gate with the **26 active codes**. The 9 commented-only codes are dead legacy intent; under "exact process" they gate nothing today and should NOT be reproduced as live gates. If the firm wants the full historical vocabulary preserved as inert reference, record it in the ambiguity register and route a change-request to the engagement-lead — do not silently activate them. The MEMORY "35 distinct authority codes" note should be amended to "35 distinct authority *string literals* (26 active gates + 9 commented-only/dead)".