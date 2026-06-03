The `@Audited` / fingerprint grep returned zero actual matches (the 5 files matched only on the literal "deceased" / "Deceased" terms, not on audit or device-binding annotations). Let me confirm there's no `@Audited` anywhere and check the `MainApplication.java` / `UpdatePatient.java` matches were just the "deceased" string, and verify the searchKey-collision/`@NotBlank` enforcement note. The grep already confirms no `@Audited` and no `fingerprint`/`deviceId` tokens matched. I have everything needed.

# Legacy Zana HMIS — EXTRACTION 1: The `Patient` Aggregate (file:line-cited)

**Source of truth:** `com/orbix/api/domain/Patient.java` (the whole entity is only 107 lines). Supporting logic: `service/PatientServiceImpl.java`, `api/PatientResource.java`, `api/accessories/Sanitizer.java`, `accessories/Formater.java`.

---

## 1. Class-level facts

- `@Entity @Data @NoArgsConstructor @AllArgsConstructor @Table(name = "patients")` — `Patient.java:36-41`. Lombok `@Data` generates all getters/setters/equals/hashCode/toString.
- **No `@Audited` annotation** anywhere on `Patient` (or in `com.orbix.api` at all — confirmed by repo-wide grep returning zero `@Audited` hits). **No device-fingerprint / device-binding field or logic** exists on the entity or its security path (grep for `fingerprint`/`deviceId`/`device_id` = zero matches). These two "phantom features" are NOT implemented and must not be reproduced (consistent with the engagement memo).
- **No next-of-kin entity and no `@Embedded` type.** Next-of-kin is three flat nullable String columns on the patient row (see below). There is exactly **one** next-of-kin, not a collection.

---

## 2. Complete field table

| # | Field | Java type | Column / mapping | Nullability & constraints | Default | Citation |
|---|-------|-----------|------------------|---------------------------|---------|----------|
| 1 | `id` | `Long` | `@Id @GeneratedValue(IDENTITY)` | PK, DB-generated | — | `Patient.java:42-44` |
| 2 | `no` | `String` | `@Column(unique = true)` | `@NotBlank`, UNIQUE | — | `Patient.java:45-47` |
| 3 | `searchKey` | `String` | `@Column(unique = true)` | `@NotBlank`, UNIQUE | — | `Patient.java:48-50` |
| 4 | `firstName` | `String` | default col | `@NotBlank` | — | `Patient.java:54-55` |
| 5 | `middleName` | `String` | default col | nullable (no constraint) | — | `Patient.java:56` |
| 6 | `lastName` | `String` | default col | `@NotBlank` | — | `Patient.java:57-58` |
| 7 | `dateOfBirth` | `LocalDate` | default col | `@NotNull` | — | `Patient.java:59-60` |
| 8 | `gender` | `String` | default col | `@NotBlank` (free-text String, NOT an enum) | — | `Patient.java:61-62` |
| 9 | `type` | `String` | default col | `@NotBlank` (patientType — free-text String, NOT an enum) | — | `Patient.java:63-64` |
| 10 | `paymentType` | `String` | default col | `@NotBlank` (free-text String, NOT an enum) | `""` | `Patient.java:68-69` |
| 11 | `membershipNo` | `String` | default col | nullable | `""` | `Patient.java:70` |
| 12 | `phoneNo` | `String` | default col | nullable | — | `Patient.java:74` |
| 13 | `address` | `String` | default col | nullable | — | `Patient.java:75` |
| 14 | `email` | `String` | default col | nullable | — | `Patient.java:76` |
| 15 | `nationality` | `String` | default col | nullable | — | `Patient.java:77` |
| 16 | `nationalId` | `String` | default col | nullable | — | `Patient.java:78` |
| 17 | `passportNo` | `String` | default col | nullable | — | `Patient.java:79` |
| 18 | `kinFullName` | `String` | default col | nullable (next-of-kin) | — | `Patient.java:83` |
| 19 | `kinRelationship` | `String` | default col | nullable (next-of-kin) | — | `Patient.java:84` |
| 20 | `kinPhoneNo` | `String` | default col | nullable (next-of-kin) | — | `Patient.java:85` |
| 21 | `active` | `boolean` | default col | primitive | `true` | `Patient.java:89` |
| 22 | `insurancePlan` | `InsurancePlan` | `@ManyToOne(EAGER, optional=true) @JoinColumn(name="insurance_plan_id", nullable=true, updatable=true) @OnDelete(NO_ACTION)` | nullable FK | `null` | `Patient.java:97-100` |
| 23 | `createdBy` | `Long` | `@Column(name="created_by_user_id", nullable=false, updatable=false)` | NOT NULL, immutable | — | `Patient.java:102-103` |
| 24 | `createdOn` | `Long` | `@Column(name="created_on_day_id", nullable=false, updatable=false)` | NOT NULL, immutable (FK-by-id to `Day`) | — | `Patient.java:104-105` |
| 25 | `createdAt` | `LocalDateTime` | default col | initialized field-level; later overwritten with business clock | `LocalDateTime.now()` | `Patient.java:106` |

