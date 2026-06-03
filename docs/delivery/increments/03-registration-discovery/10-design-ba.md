Both artifacts confirmed. I have everything needed to produce the BA deliverables.

---

# INCREMENT 03 — Registration & Patient: BA / Product Owner Deliverables

Authored from the 5 file:line-cited extractions + adversarial verification. Legacy is the source of truth; the planning doc (`03-registration-patient.md`) is the DRIFT source and is overridden wherever it disagrees with cited legacy code.

## DELIVERABLE 1 — Feature / Endpoint Inventory (inc-03)

Legacy base path `/zana-hmis-api`. Gate column: ACTIVE = enforced `@PreAuthorize`; OPEN = annotation commented out (ships authenticated-only); NONE = no annotation.

| # | Legacy endpoint | Modern intent (corrected) | Legacy gate (verbatim) | Cite |
|---|---|---|---|---|
| F1 | `POST /patients/register` | Register patient (CASH/INSURANCE) | ACTIVE `PATIENT-ALL,PATIENT-CREATE` | PatientResource.java:288-289 |
| F2 | `POST /patients/update` | Edit demographics/payment | ACTIVE `PATIENT-ALL,PATIENT-UPDATE` | :378-379 |
| F3 | `POST /patients/change_payment_type` | Flip CASH↔INSURANCE | **OPEN** (gate commented :311) | :310-376 |
| F4 | `POST /patients/change_type` | Flip OUTPATIENT↔OUTSIDER | ACTIVE `PATIENT-ALL,PATIENT-UPDATE` | :398-506 |
| F5 | `POST /patients/do_consultation` | **THE "send-to-doctor"** (1:1 map) | ACTIVE `PATIENT-ALL,PATIENT-CREATE,PATIENT-UPDATE` | :508-541 |
| F6 | `POST /patients/cancel_consultation` | Cancel PENDING consultation (+ credit note refund) | ACTIVE same 3 | :605-678 |
| F7 | `POST /patients/free_consultation` | Sign-out IN-PROCESS/TRANSFERED | ACTIVE same 3 | :680-770 |
| F8 | `POST /patients/switch_to_normal_consultation` | Follow-up → normal | ACTIVE same 3 | :547-565 |
| F9 | `GET /patients` | List all (no paging) | NONE | :261 |
| F10 | `GET /patients/get_by_search_key` | Exact searchKey lookup | NONE | :267-272 |
| F11 | `GET /patients/get` | Get by id | NONE | :274 |
| F12 | `GET /patients/get_all_search_keys` | All searchKeys (typeahead source) | NONE | :281-286 |
| F13 | `GET /patients/load_patients_like` | Fuzzy search (5 keys) | NONE | :5162-5170 |
| F14 | `GET /patients/load_patients_like_and_card` | Fuzzy search + membershipNo (6 keys) | NONE | :5172-5180 |
| F15 | `GET /patients/last_visit_date_time` | Last-visit timestamp | NONE | :788-804 |
| F16 | `POST /patients/save_deceased_note` | Deceased record (sets type=DECEASED via summary flow) | OPEN (commented `PATIENT-A,C,U`) | :5693 |

**There is NO `send_to_doctor`/`sendToDoctor` endpoint in legacy (zero grep matches).** Modern `send-to-doctor` = legacy `do_consultation` (F5).

Entities in scope (distinct — do NOT collapse): `Patient` (master), `Registration` (OneToOne to Patient + OneToOne to reg-fee `PatientBill`, status `ACTIVE`), `Visit` (ManyToOne encounter log), `PatientBill` (reg fee + consultation fee), `PatientInvoice`/`PatientInvoiceDetail` (insurance claim), `Consultation`, `DeceasedNote`.

---

## DELIVERABLE 2 — Acceptance Criteria (testable, QA-verifiable without legacy source)

