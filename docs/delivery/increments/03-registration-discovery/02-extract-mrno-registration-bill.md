I have all the evidence needed. Here is the complete, file:line-cited extraction.

---

# EXTRACTION 2 — MR-number generation, patient registration creation, and the registration bill

Legacy source root: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api`
Primary site: `service/PatientServiceImpl.java#doRegister` (lines 226–422). Endpoint: `api/PatientResource.java#registerPatient` (lines 288–308).

## (a) The EXACT MR / patient number format

**Format is `MRNO/{year}/{id}` — NOT `formatNine`, NOT `formatSix`.** `Formater` is never called in the registration path.

- `PatientServiceImpl.java:250`:
  ```java
  patient.setNo("MRNO/"+String.valueOf(Year.now().getValue())+"/"+ patient.getId().toString());
  ```
- `{year}` = `java.time.Year.now().getValue()` (`PatientServiceImpl.java:250`, import `java.time.Year` at line 16). **No padding** on the year; rendered as the 4-digit current year.
- `{seq}` is **NOT a sequence and NOT `MAX(id)+1`**. It is `patient.getId().toString()` — the JPA-generated identity PK. `Patient.id` is `@GeneratedValue(strategy = GenerationType.IDENTITY)` (`domain/Patient.java:42-44`), i.e. the MySQL `AUTO_INCREMENT` column value. The flow first persists the patient to obtain the id (`PatientServiceImpl.java:241` `patientRepository.save(p)`), then sets `no` from that id (`:250`), then re-saves (`:255`, `:262`, `:307`).
- **No zero-padding** on the id segment. Example renderings: `MRNO/2026/1`, `MRNO/2026/1457`. The id is global (monotonic across the whole table), **never resets** per year — so the `{year}` segment changes annually but the numeric segment keeps climbing; numbers are not contiguous within a year.
- **`Formater.formatNine` / `formatSix` / `formatNine` are NOT used here.** Confirmed: no `Formater` import or call in `PatientServiceImpl.java`. `Formater.formatNine` (`accessories/Formater.java:18-35`) produces `XXX-XXX-XXX` (left zero-padded to 9 chars, then dashes inserted at index 6 then 3). It is unrelated to MR numbers. Quoted for reference:
  ```java
  public static String formatNine(String value) {            // Formater.java:18
      int tokenLength = 9; int serialLength = value.length();
      tokenLength = tokenLength - serialLength;               // left-pad with '0'
      ... sb.insert(6, "-"); sb.insert(3, "-");               // -> 000-000-001
  }
  ```

**Timezone for the embedded year:** `Year.now()` (`PatientServiceImpl.java:250`) uses the **JVM/server default timezone**, NOT an explicit `Africa/Dar_es_Salaam`. There is no `ZoneId` argument. (Contrast: `DayServiceImpl.getTimeStamp()` at `service/DayServiceImpl.java:86-87` does `LocalDateTime.now().plusHours(3)` — a hard-coded +3h offset hack used for `createdAt` audit timestamps, but **the MR-number year does not use this**; it uses bare `Year.now()`.) The legacy year boundary therefore flips at server-local midnight, whatever the JVM tz is.

**Counter mechanism — definitive: neither sequence nor MAX(id)+1.** It is the auto-increment surrogate key reused as the human-facing number. There is no `seq_mrno`, no `MAX(...)` query, no application-level counter in this path.

> DRIFT vs planning doc (`03-registration-patient.md:52-53`, `:90`): The doc specifies `{seq} = nextval('seq_mrno')` (a dedicated Postgres sequence) and EAT-zoned year `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).getYear()`, and at `:53` claims this "replaces the legacy `MAX(id)+1` race." **Both the sequence claim and the `MAX(id)+1` characterization are DRIFT.** Legacy uses the IDENTITY PK (`patient.getId()`), not `MAX(id)+1` and not a named sequence. Behavioural consequences to preserve vs. flag:
> - The legacy numeric segment equals the patient PK exactly (so `MRNO/2026/1457` ⇒ patient id 1457). A dedicated `seq_mrno` will **decouple** the number from the PK — an observable change. If exact-process parity is required, the rebuild numeric segment must equal the patient surrogate id, OR engagement-lead must approve the decoupling as a change request.
> - Legacy has **no per-year reset and no zero-padding**; planning doc's "gap-free per calendar year" parity assertion (`:53`) does not match legacy (legacy ids are global and gappy within a year due to deletes/rollbacks). Flag as an ambiguity for engagement-lead: which numbering contract is authoritative?
> - The EAT-zoned year (`:53`) is an *improvement* over legacy bare `Year.now()`; preserving "exact process" means the year is server-tz-dependent. Recommend confirming the legacy deployment server tz (likely Africa/Dar_es_Salaam EAT already) so the EAT pin is behaviour-preserving rather than a silent change.

## (b) Registration creation flow (`registerPatient` → `doRegister`)

