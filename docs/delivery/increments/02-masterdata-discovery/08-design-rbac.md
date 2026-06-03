I now have the complete and verified picture. Note that `SUPPLIER_PRICE_LIST-ALL`, `MEDICINE_STOCK-UPDATE`, `ITEM_STOCK-UPDATE` are the actual gate tokens used (matching the leading token in Object_.java) and are live legacy codes. `PROCUREMENT-ACCESS` and `ROLE-CREATE` (the two commented-out ones) are confirmed DEAD per MEMORY. Here is the deliverable.

---

# Increment-02 Masterdata RBAC Gate Map (legacy-verified)

All citations are to legacy `com.orbix.api/api/*Resource.java`. Mutation = POST/PUT/DELETE; reads = GET. Only the 35 legacy codes may be used; the live gate codes appearing in masterdata controllers are exactly four: `ADMIN-ACCESS`, `MEDICINE_STOCK-UPDATE`, `ITEM_STOCK-UPDATE`, `SUPPLIER_PRICE_LIST-ALL` (all confirmed registered in `security/Object_.java`).

## Endpoint-group -> gate map (mutations)

| Increment-02 endpoint group | Legacy mutation endpoint(s) | Legacy gate (verbatim) | Gate to use in inc-02 | Status |
|---|---|---|---|---|
| Clinic write | `/clinics/save` | `ADMIN-ACCESS` (ClinicResource:72) | `ADMIN-ACCESS` | exact |
| Ward write + bed CRUD/activate | `/wards/save`, `/register_bed`, `/update_bed`, `/activate_bed`, `/deactivate_bed` | `ADMIN-ACCESS` (WardResource:115,126,159,169,179) | `ADMIN-ACCESS` | exact |
| WardType write | `/ward_types/save` | `ADMIN-ACCESS` (WardTypeResource:68) | `ADMIN-ACCESS` | exact |
| WardCategory write | `/ward_categories/save` | `ADMIN-ACCESS` (WardCategoryResource:74) | `ADMIN-ACCESS` | exact |
| Pharmacy write | `/pharmacies/save` | `ADMIN-ACCESS` (PharmacyResource:122) | `ADMIN-ACCESS` | exact |
| Pharmacy stock update | `/pharmacies/update_stock` | `MEDICINE_STOCK-UPDATE` (PharmacyResource:200) | `MEDICINE_STOCK-UPDATE` | exact |
| Store write + item-register update | `/stores/save`, `/stores/update_store_item_register` | `ADMIN-ACCESS` (StoreResource:108,119) | `ADMIN-ACCESS` | exact |
| Store stock update | `/stores/update_stock` | `ITEM_STOCK-UPDATE` (StoreResource:173) | `ITEM_STOCK-UPDATE` | exact |
| Theatre write | `/theatres/save` | `ADMIN-ACCESS` (TheatreResource:78) | `ADMIN-ACCESS` | exact |
| Medicine write + (de)activate | `/medicines/save`, `/activate_medicine`, `/deactivate_medicine` | `ADMIN-ACCESS` (MedicineResource:103,129,140) | `ADMIN-ACCESS` | exact |
| LabTestType write | `/lab_test_types/save` | `ADMIN-ACCESS` (LabTestTypeResource:74) | `ADMIN-ACCESS` | exact |
| LabTestTypeRange write/delete | `/lab_test_type_ranges/save`, `/delete` | `ADMIN-ACCESS` (LabTestTypeRangeResource:72,101) | `ADMIN-ACCESS` | exact |
| RadiologyType write | `/radiology_types/save` | `ADMIN-ACCESS` (RadiologyTypeResource:74) | `ADMIN-ACCESS` | exact |
| ProcedureType write | `/procedure_types/save` | `ADMIN-ACCESS` (ProcedureTypeResource:78) | `ADMIN-ACCESS` | exact |
| DiagnosisType write | `/diagnosis_types/save` | `ADMIN-ACCESS` (DiagnosisTypeResource:74) | `ADMIN-ACCESS` | exact |
| InsuranceProvider write | `/insurance_providers/save` | `ADMIN-ACCESS` (InsuranceProviderResource:74) | `ADMIN-ACCESS` | exact |
| InsurancePlan header write | `/insurance_plans/save` | `ADMIN-ACCESS` (InsurancePlanResource:143) | `ADMIN-ACCESS` | exact |
| Consultation plan price write/delete | `/consultation_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (ConsultationInsurancePlanResource:71,103) | `ADMIN-ACCESS` | exact |
| Registration plan price write/delete | `/registration_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (RegistrationPlanResource:66,98) | `ADMIN-ACCESS` | exact |
| Lab plan price write/delete | `/lab_test_type_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (LabTestTypePlanResource:69,103) | `ADMIN-ACCESS` | exact |
| Medicine plan price write/delete | `/medicine_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (MedicinePlanResource:79,111) | `ADMIN-ACCESS` | exact |
| Procedure plan price write/delete | `/procedure_type_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (ProcedurePlanResource:73,105) | `ADMIN-ACCESS` | exact |
| Radiology plan price write/delete | `/radiology_type_insurance_plans/save`, `/delete` | `ADMIN-ACCESS` (RadiologyPlanResource:68,100) | `ADMIN-ACCESS` | exact |
| Supplier write | `/suppliers/save` | gate COMMENTED OUT (SupplierResource:106) — effectively UNGATED | `ADMIN-ACCESS` (recommended) | DEVIATION |
| SupplierItemPrice write/delete/save_or_update | `/supplier_item_prices/{save,delete,save_or_update}` | `SUPPLIER_PRICE_LIST-ALL` (SupplierItemPriceResource:100,117,144) | `SUPPLIER_PRICE_LIST-ALL` | exact |
| CompanyProfile write + logo | `/company_profile/save`, `/save_logo` | `ADMIN-ACCESS` (CompanyProfileResource:59,68) | `ADMIN-ACCESS` | exact |
| Clinician write | `/clinicians/save` (also clinic-clinician affiliation, set via Clinician.clinics) | `ADMIN-ACCESS` (ClinicianResource:80) | `ADMIN-ACCESS` | exact |
| StorePerson write | `/store_persons/save` | `ADMIN-ACCESS` (StorePersonResource:78) | `ADMIN-ACCESS` | exact |
| Item write | `/items/save` | gate COMMENTED OUT — `PROCUREMENT-ACCESS` (ItemResource:187), a DEAD code | `ADMIN-ACCESS` (recommended) | DEVIATION |
| ItemMedicineCoefficient write | `/item_medicine_coefficients/save` | gate COMMENTED OUT — `ROLE-CREATE` (ConversionCoefficientResource:70), a DEAD code | `ADMIN-ACCESS` (recommended) | DEVIATION |

## DEVIATIONS requiring an engagement-lead decision (no usable live legacy gate)

These three masterdata write paths are EFFECTIVELY UNGATED in legacy (annotation commented out, and where present it references a DEAD code). Reproducing "ungated" exactly would expose masterdata mutations to any authenticated user — a security regression. Recommend gating all three with `ADMIN-ACCESS` (consistent with every sibling masterdata write) and recording as a ratified deviation; do NOT silently invent a new code.

1. **Item write** (`/items/save`): commented `PROCUREMENT-ACCESS` — DEAD (not in the 35). Recommended gate: `ADMIN-ACCESS`.
2. **ItemMedicineCoefficient write** (`/item_medicine_coefficients/save`): commented `ROLE-CREATE` — DEAD. Recommended gate: `ADMIN-ACCESS`.
3. **Supplier write** (`/suppliers/save`): commented `ADMIN-ACCESS` (the intended code is live and correct; only the annotation is disabled). Recommended gate: `ADMIN-ACCESS` (re-enable). Lowest-risk of the three.

Note: `SupplierItemPrice` IS gated (`SUPPLIER_PRICE_LIST-ALL`) even though `Supplier` itself is not — preserve that asymmetry only if the deviation is rejected; otherwise both become `ADMIN-ACCESS`/their live codes.

Also flag (already in extraction-2 decisions): `savePharmacySaleOrderDetail` and `/pharmacies/save_pharmacy_sale_order` have their `ADMIN-ACCESS` commented out (PharmacyResource:298) — these are pharmacy-sale transactional endpoints, out of masterdata scope, but the same deviation pattern applies if inc-02 surfaces them.

## Reads — authentication posture

- **No legacy masterdata GET carries any `@PreAuthorize`.** Every read (`/clinics`, `/wards`, `/medicines`, `/items`, `/insurance_plans`, `/suppliers`, `*/get`, `*/get_names`, `load_*_like`, etc.) is open to any authenticated principal. Reproduce as authenticated-but-unauthorized-by-role (require a valid JWT; no role gate) — masterdata reads are reference/catalog data.
- **PII-bearing reads to flag for tighter handling (authentication mandatory, and these must be excluded from any anonymous/actuator exposure):**
  - `/clinicians/*` and `/store_persons/*` — return staff identity (name, username linkage via `assign_user_profile`); these touch the IAM boundary. Treat as authenticated-only; never expose in public/health endpoints.
  - `/company_profile/get`, `/get_logo` — org banking blocks (3 bank-account sets: account name/no/bank) are sensitive financial PII. Keep authenticated; do NOT leak bank fields into logs or non-admin DTOs.
  - The insurance coverage/price reads (`get_*_prices`, `get_*_cash_prices`) expose negotiated insurer pricing — authenticated-only; not anonymous.

## Additional security finding — UNGATED insurance coverage/price mutations (HIGH)

`InsurancePlanResource` has only ONE `@PreAuthorize` (line 143, on `/insurance_plans/save`). All 14 coverage/price MUTATION endpoints are UNGATED:
`change_lab_test_type_coverage`, `update_lab_test_type_price_by_insurance`, `change_procedure_type_coverage`, `update_procedure_type_price_by_insurance`, `change_radiology_type_coverage`, `update_radiology_type_price_by_insurance`, `change_medicine_coverage`, `update_medicine_price_by_insurance`, `change_consultation_coverage`, `update_consultation_price_by_insurance`, `change_registration_coverage`, `update_registration_price_by_insurance`, `change_ward_type_coverage`, `update_ward_type_price_by_insurance` (POSTs at InsurancePlanResource:206,240,338,372,469,503,600,634,730,764,863,891,986,1020).

These directly mutate insurer pricing/coverage (financial-impact data) yet require no authority. The dedicated per-service plan resources (`ConsultationInsurancePlanResource`, etc.) gate the same data with `ADMIN-ACCESS`. This is an inconsistent, almost-certainly-unintended legacy gap. **Recommendation:** gate all 14 with `ADMIN-ACCESS` in inc-02; record as a ratified deviation (DEVIATION-4). Do not reproduce ungated.

## Summary for the inc-02 spec's "privilege-code completeness" gate

- Legacy masterdata uses only 4 distinct live codes: `ADMIN-ACCESS` (dominant), `MEDICINE_STOCK-UPDATE`, `ITEM_STOCK-UPDATE`, `SUPPLIER_PRICE_LIST-ALL` — all four exist in `iam_privilege` (the 35). No invented codes required for any gated endpoint.
- 4 deviations need engagement-lead ratification, all resolvable WITHOUT new codes (gate with the existing `ADMIN-ACCESS`): Item write, ItemMedicineCoefficient write, Supplier write, and the 14 ungated InsurancePlan coverage/price mutations.
- This map must be reviewed by **legacy-analyst** (confirm I have not missed any masterdata controller mutation) and by **engagement-lead** (ratify the 4 deviations) before backend-engineer implements `@PreAuthorize` on inc-02 endpoints.

Source files (all under `D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api\`): `api\*Resource.java` (cited per row), `security\Object_.java` (privilege-code registry, lines 20/52/53/57).