Data-classification note applies to all stories touching `dateOfBirth, phoneNo, email, address, nationalId, passportNo, membershipNo, kin*` = **PHI/PII** — anonymized test data only.

### STORY S1 — Register CASH patient [safety-critical, PHI]
RBAC: `PATIENT-ALL` OR `PATIENT-CREATE` (PatientResource.java:289).
- **AC1.1** Required (`@NotBlank`/`@NotNull`): `firstName, lastName, dateOfBirth, gender, type, paymentType`; missing any → validation error (Patient.java:54-69).
- **AC1.2** Any client-supplied `no` (incl `""`/`"NA"`) is ignored; server overwrites with `MRNO/{year}/{id}` (PatientServiceImpl.java:250). Format = literal `MRNO/` + 4-digit calendar year + `/` + numeric patient PK; **no zero-pad, no per-year reset** (e.g. `MRNO/2026/4137`).
- **AC1.3** `paymentType=CASH` → `insurancePlan` forced null (PatientResource.java:303-304).
- **AC1.4** A `PatientBill` is created unconditionally: `billItem="Registration"`, `description="Registration Fee"`, `qty=1`, `amount=balance=CompanyProfile.registrationFee`, `status="UNPAID"` if fee>0 else `"VERIFIED"` (PatientServiceImpl.java:267-277). There is **no `billType` field** — categorization is `billItem`.
- **AC1.5** A `Registration` row (`status="ACTIVE"`, OneToOne to patient, linked to reg bill) and a `Visit` (`sequence="FIRST"`, `status="PENDING"`, `type=patient.type`) are created in the same transaction (PatientServiceImpl.java:293-302, 409-419).
- **AC1.6** `searchKey` = sanitized `no + " " + firstName + " " + middleName + " " + lastName + " " + phoneNo`, whitespace-collapsed, `+`→space, with the regex `[+^]*#$%&` applied (strips only the literal `#$%&` sequence), **case preserved** (PatientServiceImpl.java:739-744; Sanitizer.java:11-17). Reproduce verbatim — byte-identical keys required.
- **AC1.7** `createdBy`=user id, `createdOn`=business-day id, `createdAt`=`now()+3h` (DayServiceImpl.java:86-87). **No `updatedBy/On/At`** fields exist.

### STORY S2 — Register INSURANCE patient [safety-critical, PHI]
- **AC2.1** Client sends plan **name**; server re-resolves via `insurancePlanRepository.findByName(...)` to managed entity (PatientResource.java:297-298).
- **AC2.2** Empty `membershipNo` → `MissingInformationException("Membership number required")` (PatientResource.java:299-301).
- **AC2.3** If `paymentType=INSURANCE` AND `regFee>0` AND a `RegistrationInsurancePlan(plan, covered=true)` exists: reg bill rewritten to `amount=paid=plan.registrationFee`, `balance=0`, `status="COVERED"`, `paymentType="INSURANCE"`; a `PENDING` `PatientInvoice` + `PatientInvoiceDetail` (qty 1, desc "Registration Fee") created/reused (PatientServiceImpl.java:320-402).
- **AC2.4 (edge)** INSURANCE but no covered `RegistrationInsurancePlan` → bill stays as cash-style `UNPAID` with CompanyProfile fee, NO invoice claim (PatientServiceImpl.java:321 fall-through). Verify exactly.
- **AC2.5 (edge)** INSURANCE but `regFee==0` → entire insurance block skipped; bill stays `status="VERIFIED"`, no invoice (PatientServiceImpl.java:312).

