## EXTRACTION 5 — Stakeholders + System/Reference (CompanyProfile, Currency, Document-Type & Document-Number model, BusinessDay)

All citations are to the legacy source of truth (READ-ONLY): `com.orbix.api` under `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/`.

---

### 1. CompanyProfile — every legacy field

Entity: `domain/CompanyProfile.java:30` — `@Table(name = "company_profile")`. Single-table, identity PK (`id BIGINT`, `CompanyProfile.java:31-33`).

Full field list (all are bare `String` unless noted; **no `@Column` length/precision overrides except `@Lob` logo**):
- `companyName` — `@NotBlank` (`CompanyProfile.java:34-35`)
- `contactName` — `@NotBlank` (`CompanyProfile.java:36-37`)
- `logo` — `byte[]`, `@Lob` (`CompanyProfile.java:38-39`). Stored compressed; `CompanyProfileServiceImpl.decompressBytes` exists (`CompanyProfileServiceImpl.java:121`) but the decompress call on read is **commented out** (`CompanyProfileServiceImpl.java:115`).
- `tin` (Tax Identification Number) (`CompanyProfile.java:40`)
- `vrn` (VAT Registration Number) (`CompanyProfile.java:41`)
- Address block: `physicalAddress`, `postCode`, `postAddress`, `telephone`, `mobile`, `email`, `website`, `fax` (`CompanyProfile.java:42-49`)
- **Three bank-account blocks** (1/2/3), each 6 fields: `bankAccountName{,2,3}`, `bankPhysicalAddress{,2,3}`, `bankPostCode{,2,3}`, `bankPostAddress{,2,3}`, `bankName{,2,3}`, `bankAccountNo{,2,3}` (`CompanyProfile.java:51-70`)
- `quotationNotes`, `salesInvoiceNotes` — free text; `@Length(max=2000)` is **commented out** (`CompanyProfile.java:72-75`)
- `registrationFee` — **`double`, default `0`** (`CompanyProfile.java:77`). This is the default cash-patient registration fee (see §6).
- `publicPath` — `String` (`CompanyProfile.java:78`); base path used for public/logo asset references.
- `employeePrefix` — `String`, **default `"EMP"`** (`CompanyProfile.java:80-81`). The ONLY document-prefix configured on CompanyProfile (consumed in §5).

**Important: there is NO `defaultCurrency` field, NO currency code, NO fiscal-year / sequence-reset config, and NO document-type/prefix table referenced from CompanyProfile.** Modernization note: `registrationFee` is `double` → migrate to `NUMERIC(19,2)` per pre-approved double→BigDecimal directive.

**CompanyProfile is a singleton-by-convention.** `CompanyProfileServiceImpl.saveCompanyProfile` (`CompanyProfileServiceImpl.java:36-99`): loads `findAll()`, counts rows; if `>1` rows OR incoming `id == null` it calls `deleteAll()` first, then keeps exactly one row. `validateCompany(...)` (`CompanyProfileServiceImpl.java:101-107`) **always returns `true`** — there is no field validation despite the `@NotBlank` JPA-bean-validation annotations (those fire only on the persistence layer). `getCompanyProfile` (`CompanyProfileServiceImpl.java:109-119`) returns the first row from `findAll()`, or a brand-new empty `CompanyProfile()` if none exists (so callers may see default field values — notably `employeePrefix="EMP"`, `registrationFee=0`).

REST surface: `api/CompanyProfileResource.java` (gates not in scope of this extraction).

---

### 2. Currency concept — DOES NOT EXIST as a modeled concept

- **There is no `Currency` entity, no `currency` field, and no `TZS`/`TSH`/`Tsh` string literal anywhere in `com.orbix.api`.** Verified by case-insensitive scan across the whole package: the only apparent hit (`api/ItemResource.java`) is a **false positive** — it matched the substring "tsh" inside `setShortName`/`setShortName` lines (`ItemResource.java:192`), not a currency.
- All monetary amounts are stored as bare `double` (e.g., `CompanyProfile.registrationFee` at `CompanyProfile.java:77`; `RegistrationInsurancePlan.registrationFee` `double` at `RegistrationInsurancePlan.java:42`) with **no associated currency code, no currency column, and no formatting locale captured in the domain**. Currency is effectively implicit/hardcoded-to-locale at the UI/report layer only — it is NOT in the backend domain model.
- **DECISION for data-architect:** target build must decide whether to (a) keep currency implicit (single-currency system, exact legacy behaviour) or (b) introduce an explicit currency. Per "exact process" this should remain implicit single-currency unless the engagement-lead approves a change request. Do NOT invent a Currency entity in masterdata without an approved CR.

---

### 3. Document-type model — NO `document_type` table exists

- **There is no `document_type` entity/table and no `DocumentType` class anywhere in `com.orbix.api`.** Verified by scan for `document_type` / `documentType` / `DocumentType` / `@Table(name="document...")` — zero matches.
- Document prefixes are NOT data-driven. With the single exception of `employeePrefix` (configurable on CompanyProfile), **every document prefix is a hardcoded String literal inside the relevant `*ServiceImpl` class.** There is no central registry, no fiscal-year reset table, and no prefix-management UI backing entity.

