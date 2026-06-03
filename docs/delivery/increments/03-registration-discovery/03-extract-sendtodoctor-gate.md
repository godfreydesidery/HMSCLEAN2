I have everything needed. The grep confirms no `@Audited`, no Envers, no fingerprint/device anywhere in `com.orbix.api`. Here is the extraction.

---

# EXTRACTION 3 — Send-to-Doctor, Consultation Creation, Cash Gate, Type-Flip Guards (Legacy Registration/Patient)

**Source files** (all under `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/`):
- `api/PatientResource.java`
- `service/PatientServiceImpl.java`
- `domain/Consultation.java`, `domain/Visit.java`, `domain/Patient.java`

### Early-discovery banner (verified for THIS module)
- **Audit trail:** A scan of all of `com.orbix.api` for `@Audited` / `Envers` returned **no matches**. *No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity is annotated `@Audited`. Downstream agents must not assume an Envers audit baseline exists.* "Forensic" data here is hand-rolled scalar columns only: `createdBy` (user id), `createdOn` (day id), `createdAt` (timestamp) — e.g. `Consultation.java:104-108`, `PatientServiceImpl.java:473-475, 517-519`.
- **Device binding:** Scan for `fingerprint` / `deviceId` / `device_id` / `DeviceFingerprint` across `com.orbix.api` returned **no matches**. *No device-fingerprint or device-binding feature exists in the legacy system. Agents must not treat this as a feature to preserve or re-implement.*

---

## (a) Send-to-doctor / consultation-booking flow

**Endpoint:** `POST /patients/do_consultation` — `PatientResource.java:508-540`.
- RBAC: `@PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')")` (`:509`). These match the live seeded codes.
- Params: `patient_id`, `clinic_name`, `clinician_name`, `follow_up` (int; must be `0` or `1` else `InvalidEntryException`, `:516-522`).
- Controller pre-guards before delegating:
  - Blocks if patient has a `PENDING`/`IN-PROCESS` **admission** → `InvalidOperationException("Could not process consultation, the patient has an active admission")` (`:528-534`).
  - Requires `patient.getType().equals("OUTPATIENT")` else `InvalidOperationException("Please change patient type to OUTPATIENT to continue with operation")` (`:535-537`).
- Then calls `patientService.doConsultation(patient, clinic, clinician, followUp, request)` (`:540`).

**Service:** `PatientServiceImpl.doConsultation(...)` — `PatientServiceImpl.java:425-679`. What it creates, in order:

1. **Clinician active gate** (`:427-429`): clinician must be `isActive()` else `InvalidOperationException`.
2. **Consultation-transfer reconciliation** (`:431-439`): if a `PENDING` `ConsultationTransfer` exists for the patient, the target clinic must match the transfer's clinic, else error; otherwise the transfer is marked `COMPLETED`.
3. **Duplicate-consultation guards**:
   - `PENDING` or `TRANSFERED` consultation present → `"Patient has pending or held consultation, please consider freeing the patient"` (`:444-450`).
   - adding `IN-PROCESS` → if any present → `"Patient has an active consultation, please wait for the patient to be released"` (`:451-455`).
4. **Consultation-fee `PatientBill` created ATOMICALLY** (`:459-483`):
   - `amount = clinic.getConsultationFee()` (`:460`); `paid = 0` (`:461`); `balance = consultationFee` (`:462`); `qty = 1` (`:463`); `billItem="Consultation"`, `description="Consultation"` (`:464-465`).
   - `status = "UNPAID"` (`:466`); **but if `followUp==true`, status is overridden to `"NONE"`** (`:467-469`) — i.e. follow-up consultations carry no payable consultation bill.
   - patient attached (`:479`), saved (`:483`).
5. **Consultation entity created** (`:487-523`): `patient`, `clinic`, `clinician` set; `followUp` flag (`:491-493`); `status="PENDING"` (`:494`); `patientBill = conBill` (`:495`); `paymentType = patient.getPaymentType()` (`:496`).
6. **Visit created every time** (`:501-512`): `sequence="SUBSEQUENT"` (hard-coded, `:503`), `type = patient.getType()` (`:504`), `status="PENDING"` (`:505`). **DRIFT/NOTE:** the comment at `:499` says "create one if the last visit is not for today," but the code unconditionally `new Visit()` + `save` on every consultation — there is **no same-day visit reuse and `sequence` is always `"SUBSEQUENT"`, never `"FIRST"`** in this path.
7. **Insurance/cash branch** (`:529-583`):
   - `INSURANCE`: re-resolves plan by name (`:530`, NPE-risk if patient has no plan); guards against mixing two insurance plans while a `PENDING` `PatientInvoice` exists (`:537-549`); sets patient + consultation to `INSURANCE` with plan + membership (`:550-563`).
   - `CASH`: clears plan/membership on patient and consultation (`:564-580`).
   - else → `InvalidOperationException("Invalid Payment type selected")` (`:581-582`).