### STORY S3 — Send-to-doctor (`do_consultation`) [safety-critical, PHI]
RBAC: `PATIENT-ALL` OR `PATIENT-CREATE` OR `PATIENT-UPDATE` (:509).
- **AC3.1** Pre-guards in order: `follow_up` must be 0 or 1 else `InvalidEntryException` (:516-522); block if patient has PENDING/IN-PROCESS admission → `"Could not process consultation, the patient has an active admission"` (:528-534); patient `type` must equal `"OUTPATIENT"` else `"Please change patient type to OUTPATIENT to continue with operation"` (:535-537); clinician must be active (PatientServiceImpl.java:427-429); no existing PENDING/TRANSFERED consultation (:447-450); no IN-PROCESS consultation (:451-455).
- **AC3.2** **There is NO registration-fee check at send-to-doctor.** Registration bill status is never read in this path (verified Claim 2). Do NOT add one (see CR-01).
- **AC3.3** Atomically creates: consultation `PatientBill` (`billItem="Consultation"`, `amount=balance=clinic.consultationFee`, `qty=1`, `status="UNPAID"`, **but `"NONE"` if follow_up=true**, PatientServiceImpl.java:459-469); `Consultation` (`status="PENDING"`, `paymentType=patient.paymentType`, 1:1 `patientBill` non-null per Consultation.java:70-73); `Visit` (`sequence="SUBSEQUENT"` hard-coded, `type=patient.type`, `status="PENDING"`, PatientServiceImpl.java:501-512). All-or-nothing (single tx).
- **AC3.4** INSURANCE coverage: looks up `ConsultationInsurancePlan(clinic, plan, covered=true)`; if absent → `"Plan not available for this clinic. Please change payment method"` (:599-601); on coverage, bill → `amount=paid=plan fee, balance=0, status="COVERED"` (or `"NONE"` if follow-up) + `PatientInvoiceDetail` appended (:606-674).
- **AC3.5** Service returns `null`; controller wraps in 201 (PatientServiceImpl.java:678). See CR-06.
- **AC3.6 (no same-day dedup)** Each call creates a NEW `SUBSEQUENT` Visit — repeated sends same day → multiple Visit rows. The "reuse if last visit not today" comment is dead (no date check). Reproduce as-is.

### STORY S4 — Consultation-fee payment gate (the REAL gate) [safety-critical]
- **AC4.1** Queue filter (soft, UI): `load_pending_consultations_by_clinician_id` shows a consultation only if its `PatientBill.status` is `"PAID"` OR `"COVERED"` (PatientResource.java:822-826).
- **AC4.2** Hard gate at doctor-open: `open_consultation` sets `IN-PROCESS` only if consultation bill is `"PAID"`/`"COVERED"`, else throws `InvalidOperationException("Could not open. Payment not verified.")`; non-PENDING → `"Could not open. Not a pending consultation."` (:884-897). Follow-up open additionally accepts `"NONE"` (:914).
- **AC4.3** The gate is on the **consultation** bill at **open time** — never the registration bill, never at send time.

### STORY S5 — Patient search [PHI]
- **AC5.1** `load_patients_like?name_like=X` OR-matches `LIKE %X%` across `no, firstName, middleName, lastName, phoneNo` (PatientRepository.java:41-42).
- **AC5.2** `load_patients_like_and_card?name_like=X` adds `membershipNo` as 6th OR-key (PatientRepository.java:50-51).
- **AC5.3** `get_by_search_key` exact-matches `searchKey`; absent → `NotFoundException("Patient not found")` (PatientServiceImpl.java:218-224).
- **AC5.4** Legacy has **no server-side pagination** and **no DOB/nationalId/passport search**. Typeahead filters client-side, min 4 chars (patient-register.component.ts:1470-1484). See CR-07.
- **AC5.5** Read endpoints have **no `@PreAuthorize`** (authenticated-only). No `PATIENT-VIEW` exists. See CR-04.
- **AC5.6** `last_visit_date_time` returns `createdAt` of the last element of unordered `findAllByPatient` (PatientResource.java:795-799). Intended semantics = most-recent by `createdAt`. See CR-08.