---

### 4. Document-NUMBER generation scheme (formatter + counter)

**Single shared formatter:** `accessories/Formater.java`.
- `formatWithCurrentDate(prefix, suffix)` (`Formater.java:14-17`): returns `prefix + yyyyMMdd + "-" + suffix`. The date is `LocalDateTime.now()` formatted `yyyyMMdd`. **The `suffix` (the running id) is NOT zero-padded by this method.**
- `formatSix(value)` (`Formater.java:36-52`): left-pads `value` with `0` to length 6, then inserts ONE dash at index 3 → e.g. `5` → `000005` → `000-005`.
- `formatThree` (`:53-68`): zero-pad to 3, no dash. `formatFive` (`:69-85`): zero-pad to 5, dash at index 3. `formatNine` (`:18-35`): zero-pad to 9, dashes at indices 6 and 3 (→ `NNN-NNN-NNN`). `formatNinePlain` (`:87-102`): zero-pad to 9, no dashes.

**Counter mechanism — application-level `MAX(id)+1`, NOT a DB sequence.** Every generator follows the identical pattern: `Long id = 1L; try { id = <repo>.getLastId() + 1; } catch(Exception e){}` then format. `getLastId()` is a JPQL `SELECT MAX(<alias>.id) FROM <Entity>` per repository (e.g. `StoreToPharmacyTORepository.java:20-21`, `GoodsReceivedNoteRepository.java:23-24`, `LocalPurchaseOrderRepository.java:21-22`). **This is the same pattern as the IAM `USR-NNN-NNN` scheme** (`UserServiceImpl.requestUserCode` `:658-669`: `"USR-" + Formater.formatSix(MAX(id)+1)` → e.g. `USR-000-005`).

**Concurrency / edge-case warnings (preserve behaviour but flag):**
- `MAX(id)+1` is NOT collision-safe under concurrency and is NOT a true monotonic sequence per prefix — it is global per-table on the surrogate `id`. Two concurrent requests can compute the same number.
- If the table is empty, `MAX(id)` returns `null`; the `+1` would NPE, but the `catch(Exception e){}` swallows it and leaves `id = 1L` (so the first document of an empty table is `...-1`).
- The numbering uses `MAX(id)+1` (the *next* surrogate id), so the document number embeds a value that is assumed to equal the row's eventual `id` — but for entities where the row is `save()`d FIRST with a placeholder and re-numbered after (GRN below), the embedded number may differ from the actual `id`. Flag for qa-test-engineer.

**Per-document prefix table (all use `formatWithCurrentDate`, i.e. `PREFIX + yyyyMMdd + "-" + (MAX(id)+1)`, no padding on the id):**

| Document | Prefix (literal) | Source file:line | Counter repo query |
|---|---|---|---|
| Goods Received Note | `GRN` | `GoodsReceivedNoteServiceImpl.java:213` | `GoodsReceivedNoteRepository.java:23` `MAX(grn.id)` |
| Local Purchase Order | `LPO` | `LocalPurchaseOrderServiceImpl.java:332` | `LocalPurchaseOrderRepository.java:21` `MAX(lpo.id)` |
| Patient Credit Note | `PCN` | `PatientCreditNoteServiceImpl.java:39` | (PatientCreditNote repo) |
| Payroll | `PRL` | `PayrollServiceImpl.java:406` | (Payroll repo) |
| Store→Pharmacy Transfer Order | **`SPT`** | `StoreToPharmacyTOServiceImpl.java:438` | `StoreToPharmacyTORepository.java:20` `MAX(s.id)` |
| Pharmacy→Pharmacy Transfer Order | **`SPT`** | `PharmacyToPharmacyTOServiceImpl.java:428` | `PharmacyToPharmacyTORepository` `MAX(...)` |
| Store→Pharmacy Received Note (RN) | `PGRN` | `StoreToPharmacyRNServiceImpl.java:339` | (repo) |
| Pharmacy→Pharmacy Received Note (RN) | `PPRN` | `PharmacyToPharmacyRNServiceImpl.java:328` | (repo) |
| Pharmacy→Pharmacy Requisition Order (RO) | `PPR` | `PharmacyToPharmacyROServiceImpl.java:379` | (repo) |
| Pharmacy→Store Requisition Order (RO) | `PSR` | `PharmacyToStoreROServiceImpl.java:374` | (repo) |
| User code (IAM) | `USR-` + `formatSix` | `UserServiceImpl.java:666-667` | `userRepository.getLastId()` |
| Employee | `employeePrefix` (cfg, def `EMP`) + `/` + entity id | `EmployeeServiceImpl.java:47-59` | uses `employee.getId()` after save |
| Patient (medical record no) | `MRNO/` + calendar year + `/` + entity id | `PatientServiceImpl.java:250` | uses `patient.getId()` after save |