8. **Pending invoices flipped to APPROVED** (`:586-590`): all `PENDING` `PatientInvoice` for the patient → `APPROVED`.
9. **Insurance coverage / claim build** (`:594-675`): looks up `ConsultationInsurancePlan` by (clinic, plan, covered=true); if absent → `"Plan not available for this clinic. Please change payment method"` (`:599-601`). On coverage: `conBill.amount = plan consultationFee`, `paid = fee`, `balance = 0`, `status = "COVERED"` (again `"NONE"` if follow-up, `:606-608`); then appends a `PatientInvoiceDetail` (qty 1, description "Consultation") to an existing `PENDING` `PatientInvoice` or creates one (`:621-674`).
10. Returns **`null`** (`:678`) despite the `Patient` return type — the controller wraps `null` in the 201 body.

**Atomicity:** Bill + consultation + visit (+ insurance invoice/detail) are all created within the one `doConsultation` service call. A consultation-fee `PatientBill` **is always created atomically** with the consultation; `Consultation.patientBill` is `@OneToOne(optional=false, nullable=false, updatable=false)` (`Consultation.java:70-73`), enforcing 1:1 non-null at the schema level.

---

## (b) THE CASH GATE — registration fee vs consultation send

**Finding: `do_consultation` does NOT hard-block on an unpaid *registration* fee, and does NOT hard-block on an unpaid *consultation* fee either.** The only hard pre-conditions to *creating* the consultation are: no active admission (`PatientResource.java:528-534`), patient type `OUTPATIENT` (`:535-537`), clinician active (`PatientServiceImpl.java:427-429`), and no existing pending/active consultation (`:444-455`). There is **no `PatientBill` status check on the registration bill anywhere in the send-to-doctor path.**

The payment gate is enforced **downstream, as a queue filter + an open gate**, against the **consultation bill** (not the registration bill):

1. **Queue filter** — `load_pending_consultations_by_clinician_id` (`PatientResource.java:806-828`). Quote (`:822-826`):
```java
for(Consultation cn : cons) {
    if(cn.getPatientBill().getStatus().equals("PAID") || cn.getPatientBill().getStatus().equals("COVERED")) {
        consultationsToShow.add(cn);
    }
}
```
Unpaid consultations are simply hidden from the doctor's pending list (soft filter).

2. **Hard open gate** — `open_consultation` (`PatientResource.java:879-901`). Quote (`:884-897`):
```java
if(c.get().getStatus().equals("PENDING")) {
    if(c.get().getPatientBill().getStatus().equals("PAID") || c.get().getPatientBill().getStatus().equals("COVERED")) {
        c.get().setStatus("IN-PROCESS");
        ...
    }else {
        throw new InvalidOperationException("Could not open. Payment not verified.");
    }
}else {
    throw new InvalidOperationException("Could not open. Not a pending consultation.");
}
```
Follow-up open additionally accepts `"NONE"` (`:914`).

**Conclusion for inc-03:** The gate is on the **consultation `PatientBill`** (`PAID`/`COVERED`), enforced at *doctor-open time*, not at send-to-doctor time, and the queue-list filter is purely a UI/queue filter. **A gate specifically on the *registration* fee being unpaid blocking *send-to-doctor* does NOT exist in legacy — that would be NET-NEW.** A consultation-fee gate at *open* time IS parity. If inc-03's "registration-fee-unpaid" gate blocks send-to-doctor (the `do_consultation` step), flag it as **NET-NEW (drift from legacy)**; if it instead gates doctor-open on the consultation bill, that is **parity**.

---

## (c) Payment-type / patient-type FLIP guards

Both flips DO have guards (this is parity territory):

**Payment-type flip** — `POST /patients/change_payment_type` (`PatientResource.java:310-376`):
- **DRIFT/RISK:** `@PreAuthorize` is **commented out** (`:311`) — endpoint is effectively unsecured at method level in legacy.
- Guard against open clinical work (`:325-357`): collects statuses `PENDING, IN-PROCESS, STOPPED, HELD`; blocks if any matching `Consultation` exists → `"Could not change. Patient has an ongoing medical operation s"` (`:331-334`); same message for matching `NonConsultation` with non-empty lab/radiology/procedure (`:335-353`) and for matching `Admission` (`:354-357`). NonConsultations with no work are auto `SIGNED-OUT` (`:350-351`).
- Then sets INSURANCE (requires membership no, `:359-367`) or forces CASH and clears plan/membership (`:368-373`).
- **Note:** the type-string check at `:321` uses `==` on Strings and is effectively dead/no-op (its body is commented out, `:322`).