**Endpoint:** `POST /zana-hmis-api/patients/register` (`PatientResource.java:288`), guarded `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")` (`PatientResource.java:289`). Confirms real codes `PATIENT-ALL`/`PATIENT-CREATE` (matches V2__seed_iam.sql; not the invented `PATIENT-EDIT/REGISTRATION-ALL`).

**Controller pre-processing** (`PatientResource.java:293-305`):
- If `patient.no` is `""` or `"NA"` → forced to `"NA"` (`:293-295`). (This pre-save placeholder is overwritten at `PatientServiceImpl.java:250`.)
- If `paymentType == "INSURANCE"`: resolves `InsurancePlan` by name via `insurancePlanRepository.findByName(...).get()` (`:297-298`, unguarded `.get()` — NoSuchElement risk if plan name unknown → ambiguity), and **requires `membershipNo`** non-empty else `MissingInformationException("Membership number required")` (`:299-301`).
- Else (`CASH`): `insurancePlan` set to `null` (`:303-304`).

**Transaction boundary:** class-level `@Transactional` on `PatientServiceImpl` (`:151`, `javax.transaction.Transactional`). The entire `doRegister` runs in one transaction. Note multiple `saveAndFlush` calls inside (`:368, :384`) but commit is at method exit.

**Steps & entities created (all inside `doRegister`):**
1. Read registration fee: loops **all** `CompanyProfile` rows, `regFee = cp.getRegistrationFee()` — last row wins (`:230-233`). `CompanyProfile.registrationFee` is `double`, default `0` (`domain/CompanyProfile.java:77`). (Ambiguity: assumes a single company-profile row; multi-row would be nondeterministic.)
2. Set forensic fields `createdBy / createdOn / createdAt` (`:237-239`) — `createdAt` via `dayService.getTimeStamp()` = `LocalDateTime.now().plusHours(3)` (`DayServiceImpl.java:86-87`).
3. **Save Patient** (#1) to obtain identity id (`:241`).
4. Set `no = "MRNO/{year}/{id}"` (`:250`); set temporary random `searchKey = Math.random()` (`:254`); **save Patient (#2)** (`:255`).
5. Recompute `searchKey = createSearchKey(no, firstName, middleName, lastName, phoneNo)` (`:256`, method at `:739-744`: concatenates the five fields space-joined, trims, collapses whitespace, strips `[+^]*#$%&`), then `Sanitizer.sanitizeString(...)` (`:257`; `Sanitizer.java:11-17`: replaces `+`→space, trim, collapse whitespace, strip `[+^]*#$%&`). **Save Patient (#3)** (`:262`).
   > DRIFT: planning doc `:71` says searchKey = `firstName + lastName` only. Legacy includes **`no`, `middleName`, AND `phoneNo`** as well (`:740`). Flag.
6. **Create registration `PatientBill`** (see (c)) and save (`:267-288`).
7. **Create `Registration`** entity: `patient`, forensic fields, `patientBill = regBill`, `status = "ACTIVE"`; save (`:293-302`).
8. **Save Patient (#4)** (no-op resave) (`:307`).
9. **INSURANCE branch** (`:312-405`) only if `paymentType == "INSURANCE"` AND `regFee > 0`: looks up `RegistrationInsurancePlan` by plan+covered=true (`:320`); if present, rewrites regBill to plan price, creates/links a `PENDING` `PatientInvoice` + `PatientInvoiceDetail` (claim), sets regBill `status="COVERED"`, `paymentType="INSURANCE"` (`:326-402`). See (c).
10. **Create first `Visit`**: `patient`, `sequence="FIRST"`, `status="PENDING"`, `type = patient.getType()`, forensic fields; save (`:409-419`).
11. Return `patient` (`:421`).

**Entities created per registration:** 1 `Patient`, 1 `PatientBill` (registration fee), 1 `Registration`, 1 `Visit`; plus (insurance + regFee>0 only) possibly 1 `PatientInvoice` + 1 `PatientInvoiceDetail`. (`createdAt` timestamps via the +3h hack.)

## (c) The registration BILL

**Yes — a registration-fee `PatientBill` is always created at registration**, unconditionally, regardless of payment type (`PatientServiceImpl.java:267-288`).

Field values at creation (`:267-278`):
| Field | Value | Line |
|---|---|---|
| `amount` | `regFee` (from `CompanyProfile.registrationFee`, `double`) | :268 |
| `qty` | `1` | :269 |
| `balance` | `regFee` | :270 |
| `billItem` | `"Registration"` | :271 |
| `description` | `"Registration Fee"` | :272 |
| `status` | `"UNPAID"` if `regFee > 0`, else `"VERIFIED"` | :273-277 |
| `patient` | the new patient | :278 |
| `paid` | not set → `double` default `0.0` (`PatientBill.java:51`) | — |
| `paymentType` | not set → entity default `"CASH"` (`PatientBill.java:57`) | — |
| `billType` | **NO SUCH FIELD on `PatientBill`** — does not exist | — |
| forensic `createdBy/On/At` | set (`:282-284`) | :282-284 |

> Note: there is **no `billType` field** anywhere on `PatientBill` (confirmed `domain/PatientBill.java:40-85`). The categorization field is **`billItem`** (`"Registration"`). If the planning doc/spec references a `billType`, that is invented — use `billItem`.

**Price lookup (kind/service):** For CASH (and the base case), the amount is **not** a `md_service_price` lookup — it is read directly from `CompanyProfile.registrationFee` (`:230-233, :268`). There is **no service/kind price record** for cash registration in legacy.
> DRIFT: planning doc `:47` implies registration fee comes from `md_service_price`. Legacy CASH registration fee comes from `CompanyProfile.registrationFee`, a singleton config value, NOT a price table. Flag.

**Insurance override** (`:312-403`): only when `paymentType=="INSURANCE"` AND `regFee>0`. Looks up `RegistrationInsurancePlan` for the patient's plan with `covered=true` (`registrationInsurancePlanRepository.findByInsurancePlanAndCovered(plan, true)`, `:320`). If present:
- `regBill.amount = plan.getRegistrationFee()`, `paid = plan.getRegistrationFee()`, `balance = 0` (`:326-328`), save (`:329`).
- Create-or-reuse a `PENDING` `PatientInvoice` (no = `Math.random()` then overwritten with id string, `:339/:349`) and add a `PatientInvoiceDetail` claim (amount = regBill.amount, description `"Registration Fee"`, qty 1) (`:357-368` new-invoice branch, `:373-384` existing-invoice branch).
- Finally `regBill.status = "COVERED"`, `paymentType="INSURANCE"`, `insurancePlan` + `membershipNo` set (`:390-393`), save (`:398`).
- **Edge case:** if INSURANCE but `RegistrationInsurancePlan` is absent (`plan` not present at `:321`), the bill stays `UNPAID`/`COVERED`-untouched with `amount=regFee` from CompanyProfile and **no invoice claim is created** — patient is insurance but the reg bill is effectively a cash-style UNPAID bill. Flag as ambiguity (silent fall-through).
- **Edge case:** if INSURANCE but `regFee == 0`, the whole insurance block (`:312`) is skipped → bill stays `status="VERIFIED"` (from `:276`), no plan price applied, no invoice. Flag.

**Bill status state values observed for the registration bill:** `UNPAID` (cash, fee>0), `VERIFIED` (fee==0), `COVERED` (insurance with covered plan). `PatientBill.status` is `@NotBlank` (`PatientBill.java:54-55`).

---

## Ambiguity / DRIFT register (for engagement-lead / domain-expert)

1. **MR-number `{seq}` source** — legacy uses IDENTITY PK (`patient.getId()`, `:250`), NOT `seq_mrno` and NOT `MAX(id)+1` as the doc claims (`03-...:53`). Decision needed: replicate PK-coupled numbering exactly, or approve decoupled `seq_mrno` (changes the observable number-to-id relationship). **DRIFT.**
2. **No zero-pad / no per-year reset** in legacy MR numbers (`:250`); doc's "gap-free per year" parity (`:53`) is invented. **DRIFT.**
3. **MR-number year timezone** — legacy bare `Year.now()` (server tz, `:250`); doc pins EAT (`:53`). Confirm legacy server tz to decide if EAT pin is parity-preserving.
4. **searchKey composition** — legacy = `no + firstName + middleName + lastName + phoneNo` (`:740`); doc = `firstName + lastName` only (`:71`). **DRIFT.**
5. **Registration fee source** — legacy `CompanyProfile.registrationFee` singleton config (`:230-233`); doc implies `md_service_price` (`:47`). **DRIFT.**
6. **`billType` vs `billItem`** — `PatientBill` has only `billItem` (`PatientBill.java:44`); any `billType` reference is invented.
7. **CompanyProfile multi-row** — `doRegister` loops all profiles, last wins (`:230-233`); nondeterministic if >1 row.
8. **Insurance-but-no-covered-plan / insurance-but-regFee==0** fall-throughs leave reg bill un-covered with no claim (`:312-321`); confirm intended behaviour.
9. **Controller `.get()` on plan-by-name** (`PatientResource.java:297`) unguarded — unknown plan name throws raw NoSuchElement, not a domain error.

Relevant files:
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java` (doRegister :226-422; MR no :250; reg bill :267-288; insurance :312-405; visit :409-419; createSearchKey :739-744)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java` (:288-308)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java` (:42-50 id/no/searchKey)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/PatientBill.java` (:44-57 billItem/paid/paymentType defaults; no billType)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/CompanyProfile.java` (:77 registrationFee double)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/accessories/Formater.java` (:18-35 formatNine — NOT used for MR)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/accessories/Sanitizer.java` (:11-17)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/DayServiceImpl.java` (:86-87 getTimeStamp +3h)
- Planning doc (drift source): `d:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md` (:47, :52-53, :71, :90)

Audit/device note (per standing obligation): no `@Audited` annotation present on `Patient`/`PatientBill`/`Registration`/`Visit` in this path, and no device-fingerprint/device-binding logic touched in `registerPatient`/`doRegister` — neither is a registration feature to preserve.