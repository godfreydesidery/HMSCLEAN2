I have full evidence across backend and frontend. Let me produce the extraction.

---

# EXTRACTION 4 — Patient Search + Visit / Last-Visit (Legacy Zana HMIS)

**Legacy root:** `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api`
All citations are `file:line`. Process facts only — no PHI.

---

## (a) Patient lookup / search

### Search keys / repository queries

There are **four distinct lookup mechanisms**, all on `PatientRepository`:

1. **Exact search-key lookup** — `Optional<Patient> findBySearchKey(String key)` — `repositories/PatientRepository.java:29`.
   Endpoint: `GET /patients/get_by_search_key?search_key=...` — `api/PatientResource.java:267-272`, delegating to `PatientServiceImpl.findBySearchKey` — `service/PatientServiceImpl.java:218-224` (throws `NotFoundException("Patient not found")` if absent).

2. **All-search-keys list (typeahead source)** — `@Query("SELECT p.searchKey FROM Patient p") List<String> getSearchKeys()` — `repositories/PatientRepository.java:22-23`.
   Endpoint: `GET /patients/get_all_search_keys` — `api/PatientResource.java:281-286`. Returns **every** patient's `searchKey` as a flat string list (no paging, no filter — loads the whole table).

3. **Substring search WITHOUT card** — `findAllByNoContainingOrFirstNameContainingOrMiddleNameContainingOrLastNameContainingOrPhoneNoContaining(value×5)` — `repositories/PatientRepository.java:41-42`.
   Endpoint: `GET /patients/load_patients_like?name_like={value}` — `api/PatientResource.java:5162-5170`. The **same** `value` is bound to all 5 keys: `no`, `firstName`, `middleName`, `lastName`, `phoneNo` — OR-combined `LIKE %value%`.

4. **Substring search WITH insurance card** — `findAllByNoContainingOrFirstNameContainingOrMiddleNameContainingOrLastNameContainingOrPhoneNoContainingOrMembershipNoContaining(value×6)` — `repositories/PatientRepository.java:50-51`.
   Endpoint: `GET /patients/load_patients_like_and_card?name_like={value}` — `api/PatientResource.java:5172-5180`. Adds `membershipNo` (insurance card no.) as a 6th OR-`LIKE` key.

Also present: `Optional<Patient> findByNo(String no)` (`PatientRepository.java:48`) — used for fixed lookups like the `"GENERAL"` walk-in patient (`PatientServiceImpl.java:3266, 3398`), and `List<Patient> findAllByNo(String)` (`PatientRepository.java:53`, no observed caller). `getMaterials` at `PatientResource.java:261-265` returns `findAll()` (entire table).

**Search keys searched by:** file no (`no`), first/middle/last name, phone no, and (card variant only) membership/insurance card no. **No** dedicated date-of-birth, national ID, or passport search exists.

### Pagination

**NONE.** Every lookup returns an unbounded `List<Patient>` / `List<String>` or the full `findAll()`. No `Pageable`/`Page` anywhere in `PatientRepository`. Filtering for the typeahead is done **client-side**: the Angular `filterSearchKeys` only begins matching after `value.length >= 4` and does an in-memory `includes()` over the full `searchKeys` array — `patient-register.component.ts:1470-1484`, fed by the all-keys endpoint at `:486-495`. The exact selection then calls `get_by_search_key` — `:539`.

### How `searchKey` is built (numbering/format — load-bearing for rebuild)

In `PatientServiceImpl.createSearchKey(no, firstName, middleName, lastName, phoneNo)` — `service/PatientServiceImpl.java:739-744`:
```java
String key = no +" "+ firstName +" "+ middleName +" "+ lastName +" "+ phoneNo;
key = key.trim().replaceAll("\\s+", " ");
key = key.replaceAll("[+^]*#$%&", "");
```
Then passed through `Sanitizer.sanitizeString` — `api/accessories/Sanitizer.java:11-17` (replaces `+`→space, collapses whitespace, strips the literal-ish `[+^]*#$%&` regex). Applied at register (`:256-257`) and update (`:706-707`). `searchKey` is `@NotBlank @Column(unique=true)` — `domain/Patient.java:48-50`. The unique constraint means two patients producing an identical name+no+phone composite would collide on insert.

> **DRIFT/edge flag:** `createSearchKey` and `Sanitizer.sanitizeString` apply nearly-identical but **not** identical regex cleanups (the service version omits the `+`→space replace). Both run in sequence on register/update. Document as-is; the resulting stored value is the doubly-processed string.

---

## (b) The `Visit` entity

