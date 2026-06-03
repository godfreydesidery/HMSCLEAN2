I have the ADR-0007 redaction model. The taxonomy I produce drives the `@PiiField` annotation. The Registration & Patient context is in audit scope (Patient, Registration, Visit; chain nullable). I have everything needed.

---

# Security & Compliance — Registration Context (Increment 03)

Cited to verified legacy extractions and `Patient.java` (`D:/.../com/orbix/api/domain/Patient.java`), `V2__seed_iam.sql`, ADR-0007.

## (1) PHI/PII field taxonomy for `Patient` + ADR-0007 redaction rules

ADR-0007:30,75,143 model: a `@PiiField`-annotated field is replaced with `"[REDACTED]"` in `before_state`/`after_state` JSON; the structural diff (which field changed) is preserved. Registration context is audit-in-scope, chain_checksum nullable (ADR-0007:182). My taxonomy below is the authoritative `@PiiField` inclusion list for `Patient` — hand to **data-architect** (owns the field inventory) and **backend-engineer** (applies the annotation).

`@PiiField` = **REDACT** in audit JSON. Fields are the 25 in `Patient.java:42-106` (Extraction 1 §2).

| Field | Classification | `@PiiField`? | Rationale |
|---|---|---|---|
| `firstName`, `middleName`, `lastName` (`Patient.java:54-58`) | PHI (identifier) | YES | Name = direct identifier; HIPAA 18-identifier list. |
| `dateOfBirth` (`:59-60`) | PHI (identifier) | YES | Full DOB is a HIPAA identifier. |
| `phoneNo` (`:74`) | PHI/PII | YES | Telephone = HIPAA identifier. |
| `email` (`:76`) | PHI/PII | YES | Email = HIPAA identifier. |
| `address` (`:75`) | PHI/PII | YES | Geographic subdivision = HIPAA identifier. |
| `nationalId` (`:78`) | PII (gov ID) | YES | National identifier. |
| `passportNo` (`:79`) | PII (gov ID) | YES | Passport identifier. |
| `membershipNo` (`:70`) | PHI (health-plan beneficiary no.) | YES | Health-plan beneficiary number = HIPAA identifier; ties patient to insurance. |
| `kinFullName`, `kinPhoneNo` (`:83,:85`) | PII (third-party) | YES | Next-of-kin name + phone are identifiers of a related natural person. |
| `kinRelationship` (`:84`) | Low-sensitivity | NO (recommend redact-by-default) | Not an identifier alone, but contextually links to NOK; default-redact under ADR-0007:85 conservative posture until data-architect confirms. |
| `nationality` (`:77`) | Quasi-identifier | NO | Not a HIPAA identifier; retain for audit diff utility. Flag to data-architect. |
| `gender` (`:61-62`) | Quasi-identifier (demographic) | NO | Demographic, not a direct identifier; retain. |
| `no` / MRNO (`:45-47`) | Internal identifier — **NOT redacted in audit** | NO | This is the record locator; ADR-0007:28 stores `entity_uid` precisely to identify the row. Redacting `no` would defeat audit traceability. Keep visible. |
| `searchKey` (`:48-50`) | **Derived PHI — REDACT** | YES | `searchKey` = `no + firstName + middleName + lastName + phoneNo` concatenated (Extraction 1 §8, `PatientServiceImpl.java:739-744`). It embeds names + phone, so it is reconstituted PHI and must be redacted even though `no` is not. |
| `type`, `paymentType`, `active` | Non-PHI (workflow state) | NO | Workflow enums; retain for diff. |
| `insurancePlan` (FK, `:97-100`) | Non-PHI (reference) | NO | Plan reference; serialise as uid only, never embed plan PHI. |
| `createdBy`, `createdOn`, `createdAt` (`:102-106`) | Non-PHI (forensic) | NO | Operational denormalisation (ADR-0007:94); retain. |

Net: 11 fields REDACT (`firstName, middleName, lastName, dateOfBirth, phoneNo, email, address, nationalId, passportNo, membershipNo, kinFullName, kinPhoneNo` — plus `searchKey`, plus default-redact `kinRelationship`). **Critical non-obvious rule: `searchKey` MUST be `@PiiField` because it is a derived composite of names+phone; `no`/MRNO must NOT, because it is the audit locator.**