### STORY S6 — Type flip (`change_type`) [safety-critical]
RBAC: `PATIENT-ALL` OR `PATIENT-UPDATE` (:399). Only toggles OUTPATIENT↔OUTSIDER.
- **AC6.1** Null `type` coerced to `"OUTPATIENT"` (:410-414).
- **AC6.2** OUTPATIENT→OUTSIDER blocked if any PENDING/IN-PROCESS/TRANSFERED `Consultation` → `"Can not change patient type, the patient has an active consultation."` (:421-428).
- **AC6.3** OUTSIDER→OUTPATIENT: walk all NonConsultation PENDING lab/radiology/procedure orders; `UNPAID` bills deleted with the order; any other bill status → `cancelable=false` → `"Can not change patient type, the has pending paid services. Please consider clearing with the patient."` (:429-495).
- **AC6.4** `INPATIENT` → `"This operation is not allowed for inpatients"` (:499-500). Cannot set INPATIENT or DECEASED via this endpoint.

### STORY S7 — Payment-type flip (`change_payment_type`) [safety-critical]
- **AC7.1** Blocked if any `Consultation` in PENDING/IN-PROCESS/STOPPED/HELD, any `NonConsultation` with non-empty lab/radiology/procedure work, or any matching `Admission` → `"Could not change. Patient has an ongoing medical operation s"` (verbatim, sic) (:331-357). NonConsultations with no work auto `SIGNED-OUT` (:350-351).
- **AC7.2** INSURANCE requires non-empty membershipNo (:359-367); any non-INSURANCE value collapses to `"CASH"`, clears plan/membership (:368-373).
- **AC7.3** The `type==` String check at :321 with commented-out throw is a dead no-op — reproduce as no-op.
- **AC7.4** Endpoint is **ungated** in legacy (gate commented :311). See CR-03.

### STORY S8 — Deceased [safety-critical, PHI]
- **AC8.1** There is **no `deceased` boolean** on `Patient` (verified Patient.java:41-107). Deceased = `type="DECEASED"`, set only via the `get_deceased_summary` flow when an approved `DeceasedNote`'s linked admission/consultation is `HELD` and all related `PatientInvoice` bills are cleared, else `"Could not get deceased summary. Patient have uncleared bills."` (PatientResource.java:5857-5921).
- **AC8.2** A `DECEASED` patient cannot pass the OUTPATIENT check in `do_consultation`, and `change_type` cannot move DECEASED back → effectively frozen. There is **no explicit deceased guard** on send-to-doctor — it is incidental to the OUTPATIENT requirement. See CR-05.

---

## DELIVERABLE 3 — CHANGE-REQUEST REGISTER

Each tagged PARITY-RISK / NET-NEW / FIX. Approval authority = `engagement-lead` at phase gate (I maintain + impact-assess only).

