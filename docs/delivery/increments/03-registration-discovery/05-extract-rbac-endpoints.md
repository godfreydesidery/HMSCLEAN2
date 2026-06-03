I now have everything needed for a complete, file:line-cited extraction. Let me compile the findings.

# EXTRACTION 5 — Legacy RBAC + REST Endpoint Surface (Registration / Patient / Send-to-Doctor)

Source: `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java` (single `@RestController`, class declared at `PatientResource.java:188-194`, base path `/zana-hmis-api` at `PatientResource.java:189`). All citations are `PatientResource.java:<line>` unless noted.

## 1. Critical finding: there is NO `send_to_doctor` endpoint in the legacy code

A repository-wide scan for `send_to_doctor` / `sendToDoctor` / `to_doctor` across `com.orbix.api` returned **zero matches**. The planning doc's `sendToDoctor` / `POST /api/v1/patients/uid/{uid}/send-to-doctor` (03-registration-patient.md:16,29,39,61) is an **invented name**. The actual legacy "send patient to doctor" workflow is the **`do_consultation`** endpoint (`POST /patients/do_consultation`, `:508`), which creates the PENDING `Consultation` + its fee `PatientBill`/invoice for a named clinic+clinician. The modern `send-to-doctor` command maps 1:1 onto legacy `do_consultation`.

## 2. ACTIVE `@PreAuthorize` gates on registration / patient / consultation endpoints

Only **6 endpoints** carry a live (uncommented) `@PreAuthorize`. Every authority used is from the `PATIENT-ALL / PATIENT-CREATE / PATIENT-UPDATE` family. The `PATIENT-A / PATIENT-C / PATIENT-U` family appears **only in commented-out gates** — it is never an active gate anywhere in this file.

| # | Method + Path | Line | Active gate (exact) | Legacy purpose |
|---|---|---|---|---|
| 1 | `POST /patients/register` | `:288-289` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')` | Register new patient (CASH/INSURANCE); membership-no required for INSURANCE (`:296-301`) |
| 2 | `POST /patients/update` | `:378-379` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` | Update patient demographics/payment (`:380-396`) |
| 3 | `POST /patients/change_type` | `:398-399` | `hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')` | Toggle OUTPATIENT↔OUTSIDER (guards active consultations/non-consultations) (`:400-506`) |
| 4 | `POST /patients/do_consultation` | `:508-509` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | **THE "send-to-doctor" action** — create PENDING consultation for clinic+clinician (`:510-541`) |
| 5 | `POST /patients/switch_to_normal_consultation` | `:547-548` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | Convert follow-up → normal consultation (`:549-565`) |
| 6 | `POST /patients/cancel_consultation` | `:605-606` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | Cancel PENDING consultation; cancels bill, refunds via `PatientCreditNote`, deletes `PatientInvoiceDetail` (`:607-678`) |
| 7 | `POST /patients/free_consultation` | `:680-681` | `hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')` | Sign-out / free an IN-PROCESS or TRANSFERED consultation (`:682-770`) |

Note: `change_payment_type` (`:310`) has its gate **commented out** (`:311`) — it ships effectively **un-gated** (authenticated-only). Flag as ambiguity.

## 3. RECONCILIATION vs. the 35 live privilege codes (V2__seed_iam.sql)

The seed file (`D:/My_Works/HMS/HMSCLEAN2/backend/src/main/resources/db/migration/V2__seed_iam.sql`) contains these PATIENT-family codes and nothing else patient-related:
- `PATIENT-ALL` (`:55`), `PATIENT-CREATE` (`:56`), `PATIENT-UPDATE` (`:57`)
- `PATIENT-A` (`:58`), `PATIENT-C` (`:59`), `PATIENT-U` (`:60`)
- `PRODUCT-CREATE` (`:61`) — used by commented attachment/lab/radiology gates only

**Planning-doc codes that DO NOT EXIST** (neither in legacy `@PreAuthorize` nor in V2 seed) — confirmed DRIFT (03-registration-patient.md:46,98):
- `PATIENT-EDIT` — **does not exist**. The real update gate is `PATIENT-UPDATE`.
- `PATIENT-VIEW` — **does not exist**. Legacy GET/read endpoints are **un-gated** (see §4); there is no view privilege at all.
- `REGISTRATION-ALL` — **does not exist**. Registration is gated by `PATIENT-ALL`/`PATIENT-CREATE`.
- `CONSULTATION-BOOK` — **does not exist**. The "book consultation / send-to-doctor" (`do_consultation`) is gated by `PATIENT-ALL`/`PATIENT-CREATE`/`PATIENT-UPDATE`.