**Cross-cutting redaction rules (apply to all Registration endpoints):**
- No PHI in application logs, exception messages, or API error bodies (e.g., the unguarded `.get()` at `PatientResource.java:297` must not echo `membershipNo`/plan name in the 500).
- No PHI in URLs/query strings — but note `load_patients_like?name_like=` and `get_by_search_key?search_key=` (Extraction 4 §a) pass **name/phone substrings as GET query params**. These reach access logs. **Finding R-1 (Medium):** search terms containing names/phone in the query string will land in web-server/proxy access logs = PHI leak. Recommend POST-with-body for the search endpoints, or guaranteed access-log scrubbing of `name_like`/`search_key`. This is a control recommendation, not a process change (the search behaviour itself is preserved).
- No PHI in JWT claims beyond the subject/user uid and authorities (consistent with ADR-0006).
- MRNO (`no`) embeds the patient PK (Extraction 2 §a, `PatientServiceImpl.java:250`). It is a low-sensitivity record locator, acceptable in `entity_uid`/logs; it is not itself PHI but is enumerable — note for threat model, not a redaction target.

## (2) RBAC mapping — Registration endpoints to LIVE privilege codes ONLY

Live seeded PATIENT codes (`V2__seed_iam.sql:55-60`): `PATIENT-ALL`, `PATIENT-CREATE`, `PATIENT-UPDATE`, `PATIENT-A`, `PATIENT-C`, `PATIENT-U`. The `-A/-C/-U` family is seeded but appears only in commented-out legacy gates in the patient surface (Extraction 5 §3). **Planning-doc codes `PATIENT-EDIT`, `PATIENT-VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK` are REJECTED — they do not exist in seed or legacy** (Extraction 5 §3, Adversarial DRIFT #7).

Mapping = exact legacy `@PreAuthorize` parity (Extraction 5 §2, Adversarial Claim 3):

| Modern endpoint (legacy action) | Spring Security 6 authority expression | Legacy cite |
|---|---|---|
| Register patient (`do_register`) | `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")` | `PatientResource.java:288-289` |
| Update patient (`update`) | `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")` | `:378-379` |
| Change type (OUTPATIENT↔OUTSIDER) | `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")` | `:398-399` |
| Send-to-doctor (`do_consultation`) | `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')")` | `:508-509` |
| Switch-to-normal / cancel / free consultation | `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')")` | `:547-548,:605-606,:680-681` |
| Read/search (`get`, `get_by_search_key`, `get_all_search_keys`, `load_patients_like[_and_card]`, `last_visit_date_time`, list) | **No privilege gate — authenticated-only (parity)** | `:261,267,274,281,788,5162,5172` (no `@PreAuthorize`) |

**Authorization-parity findings requiring engagement-lead decision (do not silently fix):**
- **R-2 (High) — `change_payment_type` ships ungated.** Legacy `@PreAuthorize` is commented out (`PatientResource.java:310-311`; Adversarial Claim 3). Exact-process = ungated, but a CASH↔INSURANCE payment-type change is a financially material mutation. **Recommendation:** treat as a change request to gate with `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` (by analogy to `update`/`change_type`). Flag to engagement-lead; do NOT invent a new code.
- **R-3 (Medium) — read/search endpoints have no view privilege.** Parity = authenticated-only reads; there is no `PATIENT-VIEW` in legacy. Per exact-process, default to authenticated-only (do not invent `PATIENT-VIEW`). If a read gate is desired it is a change request. Note this means any authenticated user can run `load_patients_like`/`get_all_search_keys` (full-table PHI enumeration — see R-4).

## (3) Authorization-parity concerns — the cash gate and the deceased gate

**Cash gate (R-5):** There is **NO registration-fee gate on send-to-doctor** in legacy (Extraction 3 §b, Adversarial Claim 2). `do_consultation` pre-conditions are only: `follow_up` ∈ {0,1} (`PatientResource.java:516-522`), no active admission (`:531-534`), `type == "OUTPATIENT"` (`:535-537`), clinician active (`PatientServiceImpl.java:427`), no existing pending/active consultation (`:447-455`). The pay-before-service gate is on the **consultation `PatientBill`** (status `PAID`/`COVERED`, plus `NONE` for follow-up), enforced at **doctor-open time** (`open_consultation`, `:884-897`) with a soft queue-filter (`:822-826`) — never on the registration bill. **Parity ruling:** a consultation-bill gate at open = PARITY and aligns with the inc-04 scoped SettlementPolicy (pay-before-service applied to the consultation charge via `BillingCommands.recordClinicalCharge` + per-bill `settled` flag). **Any inc-03 gate that blocks send-to-doctor on an unpaid REGISTRATION fee is NET-NEW and requires an explicit engagement-lead change request.** Security position: do not approve a registration-fee send-block as "parity."

**Deceased gate (R-6):** There is **NO `deceased` boolean** on `Patient` and **no explicit deceased guard** on send-to-doctor (Extraction 3 §d, Adversarial DRIFT #10; `Patient.java:42-106` has no such field). Deceased = `type == "DECEASED"`, set only via the `save_deceased_note` workflow (`PatientResource.java:5693,5901,5921`). A deceased patient is excluded from `do_consultation` only **incidentally** because `do_consultation` requires `type == "OUTPATIENT"` (`:535-537`) and `change_type` cannot move a `DECEASED` patient back (`:499-503`). **Parity ruling:** reproduce the `type`-enum mechanism + the OUTPATIENT-only check. The planning doc's invented `deceased` boolean, `PATCH .../deceased`, and explicit `PATIENT_DECEASED` send-guard are DRIFT — do not implement an explicit deceased gate; it would be NET-NEW behaviour. Flag to engagement-lead if the spec insists on it.

**R-4 (Medium) — full-table PHI enumeration via unbounded reads.** `get_all_search_keys` returns every patient's `searchKey` (= names+phone composite) with no paging and no privilege (Extraction 4 §a, `PatientResource.java:281`); `load_patients_like` OR-matches across name/phone/membership with no paging. Combined with R-3, any authenticated user can enumerate the full PHI set. Exact-process preserves the behaviour, but recommend (change-request) a privilege gate and server-side paging. For audit: reads are NOT audited by ADR-0007 (CREATE/UPDATE/DELETE only) — if PHI access-logging for reads is a compliance requirement, that is a net-new control to raise with data-architect (separate from the mutation audit_log).

## Summary of findings handed back

- **R-1 (Med):** PHI (names/phone) in GET query strings on search endpoints → access-log leak. Recommend POST-body or log scrubbing.
- **R-2 (High):** `change_payment_type` ungated in legacy → change-request to gate `PATIENT-ALL`/`PATIENT-UPDATE`; engagement-lead decision.
- **R-3 (Med):** No `PATIENT-VIEW` exists; reads are authenticated-only (parity). Do not invent a view code.
- **R-4 (Med):** Unbounded, ungated full-table PHI enumeration via `get_all_search_keys`/`load_patients_like`; reads unaudited. Compliance read-log = net-new control for data-architect.
- **R-5 (cash gate):** Registration-fee send-block = NET-NEW; only the consultation-bill open-time gate is parity.
- **R-6 (deceased gate):** No explicit deceased gate exists; `type="DECEASED"` + OUTPATIENT-only check is parity. Invented boolean/PATCH/guard = DRIFT.

**`@PiiField` redaction list (for data-architect + backend-engineer):** `firstName, middleName, lastName, dateOfBirth, phoneNo, email, address, nationalId, passportNo, membershipNo, kinFullName, kinPhoneNo, searchKey` (REDACT); `kinRelationship` (default-redact, confirm); `no`/MRNO explicitly NOT redacted (audit locator).

**Phantom-feature confirmation (standing obligation):** no `@Audited`/Envers and no device-fingerprint/device-binding in the legacy Registration path (Extractions 1,3,5 + Adversarial). No device-binding requirement exists for this context; any proposal to add one is a change request. Audit scope is driven by ADR-0007 compliance requirements + confirmed mutation points (register/update/change_type/do_consultation create Patient, Registration, Visit, PatientBill), NOT by Envers annotation coverage (zero).

Relevant files: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/domain/Patient.java`, `.../api/PatientResource.java`, `.../service/PatientServiceImpl.java`; `D:/My_Works/HMS/HMSCLEAN2/backend/src/main/resources/db/migration/V2__seed_iam.sql`; `D:/My_Works/HMS/HMSCLEAN2/docs/adr/0007-audit-trail.md`; DRIFT source `D:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md`.