**`domain/Visit.java:33-61`** — `@Table(name = "visits")`:

| Field | Type | Notes / cite |
|---|---|---|
| `id` | Long | `@GeneratedValue(IDENTITY)` — `:39-41` |
| `sequence` | String, `@NotBlank`, default `""` | "FIRST or SUBSEQUENT visit" — `:42-43` |
| `type` | String, `@NotBlank`, default `""` | "INPATIENT or OUTPATIENT" — `:44-45` |
| `status` | String, `@NotBlank` | `:46-47` |
| `patient` | `@ManyToOne(EAGER, optional=false)` `patient_id` not-null/not-updatable | `:49-52` |
| `createdBy` | Long, `created_by_user_id` not-null/not-updatable | `:55-56` |
| `createdOn` | Long, `created_on_day_id` (FK to Day/business-day) not-null/not-updatable | `:57-58` |
| `createdAt` | LocalDateTime, default `LocalDateTime.now()` | `:59` |

Note `Visit` is **ManyToOne** to `Patient` (a patient has many visits) — contrast with `Registration` which is OneToOne (see part c).

**Observed `sequence` values (string enum, code-assigned):** `"FIRST"`, `"SUBSEQUENT"`, `"SUBSEQUENT-FOR-ADMISSION"`. **Observed `status`:** only `"PENDING"` is ever set. **Observed `type`:** copied from `patient.getType()` (`"OUTPATIENT"`/`"INPATIENT"`/`"OUTSIDER"`), except the non-consultation path hard-codes `"OUTSIDER"`.

### When a Visit row is created

A `Visit` is **NOT created at search**, and the comments claiming "create one if the last visit is not for today" (`PatientServiceImpl.java:499, 1722`) are **misleading — no such date check exists**. A brand-new `Visit` row is unconditionally inserted at each of these 4 points:

1. **At registration** (`FIRST`, status `PENDING`, type=patient.type) — `PatientServiceImpl.doRegister`, `service/PatientServiceImpl.java:409-419`. So yes, every registration creates one Visit.
2. **At send-to-doctor / consultation** (`SUBSEQUENT`) — `PatientServiceImpl.doConsultation`, `:501-512`, then linked via `consultation.setVisit(visit)`.
3. **At admission** (`SUBSEQUENT-FOR-ADMISSION`) — `PatientServiceImpl.doAdmission`, `:1724-1738`, linked via `admission.setVisit(visit)`.
4. **At outsider non-consultation creation** (`SUBSEQUENT`, type forced `"OUTSIDER"`) — controller `PatientResource.java:4085-4099`, only when no PENDING/IN-PROCESS NonConsultation already exists (`:4083, 4098`), linked via `nonConsultation.setVisit(visit)`.

> **Edge flag:** Visit creation is unconditional per action — repeated send-to-doctor for the same patient on the same day produces **multiple** SUBSEQUENT visit rows. There is no per-day dedup despite the comments.

### How "last visit" is derived

Endpoint `GET /patients/last_visit_date_time?patient_id=...` — `PatientResource.java:788-804`:
```java
List<Visit> visits = visitRepository.findAllByPatient(p.get());   // :795
LocalDateTime lastVistiDateTime = null;
for(Visit visit : visits) { lastVistiDateTime = visit.getCreatedAt(); }  // :797-799
```
"Last visit" = the `createdAt` of the **last element of the unordered `findAllByPatient` list** (`VisitRepository.java:31` — no `OrderBy`). It relies on default insertion/ID order, **not** an explicit sort.

> **Edge/correctness flag:** This is fragile — `findAllByPatient` has no ordering clause, so "last" is implementation-ordering-dependent (typically PK order, which usually coincides with chronology but is not guaranteed). Also `p.get()` is called without `isPresent()` check (`:793`) → unguarded `NoSuchElementException` for an unknown id. For the rebuild, the intended semantics are "most recent visit by createdAt" — recommend an explicit `ORDER BY createdAt DESC LIMIT 1`.

> **Dead code:** `VisitRepository.findLastByPatient(Patient)` (`VisitRepository.java:25`) is declared but **never called anywhere** in `com.orbix.api` (confirmed by grep — only the declaration matches). As a Spring Data derived name, `findLast...` is also **not a valid query keyword** (`Last` is not parsed as ordering) — it would behave like `findByPatient`. Do not reproduce; it is not part of live behaviour.
> `findAllBySequenceAndCreatedAtBetween(String, LocalDateTime, LocalDateTime)` (`VisitRepository.java:33`) — no caller in the patient module either; likely used by a report service outside this extraction's scope.

---