**Patient-type flip** — `POST /patients/change_type` (`PatientResource.java:398-506`), RBAC `PATIENT-ALL`/`PATIENT-UPDATE` (`:399`):
- `OUTPATIENT → OUTSIDER`: blocked if any `PENDING`/`IN-PROCESS`/`TRANSFERED` `Consultation` exists → `"Can not change patient type, the patient has an active consultation."` (`:421-428`).
- `OUTSIDER → OUTPATIENT`: walks all `NonConsultation`s' `PENDING` lab/radiology/procedure orders; `UNPAID` bills are **deleted** along with the order; if a bill is in any other status, `cancelable=false` → `"Can not change patient type, the has pending paid services. Please consider clearing with the patient."` (`:429-495`).
- `INPATIENT`: hard-blocked → `"This operation is not allowed for inpatients"` (`:499-500`).
- **Note:** `change_type` only toggles between OUTPATIENT and OUTSIDER (`:426`, `:496`); it cannot set INPATIENT or DECEASED.

So: guards on flip **do exist** and key on open consultations / open orders / unpaid-vs-paid draft bills. Quote the canonical guard (`:422-424`):
```java
List<Consultation> cs = consultationRepository.findAllByPatientAndStatusIn(p.get(), statuses);
if(cs.isEmpty() == false) {
    throw new InvalidOperationException("Can not change patient type, the patient has an active consultation.");
}
```

---

## (d) The "deceased" flag

**There is NO boolean `deceased` field on `Patient`.** `Patient.java:41-107` has no such property. "Deceased" is modelled as **`Patient.type == "DECEASED"`**, set only in `getDeceasedSummary` (`PatientResource.java:5901` and `:5921`) when an approved `DeceasedNote`'s linked admission/consultation is in `HELD` status; it also requires all related `PatientInvoice` bills to be cleared (not `UNPAID`/`VERIFIED`) else `"Could not get deceased summary. Patient have uncleared bills."` (`:5857-5858`, `:5876-5877`). A separate `DeceasedNote` entity (status `PENDING`→`APPROVED`) carries the death record (`:5693-5774`).

**Is DECEASED checked before send-to-doctor?** **No, not directly.** `do_consultation` only checks `type.equals("OUTPATIENT")` (`:535`). A `DECEASED` patient fails that OUTPATIENT check and so cannot be sent — but only as an incidental side-effect of the OUTPATIENT requirement, **not via any explicit deceased guard**. `change_type` (`:398-506`) likewise cannot move a `DECEASED` patient back to OUTPATIENT (it falls through to the else → `"Patient type could not be changed."` at `:501-503`), so once DECEASED a patient is effectively frozen out of consultation.

**DRIFT to flag:** If the inc-03 planning doc references a `deceased` boolean field/flag on Patient or an explicit "patient is deceased" guard on send-to-doctor, that is **invented/drift** — legacy uses the `type="DECEASED"` enum-string value and relies on the OUTPATIENT-only check, with the deceased state produced solely by the deceased-summary workflow.

---

## Status-value catalogue discovered (for state-machine spec)
- `Consultation.status`: `PENDING` → `IN-PROCESS` (via open) → `SIGNED-OUT` (free, `:763-765`); plus `CANCELED` (cancel a PENDING, `:617-619`), `TRANSFERED`, `HELD`, `STOPPED` (referenced in guards `:328-329`, `:446`, `:873`). Only `PENDING` is cancelable (`:611-612`); only `TRANSFERED`/`IN-PROCESS` are freeable (`:757`).
- `Consultation.paymentType`: `CASH`, `INSURANCE` (others rejected at `:581`). Entity comment lists `CASH, DEBIT CARD, CREDIT CARD, MOBILE, INSURANCE` (`Consultation.java:53`) but code only accepts CASH/INSURANCE.
- Consultation `PatientBill.status`: `UNPAID` / `PAID` / `COVERED` / `NONE` (follow-up). Gate accepts `PAID`/`COVERED` (`+NONE` for follow-up).
- `Patient.type`: `OUTPATIENT`, `INPATIENT`, `OUTSIDER`, `DECEASED`.
- `Visit.sequence`: always written `"SUBSEQUENT"` in this path (`:503`); `Visit.type = patient.type`; `Visit.status="PENDING"`.

## Ambiguity register (recommend asking healthcare-domain-expert / business-analyst)
1. **Visit reuse:** comment `:499` promises same-day visit reuse and `FIRST` vs `SUBSEQUENT` logic, but code always creates a new `SUBSEQUENT` visit. Confirm intended behaviour.
2. **`do_consultation` returns `null`** (`:678`) — controller returns 201 with null body. Confirm whether inc-03 should return the created consultation/patient.
3. **`change_payment_type` `@PreAuthorize` commented out** (`:311`) — confirm intended RBAC; likely should mirror `PATIENT-ALL`/`PATIENT-UPDATE`.
4. **`PatientInvoice.no` seeded with `String.valueOf(Math.random())` then overwritten with id** (`:627`, `:638`) — invoice numbering scheme is effectively the row id; flag for the numbering-scheme spec.
5. **Registration-fee gate:** legacy has none on send-to-doctor; the consultation-fee gate lives at doctor-open. Confirm inc-03's "registration-fee-unpaid" gate is an approved NET-NEW change request, not parity.