| CR | Title | Tag | Legacy reality (cite) | Planning-doc claim | Impact / Recommendation |
|---|---|---|---|---|---|
| **CR-01** | Registration-fee gate blocking send-to-doctor | **NET-NEW** | No reg-fee check in `do_consultation`; gate is on consultation bill at open (PatientResource.java:528-537, 884-897) | Doc step 4 + DoD: `sendToDoctor` returns 422 `REGISTRATION_FEE_UNPAID` if reg bill UNPAID (03:38,59,92) | This is a hard process change, NOT parity. **Recommend:** REJECT as "parity"; if business wants it, log as NET-NEW with engagement-lead sign-off. Parity gate belongs on consultation bill at doctor-open (inc-05). Do NOT write AC as legacy reproduction. |
| **CR-02** | MR-number generator: `seq_mrno` vs IDENTITY-PK | **PARITY-RISK** | `MRNO/{Year.now()}/{patient.getId()}`, PK-coupled, no padding, no per-year reset (PatientServiceImpl.java:250) | `nextval('seq_mrno')`, EAT-zoned year, "gap-free per year", "replaces MAX(id)+1 race" (03:52-53,90) | `seq_mrno` **decouples number from PK** (observable: `MRNO/2026/1457` no longer ⇒ id 1457). The "MAX(id)+1 race" premise is false (legacy never used MAX+1). **Recommend:** either (a) keep number = surrogate id for byte-parity, or (b) approve decoupling as CR. EAT-year pin is an improvement — confirm legacy server TZ first. |
| **CR-03** | Secure `change_payment_type` | **FIX** | `@PreAuthorize` commented; ships ungated (PatientResource.java:311) | Doc implies all endpoints gated | **Recommend:** apply `PATIENT-ALL,PATIENT-UPDATE` by analogy with `change_type`. Reducing-privilege-removal requires no CR (it's adding a missing guard) but flag to security-architect. |
| **CR-04** | Patient read endpoints / `PATIENT-VIEW` | **PARITY-RISK** | All reads ungated; no `PATIENT-VIEW` exists (PatientResource.java:261-281; V2 seed has none) | Doc requires `PATIENT-VIEW` on reads (03:46,98) | `PATIENT-VIEW` is invented. **Recommend:** default to exact-process (reads = authenticated-only) UNLESS engagement-lead approves a NET-NEW read gate. Do not invent the privilege. |
| **CR-05** | `deceased` boolean + `PATCH .../deceased` + `PATIENT_DECEASED` send-guard | **PARITY-RISK / partly NET-NEW** | No boolean; `type="DECEASED"` via `save_deceased_note` workflow; no explicit deceased guard on send (Patient.java:41-107; PatientResource.java:5693,5901) | `deceased` boolean, dedicated PATCH, explicit 422 `PATIENT_DECEASED` (03:41,67-68,96) | The boolean + PATCH + explicit guard are invented. **Recommend:** reproduce legacy (`type=DECEASED` via deceased-note flow; send blocked incidentally by OUTPATIENT check). If business wants an explicit guard/flag, log as NET-NEW. |
| **CR-06** | `do_consultation` returns null | **FIX** | Service returns `null`, 201 with null body (PatientServiceImpl.java:678) | Doc implies returns consultation | **Recommend:** return created consultation/patient in modern build (DX/UX improvement, behaviour-preserving on 201). |
| **CR-07** | Pagination on search | **PARITY-RISK** | No server pagination; full-table loads; client-side filter min-4-char (PatientRepository; component.ts:1470) | Paginated `GET /patients?query=` (03:24,94) | Pagination is an architecture/perf improvement that preserves results. **Recommend:** APPROVE as behaviour-preserving improvement (note: changes response envelope — coordinate with frontend-engineer/OpenAPI). |
| **CR-08** | `last_visit` ordering | **FIX** | Last element of **unordered** `findAllByPatient` (PatientResource.java:795-799); unguarded `.get()` | Doc: last-visit timestamp | **Recommend:** implement explicit `ORDER BY createdAt DESC LIMIT 1` + guard missing id (preserves intended semantics, fixes fragility). |
| **CR-09** | searchKey composition | **PARITY-RISK** | `no + firstName + middleName + lastName + phoneNo`, case-preserved, regex `[+^]*#$%&` (PatientServiceImpl.java:739-744) | `firstName + lastName`, **lowercased** (03:36,71) | Doc's composition + lowercasing are wrong — would change stored keys & lookups. **Recommend:** reproduce legacy composition verbatim (incl. case preservation and the latent-bug regex) for parity; do not lowercase. |
| **CR-10** | `paymentType` enum set | **PARITY-RISK** | Only `CASH`/`INSURANCE` accepted; others rejected at :581 | `CASH,INSURANCE,DEBIT_CARD,CREDIT_CARD,MOBILE` (03:13) | `DEBIT_CARD/CREDIT_CARD/MOBILE` invented (entity comment lists them but code rejects). **Recommend:** model only `CASH`/`INSURANCE`. Adding others = NET-NEW. |
| **CR-11** | `patientType` enum set | **PARITY-RISK** | 4 values: `OUTPATIENT,OUTSIDER,INPATIENT,DECEASED` (PatientResource.java:411,426,321,5901) | Only `OUTPATIENT,OUTSIDER` (03:13) | Doc enum incomplete. **Recommend:** model all 4; `change_type` only toggles OUTPATIENT↔OUTSIDER (INPATIENT/DECEASED set by other flows). |
| **CR-12** | Registration fee source | **PARITY-RISK** | `CompanyProfile.registrationFee` singleton, loop-all-last-wins (PatientServiceImpl.java:230-233,268) | `ServicePrice.resolve(kind=REGISTRATION)` / `md_service_price` (03:47,55-56) | Reg fee is config, NOT a price-table lookup (consultation fee IS `clinic.consultationFee`). **Recommend:** source CASH reg fee from CompanyProfile-equivalent config; resolve multi-row nondeterminism (CR sub-item). |
| **CR-13** | `billType=REGISTRATION` field | **FIX** | No `billType` on `PatientBill`; field is `billItem="Registration"` (PatientBill.java:44; PatientServiceImpl.java:271) | `billType=REGISTRATION` (03:15,91) | **Recommend:** use `billItem` value `"Registration"`. Do not invent `billType`. (Modern may add an enum but must emit/categorize equivalently.) |
| **CR-14** | NextOfKin = up to 3 embedded | **PARITY-RISK** | Exactly ONE next-of-kin, 3 flat String columns (`kinFullName/kinRelationship/kinPhoneNo`), no entity (Patient.java:83-85) | "up to three NextOfKin embedded entries" (03:13) | Doc invents a kin collection. **Recommend:** single next-of-kin (3 fields). Multiple kin = NET-NEW. |
| **CR-15** | Net-new audit trail (Envers / audit_log) | **NET-NEW (pre-approved)** | No `@Audited`, no Envers active; no `updatedBy/On/At`; forensic = scalar `createdBy/On/At` only (repo-wide zero matches) | `audit_log` rows, PHI redaction, `@PiiField` (03:80,97) | Audit trail is net-new per engagement memo (Envers is a phantom dep) — already accepted as net-new, NOT a legacy behaviour to port. **Recommend:** keep as net-new; do not write its AC as "legacy parity." |
| **CR-16** | Device-fingerprint / device-binding | **DROP** | Zero matches anywhere | (phantom dep) | **Recommend:** do not implement. Phantom feature. |

---

## Open queries routed (for `legacy-analyst` / `engagement-lead` / `healthcare-domain-expert`)
1. CR-02: confirm legacy production server TZ (decides if EAT-year pin is parity-preserving) and whether MR number must equal surrogate id. → engagement-lead.
2. CR-01/CR-05: confirm reg-fee send-gate and explicit deceased guard are intentional NET-NEW (not "parity"). → engagement-lead.
3. CR-09: confirm the `[+^]*#$%&` regex and case-preservation are reproduced verbatim (latent bug accepted). → engagement-lead change gate.
4. CR-12: confirm CompanyProfile multi-row handling (last-wins is nondeterministic). → legacy-analyst/data-architect.
5. AC2.4/AC2.5: confirm insurance-but-no-covered-plan and insurance-but-regFee==0 fall-throughs are intended. → healthcare-domain-expert.

## Relevant files (absolute)
- Legacy SoT: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/service/PatientServiceImpl.java`, `.../api/PatientResource.java`, `.../domain/Patient.java`, `.../domain/Registration.java`, `.../domain/Visit.java`, `.../domain/PatientBill.java`, `.../domain/Consultation.java`, `.../api/accessories/Sanitizer.java`, `.../service/DayServiceImpl.java`, `.../repositories/PatientRepository.java`
- Live privilege seed: `D:/My_Works/HMS/HMSCLEAN2/backend/src/main/resources/db/migration/V2__seed_iam.sql` (PATIENT codes :55-60)
- DRIFT source (override on conflict): `D:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md`