**Audit-stamp model:** `createdBy` = user id, `createdOn` = business-day id (the `Day` entity's id — NOT a date), `createdAt` = wall-clock timestamp. There is **NO `updatedBy`/`updatedOn`/`updatedAt`** — the entity records creation only; updates are not stamped (`update()` mutates fields without touching any audit field, `PatientServiceImpl.java:697-726`). Two commented-out earlier audit fields exist at `Patient.java:93-94` (dead code — ignore).

---

## 3. `paymentType` — EXACT confirmed values

The entity does **not** enumerate values; `paymentType` is a `@NotBlank String` (`Patient.java:68-69`). The only values the code ever compares against or sets are **exactly two**:

- `"INSURANCE"` and `"CASH"`.
- Registration: if `paymentType == "INSURANCE"` requires a resolved `insurancePlan` + non-empty `membershipNo`, else `insurancePlan` is nulled (`PatientResource.java:296-305`).
- `doConsultation` branches on only `"INSURANCE"` / `"CASH"` and throws `InvalidOperationException("Invalid Payment type selected")` for anything else (`PatientServiceImpl.java:529-583`, esp. 564, 581-582).
- `changePaymentType` collapses every non-`INSURANCE` value to `"CASH"` (`PatientResource.java:359-373`).

**DRIFT FLAG:** The inc-03 planning doc's payment-type set `CASH/INSURANCE/DEBIT_CARD/CREDIT_CARD/MOBILE` is **invented**. The legacy system supports only `CASH` and `INSURANCE`. `DEBIT_CARD`, `CREDIT_CARD`, and `MOBILE` appear **nowhere** in the patient flow. Do not reproduce them in Increment 03.

---

## 4. `type` (patientType) — EXACT confirmed values

`type` is a `@NotBlank String` (`Patient.java:63-64`), copied into `Visit.type` and `Consultation` flows. Confirmed values set in code:

- `"OUTPATIENT"` — `PatientResource.java:411, 496, 5373, 5624, 5674`
- `"OUTSIDER"` — `PatientResource.java:426, 4088`
- `"INPATIENT"` — referenced/guarded at `PatientResource.java:321, 499-500` (set elsewhere during admission flow)
- `"DECEASED"` — `PatientResource.java:5901, 5920` (set via the deceased-note flow, not a boolean)

`changeType` state machine (`PatientResource.java:398-506`): `OUTPATIENT → OUTSIDER` (blocked if active consultation, `:421-428`); `OUTSIDER → OUTPATIENT` (blocked if non-consultation has paid pending services, cancellable check `:429-498`); `INPATIENT` change throws `InvalidOperationException("This operation is not allowed for inpatients")` (`:499-500`). Null `type` is coerced to `"OUTPATIENT"` (`:410-414`).

**DRIFT FLAG:** The planning doc's `OUTPATIENT/OUTSIDER` pair is incomplete. The legacy `type` field is a 4-value vocabulary: **`OUTPATIENT`, `OUTSIDER`, `INPATIENT`, `DECEASED`**. Note `change_payment_type` also informally references `"INPATIENT"` in a dead/no-op comparison (`PatientResource.java:321-323`, which is a buggy `==` String comparison whose `throw` is commented out — see Ambiguities).

---

## 5. Deceased flag — there is NO boolean

**There is no `deceased` / `isDeceased` boolean field on `Patient`.** "Deceased" is modelled as `type = "DECEASED"` (`PatientResource.java:5901, 5920`) plus a **separate** `DeceasedNote` entity (`domain/DeceasedNote.java`, repository `DeceasedNoteRepository.java`) created by the `save_deceased_note` endpoint (`PatientResource.java:5693`). **DRIFT FLAG:** if the planning doc posits a `deceased` boolean on Patient, that is invented — the legacy representation is a `type` enum value + companion note entity.

---

## 6. Insurance fields

- `insurancePlan` is a nullable `@ManyToOne` to `InsurancePlan` (`Patient.java:97-100`), eagerly fetched, `@OnDelete(NO_ACTION)`.
- `membershipNo` is a flat `String` defaulting to `""` (`Patient.java:70`).
- On register/update, an `INSURANCE` patient must supply a plan whose **name** resolves via `insurancePlanRepository.findByName(...)` — note the client sends a plan name, the server re-resolves to the managed entity (`PatientResource.java:297-298, 360, 384`). Empty `membershipNo` → `MissingInformationException("Membership number required")` (`PatientResource.java:299-301, 364-366, 386-388`).

---

## 7. `no` (patient file/MRN) numbering scheme — EXACT format

Generated **application-side, post-insert** in `doRegister` (`PatientServiceImpl.java:250`):

```
patient.setNo("MRNO/"+String.valueOf(Year.now().getValue())+"/"+ patient.getId().toString());
```

- Format: `MRNO/{currentYear}/{patientId}` — e.g. `MRNO/2026/4137`.
- Prefix literal `MRNO/`; year = `Year.now().getValue()` (4-digit, **no fiscal-year logic**, just calendar year); suffix = raw DB identity id, **no zero-padding** (`Formater.formatNine/Six/etc.` are NOT applied to patient `no`).
- The id must already exist, so the patient is `save()`d once before `no` is set, then re-saved (`PatientServiceImpl.java:241, 250, 255, 262`).
- The client may send `no=""` or `no="NA"`, which the controller normalizes to `"NA"` before calling the service (`PatientResource.java:293-295`); the service then **unconditionally overwrites** it with the `MRNO/...` value (`PatientServiceImpl.java:250`). So any client-supplied `no` is ignored on registration. **DRIFT FLAG:** the inline comment "change this to conventional no, this is only for starting" (`PatientServiceImpl.java:248`) signals this is an admitted placeholder format — confirm with the business analyst whether `MRNO/YYYY/{id}` is the format to reproduce exactly or a known-temporary scheme.

---

## 8. `searchKey` generation + Sanitizer logic — EXACT

`searchKey` is `@NotBlank @Column(unique = true)` (`Patient.java:48-50`). Built in two layers:

**Step A — placeholder during initial insert** (`PatientServiceImpl.java:254`): set to `String.valueOf(Math.random())` purely to satisfy the UNIQUE/NotBlank constraint on first save (because `no` isn't known until id is generated).

**Step B — real key** via `createSearchKey(...)` then `Sanitizer.sanitizeString(...)` (`PatientServiceImpl.java:256-257`, also on update `:706-707`):

`createSearchKey(no, firstName, middleName, lastName, phoneNo)` (`PatientServiceImpl.java:739-744`):
```java
String key = no +" "+ firstName +" "+ middleName +" "+ lastName +" "+ phoneNo;
key = key.trim().replaceAll("\\s+", " ");
key = key.replaceAll("[+^]*#$%&", "");
return key;
```
- Concatenates `no firstName middleName lastName phoneNo` separated by single spaces.
- Trims, collapses internal whitespace runs to a single space.
- `replaceAll("[+^]*#$%&", "")` — this regex matches the literal substring `#$%&` optionally preceded by any run of `+`/`^` chars; it does NOT strip arbitrary special characters individually. (i.e., it only removes the exact sequence `...#$%&`; `[+^]*` is a quantified char-class prefix.) **Reproduce this regex verbatim — it is almost certainly not what the author intended, but it is the legacy behaviour.**
- **No lowercasing** is performed in `createSearchKey`.

`Sanitizer.sanitizeString(s)` (`api/accessories/Sanitizer.java:11-17`):
```java
s = s.replace("+", " ");                 // every '+' -> space
s = s.trim().replaceAll("\\s+", " ");    // trim + collapse whitespace
s = s.replaceAll("[+^]*#$%&", "");       // same literal-sequence strip as above
return s;
```
- Replaces every `+` with a space, trims, collapses whitespace, then the same `#$%&`-sequence strip. **No lowercasing here either.**

**Net behaviour:** `searchKey` is the space-joined, whitespace-normalized, `+`-replaced concatenation of `no + names + phoneNo`, **case-preserved**. Note the entity declares `searchKey` UNIQUE (`Patient.java:49`) — two patients with identical no+name+phone would collide, but since `no` embeds the unique DB id, collisions are effectively impossible.

**Naming note / DRIFT:** the planning doc / memo referenced `accessories/Sanitizer.java`; the real Sanitizer lives at **`com.orbix.api.api.accessories.Sanitizer`** (`api/accessories/Sanitizer.java`), NOT `com.orbix.api.accessories`. The `com.orbix.api.accessories` package contains only `Formater.java`, an empty `Validator.java` stub (`accessories/Validator.java:10-12` — no logic), and `package-info.java`. Patient registration does **not** call `Formater` at all.

---

## 9. RBAC on patient endpoints (for the security cross-ref)

Confirms the memo's real privilege codes:
- `POST /patients/register` → `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")` (`PatientResource.java:288-289`)
- `POST /patients/update` → `('PATIENT-ALL','PATIENT-UPDATE')` (`PatientResource.java:378-379`)
- `POST /patients/change_type` → `('PATIENT-ALL','PATIENT-UPDATE')` (`PatientResource.java:398-399`)
- `POST /patients/do_consultation` → `('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` (`PatientResource.java:508-509`)
- `POST /patients/change_payment_type` → `@PreAuthorize` is **commented out** (currently unsecured) (`PatientResource.java:310-311`).
- Many later endpoints have commented-out `('PATIENT-A','PATIENT-C','PATIENT-U')` guards (`PatientResource.java:572, 595, 5184…`). **DRIFT/AMBIGUITY:** both code families (`PATIENT-CREATE/UPDATE` and `PATIENT-A/C/U`) appear; the live seeded set is `PATIENT-ALL/CREATE/UPDATE/A/C/U`. Confirm whether the commented `PATIENT-A/C/U` endpoints should be secured in the rebuild.

---

## 10. Ambiguity register (Patient aggregate)

1. **`no` format is an admitted placeholder** (`PatientServiceImpl.java:248`) — reproduce `MRNO/YYYY/{id}` exactly, or treat as drift? → business-analyst.
2. **Broken `searchKey` regex** `[+^]*#$%&` (`PatientServiceImpl.java:742`, `Sanitizer.java:15`) — strips only the literal `#$%&` sequence, not general special chars. Reproduce verbatim for byte-identical keys, or is this a latent bug to flag? → engagement-lead change-request gate.
3. **`changePaymentType` uses `==` for String comparison** on patient type (`PatientResource.java:321`) — always false, and its guard `throw` is commented out (`:322`), so the block is a no-op. Reproduce the no-op exactly. → flag as dead code.
4. **`changePaymentType` is unsecured** (`@PreAuthorize` commented, `PatientResource.java:311`) — decide intended privilege. → security-architect.
5. **No update audit stamp** — `update()` never sets any audit field (`PatientServiceImpl.java:697-726`); the entity has no `updatedAt`. Confirm rebuild should likewise not stamp updates (exact process), even though modern design might want it. → engagement-lead.
6. **`gender`/`type`/`paymentType` are free-text `@NotBlank` Strings, not enums** (`Patient.java:62-69`). The rebuild may model them as enums (data-model change is permitted) but must accept/emit exactly the legacy string values listed in §3–§4.

---

## 11. Relevant file paths (absolute)

- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/accessories/Sanitizer.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/accessories/Formater.java` (not used by patient registration)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/accessories/Validator.java` (empty stub)
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/models/PatientModel.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/DeceasedNote.java`

**Early-discovery confirmations for this spec:** No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity (including `Patient`) is annotated `@Audited`. No device-fingerprint or device-binding feature exists in the legacy `Patient` flow or security path. Downstream agents must not assume either feature exists.