**Real codes that SHOULD gate each modern endpoint** (exact-process):
| Modern endpoint | Correct legacy authority set |
|---|---|
| Register patient | `PATIENT-ALL` OR `PATIENT-CREATE` |
| Update patient | `PATIENT-ALL` OR `PATIENT-UPDATE` |
| Change type | `PATIENT-ALL` OR `PATIENT-UPDATE` |
| Send-to-doctor (`do_consultation`) | `PATIENT-ALL` OR `PATIENT-CREATE` OR `PATIENT-UPDATE` |
| Switch to normal / cancel / free consultation | `PATIENT-ALL` OR `PATIENT-CREATE` OR `PATIENT-UPDATE` |
| List/search/get patient (reads) | **No legacy gate** — see ambiguity below |

The `PATIENT-A / PATIENT-C / PATIENT-U` codes are real (seeded) but in **this file** appear only in dead commented gates (e.g. `:572,595,5184,5214…`). They are presumably active in other resources — do not assume they gate patient registration.

## 4. AMBIGUITY: read endpoints and many write endpoints are UN-GATED

These are **authenticated-only** in legacy (no `@PreAuthorize`, no class-level `@Secured`/`@PreAuthorize` on `PatientResource` — class annotations at `:188-193` carry only `@RestController/@RequestMapping/@RequiredArgsConstructor/@CrossOrigin/@MultipartConfig/@Transactional`):
- All `GET /patients/*` reads (e.g. `:261,267,274,281`) — no authority required.
- Many clinical writes (e.g. `save_lab_test :1931`, `save_radiology :1974`, `do_admission :5183`, all charts/diagnosis/discharge/referral/deceased endpoints) — **gates commented out** (`:1932,1975,5184…`).

Recommended question for `engagement-lead`/`business-analyst`: should the modern build (a) reproduce legacy exactly = reads require only authentication, no view privilege; or (b) introduce a read gate? Per "exact process," default to (a) unless a change request is approved. Do **not** invent `PATIENT-VIEW`.

## 5. Full legacy patient/registration endpoint inventory (method + path + purpose)

Base path prefix `/zana-hmis-api`. (Gate column: ACTIVE = enforced; commented = ships open; none = no annotation.)

**Patient registry / lifecycle (registration module core):**
- `GET /patients` `:261` — list all patients. (none)
- `GET /patients/get_by_search_key` `:267` — find by search key. (none)
- `GET /patients/get` `:274` — find by id. (none)
- `GET /patients/get_all_search_keys` `:281` — distinct search keys (autocomplete). (none)
- `POST /patients/register` `:288` — register patient. **ACTIVE `PATIENT-ALL,PATIENT-CREATE`**
- `POST /patients/change_payment_type` `:310` — switch CASH↔INSURANCE; blocks if active operations (`:331-357`). (gate commented — open)
- `POST /patients/update` `:378` — update patient. **ACTIVE `PATIENT-ALL,PATIENT-UPDATE`**
- `POST /patients/change_type` `:398` — OUTPATIENT↔OUTSIDER toggle with active-work guards. **ACTIVE `PATIENT-ALL,PATIENT-UPDATE`**
- `GET /patients/load_patients_like` `:5162` — fuzzy patient search. (none)
- `GET /patients/load_patients_like_and_card` `:5172` — fuzzy search incl. card. (none)
- `GET /patients/last_visit_date_time` `:788` — last visit timestamp (patient-list column). (none)

**Send-to-doctor / consultation lifecycle:**
- `POST /patients/do_consultation` `:508` — **send-to-doctor**: create PENDING consultation (rejects if active admission `:531-534`; requires type OUTPATIENT `:535-537`; follow_up flag 1/0 `:516-522`). **ACTIVE `PATIENT-ALL,PATIENT-CREATE,PATIENT-UPDATE`**
- `POST /patients/switch_to_normal_consultation` `:547` — follow-up → normal. **ACTIVE same 3 codes**
- `POST /patients/cancel_consultation` `:605` — cancel PENDING; bill→CANCELED, refund→`PatientCreditNote`, delete `PatientInvoiceDetail`. **ACTIVE same 3 codes**
- `POST /patients/free_consultation` `:680` — sign out IN-PROCESS/TRANSFERED consultation. **ACTIVE same 3 codes**
- `POST /patients/create_consultation_transfer` `:571` — transfer consultation to another clinic. (commented `PATIENT-A,C,U`)
- `GET /patients/get_consultation_transfers` `:594` — list PENDING transfers. (commented `PATIENT-A,C,U`)
- `GET /patients/cancel_consultation_transfer` `:983` — cancel transfer (stub "to do later"). (none)
- `GET /patients/get_active_consultations` `:771` — active consultation list. (none)
- `GET /patients/load_consultation` `:957` / `load_non_consultation` `:970` / `load_non_consultation_id` `:4069` — loaders. (none)
- `GET /patients/open_consultation` `:879` / `open_follow_up_consultation` `:905` / `switch_to_consultation_by_consultation_id` `:933` — clinician open (stubs). (none)
- `GET /patients/load_pending_consultations_by_clinician_id` `:806` / `load_follow_up_list_by_clinician_id` `:832` / `load_in_process_consultations_by_clinician_id` `:859` — doctor worklists (stubs). (none)