**CRITICAL FINDING — SPTO/PPTO drift + SPT collision.** The Increment-02 spec proposes prefixes `SPTO` (Store→Pharmacy) and `PPTO` (Pharmacy→Pharmacy). **Legacy uses NEITHER.** Both `StoreToPharmacyTO` AND `PharmacyToPharmacyTO` emit the **same literal `"SPT"`** (`StoreToPharmacyTOServiceImpl.java:438` and `PharmacyToPharmacyTOServiceImpl.java:428`). Because the running counter is `MAX(id)` of each *separate* table, the two document streams can produce **identical document numbers** (same `SPT` + same date + overlapping id ranges) — they are only distinguishable by which table the row lives in, not by the `no` string. This is a genuine legacy defect. Per "exact process" the rebuilt system reproduces `SPT` for both unless the engagement-lead approves a CR to split them into `SPTO`/`PPTO`. Flag to data-migration-engineer and qa-test-engineer.

**Table-name note (Hibernate naming):** the TO tables are `store_to_pharmacy_t_os` (`StoreToPharmacyTO.java:43`) and `pharmacy_to_pharmacy_t_os` (`PharmacyToPharmacyTO.java:42`) — note the `_t_os` from the "TO" + plural "s". Target build uses plural snake_case; document these legacy literal names for the data-migration-engineer.

**Employee numbering edge case:** `EmployeeServiceImpl.java:47` sets `no = String.valueOf(Math.random())` as a temporary placeholder before first save, then re-numbers to `employeePrefix + "/" + id` (or `EMP/` + id) after save (`:52-58`). If `employeePrefix` is empty string it falls through to `EMP/` (`:52`). No date, no padding — pure `prefix/id`.

**Patient numbering edge case:** `PatientServiceImpl.java:250` — `"MRNO/" + Year.now().getValue() + "/" + patient.getId()`. Calendar year is embedded (looks like a fiscal/year reset but is NOT a reset — id is global, never resets; the year is cosmetic). The inline comment at `:248` says "change this to conventional no, this is only for starting" — flag as known-temporary legacy code for healthcare-domain-expert.

---

### 5. BusinessDay (Day) — open/close confirmation

Entity: `domain/Day.java:34` — `@Table(name = "days")`.
- `id BIGINT` identity (`Day.java:35-37`)
- `bussinessDate` — `LocalDate`, `@NotNull` (note legacy typo "bussinessDate"), `@Column(unique=true)`, default `LocalDate.now()` (`Day.java:38-40`). One row per calendar date (unique constraint).
- `startedAt` — `Date` (`TIMESTAMP`), default `new Date()` (`Day.java:41-42`)
- `endedAt` — `Date` (`TIMESTAMP`), default `null` (open) (`Day.java:43-44`)
- `status` — `String`, default `"STARTED"` (`Day.java:45`). Open = `STARTED` with `endedAt == null`; close sets `endedAt` and (presumably) a closed status via `DayService`/`DayResource` (`api/DayResource.java`). Forensic stamps across the codebase reference the Day via `createdOn = dayService.getDay().getId()` (e.g. `PatientServiceImpl.java:238,283,297`). This confirms the shared-kernel BusinessDay open/close concept already noted as present in shared.

**Note for solution-architect:** legacy uses `java.util.Date` + `@Temporal(TIMESTAMP)` for `startedAt`/`endedAt` and `LocalDate` for `bussinessDate`. Field name `bussinessDate` is a misspelling in the legacy column — data-migration-engineer must map the legacy column name explicitly.

---

### 6. registrationFee usage (CompanyProfile config → patient flow)

`CompanyProfile.registrationFee` is read at patient registration: `PatientServiceImpl.doRegister` (`:230-233`) loads `companyProfileRepository.findAll()` and takes `cp.getRegistrationFee()` as the cash regFee. A `PatientBill` "Registration" is created with `amount=balance=regFee` (`:267-272`); status `UNPAID` if `regFee>0` else `VERIFIED` (`:273-277`). For INSURANCE patients with `regFee>0`, the bill is overridden by `RegistrationInsurancePlan.registrationFee` for the covered plan (`:312-329`). This couples masterdata (CompanyProfile + RegistrationInsurancePlan) to billing — relevant cross-reference for billing-context spec, but the masterdata fact is: **the single default registration fee lives on CompanyProfile.registrationFee.**

---

### EARLY-DISCOVERY OBLIGATIONS (restated for any audit/auth-touching downstream spec)
- **Audit trail (Envers):** Not re-verified in this extraction's scope, but per established engagement memory the Envers dependency is present yet NO entity is `@Audited` — no effective audit baseline. Reconfirm in any audit-touching spec.
- **Device fingerprint / binding:** No such feature in the security filters or Angular app per established memory — do not preserve. (Neither concept appears in any entity examined here.)

---

### Cross-reference index (legacy artefact → this spec section)
- `CompanyProfile` / `CompanyProfileServiceImpl` → §1, §6
- `Formater` (`formatWithCurrentDate`, `formatSix`, `formatNine`...) → §4
- `*ServiceImpl.requestTransferOrderNo / requestRequestGrnNo / requestRequestOrderNo / requestUserCode` → §4
- `Day` / `DayService` / `DayResource` → §5
- Currency (absence) → §2; document_type (absence) → §3