## (c) "Registration" vs "Visit" relationship — and the planning-doc entity

**There is a real legacy `Registration` entity** — this is the key finding. The planning doc's "Registration" most likely maps to **this real entity, NOT to Visit.** They are distinct:

**`domain/Registration.java:34-62`** — `@Table(name = "registrations")`:
- `id` Long IDENTITY (`:40-42`)
- `status` String `@NotBlank` (`:43-44`) — set to `"ACTIVE"` on creation (`PatientServiceImpl.java:301`)
- `patient` — **`@OneToOne`** `(EAGER, optional=false)`, `patient_id` not-null/not-updatable (`:46-49`) → **one Registration per patient**
- `patientBill` — `@OneToOne` to `PatientBill`, `patient_bill_id` not-null/not-updatable (`:51-54`) → links the registration to its **registration-fee bill**
- forensic `createdBy` / `createdOn` / `createdAt` (`:57-61`)

**Creation:** exactly **one** `Registration` is created at registration time, alongside the registration-fee `PatientBill` — `PatientServiceImpl.doRegister`, `service/PatientServiceImpl.java:293-302`:
```java
Registration reg = new Registration();
reg.setPatient(patient);
reg.setPatientBill(regBill);
reg.setStatus("ACTIVE");
registrationRepository.save(reg);   // :302
```
**Repository** `RegistrationRepository.java:19-30`: `findByPatient(Patient)` (returns a single `Registration`, consistent with OneToOne), `findAllByPatientBillIn(List<PatientBill>)`, `findAllByCreatedAtBetween(...)` (for registration reports).

### Summary of the three concepts (so the rebuild does not conflate them)

| Concept | Cardinality to Patient | Created when | Purpose | Cite |
|---|---|---|---|---|
| **Patient** | the master record | `/patients/register` | identity, demographics, `no`, `searchKey`, payment type/plan | `Patient.java`; `PatientServiceImpl.java:227-422` |
| **Registration** | **OneToOne** (one per patient) | at register, once | pairs the patient with the **registration-fee `PatientBill`**; `status="ACTIVE"` | `Registration.java`; `PatientServiceImpl.java:293-302` |
| **Visit** | **ManyToOne** (many per patient) | at register (FIRST) + each send-to-doctor / admission / outsider NC (SUBSEQUENT*) | per-encounter marker; linked from Consultation/Admission/NonConsultation | `Visit.java`; `PatientServiceImpl.java:409-419, 501-512, 1724-1738`; `PatientResource.java:4085-4099` |

**DRIFT verdict for the inc-03 planning doc:**
- If the planning doc's "Registration" entity carries fields like a *visit sequence / encounter / last-visit*, it has **misattributed Visit behaviour to Registration** — flag as DRIFT. The legacy `Registration` is a thin Patient↔registration-fee-bill join (`status` ACTIVE only); it has **no** sequence/type/encounter semantics.
- If the planning doc's "Registration" is meant to be the *registration event that creates the patient + reg-fee bill*, then it **correctly** corresponds to the legacy `Registration` entity (`registrations` table), and the **encounter/visit lifecycle is a separate `Visit` concern** that must be modelled independently.
- Either way: **do not collapse Registration and Visit into one entity** — the legacy keeps them separate (OneToOne fee-binding vs ManyToOne encounter log), and the `FIRST` Visit + the `ACTIVE` Registration are both created in the same `doRegister` transaction but are not the same row.

### RBAC note (search/visit endpoints)

- `/patients/register` → `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")` — `PatientResource.java:289`.
- `/patients/update` → `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")` — `:379`.
- All **read/search** endpoints (`/patients/get_by_search_key`, `/patients/get_all_search_keys`, `/patients/load_patients_like`, `/patients/load_patients_like_and_card`, `/patients/last_visit_date_time`, `/patients`, `/patients/get`) have **NO `@PreAuthorize`** — `:261, 267, 274, 281, 788, 5162, 5172`. They are gated only by the global JWT auth filter, not by privilege. `/patients/do_admission`'s `@PreAuthorize` is **commented out** (`:5184`). Confirm with security-architect whether unauthenticated-privilege read access to patient search is intended or an omission.

These privilege codes (`PATIENT-ALL`/`PATIENT-CREATE`/`PATIENT-UPDATE`) match your seeded set in `V2__seed_iam.sql`; the read endpoints reference none.

---

### Relevant files
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/repositories/PatientRepository.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/repositories/VisitRepository.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/repositories/RegistrationRepository.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Visit.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Registration.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/accessories/Sanitizer.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/zana-hmis/src/app/pages/registration/patient-register/patient-register.component.ts`