**Admission / discharge / referral / deceased (lifecycle, registration-adjacent):**
- `POST /patients/do_admission` `:5183` (commented `PATIENT-A,C,U`); `GET /patients/load_admission` `:1201` (none)
- `GET /patients/get_patient_direct_pending_invoices` `:5213`, `get_patient_insurance_pending_invoices` `:5224`, `get_patient_invoice` `:5237` (all commented `PATIENT-A,C,U`)
- `POST /patients/save_discharge_plan` `:5253`; `GET load_discharge_plan` `:5292`, `load_discharge_list` `:5312`, `get_discharge_summary` `:5329` (all commented `PATIENT-A,C,U`)
- `POST /patients/save_referral_plan` `:5392`; `GET load_referral_plan` `:5536`, `load_referral_list` `:5562`, `get_referral_summary` `:5578` (all commented `PATIENT-A,C,U`)
- `POST /patients/save_deceased_note` `:5693`; `GET load_deceased_note` `:5777`, `load_deceased_list` `:5821`, `get_deceased_summary` `:5837` (all commented `PATIENT-A,C,U`). Note: legacy sets deceased via `save_deceased_note`, NOT the planning doc's invented `PATCH .../deceased` (03-registration-patient.md:41) — DRIFT.

Remaining endpoints (clinical notes, vitals, lab/radiology/procedure/prescription orders+results, charts, pharmacy issue/give-medicine, attachments) at `:1015–6330` are **clinical/pharmacy module surface**, out of scope for the Registration spec; all are either un-gated or gated by commented `PATIENT-A,C,U` / `PRODUCT-CREATE`. Listed in §2 grep output if needed for cross-reference.

## 6. Early-discovery findings (mandatory inclusions)

- **Audit trail:** No `@Audited` annotation appears on `PatientResource` or its referenced entities in this extraction scope; consistent with memory note `zana-legacy-phantom-features.md`. Restated: *No Hibernate Envers audit trail is effectively active in the legacy system — the Envers dependency is present but no entity is annotated `@Audited`. Downstream agents must not assume an Envers audit baseline.* (Full entity-scan confirmation belongs in the entity-level extraction.)
- **Device-fingerprint / device-binding:** No device-fingerprint or device-binding logic is present in `PatientResource` (security is purely `@PreAuthorize` authority checks). Restated: *No device-fingerprint or device-binding feature exists in the legacy system. Agents must not treat it as a feature to preserve.* (JWT-filter-level confirmation belongs in the security extraction.)

## 7. Ambiguity register (this extraction)

1. `change_payment_type` (`:310-311`) — gate commented out; ships open. Resolve intended privilege (likely `PATIENT-ALL,PATIENT-UPDATE` by analogy). → business-analyst.
2. All read endpoints un-gated (§4). Confirm "authenticated-only reads" is the exact process to reproduce (no `PATIENT-VIEW`). → engagement-lead.
3. Deceased flag set via `save_deceased_note` POST (`:5693`), not a PATCH on patient; planning doc's `PATCH .../deceased` + `PATIENT_DECEASED` guard inside send-to-doctor is NOT in legacy `do_consultation` (`:508-541`, which guards only active-admission and type). The deceased guard on send-to-doctor is a planning-doc INVENTION — flag before reproducing. → engagement-lead.
4. Planning-doc privilege set (`PATIENT-EDIT/VIEW`, `REGISTRATION-ALL`, `CONSULTATION-BOOK`) is fully invented (03-registration-patient.md:46,98). Modern endpoints must use `PATIENT-ALL/CREATE/UPDATE` exactly as §3 maps.

**Relevant files:** legacy `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/api/PatientResource.java`; seed `D:/My_Works/HMS/HMSCLEAN2/backend/src/main/resources/db/migration/V2__seed_iam.sql`; planning doc (contains DRIFT) `D:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/03-registration-patient.md`.