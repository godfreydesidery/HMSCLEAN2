All three high-risk claims independently verified against the legacy source. Here is the adversarial verification report.

# ADVERSARIAL VERIFICATION — Legacy Registration/Patient module

Re-verified by reading the legacy source directly. All citations re-confirmed from file reads (not the extractions). Legacy root: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api`.

## CLAIM 1 — MR/patient-number format & counter mechanism — CONFIRMED

The extractions are correct; the planning doc is DRIFT.

- `PatientServiceImpl.java:250` (verbatim): `patient.setNo("MRNO/"+String.valueOf(Year.now().getValue())+"/"+ patient.getId().toString());`
- Format = `MRNO/{Year.now().getValue()}/{patient.getId()}`. Year via `java.time.Year.now()` (server-default TZ, no `ZoneId`, no fiscal logic). Suffix = the JPA IDENTITY PK (`Patient.id` is `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `Patient.java:42-44`), NOT a sequence and NOT `MAX(id)+1`.
- Mechanism confirmed: patient is `save()`d first to obtain the identity id (`PatientServiceImpl.java:241`), then `no` is set from `getId()` (`:250`), then re-saved (`:255`, `:262`, `:307`). No `Formater.formatNine/formatSix` call exists in this path. No zero-padding. No per-year reset (id is global/monotonic; gappy within a year).
- Admitted-placeholder comment present at `PatientServiceImpl.java:248`: "change this to conventional no, this is only for starting".

VERDICT: Extractions 1 & 2 CONFIRMED. The planning-doc claims of `nextval('seq_mrno')`, EAT-zoned year, "gap-free per calendar year", and "replaces the legacy `MAX(id)+1` race" are all DRIFT — legacy never uses a named sequence or `MAX(id)+1`; the number is PK-coupled (`MRNO/2026/1457` ⇒ patient id 1457). A `seq_mrno` decouples number from PK = observable change requiring engagement-lead sign-off.

## CLAIM 2 — Send-to-doctor cash gate: HARD-BLOCK vs queue/UI filter — CONFIRMED (it is NOT a registration-fee block at send time)

The extractions are correct. There is NO registration-fee gate on send-to-doctor.

- `do_consultation` controller pre-guards (`PatientResource.java:508-541`) are EXACTLY: follow_up must be 0/1 (`:516-522`); no PENDING/IN-PROCESS admission (`:531-534`); `patient.getType().equals("OUTPATIENT")` (`:535-537`). There is **no `PatientBill`/registration-fee status check** in the controller or in `PatientServiceImpl.doConsultation` (`:425-455` pre-creation guards are: clinician active `:427`, transfer reconciliation `:431-439`, no PENDING/TRANSFERED consultation `:447-450`, no IN-PROCESS `:451-455`). Registration bill status is never read.
- The payment gate is on the **consultation bill**, enforced downstream:
  - Queue filter (soft, UI only): `load_pending_consultations_by_clinician_id` shows a consultation only if `cn.getPatientBill().getStatus().equals("PAID") || ...equals("COVERED")` (`PatientResource.java:822-826`, verbatim confirmed).
  - Hard gate at doctor-open: `open_consultation` throws `InvalidOperationException("Could not open. Payment not verified.")` unless the consultation bill is `PAID`/`COVERED` (`:884-897`); follow-up `open_follow_up_consultation` additionally accepts `"NONE"` (`:914`).

VERDICT: Extraction 3 CONFIRMED. For inc-04 SettlementPolicy parity: legacy enforces pay-before-service on the **consultation `PatientBill`** at **open time**, not at send-to-doctor, and never on the registration bill. Any inc-03 gate that blocks send-to-doctor on an unpaid **registration** fee is NET-NEW (drift) and requires an explicit change request. A consultation-bill gate at open is PARITY. (Mirrors the inc-04 scoped pay-before-service decision applied to the consultation charge, not the registration charge.)

## CLAIM 3 — Exact @PreAuthorize codes, reconciled to the 35 live codes — CONFIRMED

Verified verbatim:

| Endpoint | Line | Active gate |
|---|---|---|
| `POST /patients/register` | `:288-289` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')` |
| `POST /patients/change_payment_type` | `:310-311` | **COMMENTED OUT** — ships ungated (`//@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")`) |
| `POST /patients/update` | `:378-379` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` |
| `POST /patients/change_type` | `:398-399` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` |
| `POST /patients/do_consultation` | `:508-509` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` |
| `GET /patients`, `/get_by_search_key`, `/get`, `/get_all_search_keys` | `:261,267,274,281` | **NO gate** (authenticated-only) |

Reconciliation to live seed `V2__seed_iam.sql:55-60`: the only seeded PATIENT codes are `PATIENT-ALL`, `PATIENT-CREATE`, `PATIENT-UPDATE`, `PATIENT-A`, `PATIENT-C`, `PATIENT-U`. Every ACTIVE patient gate uses only the `ALL/CREATE/UPDATE` family. The `PATIENT-A/C/U` family appears in this file **only in commented-out gates** (it is real/seeded but never an active gate here).

VERDICT: Extractions 1, 2, 4 & 5 CONFIRMED on RBAC. The planning-doc codes `PATIENT-EDIT`, `PATIENT-VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK` are all DRIFT (neither in legacy `@PreAuthorize` nor in V2 seed). Note Extraction 1 §9 mislabels `do_consultation` example formatting but its code set is correct; Extraction 5 §2's table is exact.

## Independently re-verified phantom-feature finding (mandatory banner)
Repo-wide grep across `com.orbix.api` for `@Audited`, `org.hibernate.envers`, `fingerprint`, `deviceId`, `device_id`, `DeviceFingerprint`, `deviceBinding` (case-insensitive) returned **ZERO matches**.
- *No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity is annotated `@Audited`. Downstream agents must not assume an Envers audit baseline exists.*
- *No device-fingerprint or device-binding feature exists in the legacy system. Agents must not treat this as a feature to preserve or re-implement.*
"Forensic" data is hand-rolled scalar columns only: `createdBy` (user id), `createdOn` (Day id), `createdAt` (timestamp) — e.g. `Patient.java:102-106`. There is no `updatedBy/updatedOn/updatedAt`.

## DRIFT register — planning doc (`03-registration-patient.md`) vs verified legacy reality

1. **MR-number `{seq}` source** — Legacy uses IDENTITY PK `patient.getId()` (`PatientServiceImpl.java:250`). Doc's `nextval('seq_mrno')` is invented; decouples number from PK. DRIFT.
2. **MR-number per-year reset / zero-padding / "gap-free"** — Legacy has none (global gappy id, `:250`). Doc's "gap-free per calendar year" is invented. DRIFT.
3. **MR-number year timezone** — Legacy bare `Year.now()` (server TZ, `:250`). Doc's EAT (`Africa/Dar_es_Salaam`) pin is a silent improvement; confirm legacy server TZ before treating as parity. DRIFT/flag.
4. **Registration fee source** — Legacy reads `CompanyProfile.registrationFee` singleton config, looping all profiles last-wins (`PatientServiceImpl.java:230-233, :268`). Doc implies `md_service_price` lookup. DRIFT.
5. **`billType` field** — Does not exist on `PatientBill`; the field is `billItem` (set to `"Registration"`, `:271`). Any `billType` reference is invented. DRIFT.
6. **searchKey composition** — Legacy = `no + firstName + middleName + lastName + phoneNo` (`PatientServiceImpl.java:256`, builder `:739-744`) then `Sanitizer.sanitizeString` (`:257`). Doc's `firstName + lastName` only is wrong. DRIFT. (Also note the `[+^]*#$%&` regex strips only the literal `#$%&` sequence — reproduce verbatim for byte-identical keys; flag as latent bug.)
7. **Privilege codes** — `PATIENT-EDIT`, `PATIENT-VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK` are all invented. Real codes: `PATIENT-ALL/CREATE/UPDATE` (active) + `PATIENT-A/C/U` (seeded, commented-only here). DRIFT.
8. **`send-to-doctor` endpoint name** — No `send_to_doctor`/`sendToDoctor` exists in legacy (zero grep matches per Extraction 5); the real action is `POST /patients/do_consultation` (`:508`). Modern `send-to-doctor` maps 1:1 to `do_consultation`. DRIFT (naming).
9. **Registration-fee gate on send-to-doctor** — Invented (see Claim 2). The legacy pay-gate is on the consultation bill at open-time. NET-NEW if reproduced as a registration-fee send-block.
10. **`deceased` boolean / `PATCH .../deceased` / `PATIENT_DECEASED` send-guard** — Invented. Legacy has no `deceased` boolean on `Patient` (confirmed `Patient.java:41-107`); deceased is `type == "DECEASED"` set via the `save_deceased_note` workflow, and `do_consultation` only guards active-admission + OUTPATIENT (`:531-537`), never an explicit deceased check. DRIFT.
11. **"Registration" entity semantics** — A real `Registration` entity exists (`domain/Registration.java`; created at `PatientServiceImpl.java:293-302`, `status="ACTIVE"`, OneToOne to Patient, OneToOne to the reg-fee `PatientBill`). It is distinct from `Visit` (ManyToOne, encounter log). If the doc attributes visit/encounter/sequence semantics to "Registration", that is DRIFT — do not collapse the two.

## Overclaim / correction notes on the extractions
- All five extractions are accurate on the three load-bearing claims. No overclaims found on Claims 1–3.
- Minor: `changePaymentType` type check at `PatientResource.java:321` uses `==` on Strings with its `throw` commented out (`:322`) — confirmed a dead no-op; reproduce as no-op. The endpoint is genuinely ungated (`:311`).
- `paymentType` legacy vocabulary is exactly `CASH`/`INSURANCE` (register branch `:296-305`; `changePaymentType` collapses non-INSURANCE to `CASH` `:368-372`); doc's `DEBIT_CARD/CREDIT_CARD/MOBILE` are invented (entity comment lists them but code rejects them — Extraction 3 §status-catalogue is correct).

Relevant files (absolute):
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java`
- `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java`
- `D:/My_Works/HMS/HMSCLEAN2/backend/src/main/resources/db/migration/V2__seed_iam.sql`
- `D:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md` (DRIFT source)