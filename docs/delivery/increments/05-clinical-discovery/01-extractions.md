# Inc-05 Clinical — Legacy Extractions (7 areas, all VERIFIED 0-materially-wrong)


## Area: consultation-lifecycle

```json
{
  "area": "consultation-lifecycle",
  "entities": [
    {
      "name": "Consultation",
      "legacyFile": "domain/Consultation.java:47-110",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (Consultation.java:48-50)",
        "paymentType : String : @NotBlank; comment lists CASH,DEBIT CARD,CREDIT CARD,MOBILE,INSURANCE but doConsultation only ever sets 'INSURANCE' (PatientServiceImpl.java:555) or 'CASH' (PatientServiceImpl.java:571), else throws (PatientServiceImpl.java:582) (Consultation.java:52-53)",
        "membershipNo : String : nullable; set from patient.membershipNo when INSURANCE (PatientServiceImpl.java:556), else '' (Consultation.java:54)",
        "status : String : @NotBlank; free-text status string (NOT an enum). Observed values PENDING/IN-PROCESS/TRANSFERED/CANCELED/SIGNED-OUT (Consultation.java:55-56)",
        "followUp : boolean : default false; set true when follow_up==1 (PatientServiceImpl.java:491-493) (Consultation.java:57)",
        "patient : Patient : @ManyToOne EAGER optional=false, JoinColumn patient_id nullable=false updatable=false (Consultation.java:62-65)",
        "patientBill : PatientBill : @OneToOne EAGER optional=false, JoinColumn patient_bill_id nullable=false updatable=false; one consultation billed exactly once (Consultation.java:66-73)",
        "clinic : Clinic : @ManyToOne EAGER optional=false, JoinColumn clinic_id nullable=false updatable=false (Consultation.java:74-80)",
        "clinician : Clinician : @ManyToOne EAGER optional=false, JoinColumn clinician_id nullable=false updatable=TRUE (reassignable) (Consultation.java:81-88)",
        "visit : Visit : @ManyToOne EAGER optional=false, JoinColumn visit_id nullable=false updatable=false (Consultation.java:90-97)",
        "insurancePlan : InsurancePlan : @ManyToOne EAGER optional=true, JoinColumn insurance_plan_id nullable=true updatable=true (Consultation.java:99-102)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false; = userService.getUser(request).getId() (Consultation.java:104-105)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false; = dayService.getDay().getId() i.e. business-day FK by id (Consultation.java:106-107)",
        "createdAt : LocalDateTime : default LocalDateTime.now(), overwritten with dayService.getTimeStamp() (Consultation.java:108)"
      ],
      "notes": "Legacy status is a free-text String, NOT an enum. Planning-doc BOOKED/IN_PROGRESS/COMPLETED do NOT exist. Real values: PENDING, IN-PROCESS (hyphen, not underscore), TRANSFERED (single-R legacy spelling), CANCELED (single-L), SIGNED-OUT. There is NO 'COMPLETED' status on Consultation anywhere; 'COMPLETED' at PatientServiceImpl.java:436 is on ConsultationTransfer, not Consultation. The follow-up NONE-bill path corresponds to inc-03 CR-20. createdOn is a day-id (business-day FK), createdBy a user-id."
    },
    {
      "name": "NonConsultation",
      "legacyFile": "domain/NonConsultation.java:44-80",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (NonConsultation.java:45-47)",
        "paymentType : String : @NotBlank default '' (NonConsultation.java:48-49)",
        "membershipNo : String : default '' (NonConsultation.java:50)",
        "status : String : @NotBlank free-text; observed IN-PROCESS (PatientServiceImpl.java:791,1034,1281) and SIGNED-OUT (PatientResource.java:350) (NonConsultation.java:51-52)",
        "patient : Patient : @ManyToOne EAGER optional=false patient_id nullable=false updatable=false (NonConsultation.java:54-60)",
        "visit : Visit : @ManyToOne EAGER optional=false visit_id nullable=false updatable=false (NonConsultation.java:62-68)",
        "insurancePlan : InsurancePlan : @ManyToOne EAGER optional=true insurance_plan_id nullable=true updatable=true (NonConsultation.java:70-73)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (NonConsultation.java:75-76)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false (NonConsultation.java:77-78)",
        "createdAt : LocalDateTime : default now() (NonConsultation.java:79)"
      ],
      "notes": "NonConsultation has NO patientBill, NO clinic, NO clinician, NO followUp field, NO openConsultation gate. It is the 'walk-in / no-doctor' track (e.g. direct lab/radiology) and is created lazily inside saveLabTest/saveRadiology/saveProcedure rather than via a do_consultation booking. Not part of the doctor pay-before-service consultation flow; out of scope for the consultation state machine but documented here because the area brief named it."
    },
    {
      "name": "ConsultationTransfer",
      "legacyFile": "domain/ConsultationTransfer.java (referenced); behaviour in PatientServiceImpl.java:2755-2820 & PatientResource.java:983-1010",
      "fields": [
        "consultation : Consultation : the transferred consultation (PatientServiceImpl.java:2802)",
        "clinic : Clinic : destination clinic; must differ from current (PatientServiceImpl.java:2804-2806)",
        "patient : Patient : = consultation.getPatient() (PatientServiceImpl.java:2813)",
        "status : String : PENDING on create (PatientServiceImpl.java:2811); COMPLETED when destination clinician picks up via doConsultation match (PatientServiceImpl.java:436); CANCELED via cancel_consultation_transfer (PatientResource.java:993)",
        "createdBy/createdOn/createdAt : forensic (PatientServiceImpl.java:2815-2817)"
      ],
      "notes": "Drives the Consultation TRANSFERED status. Created only from an IN-PROCESS consultation (PatientServiceImpl.java:2756). Guards: no other PENDING transfer for patient (2764-2766); no PENDING LabTest/Radiology/Procedure/Prescription on that consultation (2769-2800); destination clinic != current clinic (2804-2806)."
    }
  ],
  "stateMachine": [
    {
      "from": "(none / new)",
      "to": "PENDING",
      "trigger": "POST /patients/do_consultation -> PatientServiceImpl.doConsultation(): new Consultation, consultation.setStatus(\"PENDING\")",
      "guard": "Clinician must be active else InvalidOperationException (PatientServiceImpl.java:427-429). Patient must NOT already have a Consultation in {PENDING,TRANSFERED} (PatientServiceImpl.java:444-450) nor in {PENDING,TRANSFERED,IN-PROCESS} (PatientServiceImpl.java:451-455). At resource layer: patient must have NO Admission in {PENDING,IN-PROCESS} (PatientResource.java:528-534) and patient.type must == 'OUTPATIENT' (PatientResource.java:535-537). If a PENDING ConsultationTransfer exists, target clinic must match it (PatientServiceImpl.java:431-435).",
      "citation": "PatientServiceImpl.java:494"
    },
    {
      "from": "PENDING",
      "to": "IN-PROCESS",
      "trigger": "GET /patients/open_consultation -> openConsultation(): c.setStatus(\"IN-PROCESS\")",
      "guard": "PAY-BEFORE-SERVICE GATE: consultation.status must == 'PENDING' (PatientResource.java:884) AND consultation.patientBill.status must be 'PAID' OR 'COVERED' (PatientResource.java:885); otherwise InvalidOperationException 'Could not open. Payment not verified.' (PatientResource.java:896). Also creates a ClinicianPerformance record (PatientResource.java:889-893).",
      "citation": "PatientResource.java:886"
    },
    {
      "from": "PENDING",
      "to": "IN-PROCESS",
      "trigger": "GET /patients/open_follow_up_consultation -> openFollowUpConsultation(): c.setStatus(\"IN-PROCESS\")",
      "guard": "consultation.followUp must == true else InvalidOperationException (PatientResource.java:910-912); status must == 'PENDING' (PatientResource.java:913); patientBill.status must be 'PAID' OR 'COVERED' OR 'NONE' (PatientResource.java:914) — note the extra 'NONE' allowance vs the normal gate, this is the follow-up free-bill (CR-20) path. Else 'Could not open. Payment not verified.' (PatientResource.java:925).",
      "citation": "PatientResource.java:915"
    },
    {
      "from": "IN-PROCESS",
      "to": "TRANSFERED",
      "trigger": "POST /patients/create_consultation_transfer -> PatientServiceImpl.createConsultationTransfer(): con.setStatus(\"TRANSFERED\")",
      "guard": "consultation.status must == 'IN-PROCESS' else 'Can not transfer. Not an active consultation' (PatientServiceImpl.java:2756-2758); no existing PENDING ConsultationTransfer for patient (2764-2766); no PENDING LabTest/Radiology/Procedure/Prescription on the consultation (2769-2800); destination clinic.id != current clinic.id (2804-2806).",
      "citation": "PatientServiceImpl.java:2808"
    },
    {
      "from": "TRANSFERED",
      "to": "IN-PROCESS",
      "trigger": "GET /patients/cancel_consultation_transfer -> cancelConsultationTransfer(): c.setStatus(\"IN-PROCESS\") and the ConsultationTransfer.setStatus(\"CANCELED\")",
      "guard": "consultation.status must == 'TRANSFERED' (PatientResource.java:989) AND a PENDING ConsultationTransfer must exist for it (PatientResource.java:990-992); else no-op / InvalidOperationException (PatientResource.java:998).",
      "citation": "PatientResource.java:995"
    },
    {
      "from": "TRANSFERED",
      "to": "PENDING (at destination, new pickup)",
      "trigger": "POST /patients/do_consultation at destination clinic: matching PENDING ConsultationTransfer is set COMPLETED (PatientServiceImpl.java:436) then a NEW Consultation is created PENDING (PatientServiceImpl.java:494) for the destination clinician",
      "guard": "If a PENDING ConsultationTransfer exists, the requested clinic.id must equal the transfer's clinic.id else 'send the patient to the specified clinic' (PatientServiceImpl.java:431-435). NOTE: this creates a NEW Consultation row; the original TRANSFERED consultation is NOT itself advanced to IN-PROCESS at destination. AMBIGUITY: the original TRANSFERED consultation has no explicit closing transition — see open questions.",
      "citation": "PatientServiceImpl.java:436"
    },
    {
      "from": "PENDING",
      "to": "CANCELED",
      "trigger": "POST /patients/cancel_consultation -> cancelConsultation(): consultation.setStatus(\"CANCELED\")",
      "guard": "consultation.status must == 'PENDING' else 'Could not cancel, only a PENDING consultation can be canceled' (PatientResource.java:611-612). Side effects: patientBill.status->'CANCELED' (PatientResource.java:627); if a RECEIVED PatientPaymentDetail exists it is set 'REFUNDED' and a PENDING PatientCreditNote is raised (PatientResource.java:636-654); any PatientInvoiceDetail for that bill is deleted (PatientResource.java:659-672).",
      "citation": "PatientResource.java:618"
    },
    {
      "from": "IN-PROCESS",
      "to": "SIGNED-OUT",
      "trigger": "POST /patients/free_consultation -> freeConsultation(): c.setStatus(\"SIGNED-OUT\")",
      "guard": "If status != 'TRANSFERED': status must == 'IN-PROCESS' (PatientResource.java:688) AND request 'no' must be non-empty (PatientResource.java:689-691) AND patientRepository.findByNo(no) must exist (PatientResource.java:692-695) AND that patient.id must == consultation.patient.id (PatientResource.java:696-698); else 'Could not free, only a TRANSFERED or IN-PROCESS consultation can be freed' (PatientResource.java:757). Side effect: every UNPAID (or null-status) child PatientBill on LabTest/Radiology/Procedure/Prescription is set 'CANCELED' (PatientResource.java:701-754).",
      "citation": "PatientResource.java:699"
    },
    {
      "from": "TRANSFERED",
      "to": "SIGNED-OUT",
      "trigger": "POST /patients/free_consultation -> freeConsultation(): falls through the 'no' validation block (the inner block only runs for IN-PROCESS) and unconditionally sets status 'SIGNED-OUT' at the end",
      "guard": "status == 'TRANSFERED' bypasses the inner IN-PROCESS branch entirely (PatientResource.java:687) and reaches the unconditional consultation.setStatus('SIGNED-OUT') at PatientResource.java:764. No registration-number check is applied for TRANSFERED. AMBIGUITY: child-bill cancellation loop (701-754) is skipped for TRANSFERED.",
      "citation": "PatientResource.java:764"
    },
    {
      "from": "{PENDING,IN-PROCESS}",
      "to": "SIGNED-OUT",
      "trigger": "Admission flow: when patient is admitted, all of the patient's Consultations in {PENDING,IN-PROCESS} are force-closed: con.setStatus(\"SIGNED-OUT\")",
      "guard": "Executed inside the admission save path (admission set IN-PROCESS, ward bed OCCUPIED) for the patient (PatientServiceImpl.java:1951-1962).",
      "citation": "PatientServiceImpl.java:1956"
    }
  ],
  "rules": [
    {
      "rule": "follow_up request param must be exactly 1 or 0; any other value throws InvalidEntryException 'Follow up should be equal to 1 or 0'. 1 -> followUp=true, 0 -> followUp=false.",
      "citation": "PatientResource.java:515-522"
    },
    {
      "rule": "Consultation booking blocks if patient has an active Admission in {PENDING,IN-PROCESS}.",
      "citation": "PatientResource.java:528-534"
    },
    {
      "rule": "Consultation booking requires patient.type == 'OUTPATIENT', else must change type first.",
      "citation": "PatientResource.java:535-537"
    },
    {
      "rule": "Booking creates a PatientBill: amount = clinic.getConsultationFee(), paid=0, balance=consultationFee, qty=1, billItem='Consultation', description='Consultation', status='UNPAID'. If followUp==true the bill status is overridden to 'NONE' (the free follow-up bill, CR-20).",
      "citation": "PatientServiceImpl.java:459-469"
    },
    {
      "rule": "INSURANCE re-pricing: when paymentType=='INSURANCE', look up ConsultationInsurancePlan by (clinic, plan, covered=true); if absent throw 'Plan not available for this clinic'. Then bill.amount=bill.paid=plan.consultationFee, balance=0, status='COVERED' (overridden to 'NONE' if followUp), paymentType='INSURANCE'.",
      "citation": "PatientServiceImpl.java:597-616"
    },
    {
      "rule": "For INSURANCE consultations a PatientInvoice (status PENDING) and a PatientInvoiceDetail (description 'Consultation', qty 1, amount=bill.amount) are created/attached; new invoice no is initially Math.random() then overwritten with the invoice id string.",
      "citation": "PatientServiceImpl.java:621-674"
    },
    {
      "rule": "PAY-BEFORE-SERVICE: a normal consultation can only be opened (PENDING->IN-PROCESS) when its patientBill.status is 'PAID' or 'COVERED'. This is the exact payment verification gate.",
      "citation": "PatientResource.java:885-896"
    },
    {
      "rule": "PAY-BEFORE-SERVICE (follow-up variant): a follow-up consultation may open when patientBill.status is 'PAID' OR 'COVERED' OR 'NONE' (the NONE free-bill is accepted only here).",
      "citation": "PatientResource.java:914"
    },
    {
      "rule": "The clinician worklist (load_pending_consultations_by_clinician_id) shows ONLY consultations whose patientBill.status is 'PAID' or 'COVERED' — i.e. unpaid PENDING consultations are hidden from the doctor; this is a second pay-before-service filter at list level.",
      "citation": "PatientResource.java:817-826"
    },
    {
      "rule": "Opening a consultation (both normal and follow-up) creates a ClinicianPerformance record linking the clinician and the consultation, via clinicianPerformanceService.check(...).",
      "citation": "PatientResource.java:889-893"
    },
    {
      "rule": "Cancel-consultation refund: only refunds when a PatientPaymentDetail exists with status 'RECEIVED'; it is set 'REFUNDED' and a PatientCreditNote (status PENDING, reference 'Canceled consultation', amount=bill.amount, no from patientCreditNoteService.requestPatientCreditNoteNo()) is created.",
      "citation": "PatientResource.java:636-654"
    },
    {
      "rule": "switchToConsultationById (switch follow-up back to a normal consultation): sets patientBill.status to 'COVERED' if bill has an insurancePlan else 'UNPAID', and sets consultation.followUp=false. This re-introduces a payment requirement for a previously-NONE follow-up.",
      "citation": "PatientResource.java:938-948"
    },
    {
      "rule": "switchToNormalConsultation service: sets patientBill.status to 'COVERED' if bill.insurancePlan != null else 'UNPAID' (does NOT touch followUp). Returns the patient unchanged.",
      "citation": "PatientServiceImpl.java:682-693"
    },
    {
      "rule": "Consultation transfer requires destination clinic differ from current clinic, and the source consultation must be IN-PROCESS, with no pending child orders/prescriptions and no other pending transfer for the patient.",
      "citation": "PatientServiceImpl.java:2756-2806"
    },
    {
      "rule": "On freeing an IN-PROCESS consultation, every child LabTest/Radiology/Procedure/Prescription PatientBill that is 'UNPAID' or has null status is set 'CANCELED'.",
      "citation": "PatientResource.java:701-754"
    }
  ],
  "numbering": "Consultation entity itself has NO business document number — only the IDENTITY-generated id (Consultation.java:48-50). The linked PatientInvoice created for INSURANCE consultations uses no proper sequence: it is first set to String.valueOf(Math.random()) (PatientServiceImpl.java:627) then overwritten with the invoice's own DB id as a string (PatientServiceImpl.java:638). The PatientCreditNote raised on cancellation gets its no from patientCreditNoteService.requestPatientCreditNoteNo() (PatientResource.java:648) — that generator is outside this area (billing). NonConsultation also has no document number (id only). NO prefix/zero-padding/fiscal-year reset exists for any consultation-area artefact.",
  "rbac": [
    "do_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:509)",
    "switch_to_normal_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:548)",
    "cancel_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:606)",
    "free_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:681)",
    "create_consultation_transfer: NO @PreAuthorize — annotation is commented out '//@PreAuthorize(...PATIENT-A/C/U)' (PatientResource.java:572)",
    "get_consultation_transfers: NO @PreAuthorize (commented out) (PatientResource.java:595)",
    "open_consultation: NO @PreAuthorize present (PatientResource.java:879)",
    "open_follow_up_consultation: NO @PreAuthorize present (PatientResource.java:905)",
    "switch_to_consultation_by_consultation_id: NO @PreAuthorize present (PatientResource.java:933)",
    "cancel_consultation_transfer: NO @PreAuthorize present (PatientResource.java:983)",
    "get_active_consultations: NO @PreAuthorize present (PatientResource.java:771)",
    "load_pending_consultations_by_clinician_id: NO @PreAuthorize present (PatientResource.java:806)",
    "load_follow_up_list_by_clinician_id: NO @PreAuthorize present (PatientResource.java:832)",
    "load_in_process_consultations_by_clinician_id: NO @PreAuthorize present (PatientResource.java:859)",
    "load_consultation: NO @PreAuthorize present (PatientResource.java:957)",
    "CONFIRMED: the only real privilege codes on consultation endpoints are the three PATIENT-ALL / PATIENT-CREATE / PATIENT-UPDATE strings. Planning-doc codes CONSULTATION_START, PROCEDURE_ORDER_APPROVE, PRESCRIPTION_CREATE DO NOT EXIST in the legacy consultation endpoints. The critical doctor actions (open_consultation, open_follow_up_consultation, transfers) carry NO method-level authorization at all."
  ],
  "driftVsPlanningDoc": [
    "Status names: planning doc claims BOOKED/IN_PROGRESS/COMPLETED. LEGACY REALITY: status is a free-text String (Consultation.java:55-56) with values PENDING (PatientServiceImpl.java:494), IN-PROCESS (PatientResource.java:886, note hyphen not underscore), TRANSFERED (PatientServiceImpl.java:2808, single-R legacy spelling), CANCELED (PatientResource.java:618, single-L), SIGNED-OUT (PatientResource.java:699,764; PatientServiceImpl.java:1956). There is NO 'BOOKED', NO 'IN_PROGRESS', and NO 'COMPLETED' on Consultation. The closest to 'BOOKED' is PENDING; the closest to 'IN_PROGRESS' is IN-PROCESS; there is NO terminal 'COMPLETED' — completion is expressed as SIGNED-OUT.",
    "Privilege codes: planning doc's CONSULTATION_START / PROCEDURE_ORDER_APPROVE / PRESCRIPTION_CREATE do not exist. Real consultation-area codes are only PATIENT-ALL / PATIENT-CREATE / PATIENT-UPDATE (PatientResource.java:509,548,606,681), and many endpoints (open_consultation, transfers, loaders) have NO @PreAuthorize at all.",
    "Polymorphic 'ClinicalOrder' (kind=LAB_TEST|RADIOLOGY|PROCEDURE): does not exist. Legacy uses three separate entities/repositories referenced as LabTest, Radiology, Procedure throughout (e.g. PatientServiceImpl.java:2759-2761; PatientResource.java:701,715,728). Prescription is a fourth separate entity (PatientServiceImpl.java:2762).",
    "Single 'ConsultationDiagnosis' (kind=WORKING|FINAL): does not exist. Legacy has two separate entities WorkingDiagnosis (workingDiagnosisRepository, PatientResource.java:1662,1720) and FinalDiagnosis (finalDiagnosisRepository, PatientResource.java:1782,1838).",
    "'ProviderProfile' entity: not referenced in the consultation flow; the doctor is modelled as the Clinician personnel extension (Consultation.clinician @ManyToOne, Consultation.java:85-88) and the inc-01 personnel extensions.",
    "Planning-doc 'COMPLETED' as a discrete close state: legacy has no such value; the doctor 'releases'/closes a patient via free_consultation -> SIGNED-OUT (PatientResource.java:764), and on admission consultations are force-SIGNED-OUT (PatientServiceImpl.java:1956).",
    "Follow-up semantics: planning docs typically ignore the bill-status 'NONE' free-follow-up path. Legacy explicitly creates a 'NONE'-status PatientBill for follow-ups (PatientServiceImpl.java:467-469,606-608) and the follow-up open gate uniquely accepts 'NONE' (PatientResource.java:914). This is the inc-03 CR-20 followUp NONE-bill flag and MUST be preserved."
  ],
  "openQuestions": [
    "doConsultation returns null on success (PatientServiceImpl.java:678) yet the controller wraps it as ResponseEntity<Patient> body (PatientResource.java:540) — clients receive a null body. Confirm intended; inc-03 reproduced booking as POST /send-to-doctor, so verify the new endpoint's response contract is deliberate.",
    "The original TRANSFERED consultation has no explicit close on destination pickup: at the destination clinic a NEW Consultation (PENDING) is created (PatientServiceImpl.java:494) and the ConsultationTransfer is set COMPLETED (PatientServiceImpl.java:436), but the source TRANSFERED Consultation row is left in TRANSFERED until later (free_consultation -> SIGNED-OUT, or admission force-close). Confirm whether the source consultation should auto-close on pickup or intentionally lingers.",
    "free_consultation TRANSFERED path: a TRANSFERED consultation is set SIGNED-OUT (PatientResource.java:764) WITHOUT the registration-number identity check and WITHOUT the child-bill cancellation loop that the IN-PROCESS path runs (PatientResource.java:687-754). Confirm this asymmetry is intended (TRANSFERED frees silently, IN-PROCESS requires reg-no and cancels unpaid child bills).",
    "switchToConsultationById flips followUp->false and resets bill status to UNPAID/COVERED (PatientResource.java:938-948) — i.e. a follow-up that was free (NONE) becomes payable. Confirm whether revenue/charge implications (re-billing a previously-free follow-up) are intended.",
    "createConsultationTransfer and open_consultation/open_follow_up_consultation have NO @PreAuthorize (PatientResource.java:572,879,905). Confirm with security-architect whether the modernized system must add explicit privileges or faithfully reproduce the open (unauthorized) legacy behaviour for these doctor actions.",
    "PatientInvoice 'no' is assigned via Math.random() then overwritten by the DB id (PatientServiceImpl.java:627,638) — there is no real invoice numbering scheme in this path. Confirm the canonical invoice-number generator (billing area) that should replace this and whether the transient random value can ever persist.",
    "Patient/consultation paymentType is documented (Consultation.java:53 comment) to allow CASH/DEBIT CARD/CREDIT CARD/MOBILE/INSURANCE, but doConsultation only accepts 'INSURANCE' or 'CASH' and throws InvalidOperationException for anything else (PatientServiceImpl.java:564-583). Confirm whether the other payment types are dead/legacy or handled elsewhere before booking.",
    "clinician_name is resolved via clinicianRepository.findByNickname(clinician_name) and clinic by name (PatientResource.java:525-526) with no .isPresent() guard before .get() — a bad name throws NoSuchElementException rather than a friendly NotFound. Confirm desired error contract.",
    "On freeing/admission, consultation is set SIGNED-OUT but the associated open ClinicianPerformance record created at open time (PatientResource.java:889-893) is not explicitly closed here — verify whether performance close is handled elsewhere."
  ]
}
```

## Area: clinical-notes-exam-diagnosis

```json
{
  "area": "clinical-notes-exam-diagnosis",
  "entities": [
    {
      "name": "ClinicalNote",
      "legacyFile": "domain/ClinicalNote.java:34-75",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (ClinicalNote.java:40-42)",
        "mainComplain : String : @Column(length=500), nullable (ClinicalNote.java:44-45)",
        "presentIllnessHistory : String : nullable, default varchar 255 (ClinicalNote.java:46)",
        "pastMedicalHistory : String : nullable (ClinicalNote.java:47)",
        "familyAndSocialHistory : String : nullable (ClinicalNote.java:48)",
        "drugsAndAllergyHistory : String : nullable (ClinicalNote.java:49)",
        "reviewOfOtherSystems : String : nullable (ClinicalNote.java:50)",
        "physicalExamination : String : nullable (ClinicalNote.java:51)",
        "managementPlan : String : nullable (ClinicalNote.java:52)",
        "consultation : Consultation : @OneToOne, JoinColumn consultation_id nullable=true (ClinicalNote.java:54-57)",
        "nonConsultation : NonConsultation : @OneToOne, JoinColumn non_consultation_id nullable=true (ClinicalNote.java:59-62)",
        "admission : Admission : @ManyToOne, JoinColumn admission_id nullable=true (ClinicalNote.java:64-67)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (ClinicalNote.java:69-70)",
        "createdOn : Long : @Column created_on_day_id (Day id) nullable=false updatable=false (ClinicalNote.java:71-72)",
        "createdAt : LocalDateTime : default LocalDateTime.now() (ClinicalNote.java:73)"
      ],
      "notes": "This is the legacy SOAP-style clinical note. The 8 free-text clinical fields ARE the SOAP note structure (S=mainComplain/presentIllnessHistory/pastMedicalHistory/familyAndSocialHistory/drugsAndAllergyHistory/reviewOfOtherSystems; O=physicalExamination; A/P=managementPlan). NO @NotBlank / @NotNull on any clinical text field -> every clinical field is OPTIONAL at the entity level. NO bean-validation; the only required columns are the audit columns createdBy/createdOn. A ClinicalNote is bound to EXACTLY ONE of consultation OR nonConsultation OR admission (enforced at the resource, not the entity). For consultations and non-consultations the relation is @OneToOne (one note per encounter -> upsert); for admissions it is @ManyToOne (many notes per admission -> append). The planning doc's single polymorphic 'ConsultationNote' is not the legacy shape; the legacy note carries three distinct nullable FKs (ClinicalNote.java:54-67). Diagnosis is NOT stored on this entity."
    },
    {
      "name": "GeneralExamination",
      "legacyFile": "domain/GeneralExamination.java:32-75",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (GeneralExamination.java:38-40)",
        "pressure : String : nullable; blood pressure stored as free-text String (GeneralExamination.java:42)",
        "temperature : String : nullable; free-text String (GeneralExamination.java:43)",
        "pulseRate : String : nullable; free-text String (GeneralExamination.java:44)",
        "weight : String : nullable; free-text String (GeneralExamination.java:45)",
        "height : String : nullable; free-text String (GeneralExamination.java:46)",
        "bodyMassIndex : String : nullable; free-text String, NOT computed server-side (GeneralExamination.java:47)",
        "bodyMassIndexComment : String : nullable (GeneralExamination.java:48)",
        "bodySurfaceArea : String : nullable; free-text String (GeneralExamination.java:49)",
        "saturationOxygen : String : nullable; free-text String (GeneralExamination.java:50)",
        "respiratoryRate : String : nullable; free-text String (GeneralExamination.java:51)",
        "description : String : @Column(length=1000), nullable (GeneralExamination.java:52-53)",
        "consultation : Consultation : @OneToOne JoinColumn consultation_id nullable=true (GeneralExamination.java:55-58)",
        "nonConsultation : NonConsultation : @OneToOne JoinColumn non_consultation_id nullable=true (GeneralExamination.java:60-63)",
        "admission : Admission : @ManyToOne JoinColumn admission_id nullable=true (GeneralExamination.java:65-68)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (GeneralExamination.java:70-71)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false (GeneralExamination.java:72-73)",
        "createdAt : LocalDateTime : default LocalDateTime.now() (GeneralExamination.java:74)"
      ],
      "notes": "This entity IS the legacy 'vitals + general exam' record. ALL vitals (pressure/temperature/pulseRate/weight/height/bodyMassIndex/bodySurfaceArea/saturationOxygen/respiratoryRate) are plain String columns -> NO numeric typing, NO range validation, NO unit enforcement at any layer. BMI/BSA are NOT computed by the backend; they arrive as Strings from the client (PatientResource.java:1563-1566). Bound to exactly one of consultation/nonConsultation/admission (same rule as ClinicalNote). @OneToOne for consultation/nonConsultation (one exam per encounter -> upsert); @ManyToOne for admission (append). A SEPARATE entity PatientVital (domain/PatientVital.java) holds the same field set plus a status workflow (EMPTY/SUBMITTED/ARCHIVED, PatientVital.java:45) used by a nurse-capture flow; on request_patient_vitals_by_consultation_id the SUBMITTED PatientVital is copied field-by-field into a transient GeneralExamination and the PatientVital is set to ARCHIVED (PatientResource.java:1317-1346)."
    },
    {
      "name": "WorkingDiagnosis",
      "legacyFile": "domain/WorkingDiagnosis.java:28-65",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (WorkingDiagnosis.java:34-36)",
        "description : String : nullable free-text (WorkingDiagnosis.java:37)",
        "diagnosisType : DiagnosisType : @ManyToOne JoinColumn diagnosis_type_id nullable=false updatable=false (WorkingDiagnosis.java:39-42)",
        "consultation : Consultation : @ManyToOne JoinColumn consultation_id nullable=true updatable=true (WorkingDiagnosis.java:44-47)",
        "admission : Admission : @ManyToOne JoinColumn admission_id nullable=true updatable=true (WorkingDiagnosis.java:49-52)",
        "patient : Patient : @ManyToOne JoinColumn patient_id nullable=false updatable=false (WorkingDiagnosis.java:54-57)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (WorkingDiagnosis.java:60-61)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false (WorkingDiagnosis.java:62-63)",
        "createdAt : LocalDateTime : default LocalDateTime.now() (WorkingDiagnosis.java:64)"
      ],
      "notes": "CONFIRMED: diagnosis is modelled as TWO SEPARATE ENTITIES, not one with a kind discriminator. WorkingDiagnosis is the provisional/working diagnosis. The planning-doc 'ConsultationDiagnosis with kind=WORKING|FINAL' DOES NOT EXIST in the legacy code -> drift. WorkingDiagnosis has NO nonConsultation FK (unlike ClinicalNote/GeneralExamination); it links only to consultation OR admission, plus a MANDATORY patient FK and a MANDATORY diagnosisType FK. diagnosisType is updatable=false (the type cannot be changed after insert; you delete+re-add instead). description is optional. There is no severity/onset/laterality/certainty field. No ICD code field on the diagnosis row itself."
    },
    {
      "name": "FinalDiagnosis",
      "legacyFile": "domain/FinalDiagnosis.java:31-68",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (FinalDiagnosis.java:37-39)",
        "description : String : nullable free-text (FinalDiagnosis.java:40)",
        "diagnosisType : DiagnosisType : @ManyToOne JoinColumn diagnosis_type_id nullable=false updatable=false (FinalDiagnosis.java:42-45)",
        "consultation : Consultation : @ManyToOne JoinColumn consultation_id nullable=true updatable=true (FinalDiagnosis.java:47-50)",
        "admission : Admission : @ManyToOne JoinColumn admission_id nullable=true updatable=true (FinalDiagnosis.java:52-55)",
        "patient : Patient : @ManyToOne JoinColumn patient_id nullable=false updatable=false (FinalDiagnosis.java:57-60)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (FinalDiagnosis.java:63-64)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false (FinalDiagnosis.java:65-66)",
        "createdAt : LocalDateTime : default LocalDateTime.now() (FinalDiagnosis.java:67)"
      ],
      "notes": "FinalDiagnosis is a byte-for-byte structural twin of WorkingDiagnosis (same field set, same constraints, separate table final_diagnosises). This confirms TWO entities rather than one discriminated entity. The ONLY functional difference vs WorkingDiagnosis is the table name and which resource endpoints write to it. Also lacks any nonConsultation FK. diagnosisType mandatory and updatable=false; description optional; patient mandatory; bound to consultation OR admission."
    },
    {
      "name": "DiagnosisType",
      "legacyFile": "domain/DiagnosisType.java:32-55",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (DiagnosisType.java:38-40)",
        "code : String : @NotBlank @Column(unique=true) (DiagnosisType.java:41-43)",
        "name : String : @NotBlank @Column(unique=true) (DiagnosisType.java:44-46)",
        "description : String : nullable (DiagnosisType.java:47)",
        "active : boolean : default false; set true on create in service (DiagnosisType.java:48 ; DiagnosisTypeServiceImpl.java:48)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false (DiagnosisType.java:50-51)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false (DiagnosisType.java:52-53)",
        "createdAt : LocalDateTime : default LocalDateTime.now() (DiagnosisType.java:54)"
      ],
      "notes": "DiagnosisType is the master/lookup catalogue that WorkingDiagnosis and FinalDiagnosis both point at. It is NOT ICD. 'code' is a free-text, user-supplied, UNIQUE string -> it is the closest thing to a diagnosis code but there is NO ICD-10/ICD-11 validation, no external code system, no format mask (DiagnosisType.java:41-43). Both code and name are @NotBlank and unique. 'active' defaults false on the entity but the create path forces it true (DiagnosisTypeServiceImpl.java:43-50); update path does NOT touch active. deleteDiagnosisType is hard-wired to always refuse (allowDeleteDiagnosisType returns false -> InvalidOperationException), so diagnosis types can never be deleted via the service (DiagnosisTypeServiceImpl.java:72-90)."
    },
    {
      "name": "PatientVital",
      "legacyFile": "domain/PatientVital.java:23-67",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (PatientVital.java:29-31)",
        "pressure/temperature/pulseRate/weight/height/bodyMassIndex/bodyMassIndexComment/bodySurfaceArea/saturationOxygen/respiratoryRate : String : nullable free-text, mirror of GeneralExamination (PatientVital.java:33-42)",
        "description : String : @Column(length=1000) nullable (PatientVital.java:43-44)",
        "status : String : default 'EMPTY' (PatientVital.java:45)",
        "consultation : Consultation : @OneToOne consultation_id nullable (PatientVital.java:47-50)",
        "nonConsultation : NonConsultation : @OneToOne non_consultation_id nullable (PatientVital.java:52-55)",
        "admission : Admission : @ManyToOne admission_id nullable (PatientVital.java:57-60)",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime : audit cols (PatientVital.java:62-66)"
      ],
      "notes": "Adjacent staging entity for the nurse vitals-capture -> doctor handoff. Status lifecycle EMPTY -> SUBMITTED -> ARCHIVED. On load_patient_vitals_by_consultation_id a vital is auto-created with status='EMPTY' if none exists (PatientResource.java:1298-1307). request_patient_vitals_by_consultation_id requires status=='SUBMITTED' else throws 'Vitals already requested or not submitted', copies all fields into a fresh GeneralExamination (NOT persisted there), and flips the PatientVital to 'ARCHIVED' (PatientResource.java:1321-1346). Included because GeneralExamination is fed by this flow; full PatientVital state machine should be owned by the vitals/exam extraction area."
    }
  ],
  "stateMachine": [
    {
      "from": "(none)",
      "to": "EMPTY",
      "trigger": "GET load_patient_vitals_by_consultation_id when no PatientVital exists for the consultation -> auto-create with status='EMPTY'",
      "guard": "none",
      "citation": "PatientResource.java:1298-1307"
    },
    {
      "from": "SUBMITTED",
      "to": "ARCHIVED",
      "trigger": "GET request_patient_vitals_by_consultation_id (doctor pulls submitted vitals into a GeneralExamination)",
      "guard": "PatientVital.status must equal 'SUBMITTED', else throw InvalidOperationException 'Vitals already requested or not submitted'",
      "citation": "PatientResource.java:1321-1340"
    }
  ],
  "rules": [
    {
      "rule": "ClinicalNote and GeneralExamination are UPSERT-PER-CONSULTATION (and per-non-consultation), not append. saveCG finds the existing note/exam for the encounter via findAllBy...Consultation, takes the last one in the loop, and overwrites its 8 (note) / 11 (exam) fields in place; only if none exists is a new row created. Same overwrite logic for admissions but admission uses the last row found in a list (@ManyToOne), so multiple admission notes can accumulate (append-capable for admissions).",
      "citation": "PatientResource.java:1484-1598"
    },
    {
      "rule": "A note/exam (and the saveCG payload) must be bound to EXACTLY ONE encounter type. saveCG resolves consultation, nonConsultation and admission ids from the payload and throws InvalidOperationException('Patient can not have admission and consultation and outsider simultaneously') for every pairwise or triple co-presence; throws NotFoundException('No Admission or Consultation found') if all three are empty.",
      "citation": "PatientResource.java:1469-1521"
    },
    {
      "rule": "loadClinicalNoteByConsultationId / loadGeneralExaminationByConsultationId AUTO-CREATE an empty persisted note/exam (GET-with-side-effect) when none exists for the consultation, returning it via HTTP 201; the new row stamps createdBy=current user, createdOn=current Day id, createdAt=Day timestamp. Same auto-create for non-consultation and admission loaders.",
      "citation": "PatientResource.java:1230-1247, 1260-1277, 1356-1382, 1394-1411, 1424-1448"
    },
    {
      "rule": "saveWorkingDiagnosis: the consultation must exist (else NotFound 'Consultation not found') and the DiagnosisType must exist (else NotFound 'Diagnosis type not found'). DUPLICATE GUARD: if a WorkingDiagnosis already exists for (consultation, diagnosisType) it throws InvalidOperationException('Duplicate Diagnosis Types is not allowed'). On save the patient is copied from consultation.getPatient(); audit fields stamped only when id==null.",
      "citation": "PatientResource.java:1654-1678"
    },
    {
      "rule": "saveFinalDiagnosis: identical structure to saveWorkingDiagnosis including the SAME duplicate guard, using finalDiagnosisRepository.existsByConsultationAndDiagnosisType, error text 'Duplicate Diagnosis Types is not allowed'.",
      "citation": "PatientResource.java:1774-1797"
    },
    {
      "rule": "ADMISSION DIAGNOSIS PATHS HAVE NO DUPLICATE GUARD. saveAdmissionWorkingDiagnosis and saveAdmissionFinalDiagnosis validate admission + diagnosisType existence and stamp patient from admission.getPatient(), but DO NOT call existsBy...AndDiagnosisType -> duplicate diagnosis types CAN be added to an admission. This asymmetry with the consultation paths is a real legacy behaviour, not a bug to be silently fixed.",
      "citation": "PatientResource.java:1685-1707, 1804-1825"
    },
    {
      "rule": "Diagnosis ADD/REMOVE semantics: diagnoses are individually appended (one row per save call) and removed by hard DELETE via GET endpoints delete_working_diagnosis / delete_final_diagnosis which call deleteById(id) with NO existence check, NO authorization, NO soft-delete, NO cascade guard. There is NO update path for an existing diagnosis row (diagnosisType is updatable=false); editing means delete + re-add.",
      "citation": "PatientResource.java:1917-1929 ; WorkingDiagnosis.java:40 ; FinalDiagnosis.java:43"
    },
    {
      "rule": "DiagnosisType.save: when id==null the service stamps audit fields and forces active=true; uniqueness of code and name is enforced only by the DB unique constraint plus @NotBlank, there is no app-level pre-check, so a duplicate code/name surfaces as a DataIntegrityViolation rather than a friendly error.",
      "citation": "DiagnosisTypeServiceImpl.java:38-54 ; DiagnosisType.java:41-46"
    },
    {
      "rule": "DiagnosisType can never be deleted through DiagnosisTypeService: deleteDiagnosisType always throws InvalidOperationException because the private allowDeleteDiagnosisType() unconditionally returns false.",
      "citation": "DiagnosisTypeServiceImpl.java:72-90"
    },
    {
      "rule": "send_clinical_note_and_general_examination_to_history is ADMISSION-ONLY and APPEND-ONLY: it throws InvalidOperationException('Not Allowed for consultations') if a consultation id resolves, requires an admission, and unconditionally inserts a NEW blank ClinicalNote and a NEW blank GeneralExamination bound to the admission (saveAndFlush). This is the mechanism that lets admissions accumulate multiple historical note/exam rows.",
      "citation": "PatientResource.java:1608-1648"
    },
    {
      "rule": "BMI, BSA and all vitals are persisted exactly as the client sends them (Strings); the backend performs NO calculation, normalisation, rounding, or range checking for temperature, pressure, pulse, SpO2, respiratory rate, weight, height, BMI or BSA.",
      "citation": "PatientResource.java:1563-1588 ; GeneralExamination.java:42-51"
    }
  ],
  "numbering": "NONE. No document/sequence number scheme exists for ClinicalNote, GeneralExamination, WorkingDiagnosis or FinalDiagnosis — all use a bare DB IDENTITY surrogate id with no prefix, zero-padding, or fiscal reset (ClinicalNote.java:40-42; GeneralExamination.java:38-40; WorkingDiagnosis.java:34-36; FinalDiagnosis.java:37-39). DiagnosisType.code is a user-supplied free-text UNIQUE string, NOT system-generated and NOT format-masked (DiagnosisType.java:41-43; DiagnosisTypeServiceImpl.java:38-54).",
  "rbac": [
    "diagnosis_types/save -> @PreAuthorize(\"hasAnyAuthority('ADMIN-ACCESS')\") (DiagnosisTypeResource.java:73-74)"
  ],
  "driftVsPlanningDoc": [
    "Planning doc 05-clinical-opd.md claims a single 'ConsultationDiagnosis' entity with kind=WORKING|FINAL. LEGACY REALITY: two physically separate entities/tables — WorkingDiagnosis (table working_diagnosises, WorkingDiagnosis.java:32) and FinalDiagnosis (table final_diagnosises, FinalDiagnosis.java:35). No kind discriminator exists anywhere.",
    "Planning doc privilege names like CONSULTATION_START / PRESCRIPTION_CREATE / PROCEDURE_ORDER_APPROVE DO NOT EXIST. The only @PreAuthorize touching this area is diagnosis_types/save with the literal authority 'ADMIN-ACCESS' (DiagnosisTypeResource.java:74). Every clinical-note, general-examination, working/final-diagnosis save/load/delete endpoint in PatientResource has NO active @PreAuthorize (only commented-out PATIENT-* / PRODUCT-CREATE stubs near unrelated endpoints) — these clinical endpoints are NOT method-secured (PatientResource.java:1224-1929).",
    "Planning doc implies clean numeric/typed vitals. LEGACY REALITY: all vitals incl. BMI/BSA are free-text String columns with no validation, no server-side computation (GeneralExamination.java:42-51; PatientResource.java:1563-1588).",
    "Planning doc implies one unified diagnosis-link kind=WORKING|FINAL bound to a consultation. LEGACY REALITY: WorkingDiagnosis/FinalDiagnosis link to consultation OR admission, carry a mandatory patient FK, and have NO non-consultation linkage (WorkingDiagnosis.java:44-57; FinalDiagnosis.java:47-60), whereas ClinicalNote/GeneralExamination additionally carry a non-consultation FK (ClinicalNote.java:59-62; GeneralExamination.java:60-63). The shapes are not symmetric.",
    "The duplicate-diagnosis-type guard exists ONLY on the consultation diagnosis paths, NOT on the admission diagnosis paths (PatientResource.java:1662-1664, 1782-1784 vs 1685-1707, 1804-1825). A 'modern, cleaned-up' single guard would silently change behaviour — flag for change-request, do not auto-unify.",
    "Note/exam persistence for consultations is UPSERT (overwrite one row), but for admissions it is APPEND (multiple rows via @ManyToOne and send_to_history). Any model assuming a single note/exam per encounter regardless of type would be wrong for admissions (PatientResource.java:1484-1598, 1608-1648).",
    "EARLY-DISCOVERY (audit): No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity is annotated @Audited. Scan of com.orbix.api found ZERO @Audited annotations. The createdBy/createdOn/createdAt columns on these entities are hand-rolled stamps, NOT Envers revisions. Downstream agents must not assume an Envers audit baseline exists. (Grep @Audited over com.orbix.api -> no files.)",
    "EARLY-DISCOVERY (device binding): No device-fingerprint or device-binding feature exists in the legacy system. Scan for fingerprint/deviceId/device_id/deviceBinding/X-Device over com.orbix.api -> no files. Agents must not treat this as a feature to preserve or re-implement.",
    "No ICD coding exists. Scan for icd/ICD over com.orbix.api -> no files. DiagnosisType.code is a free-text unique string, not an ICD code (DiagnosisType.java:41-43)."
  ],
  "openQuestions": [
    "Admission diagnosis paths allow duplicate (admission, diagnosisType) pairs while consultation paths forbid them. Is the duplicate guard intended to be admission-wide, per-encounter, or genuinely absent for admissions? (PatientResource.java:1685-1707, 1804-1825) — confirm with healthcare-domain-expert.",
    "delete_working_diagnosis / delete_final_diagnosis are GET endpoints performing unguarded hard deleteById with no auth, no existence check, no audit. Should the rebuilt system preserve hard-delete (no record kept of who removed a diagnosis) or is soft-delete/audit a required behaviour? (PatientResource.java:1917-1929)",
    "loadClinicalNoteByConsultationId and loadGeneralExaminationByConsultationId are GET endpoints that PERSIST a new empty row as a side effect (HTTP 201 from a GET). Is this side-effecting-read behaviour to be reproduced exactly, or treated as a defect for change-request? (PatientResource.java:1230-1247, 1260-1277)",
    "For admissions, loadClinicalNoteByAdmissionId / loadGeneralExaminationByAdmissionId return the LAST row found by iterating the full list (no ordering clause). With multiple rows the 'current' note returned is non-deterministic w.r.t. insertion order; confirm whether an explicit order (e.g. by id/createdAt desc) is required. (PatientResource.java:1360-1364, 1426-1430)",
    "Vitals/BMI/BSA are stored as unvalidated free-text Strings. Modernization target uses BigDecimal/typed fields — is migration to numeric types with validation in scope here, or must free-text strings be preserved verbatim for exact-process fidelity? (GeneralExamination.java:42-51)",
    "request_patient_vitals_by_consultation_id only handles the consultation path and the SUBMITTED status; behaviour for non-consultation/admission vitals handoff and for re-pulling already-ARCHIVED vitals is undefined. Confirm scope of PatientVital state machine ownership with the vitals/exam extraction area. (PatientResource.java:1317-1346)",
    "DiagnosisType uniqueness on code AND name is enforced only by DB constraints (no app-level friendly check); confirm whether a graceful duplicate error is required. Also DiagnosisType can never be deleted (service hard-refuses). Confirm intended deactivation mechanism (the unused 'active' flag?). (DiagnosisTypeServiceImpl.java:38-54, 72-90; DiagnosisType.java:48)"
  ]
}
```

## Area: clinical-orders

```json
{
  "area": "clinical-orders",
  "entities": [
    {
      "name": "LabTest",
      "legacyFile": "domain/LabTest.java:43-154",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (LabTest.java:44-46)",
        "result : String : nullable; set at verify/saveResult (LabTest.java:47)",
        "report : String : @Column(length=10000); set via /lab_tests/add_report (LabTest.java:48-49)",
        "description : String : (LabTest.java:50)",
        "range : String : @Column(name=\"rrange\"); 'range' is a reserved word (LabTest.java:51-52)",
        "level : String : (LabTest.java:53)",
        "unit : String : (LabTest.java:54)",
        "status : String : free-text String, NOT an enum; values PENDING/ACCEPTED/REJECTED/COLLECTED/VERIFIED (LabTest.java:55)",
        "paymentType : String : CASH/DEBIT CARD/CREDIT CARD/MOBILE/INSURANCE per comment (LabTest.java:57)",
        "membershipNo : String : (LabTest.java:58)",
        "diagnosisType : DiagnosisType : @ManyToOne optional=true nullable=true (LabTest.java:60-63)",
        "consultation : Consultation : @ManyToOne optional=true updatable=false (LabTest.java:65-68)",
        "nonConsultation : NonConsultation : @ManyToOne optional=true updatable=false (LabTest.java:70-73)",
        "admission : Admission : @ManyToOne optional=true updatable=false (LabTest.java:75-78)",
        "labTestType : LabTestType : @ManyToOne optional=false nullable=false updatable=false (LabTest.java:80-83)",
        "patientBill : PatientBill : @OneToOne optional=false nullable=false updatable=false (LabTest.java:85-88)",
        "patient : Patient : @ManyToOne optional=false nullable=false updatable=false (LabTest.java:90-93)",
        "clinician : Clinician : @ManyToOne optional=true (LabTest.java:95-98)",
        "insurancePlan : InsurancePlan : @ManyToOne optional=true (LabTest.java:100-103)",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime : audit, createdAt defaults LocalDateTime.now() (LabTest.java:105-109)",
        "orderedBy/orderedOn/orderedAt : Long/Long/LocalDateTime : nullable=false (LabTest.java:111-115)",
        "acceptedBy/acceptedOn/acceptedAt : Long/Long/LocalDateTime : nullable=true (LabTest.java:117-121)",
        "heldBy/heldOn/heldAt : Long/Long/LocalDateTime : nullable=true (LabTest.java:123-127)",
        "rejectedBy/rejectedOn/rejectedAt/rejectComment : Long/Long/LocalDateTime/String : nullable=true (LabTest.java:129-134)",
        "collectedBy/collectedOn/collectedAt : Long/Long/LocalDateTime : nullable=true (LabTest.java:136-140)",
        "verifiedBy/verifiedOn/verifiedAt : Long/Long/LocalDateTime : nullable=true (LabTest.java:142-146)",
        "labTestAttachments : List<LabTestAttachment> : @OneToMany mappedBy=labTest orphanRemoval=true EAGER SUBSELECT (LabTest.java:149-153)"
      ],
      "notes": "SEPARATE entity (NOT a polymorphic ClinicalOrder). 'status' is a plain String column, not a JPA enum. Note acceptedBy etc. use camelCase 'By' setters here, unlike Radiology/Procedure which use lowercase 'by'."
    },
    {
      "name": "Radiology",
      "legacyFile": "domain/Radiology.java:42-150",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (Radiology.java:43-45)",
        "result : String : set at verify/saveResult (Radiology.java:46)",
        "report : String : @Column(length=10000); set via /radiologies/add_report (Radiology.java:47-48)",
        "description : String : (Radiology.java:49)",
        "attachment : Byte[] : legacy binary blob field, set at verify (Radiology.java:50)",
        "status : String : free-text; values PENDING/ACCEPTED/REJECTED/COLLECTED/VERIFIED (Radiology.java:51)",
        "paymentType : String : (Radiology.java:53)",
        "membershipNo : String : (Radiology.java:54)",
        "diagnosisType : DiagnosisType : @ManyToOne optional=true (Radiology.java:56-59)",
        "consultation : Consultation : @ManyToOne optional=true updatable=true (Radiology.java:61-64)",
        "nonConsultation : NonConsultation : @ManyToOne optional=true (Radiology.java:66-69)",
        "admission : Admission : @ManyToOne optional=true (Radiology.java:71-74)",
        "radiologyType : RadiologyType : @ManyToOne optional=false nullable=false (Radiology.java:76-79)",
        "patientBill : PatientBill : @OneToOne optional=false nullable=false updatable=false (Radiology.java:81-84)",
        "patient : Patient : @ManyToOne; nullable=false but optional=true (mismatch) (Radiology.java:86-89)",
        "clinician : Clinician : @ManyToOne optional=true (Radiology.java:91-94)",
        "insurancePlan : InsurancePlan : @ManyToOne optional=true (Radiology.java:96-99)",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime (Radiology.java:101-105)",
        "orderedby/orderedOn/orderedAt : Long/Long/LocalDateTime : note lowercase 'orderedby' (Radiology.java:107-111)",
        "acceptedby/acceptedOn/acceptedAt : Long/Long/LocalDateTime : lowercase 'acceptedby' (Radiology.java:114-118)",
        "rejectedby/rejectedOn/rejectedAt/rejectComment : (Radiology.java:120-125)",
        "heldby/heldOn/heldAt : (Radiology.java:127-131)",
        "collectedby/collectedOn/collectedAt : (Radiology.java:133-137)",
        "verifiedby/verifiedOn/verifiedAt : (Radiology.java:139-143)",
        "radiologyAttachments : List<RadiologyAttachment> : @OneToMany orphanRemoval=true EAGER SUBSELECT (Radiology.java:145-149)"
      ],
      "notes": "SEPARATE entity. Audit setters use lowercase 'by' (acceptedby/rejectedby/etc.) UNLIKE LabTest. Has legacy Byte[] attachment field in addition to the RadiologyAttachment child entity."
    },
    {
      "name": "Procedure",
      "legacyFile": "domain/Procedure.java:40-147",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (Procedure.java:41-43)",
        "note : String : @Column(length=10000); the procedure result narrative, set via add_note (Procedure.java:44-45)",
        "type : String : (Procedure.java:46)",
        "time : LocalTime : @Column(name=\"time_\") (Procedure.java:47-48)",
        "diagnosis : String : (Procedure.java:49)",
        "date : LocalDate : @Column(name=\"date_\") (Procedure.java:50-51)",
        "hours : double : (Procedure.java:52)",
        "minutes : double : (Procedure.java:53)",
        "status : String : free-text; values PENDING/ACCEPTED/REJECTED/VERIFIED (Procedure.java:54)",
        "paymentType : String : (Procedure.java:56)",
        "membershipNo : String : (Procedure.java:57)",
        "theatre : Theatre : @ManyToOne optional=true (Procedure.java:59-62)",
        "diagnosisType : DiagnosisType : @ManyToOne optional=true (Procedure.java:64-67)",
        "consultation : Consultation : @ManyToOne optional=true (Procedure.java:70-73)",
        "nonConsultation : NonConsultation : @ManyToOne optional=true (Procedure.java:75-78)",
        "admission : Admission : @ManyToOne optional=true (Procedure.java:80-83)",
        "procedureType : ProcedureType : @ManyToOne optional=false nullable=false (Procedure.java:85-88)",
        "patientBill : PatientBill : @OneToOne optional=false nullable=false updatable=false (Procedure.java:90-93)",
        "patient : Patient : @ManyToOne nullable=false but optional=true (Procedure.java:95-98)",
        "clinician : Clinician : @ManyToOne optional=true (Procedure.java:100-103)",
        "insurancePlan : InsurancePlan : @ManyToOne optional=true (Procedure.java:105-108)",
        "createdBy/createdOn/createdAt : (Procedure.java:110-114)",
        "orderedby/orderedOn/orderedAt : lowercase 'orderedby' (Procedure.java:116-120)",
        "acceptedby/acceptedOn/acceptedAt : lowercase (Procedure.java:122-126)",
        "heldby/heldOn/heldAt : NOTE: held columns EXIST on entity but NO hold endpoint exists for procedures (Procedure.java:128-132)",
        "rejectedby/rejectedOn/rejectedAt/rejectComment : (Procedure.java:134-139)",
        "verifiedby/verifiedOn/verifiedAt : (Procedure.java:141-145)"
      ],
      "notes": "SEPARATE entity. Procedure has NO collected* columns, NO approved* columns. There is NO APPROVED state and NO 'approve' step (planning-doc M14 is fabricated). The procedure 'result' is captured in the 'note' field. ProcedureModel (models/ProcedureModel.java:28-57) has NO approved/collected fields, confirming."
    },
    {
      "name": "LabTestAttachment",
      "legacyFile": "domain/LabTestAttachment.java:35-57",
      "fields": [
        "id : Long : @Id IDENTITY (LabTestAttachment.java:37-39)",
        "name : String : (LabTestAttachment.java:41)",
        "fileName : String : @NotBlank @Column(unique=true) (LabTestAttachment.java:43-45)",
        "labTest : LabTest : @ManyToOne optional=false nullable=false updatable=false (LabTestAttachment.java:47-50)",
        "createdBy/createdOn/createdAt : (LabTestAttachment.java:52-56)"
      ],
      "notes": "Child of LabTest. Max 5 attachments per test (PatientServiceImpl.java:2828-2830). Can only attach when LabTest.status==COLLECTED (PatientServiceImpl.java:2832-2834)."
    },
    {
      "name": "RadiologyAttachment",
      "legacyFile": "domain/RadiologyAttachment.java:28-50",
      "fields": [
        "id : Long : @Id IDENTITY (RadiologyAttachment.java:30-32)",
        "name : String : (RadiologyAttachment.java:34)",
        "fileName : String : @NotBlank @Column(unique=true) (RadiologyAttachment.java:36-38)",
        "radiology : Radiology : @ManyToOne optional=false nullable=false updatable=false (RadiologyAttachment.java:40-43)",
        "createdBy/createdOn/createdAt : (RadiologyAttachment.java:45-49)"
      ],
      "notes": "Child of Radiology. Max 5 attachments (PatientServiceImpl.java:2927-2929). Can only attach when Radiology.status==ACCEPTED (PatientServiceImpl.java:2931-2933) — NOTE different gate state than LabTest (COLLECTED)."
    }
  ],
  "stateMachine": [
    {
      "from": "(none)",
      "to": "PENDING",
      "trigger": "saveLabTest (POST /patients/save_lab_test -> PatientServiceImpl.saveLabTest)",
      "guard": "Exactly one of consultation/nonConsultation/admission present; if consultation, patient.type must be OUTPATIENT; if nonConsultation, OUTSIDER; admission must be IN-PROCESS (PENDING admission rejected); no duplicate LabTestType for same consultation/nonConsultation",
      "citation": "PatientServiceImpl.java:819 (test.setStatus(\"PENDING\")); guards PatientServiceImpl.java:767-817; PatientResource.java:1944-1958"
    },
    {
      "from": "PENDING",
      "to": "ACCEPTED",
      "trigger": "acceptLabTest (POST /patients/accept_lab_test)",
      "guard": "status must be PENDING or REJECTED; clears reject fields. NO bill/settlement check in this method",
      "citation": "PatientResource.java:3896-3909"
    },
    {
      "from": "REJECTED",
      "to": "ACCEPTED",
      "trigger": "acceptLabTest (POST /patients/accept_lab_test)",
      "guard": "status must be PENDING or REJECTED",
      "citation": "PatientResource.java:3896-3909"
    },
    {
      "from": "PENDING",
      "to": "REJECTED",
      "trigger": "rejectLabTest (POST /patients/reject_lab_test)",
      "guard": "status must be PENDING or ACCEPTED; clears accept fields",
      "citation": "PatientResource.java:3922-3934"
    },
    {
      "from": "ACCEPTED",
      "to": "REJECTED",
      "trigger": "rejectLabTest (POST /patients/reject_lab_test)",
      "guard": "status must be PENDING or ACCEPTED",
      "citation": "PatientResource.java:3922-3934"
    },
    {
      "from": "ACCEPTED",
      "to": "COLLECTED",
      "trigger": "collectLabTest (POST /patients/collect_lab_test)",
      "guard": "status must be ACCEPTED",
      "citation": "PatientResource.java:3947-3955"
    },
    {
      "from": "COLLECTED",
      "to": "VERIFIED",
      "trigger": "verifyLabTestResult (POST /patients/verify_lab_test) — also writes result/level/range/unit",
      "guard": "status must be COLLECTED",
      "citation": "PatientResource.java:3968-3980"
    },
    {
      "from": "ACCEPTED",
      "to": "PENDING",
      "trigger": "holdLabTest (POST /patients/hold_lab_test) — sets held* audit fields then reverts status to PENDING",
      "guard": "status must be ACCEPTED",
      "citation": "PatientResource.java:4013-4021"
    },
    {
      "from": "PENDING",
      "to": "(deleted)",
      "trigger": "deleteLabTest (DELETE /patients ... delete_lab_test)",
      "guard": "status must be PENDING; issues PatientCreditNote if a RECEIVED PatientPaymentDetail exists",
      "citation": "PatientResource.java:2917-2961"
    },
    {
      "from": "(none)",
      "to": "PENDING",
      "trigger": "saveRadiology (POST /patients/save_radiology)",
      "guard": "Exactly one parent of consultation/nonConsultation/admission; admission must be IN-PROCESS; no duplicate RadiologyType per consultation/nonConsultation",
      "citation": "PatientServiceImpl.java:1062 (radio.setStatus(\"PENDING\")); PatientResource.java:1991-1999"
    },
    {
      "from": "PENDING",
      "to": "ACCEPTED",
      "trigger": "acceptRadiology (POST /patients/accept_radiology)",
      "guard": "status must be PENDING or REJECTED; no bill/settlement check in this method",
      "citation": "PatientResource.java:4208-4221"
    },
    {
      "from": "REJECTED",
      "to": "ACCEPTED",
      "trigger": "acceptRadiology",
      "guard": "status must be PENDING or REJECTED",
      "citation": "PatientResource.java:4208-4221"
    },
    {
      "from": "PENDING",
      "to": "REJECTED",
      "trigger": "rejectRadiology (POST /patients/reject_radiology)",
      "guard": "status must be PENDING or ACCEPTED",
      "citation": "PatientResource.java:4234-4246"
    },
    {
      "from": "ACCEPTED",
      "to": "REJECTED",
      "trigger": "rejectRadiology",
      "guard": "status must be PENDING or ACCEPTED",
      "citation": "PatientResource.java:4234-4246"
    },
    {
      "from": "ACCEPTED",
      "to": "PENDING",
      "trigger": "holdRadiology (POST /patients/hold_radiology)",
      "guard": "status must be ACCEPTED",
      "citation": "PatientResource.java:4259-4267"
    },
    {
      "from": "ACCEPTED",
      "to": "VERIFIED",
      "trigger": "verifyRadiologyResult (POST /patients/verify_radiology) — writes result + attachment(Byte[])",
      "guard": "status must be ACCEPTED (NOTE: radiology verifies straight from ACCEPTED, with NO COLLECTED step, unlike LabTest)",
      "citation": "PatientResource.java:4280-4292"
    },
    {
      "from": "ACCEPTED",
      "to": "COLLECTED",
      "trigger": "collectRadiology (POST /patients/collect_radiology111 — note the '111' suffix; appears to be a dead/renamed endpoint)",
      "guard": "status must be ACCEPTED",
      "citation": "PatientResource.java:4325-4333"
    },
    {
      "from": "PENDING",
      "to": "(deleted)",
      "trigger": "deleteRadiology (POST /patients/delete_radiology)",
      "guard": "status must be PENDING; issues PatientCreditNote if RECEIVED payment exists",
      "citation": "PatientResource.java:3423-3467"
    },
    {
      "from": "(none)",
      "to": "PENDING",
      "trigger": "saveProcedure (POST /patients/save_procedure)",
      "guard": "Exactly one parent; admission must be IN-PROCESS; no duplicate ProcedureType per consultation/nonConsultation",
      "citation": "PatientServiceImpl.java:1313 (procedure.setStatus(\"PENDING\")); PatientResource.java:2067-2075"
    },
    {
      "from": "PENDING",
      "to": "ACCEPTED",
      "trigger": "acceptProcedure (POST /patients/accept_procedure)",
      "guard": "status must be PENDING or REJECTED; clears reject fields; no bill/settlement check in this method",
      "citation": "PatientResource.java:4034-4047"
    },
    {
      "from": "REJECTED",
      "to": "ACCEPTED",
      "trigger": "acceptProcedure",
      "guard": "status must be PENDING or REJECTED",
      "citation": "PatientResource.java:4034-4047"
    },
    {
      "from": "ACCEPTED",
      "to": "VERIFIED",
      "trigger": "addProcedureNote (POST /patients/procedures/add_note) — writes the procedure note (result) and finalizes",
      "guard": "note must be non-empty; patientBill.status must be PAID, COVERED or VERIFIED (THIS is the procedure settlement gate). NOTE: status guard in updateProcedure(/patients/update_procedure) requires ACCEPTED to edit the note without finalizing",
      "citation": "PatientResource.java:3405-3415 (add_note sets VERIFIED at 3410); PatientResource.java:4060-4065 (update_procedure requires ACCEPTED)"
    },
    {
      "from": "PENDING",
      "to": "(deleted)",
      "trigger": "deleteProcedure (POST /patients/delete_procedure)",
      "guard": "status must be PENDING",
      "citation": "PatientResource.java:3478-3480"
    }
  ],
  "rules": [
    {
      "rule": "THREE SEPARATE entities exist: LabTest, Radiology, Procedure — NOT one polymorphic ClinicalOrder with kind=LAB_TEST|RADIOLOGY|PROCEDURE. Each has its own table (lab_tests, radiologies, procedures), repository, status string set, and endpoints.",
      "citation": "domain/LabTest.java:42, domain/Radiology.java:41, domain/Procedure.java:39"
    },
    {
      "rule": "There is NO separate OrderResult entity. Results are captured as columns on each order: LabTest.result/level/range/unit/report; Radiology.result/report/attachment; Procedure.note.",
      "citation": "LabTest.java:47-54, Radiology.java:46-50, Procedure.java:44-45"
    },
    {
      "rule": "Accept-before-complete (M16): for LabTest, COLLECTED requires ACCEPTED, and VERIFIED requires COLLECTED. For Radiology, VERIFIED requires ACCEPTED (no collected step in the active verify path). For Procedure, VERIFIED (via add_note) follows ACCEPTED.",
      "citation": "PatientResource.java:3947-3948 (lab collect needs ACCEPTED), 3968-3969 (lab verify needs COLLECTED), 4280-4281 (radiology verify needs ACCEPTED), 4060-4061 (procedure note edit needs ACCEPTED)"
    },
    {
      "rule": "Settlement gate is NOT enforced at the accept/verify transition endpoints for lab/radiology — those methods only check the order's own status, not the bill. The settlement gate is instead applied (a) at the WORKLIST query level — orders are only listed to the lab/radiology department if patientBill.status is in {PAID, COVERED} (outpatient/outsider) or {PAID, COVERED, VERIFIED} (inpatient); and (b) at the report/note-add endpoints which require patientBill.status in {PAID, COVERED, VERIFIED}.",
      "citation": "PatientResource.java:3676 (lab outpatient), 3693 (lab inpatient adds VERIFIED), 3820 (get_lab_tests_by_patient_id), 4134 (get_radiologies_by_patient_id), 4607 (get_procedures_by_patient_id), 3389 (lab add_report gate), 3191 (radiology add_report gate)"
    },
    {
      "rule": "Procedure settlement gate IS explicit in code: addProcedureNote requires patientBill.status in {PAID, COVERED, VERIFIED} and on success sets procedure status to VERIFIED; otherwise throws 'Could not add procedure note. Payment not verified'.",
      "citation": "PatientResource.java:3408-3414"
    },
    {
      "rule": "Procedure has NO distinct APPROVED state and NO approve step. The only procedure transitions are save->PENDING, accept->ACCEPTED, add_note->VERIFIED, delete (PENDING only). The setStatus(\"APPROVED\") calls at PatientResource.java:5378/5627/5677 operate on DischargePlan/ReferralPlan objects, not Procedure; the getApprovedAt() at PatientResource.java:2576 is inside loadPrescriptions (Prescription), not procedures.",
      "citation": "PatientResource.java:4026-4067, 3397-3416; ProcedureModel.java:28-57 (no approved field); PatientResource.java:5360-5381 (DischargePlan context)"
    },
    {
      "rule": "Duplicate-order prevention: cannot create a LabTest/Radiology/Procedure of a type already ordered for the same consultation or nonConsultation (existsByConsultationAnd<Type>Type / existsByNonConsultationAnd<Type>Type).",
      "citation": "PatientResource.java:1948-1957 (lab), 1991-1999 (radiology), 2067-2075 (procedure)"
    },
    {
      "rule": "Patient-type gate at order creation: a consultation order requires patient.type==OUTPATIENT; a nonConsultation order requires patient.type==OUTSIDER; else InvalidOperationException.",
      "citation": "PatientResource.java:1945-1946 (lab outpatient), 1952-1953 (lab outsider)"
    },
    {
      "rule": "On creation each order generates a PatientBill (qty 1, billItem 'Lab Test'/'Radiology'/'Procedure', status UNPAID, amount = <Type>.getPrice()). For INSURANCE patients (or admission), if a covered <Type>InsurancePlan price exists the bill is set COVERED (paid in full, balance 0) and added to a PENDING PatientInvoice/PatientInvoiceDetail; for admission without a covered plan the bill is set VERIFIED.",
      "citation": "PatientServiceImpl.java:821-828 (lab bill UNPAID), 837-849 (COVERED branch), 912-918 (admission VERIFIED branch); equivalent radiology 1064-1157, procedure 1315-1408"
    },
    {
      "rule": "Attachment limits: max 5 attachments per LabTest and per Radiology; LabTest attachment allowed only when status==COLLECTED; Radiology attachment allowed only when status==ACCEPTED.",
      "citation": "PatientServiceImpl.java:2828-2834 (lab), 2927-2933 (radiology)"
    },
    {
      "rule": "saveLabTestResult / saveRadiologyResult allow editing results without changing status: lab edit requires COLLECTED, radiology edit requires ACCEPTED.",
      "citation": "PatientResource.java:3993-3994 (lab), 4305-4306 (radiology)"
    },
    {
      "rule": "Reject-comment endpoints persist a free-text rejectComment only when the order is currently REJECTED (else InvalidOperationException).",
      "citation": "PatientResource.java:2026-2030 (radiology), 2042-2046 (lab)"
    },
    {
      "rule": "Lab/radiology attachment viewing (download) is gated on status==VERIFIED.",
      "citation": "PatientResource.java:6021 (lab), 6154 (radiology)"
    },
    {
      "rule": "No Hibernate Envers audit trail is effectively active in the legacy system — these three entities carry no @Audited annotation; auditing is done via ad-hoc *By/*On/*At columns on each entity. Downstream agents must not assume an Envers audit baseline exists.",
      "citation": "domain/LabTest.java:1-154, domain/Radiology.java:1-150, domain/Procedure.java:1-147 (no @Audited import or annotation present)"
    },
    {
      "rule": "No device-fingerprint or device-binding feature is involved in clinical-order endpoints. All transition endpoints identify the actor solely via userService.getUserId(request)/getUser(request) from the JWT; no fingerprint logic exists in these resources.",
      "citation": "PatientResource.java:3900, 3926, 3951, 3976, 4017, 4212, 4288, 4329 (all use userService.getUserId(request))"
    }
  ],
  "numbering": "NONE for clinical orders themselves — LabTest/Radiology/Procedure use database IDENTITY primary keys only (LabTest.java:44-46, Radiology.java:43-45, Procedure.java:41-43) and have no human-facing order number. The only generated number in this flow is on side artefacts: the auto-created PatientInvoice.no is first set to String.valueOf(Math.random()) then overwritten with the invoice's own id.toString() (PatientServiceImpl.java:857,878-879); and a cancellation PatientCreditNote.no is obtained from patientCreditNoteService.requestPatientCreditNoteNo() (PatientResource.java:2932, 3438) — that scheme belongs to the billing context, not clinical-orders.",
  "rbac": [],
  "driftVsPlanningDoc": [
    "Planning doc claims a SINGLE polymorphic 'ClinicalOrder' with kind=LAB_TEST|RADIOLOGY|PROCEDURE. REALITY: three separate entities LabTest (domain/LabTest.java:43), Radiology (domain/Radiology.java:42), Procedure (domain/Procedure.java:40) with separate tables, repositories, models, and endpoints.",
    "Planning doc invents an 'OrderResult' entity. REALITY: no such entity. Results live as columns on each order — LabTest.result/level/range/unit/report (LabTest.java:47-54), Radiology.result/report/attachment (Radiology.java:46-50), Procedure.note (Procedure.java:44-45).",
    "Planning doc invents privilege codes CONSULTATION_START / PROCEDURE_ORDER_APPROVE / PRESCRIPTION_CREATE. REALITY: NONE of these exist; moreover the clinical-order endpoints carry NO active @PreAuthorize at all — the only annotations present are commented out, e.g. //@PreAuthorize(\"hasAnyAuthority('PRODUCT-CREATE')\") on save_lab_test (PatientResource.java:1932) and on the attachment endpoints (PatientResource.java:5942,6075). accept/reject/collect/verify/hold endpoints have no annotation whatsoever (PatientResource.java:3888,3914,3939,3960,4005,4026,4200,4226,4251,4272).",
    "Planning doc claims a distinct PROCEDURE 'approve' step (M14) with an APPROVED state and a PROCEDURE_ORDER_APPROVE privilege. REALITY: Procedure has NO APPROVED state and NO approve endpoint; the entity has no approved* columns (Procedure.java:40-147) and ProcedureModel has no approved field (ProcedureModel.java:28-57). The only finalize is add_note -> VERIFIED (PatientResource.java:3410).",
    "Planning doc's clean lifecycle BOOKED/IN_PROGRESS/COMPLETED does not match. REALITY status strings: LabTest = PENDING, ACCEPTED, REJECTED, COLLECTED, VERIFIED (PatientResource.java:3909,3934,3955,3980 + create PatientServiceImpl.java:819); Radiology = PENDING, ACCEPTED, REJECTED, COLLECTED, VERIFIED (PatientResource.java:4221,4246,4333,4292 + create 1062); Procedure = PENDING, ACCEPTED, REJECTED(only via reject-comment path implied), VERIFIED (PatientResource.java:4047,3410 + create 1313). There is no BOOKED, IN_PROGRESS, or COMPLETED value anywhere; status is a free-text String column, not an enum.",
    "Planning doc implies a settlement gate enforced at the accept step. REALITY: accept endpoints do NOT check the bill (PatientResource.java:3896-3909 lab, 4208-4221 radiology, 4034-4047 procedure). The settlement gate is enforced upstream at worklist queries (only PAID/COVERED, plus VERIFIED for inpatient, orders are surfaced — PatientResource.java:3676,3693,3820,4134,4607) and at report/note endpoints (PatientResource.java:3389,3191,3408).",
    "Lab vs radiology pipelines DIVERGE (not uniform as a single polymorphic model would suggest): LabTest path is ACCEPTED -> COLLECTED -> VERIFIED, attachments allowed at COLLECTED; Radiology path verifies straight from ACCEPTED (no collected step in active verify), attachments allowed at ACCEPTED (PatientResource.java:3947-3980 vs 4280-4292; PatientServiceImpl.java:2832 vs 2931)."
  ],
  "openQuestions": [
    "Radiology has BOTH a verify-from-ACCEPTED path (verifyRadiologyResult, PatientResource.java:4272-4292) AND a separate collectRadiology endpoint mapped to the suspicious URL '/patients/collect_radiology111' (PatientResource.java:4317-4333) that moves ACCEPTED->COLLECTED. Is COLLECTED a live radiology state, or is collect_radiology111 a dead/renamed endpoint? The active radiology verify ignores COLLECTED. HDE/BA to confirm intended radiology pipeline.",
    "Procedure exposes held* audit columns (Procedure.java:128-132) but there is NO hold-procedure endpoint. Confirm whether procedure 'hold' was intended/removed, or columns are vestigial.",
    "Procedure REJECTED state: acceptProcedure allows from PENDING or REJECTED (PatientResource.java:4034) implying a REJECTED state can occur, but no reject-procedure endpoint was found. How does a Procedure reach REJECTED? (Possibly only via UI that never reached the backend.) Confirm with BA.",
    "Settlement is enforced only by worklist filtering and report/note endpoints, NOT at accept/verify. A client calling accept/verify directly (bypassing the worklist) on an UNPAID order would succeed for lab/radiology. Confirm whether exact-process means we replicate this gap or treat the worklist filter as the canonical gate. Recommend explicit decision from engagement-lead before reproducing.",
    "On creation, the auto-generated PatientInvoice.no is seeded with String.valueOf(Math.random()) before being overwritten with the entity id (PatientServiceImpl.java:857,878-879). Confirm whether the random seed has any downstream significance or is purely transient (it appears transient).",
    "Several entity field-name casing inconsistencies (LabTest uses acceptedBy/rejectedBy camelCase; Radiology/Procedure use acceptedby/rejectedby lowercase). Confirm whether data-migration must preserve the exact legacy column names (e.g. ordered_by_user_id is shared, but Java field getters differ) — flag to data-migration-engineer."
  ]
}
```

## Area: prescriptions-alerts

```json
{
  "area": "prescriptions-alerts",
  "entities": [
    {
      "name": "Prescription",
      "legacyFile": "domain/Prescription.java:38-144",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (domain/Prescription.java:39-41)",
        "dosage : String : free-text, NOT an FK to Dosage (domain/Prescription.java:42)",
        "frequency : String : free-text, NOT an FK to DosingFrequency (domain/Prescription.java:43)",
        "route : String : free-text, NOT an FK to AdministrationRoute (domain/Prescription.java:44)",
        "days : String : free-text numeric-as-string; parsed via Integer.valueOf in unfinished-course alert (domain/Prescription.java:45; consumed PatientResource.java:4556)",
        "qty : double : @NotNull (domain/Prescription.java:46-47)",
        "issued : double : defaults 0; set to issued amount on dispense (domain/Prescription.java:48; PatientServiceImpl.java:1693; PatientResource.java:3227)",
        "balance : double : initialised to qty at create, decremented by issued on dispense (domain/Prescription.java:49; PatientServiceImpl.java:1694; PatientResource.java:3228)",
        "status : String : ONLY 'NOT-GIVEN' then 'GIVEN' actually written; no enum, no constraint (domain/Prescription.java:50; PatientServiceImpl.java:1532; PatientResource.java:3230)",
        "reference : String : nullable free-text (domain/Prescription.java:51)",
        "instructions : String : nullable free-text (domain/Prescription.java:52)",
        "paymentType : String : comment lists CASH,DEBIT CARD,CREDIT CARD,MOBILE,INSURANCE (domain/Prescription.java:54)",
        "membershipNo : String : nullable (domain/Prescription.java:55)",
        "consultation : Consultation : @ManyToOne nullable FK consultation_id (domain/Prescription.java:57-60)",
        "nonConsultation : NonConsultation : @ManyToOne nullable FK non_consultation_id (domain/Prescription.java:62-65)",
        "admission : Admission : @ManyToOne nullable FK admission_id (domain/Prescription.java:67-70)",
        "medicine : Medicine : @ManyToOne NOT NULL FK medicine_id (domain/Prescription.java:72-75)",
        "patientBill : PatientBill : @OneToOne NOT NULL FK patient_bill_id (domain/Prescription.java:77-80)",
        "patient : Patient : @ManyToOne NOT NULL FK patient_id (domain/Prescription.java:82-85)",
        "clinician : Clinician : @ManyToOne nullable FK clinician_id (domain/Prescription.java:87-90)",
        "insurancePlan : InsurancePlan : @ManyToOne nullable FK insurance_plan_id (domain/Prescription.java:92-95)",
        "issuePharmacy : Pharmacy : @ManyToOne nullable FK issue_pharmacy_id; set on dispense (domain/Prescription.java:97-100; PatientResource.java:3231)",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime : audit triplet, user-id + business-day-id + timestamp (domain/Prescription.java:102-106)",
        "orderedby/orderedOn/orderedAt : Long/Long/LocalDateTime : ordered audit triplet (domain/Prescription.java:108-112)",
        "acceptedby/acceptedOn/acceptedAt : Long/Long/LocalDateTime : DECLARED but NEVER written for Prescription (domain/Prescription.java:114-118)",
        "heldby/heldOn/heldAt : Long/Long/LocalDateTime : DECLARED but NEVER written (domain/Prescription.java:120-124)",
        "rejectedby/rejectedOn/rejectedAt/rejectComment : Long/Long/LocalDateTime/String : DECLARED but NEVER written (domain/Prescription.java:126-131)",
        "verifiedby/verifiedOn/verifiedAt : Long/Long/LocalDateTime : DECLARED but NEVER written (domain/Prescription.java:133-137)",
        "approvedBy/approvedOn/approvedAt : Long/Long/LocalDateTime : THE dispense audit; set in issueMedicine, used by alerts/reports as the dispense timestamp (domain/Prescription.java:139-143; PatientResource.java:3233-3235; alerts read approvedAt PatientResource.java:4499,4561; reports use prescriptions.approved_at PrescriptionRepository.java:156,178)"
      ],
      "notes": "There is NO payStatus field on Prescription and NO ACCEPTED/HELD/VERIFIED/APPROVED/SOLD/REJECTED/CANCELLED status value ever written. The accepted/held/rejected/verified/approved audit-column groups are copy-paste boilerplate from PharmacySaleOrderDetail; only the approved* group is populated (at dispense time, by issueMedicine). dosage/frequency/route/days are plain Strings, NOT FKs to Dosage/DosingFrequency/AdministrationRoute. Quantity model: qty (ordered) / issued / balance, where balance starts = qty and is reduced by issued; issue is all-or-nothing (must equal full qty, see rules)."
    },
    {
      "name": "PrescriptionBatch",
      "legacyFile": "domain/PrescriptionBatch.java:34-48",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (domain/PrescriptionBatch.java:35-37)",
        "no : String : @NotBlank batch number (domain/PrescriptionBatch.java:38-39)",
        "manufacturedDate : LocalDate : nullable (domain/PrescriptionBatch.java:40)",
        "expiryDate : LocalDate : nullable (domain/PrescriptionBatch.java:41)",
        "qty : double : defaults 0 (domain/PrescriptionBatch.java:42)",
        "prescription : Prescription : @ManyToOne NOT NULL FK prescription_id (domain/PrescriptionBatch.java:44-47)"
      ],
      "notes": "Batch-traceability child of Prescription. No status, no transitions, no consuming logic found referencing PrescriptionBatch in PatientServiceImpl/PatientResource (only repository PrescriptionBatchRepository exists). Effectively a near-inert linkage entity in the OPD/prescription flow."
    },
    {
      "name": "PatientPrescriptionChart",
      "legacyFile": "domain/PatientPrescriptionChart.java:34-82",
      "fields": [
        "id : Long : @Id @GeneratedValue IDENTITY (domain/PatientPrescriptionChart.java:35-37)",
        "dosage : String : free-text (domain/PatientPrescriptionChart.java:38)",
        "output : String : free-text (domain/PatientPrescriptionChart.java:39)",
        "remark : String : free-text (domain/PatientPrescriptionChart.java:40)",
        "consultation : Consultation : @ManyToOne nullable (domain/PatientPrescriptionChart.java:42-45)",
        "nonConsultation : NonConsultation : @ManyToOne nullable (domain/PatientPrescriptionChart.java:47-50)",
        "admission : Admission : @ManyToOne nullable (domain/PatientPrescriptionChart.java:52-55)",
        "prescription : Prescription : @ManyToOne NOT NULL FK prescription_id (domain/PatientPrescriptionChart.java:57-60)",
        "patient : Patient : @ManyToOne NOT NULL (domain/PatientPrescriptionChart.java:62-65)",
        "clinician : Clinician : @ManyToOne nullable (domain/PatientPrescriptionChart.java:67-70)",
        "nurse : Nurse : @ManyToOne nullable (domain/PatientPrescriptionChart.java:72-75)",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime : audit triplet (domain/PatientPrescriptionChart.java:77-81)"
      ],
      "notes": "Inpatient drug-administration record (a nurse charting that a GIVEN prescription was administered). It has NO status field of its own. Creation is GUARDED: the linked Prescription must be in status 'GIVEN' (i.e. dispensed from pharmacy) else InvalidOperationException 'Prescription not picked from pharmacy' (PatientServiceImpl.java:2544-2546). Only allowed for admissions; throws for outpatients/outsiders (PatientServiceImpl.java:2564-2569)."
    },
    {
      "name": "PharmacySaleOrderDetail (NOT Prescription — disambiguation)",
      "legacyFile": "domain/PharmacySaleOrderDetail.java:29-126",
      "fields": [
        "status : String : defaults 'PENDING'; THIS is the entity that owns the PENDING/ACCEPTED/HELD/VERIFIED/APPROVED/SOLD lifecycle the planning doc wrongly attributed to Prescription (domain/PharmacySaleOrderDetail.java:41)",
        "payStatus : String : defaults 'UNPAID'; set to 'PAID' on bill settlement (domain/PharmacySaleOrderDetail.java:42; PatientBillResource.java:378)",
        "soldBy/soldOn/soldAt : Long/Long/LocalDateTime : sale audit triplet that Prescription does NOT have (domain/PharmacySaleOrderDetail.java:121-125)"
      ],
      "notes": "Included ONLY to document the planning-doc conflation. The walk-in pharmacy sale line item (PharmacySaleOrderDetail) is a DIFFERENT entity from the clinician-ordered Prescription. Its defaults status='PENDING' and payStatus='UNPAID' (domain/PharmacySaleOrderDetail.java:41-42) are exactly the values the planning doc claims for Prescription. The parent PharmacySaleOrder is moved to 'APPROVED' and details to payStatus 'PAID' at PatientBillResource.java:370,378."
    }
  ],
  "stateMachine": [
    {
      "from": "(none / new)",
      "to": "NOT-GIVEN",
      "trigger": "savePrescription() — clinician orders a drug on a Consultation/NonConsultation/Admission",
      "guard": "Exactly one of consultation/nonConsultation/admission must be present (else InvalidOperationException); medicine must exist; if admission, admission.status must be 'IN-PROCESS' (PENDING => 'Admission not verified', other => 'already signed off')",
      "citation": "PatientServiceImpl.java:1532 (status set NOT-GIVEN); one-of guard 1489-1503; admission guard 1516-1522"
    },
    {
      "from": "NOT-GIVEN",
      "to": "GIVEN",
      "trigger": "issueMedicine() — pharmacy dispenses the prescribed medicine",
      "guard": "prescription.status must equal 'NOT-GIVEN' else 'not a pending prescription'; issued must equal full prescribed qty (issueMedicine enforces issued==qty); pharmacy stock must cover qty; on success sets issued, balance-=issued, issuePharmacy, and approvedBy/approvedOn/approvedAt",
      "citation": "PatientResource.java:3217-3219 (NOT-GIVEN guard), 3224-3226 (issued==qty), 3243-3245 (stock), 3230 (status GIVEN), 3227-3235 (issued/balance/audit)"
    },
    {
      "from": "NOT-GIVEN",
      "to": "(deleted)",
      "trigger": "deletePrescription() — order is removed before dispensing",
      "guard": "status must be 'PENDING' OR 'NOT-GIVEN' (the 'PENDING' branch is dead — Prescription is never PENDING); cascades delete of patientBill, any PatientInvoiceDetail (+empty PatientInvoice), any PatientPaymentDetail, and raises a PENDING PatientCreditNote if a paid PatientPaymentDetail with status 'GIVEN' existed",
      "citation": "PatientResource.java:3545-3547 (guard), 3567-3578 (credit note), 3580-3602 (cascade delete)"
    },
    {
      "from": "GIVEN",
      "to": "GIVEN (terminal)",
      "trigger": "none",
      "guard": "No further Prescription status transition exists in the legacy. GIVEN is terminal for the Prescription entity. (Inpatient administration is then recorded as a separate PatientPrescriptionChart row, which requires status=='GIVEN'.)",
      "citation": "No setStatus on Prescription beyond NOT-GIVEN/GIVEN found anywhere in com.orbix.api (grep of prescription*.setStatus); PatientPrescriptionChart guard PatientServiceImpl.java:2544-2546"
    }
  ],
  "rules": [
    {
      "rule": "COMPLETE legacy Prescription.status set is exactly two values: 'NOT-GIVEN' (at create) and 'GIVEN' (at dispense). The planning-doc set PENDING->ACCEPTED->HELD->VERIFIED->APPROVED->SOLD|REJECTED|CANCELLED is NOT the Prescription lifecycle; it is the PharmacySaleOrderDetail lifecycle. Reproduce only NOT-GIVEN/GIVEN for Prescription.",
      "citation": "PatientServiceImpl.java:1532; PatientResource.java:3230; planning-doc lifecycle actually lives at domain/PharmacySaleOrderDetail.java:41"
    },
    {
      "rule": "Prescription has NO payStatus field. Payment state for a clinician prescription is carried entirely by its linked PatientBill.status (UNPAID/COVERED/VERIFIED/CANCELED). The planning-doc 'payStatus UNPAID|PAID' belongs to PharmacySaleOrderDetail, not Prescription.",
      "citation": "domain/Prescription.java:38-144 (no payStatus); PatientBill status set PatientServiceImpl.java:1540,1555,1627; payStatus only on domain/PharmacySaleOrderDetail.java:42"
    },
    {
      "rule": "On create, a PatientBill is generated priced at medicine.price * qty, status 'UNPAID'. If patient.paymentType=='INSURANCE' OR this is an admission, insurance/inpatient repricing applies (see next rules).",
      "citation": "PatientServiceImpl.java:1533-1545,1547"
    },
    {
      "rule": "Insurance pricing: if a MedicineInsurancePlan exists for (medicine, patient.insurancePlan, covered=true), bill amount/paid = plan.price*qty, balance 0, status 'COVERED', and a PatientInvoiceDetail is attached to the patient's PENDING PatientInvoice (creating one if absent).",
      "citation": "PatientServiceImpl.java:1549-1559 (covered pricing), 1561-1620 (invoice attach)"
    },
    {
      "rule": "Inpatient-without-covered-plan pricing: if admission present but no covered MedicineInsurancePlan, bill is repriced to medicine.price*qty, status 'VERIFIED' (deferred billing), attached to a PENDING insurancePlan=null PatientInvoice.",
      "citation": "PatientServiceImpl.java:1622-1690"
    },
    {
      "rule": "Quantity/remaining model: at create issued=0 and balance=qty. Dispense requires issued==qty (all-or-nothing — partial dispense is rejected with 'You can only issue the prescribed qty'); after dispense issued=qty and balance=0.",
      "citation": "PatientServiceImpl.java:1693-1694; PatientResource.java:3221-3228"
    },
    {
      "rule": "HARD duplicate-drug guard (not a soft alert): saving a prescription for a medicine already prescribed on the same consultation is blocked with 'Duplicate drug is not allowed. Consider editing qty'. NOTE the OUTSIDER/NonConsultation branch contains a BUG: it calls existsByConsultationAndMedicine(consultation_.get(),...) on a consultation_ Optional that is empty in that branch, which will NPE rather than evaluate the duplicate.",
      "citation": "PatientResource.java:2109-2111 (consultation branch); 2116-2118 (nonConsultation branch BUG — uses consultation_.get())"
    },
    {
      "rule": "DUPLICATE-MEDICINE / SAME-MEDICINE-WITHIN-30-DAYS alert (soft, informational, legacy-present): endpoint get_same_medicine_alert_one_month_by_prescription_id loads all prior GIVEN prescriptions of same patient+medicine, takes the LAST one's approvedAt, computes days since, and if days<=30 appends ' Has Drugs this month.' (if days==0 it always appends it). Returns a free-text string in SingleObject.value; it is purely advisory and does NOT block.",
      "citation": "PatientResource.java:4480-4521; threshold days<=30 at 4508-4510; uses findAllByPatientAndMedicineAndStatus(...,'GIVEN') at 4496"
    },
    {
      "rule": "UNFINISHED-COURSE alert (soft, informational, legacy-present): endpoint get_unfinished_medicine_alert_by_patient_id_and_medicine_id loads prior GIVEN prescriptions of same patient+medicine, parses days from prescription.days (String->int), computes days elapsed since approvedAt; if elapsed < prescribed days it returns 'The patient has not completed the last prescription...' advisory string. It is wrapped in try/catch that swallows all exceptions to empty string; advisory only, does NOT block.",
      "citation": "PatientResource.java:4528-4580; threshold num<days at 4567; days parsed at 4556; approvedAt at 4561"
    },
    {
      "rule": "deletePrescription only permits removal in status PENDING or NOT-GIVEN; since Prescription is never PENDING in practice, effectively only NOT-GIVEN (undispensed) prescriptions are deletable.",
      "citation": "PatientResource.java:3545-3547"
    },
    {
      "rule": "Patient transfer is blocked while any prescription on the consultation is still 'PENDING' — but because Prescription is never set to 'PENDING', this guard never actually fires for prescriptions (dead guard). Documented for fidelity; do not 'fix' silently.",
      "citation": "PatientServiceImpl.java:2793-2796 (checks status 'PENDING')"
    }
  ],
  "numbering": "NONE for the Prescription entity itself — Prescription has no human-facing document number; its key is the DB IDENTITY id (domain/Prescription.java:39-41). Related artefacts created during prescribing carry their own numbers: the PatientInvoice no is seeded to String.valueOf(Math.random()) then overwritten with the invoice's own id.toString() (PatientServiceImpl.java:1567,1588 and 1636,1657); a PatientCreditNote raised on deletion of a paid prescription gets its number via patientCreditNoteService.requestPatientCreditNoteNo().getNo() (PatientResource.java:3572). PrescriptionBatch.no is a free-text @NotBlank batch string with no generation scheme found (domain/PrescriptionBatch.java:38-39).",
  "rbac": [],
  "driftVsPlanningDoc": [
    "Status set: planning doc claims Prescription lifecycle PENDING->ACCEPTED->HELD->VERIFIED->APPROVED->SOLD|REJECTED|CANCELLED. LEGACY Prescription.status is ONLY 'NOT-GIVEN' (create, PatientServiceImpl.java:1532) -> 'GIVEN' (dispense, PatientResource.java:3230). The full 6+3-state machine the doc describes is actually domain/PharmacySaleOrderDetail.java:41 (a DIFFERENT entity for walk-in pharmacy sales).",
    "payStatus: planning doc claims Prescription has payStatus UNPAID|PAID. LEGACY Prescription has NO payStatus field (domain/Prescription.java:38-144). payStatus UNPAID|PAID exists only on domain/PharmacySaleOrderDetail.java:42 (PAID set at PatientBillResource.java:378). Payment state of a clinician prescription is on its PatientBill, not the Prescription.",
    "FK links: planning doc implies FK links to Dosage / AdministrationRoute / DosingFrequency. LEGACY stores dosage, frequency, route, days as plain Strings (domain/Prescription.java:42-45) — there is NO FK to Dosage, AdministrationRoute, or DosingFrequency. The only true FKs are Medicine (medicine_id, mandatory, domain/Prescription.java:72-75), PatientBill, Patient, Consultation/NonConsultation/Admission, Clinician, InsurancePlan, Pharmacy(issue).",
    "Alert ownership: planning doc attributes prescribing alerts to a 'prior-attempt PrescribingAlertService'. That service is a PRIOR MODERN ATTEMPT, not legacy. The LEGACY alert logic exists but is NOT in any service class — it is inline in PatientResource controller methods: DUPLICATE/SAME-MEDICINE-within-30-days at PatientResource.java:4480-4521 and UNFINISHED-COURSE at PatientResource.java:4528-4580. There is NO PrescribingAlertService, AlertService, or equivalent in com.orbix.api (grep found none).",
    "Alert semantics precision: legacy 'same medicine' alert threshold is days<=30 on the LAST GIVEN prescription's approvedAt (PatientResource.java:4508), and the 'unfinished course' alert fires when elapsed days < the integer parsed from prescription.days (PatientResource.java:4556,4567) — NOT a generic 'remaining qty>0' rule. The planning-doc's UNFINISHED_COURSE 'on remaining qty>0' is NOT how legacy computes it (legacy ignores balance/issued for this alert; it uses approvedAt vs days). Both alerts are advisory free-text only and never block an action.",
    "Hard duplicate guard vs soft alert: separate from the soft same-medicine alert, the legacy has a HARD block on saving a duplicate medicine within the same consultation (existsByConsultationAndMedicine -> InvalidOperationException, PatientResource.java:2109-2111). The planning doc frames DUPLICATE_MEDICINE only as a 30-day alert; legacy actually has BOTH a hard same-consultation block AND a soft 30-day informational alert — these are two different mechanisms.",
    "Status names: planning doc clean names (e.g. BOOKED/IN_PROGRESS/COMPLETED style) do not apply; legacy uses hyphenated 'NOT-GIVEN'/'GIVEN' for Prescription.",
    "PatientPrescriptionChart: legacy chart has NO status field at all (domain/PatientPrescriptionChart.java) — any planned 'chart status' is a net-new invention."
  ],
  "openQuestions": [
    "Confirm intended behaviour of the OUTSIDER/NonConsultation duplicate-drug check at PatientResource.java:2116 which calls consultation_.get() on an empty Optional (will NPE for outsiders). Should the modern build replicate the NPE, silently skip the duplicate check for outsiders, or apply existsByNonConsultationAndMedicine? This is a latent legacy bug — needs an explicit change request decision.",
    "The 'PENDING' status appears in two Prescription guards (delete PatientResource.java:3545; transfer PatientServiceImpl.java:2793) but Prescription is never set to 'PENDING' anywhere. Confirm whether 'PENDING' was an intended earlier state and these are dead branches to preserve verbatim, or whether modern build should drop them.",
    "Both alert endpoints are unsecured (no @PreAuthorize, no class-level security on PatientResource). Confirm with security-architect whether the modern build must add privilege gating (a behavioural change requiring approval) or preserve them as open.",
    "The 'same medicine within 30 days' alert reads only the LAST element of an unordered findAllByPatientAndMedicineAndStatus list (PatientResource.java:4498-4500) — relying on default repository ordering (likely id/insertion order). Confirm whether 'last GIVEN' must be defined explicitly as max(approvedAt) in the rebuild to be deterministic.",
    "prescription.days is a free-text String parsed via Integer.valueOf with a swallowed exception (PatientResource.java:4556-4558); non-numeric days silently disable the unfinished-course alert. Confirm whether days should become a validated integer in the modern model (data-type change, likely in scope) while preserving the exact alert math.",
    "PrescriptionBatch has no consuming logic in the OPD/prescription flow (only a repository). Confirm with healthcare-domain-expert whether batch-level traceability of dispensed prescriptions is an active requirement or a dormant table to migrate as-is."
  ]
}
```

## Area: consultation-transfer

```json
{
  "area": "consultation-transfer",
  "entities": [
    {
      "name": "ConsultationTransfer",
      "legacyFile": "domain/ConsultationTransfer.java:35-65",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (domain/ConsultationTransfer.java:37-39)",
        "status : String : @Column(nullable=false); NOT @NotBlank, but DB-NOT-NULL (domain/ConsultationTransfer.java:40-41). Observed values: PENDING, COMPLETED, CANCELED",
        "reason : String : nullable, free text; set by caller only, never validated or read by service logic (domain/ConsultationTransfer.java:43)",
        "patient : Patient : @ManyToOne EAGER, optional=false, JoinColumn patient_id nullable=false updatable=false (domain/ConsultationTransfer.java:45-48). Set by service from consultation.getPatient() (PatientServiceImpl.java:2813)",
        "consultation : Consultation : @ManyToOne EAGER, optional=false, JoinColumn consultation_id nullable=false updatable=false (domain/ConsultationTransfer.java:50-53)",
        "clinic : Clinic : @ManyToOne EAGER, optional=false, JoinColumn clinic_id nullable=false updatable=TRUE -- this is the DESTINATION clinic (domain/ConsultationTransfer.java:55-58)",
        "createdBy : Long : @Column created_by_user_id nullable=false updatable=false; set to current user id (domain/ConsultationTransfer.java:60-61; PatientServiceImpl.java:2815)",
        "createdOn : Long : @Column created_on_day_id nullable=false updatable=false; set to business day id (domain/ConsultationTransfer.java:62-63; PatientServiceImpl.java:2816)",
        "createdAt : LocalDateTime : default LocalDateTime.now(), overwritten with dayService.getTimeStamp() (domain/ConsultationTransfer.java:64; PatientServiceImpl.java:2817)"
      ],
      "notes": "There is NO clinician field on ConsultationTransfer. The transfer targets only a destination CLINIC; the destination clinician is NOT chosen at raise time. There is NO @Version / optimistic-lock column. There is NO source-clinic, source-clinician, acceptedBy, acceptedOn, or completedAt field -- the entity records only origin (via consultation), destination clinic, reason, and forensic create stamps. The destination clinic is the only updatable relation (updatable=true), all others updatable=false."
    },
    {
      "name": "Consultation (transfer-relevant fields/states only)",
      "legacyFile": "domain/Consultation.java:47-110",
      "fields": [
        "status : String : @NotBlank (domain/Consultation.java:55-56). Full observed status string set across OPD: PENDING, IN-PROCESS, TRANSFERED (note single-R spelling), HELD, SIGNED-OUT, CANCELED",
        "clinic : Clinic : @ManyToOne, JoinColumn clinic_id nullable=false updatable=FALSE (domain/Consultation.java:77-80) -- source consultation's clinic is IMMUTABLE; the transfer does NOT mutate it",
        "clinician : Clinician : @ManyToOne, JoinColumn clinician_id nullable=false updatable=TRUE (domain/Consultation.java:85-88) -- reassignable in principle, but the transfer flow does NOT touch it",
        "followUp : boolean : default false (domain/Consultation.java:57)"
      ],
      "notes": "On RAISE the existing source Consultation row is mutated: status PENDING/IN-PROCESS check then set to TRANSFERED (PatientServiceImpl.java:2808). A brand-new Consultation row is later created by doConsultation (PatientServiceImpl.java:487-523) when the patient is re-sent (the ACCEPT side) -- the transfer does NOT carry the same consultation across clinics; it terminates the old one and a new one is created. No @Version on Consultation either."
    }
  ],
  "stateMachine": [
    {
      "from": "(source Consultation = IN-PROCESS)",
      "to": "Consultation = TRANSFERED",
      "trigger": "createConsultationTransfer() / POST /patients/create_consultation_transfer",
      "guard": "source consultation status MUST equal 'IN-PROCESS' (PatientServiceImpl.java:2756); no other patient transfer PENDING (PatientServiceImpl.java:2764-2767); no PENDING LabTest/Radiology/Procedure/Prescription (PatientServiceImpl.java:2769-2800); destination clinic != source clinic (PatientServiceImpl.java:2804-2806)",
      "citation": "PatientServiceImpl.java:2808 (con.setStatus(\"TRANSFERED\")); 2809 save"
    },
    {
      "from": "(none)",
      "to": "ConsultationTransfer = PENDING",
      "trigger": "createConsultationTransfer() -- created in same call that sets source consultation TRANSFERED",
      "guard": "same guards as above; transfer is created only after source consultation set TRANSFERED",
      "citation": "PatientServiceImpl.java:2811 (transfer.setStatus(\"PENDING\")); 2819 save"
    },
    {
      "from": "ConsultationTransfer = PENDING",
      "to": "ConsultationTransfer = COMPLETED",
      "trigger": "doConsultation(patient,clinic,clinician,...) -- i.e. registration/triage re-sends the patient to the DESTINATION clinic; the ACCEPT phase. NOTE: completion is a side-effect of re-sending the patient, NOT a dedicated 'accept transfer' endpoint",
      "guard": "a PENDING ConsultationTransfer exists for the patient AND the clinic the patient is being sent to == transfer.getClinic() (PatientServiceImpl.java:431-435). If clinic mismatches -> InvalidOperationException, transfer stays PENDING",
      "citation": "PatientServiceImpl.java:436-437 (conTrans.setStatus(\"COMPLETED\"); save)"
    },
    {
      "from": "(after transfer COMPLETED a fresh Consultation row is created)",
      "to": "new Consultation = PENDING",
      "trigger": "doConsultation() continues after completing the transfer and creates a NEW Consultation for the destination clinic+clinician",
      "guard": "no patient consultation in {PENDING,TRANSFERED} (PatientServiceImpl.java:444-450) and none in {PENDING,TRANSFERED,IN-PROCESS} (451-455). Because the old consultation is TRANSFERED, doConsultation would FAIL these guards UNLESS the transfer-complete step left it... see openQuestions -- the old consultation remains TRANSFERED so this guard appears to BLOCK re-sending; CONFLICT flagged",
      "citation": "PatientServiceImpl.java:444-455 (status-in guards); 494 (new consultation setStatus(\"PENDING\"))"
    },
    {
      "from": "Consultation = TRANSFERED",
      "to": "Consultation = IN-PROCESS (revert) + ConsultationTransfer = CANCELED",
      "trigger": "cancelConsultationTransfer() / GET /patients/cancel_consultation_transfer?id={consultationId}",
      "guard": "consultation status == 'TRANSFERED' (PatientResource.java:989) AND a PENDING ConsultationTransfer exists for that consultation (PatientResource.java:990-992)",
      "citation": "PatientResource.java:993 (conTra.setStatus(\"CANCELED\")); 995 (c.setStatus(\"IN-PROCESS\")) -- REVERTS source consultation to IN-PROCESS, NOT to PENDING"
    },
    {
      "from": "Consultation = PENDING",
      "to": "Consultation = IN-PROCESS",
      "trigger": "openConsultation() / GET /patients/open_consultation (the doctor 'opens' the consultation). This is the precondition that later enables a transfer",
      "guard": "consultation status == 'PENDING' AND its PatientBill status in {PAID, COVERED} (PatientResource.java:884-885); follow-up variant also allows bill status NONE (PatientResource.java:914)",
      "citation": "PatientResource.java:886 (setStatus(\"IN-PROCESS\"))"
    }
  ],
  "rules": [
    {
      "rule": "RAISE precondition: a consultation can be transferred ONLY when its status is exactly 'IN-PROCESS'. A PENDING (not-yet-opened) or HELD/SIGNED-OUT consultation cannot be transferred.",
      "citation": "PatientServiceImpl.java:2756-2757"
    },
    {
      "rule": "One pending transfer per patient: raise is rejected if the patient already has ANY ConsultationTransfer in status PENDING (findAllByPatientAndStatus(patient,'PENDING') non-empty).",
      "citation": "PatientServiceImpl.java:2764-2767"
    },
    {
      "rule": "'No open work' guard at RAISE is composed of FOUR independent checks, each over rows linked to THIS consultation, each tripping only on the literal status 'PENDING' (null status is skipped): (a) no LabTest with status=='PENDING'; (b) no Radiology with status=='PENDING'; (c) no Procedure with status=='PENDING'; (d) no Prescription with status=='PENDING'. 'Open work' therefore == an ordered-but-not-yet-actioned (PENDING) order/prescription; orders already past PENDING (e.g. COLLECTED/CANCELED/DISPENSED) do NOT block. The remedy message tells the user to CANCEL the pending item.",
      "citation": "PatientServiceImpl.java:2759-2762 (fetch); 2769-2776 (LabTest); 2777-2784 (Radiology); 2785-2792 (Procedure); 2793-2800 (Prescription)"
    },
    {
      "rule": "Destination must differ from source: transfer rejected if transfer.clinic.id == consultation.clinic.id (same-clinic transfer forbidden). NOTE: uses '==' on Long object ids (PatientServiceImpl.java:2804); for autoboxed Long values outside the Integer cache (-128..127) this is reference equality and may behave incorrectly for clinic ids >127 -- flagged as latent bug.",
      "citation": "PatientServiceImpl.java:2804-2806"
    },
    {
      "rule": "On successful RAISE the SOURCE consultation is moved to status 'TRANSFERED' and saved BEFORE the transfer row is saved; both happen in the single createConsultationTransfer service method (one @Transactional unit).",
      "citation": "PatientServiceImpl.java:2808-2809 (consultation); 2811-2819 (transfer)"
    },
    {
      "rule": "ACCEPT / completion is implicit, not an endpoint: when registration/triage re-sends the patient via doConsultation, if a PENDING transfer exists it must point at the SAME clinic the patient is being sent to; if it does, the transfer is marked COMPLETED. If the destination clinic differs, the whole send is rejected with a message naming the required clinic.",
      "citation": "PatientServiceImpl.java:431-438"
    },
    {
      "rule": "The destination CLINICIAN is chosen at ACCEPT time (passed into doConsultation as parameter cn and stored on the NEW consultation), NOT at raise time. The only clinician validation at accept is cn.isActive()==true.",
      "citation": "PatientServiceImpl.java:425 (signature), 427-429 (active check), 490 (consultation.setClinician(cn))"
    },
    {
      "rule": "CANCEL reverts the SOURCE consultation to 'IN-PROCESS' (restoring the pre-transfer active state) and sets the transfer to 'CANCELED'. It does NOT revert to PENDING and does NOT delete the transfer row.",
      "citation": "PatientResource.java:993-996"
    },
    {
      "rule": "CANCEL is guarded: only fires when consultation.status=='TRANSFERED' AND a transfer for (consultation,'PENDING') exists; if the found transfer is not PENDING it throws 'Could not cancel transfer'. If consultation is not TRANSFERED, the endpoint silently no-ops and returns the consultation unchanged.",
      "citation": "PatientResource.java:989-1001"
    },
    {
      "rule": "A TRANSFERED consultation CANNOT be freed/signed-out via free_consultation: free requires status IN-PROCESS; the method explicitly skips when status=='TRANSFERED' (the outer if at 687 is true so the IN-PROCESS branch is not entered and nothing happens). The error text claims TRANSFERED is freeable but the code does not free it.",
      "citation": "PatientResource.java:687-757 (esp. 687, 757 message vs behaviour)"
    },
    {
      "rule": "A patient already TRANSFERED to a clinic cannot be sent to a DIFFERENT clinic: doConsultation rejects with a message naming the clinic the patient was transferred to.",
      "citation": "PatientServiceImpl.java:432-435"
    }
  ],
  "numbering": "NONE. ConsultationTransfer has no human-facing number/sequence: only a DB IDENTITY id (domain/ConsultationTransfer.java:37-39). There is no prefix, zero-padding, fiscal-year reset, or app-level number generator for transfers (contrast PatientCreditNote which uses patientCreditNoteService.requestPatientCreditNoteNo(), PatientResource.java:648).",
  "rbac": [
    "create_consultation_transfer: @PreAuthorize is COMMENTED OUT -> endpoint is UNGUARDED at method level. The commented annotation (not active) reads hasAnyAuthority('PATIENT-A','PATIENT-C','PATIENT-U') (PatientResource.java:572)",
    "get_consultation_transfers: @PreAuthorize COMMENTED OUT -> UNGUARDED; commented annotation hasAnyAuthority('PATIENT-A','PATIENT-C','PATIENT-U') (PatientResource.java:595)",
    "cancel_consultation_transfer: NO @PreAuthorize annotation at all -> UNGUARDED (PatientResource.java:983-986)",
    "NOTE on the implicit ACCEPT path: completion happens inside doConsultation, reached via the send/do-consultation endpoint, whose ACTIVE @PreAuthorize is hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE') -- these are the real active privilege codes in this resource (cf. free_consultation PatientResource.java:681, cancel_consultation PatientResource.java:606). The planning-doc codes CONSULTATION_START / PROCEDURE_ORDER_APPROVE / PRESCRIPTION_CREATE DO NOT EXIST anywhere in this resource."
  ],
  "driftVsPlanningDoc": [
    "Planning doc claims transfer status set is PENDING -> COMPLETED|CANCELLED. Legacy reality: statuses are PENDING, COMPLETED, CANCELED (American single-L spelling, NOT 'CANCELLED'). Citations: PENDING PatientServiceImpl.java:2811; COMPLETED PatientServiceImpl.java:436; CANCELED PatientResource.java:993.",
    "Planning doc's clean 'two-phase hand-off' overstates the design. Legacy is SIMPLER and asymmetric: phase 1 (raise) = createConsultationTransfer sets source consultation TRANSFERED + transfer PENDING (PatientServiceImpl.java:2808-2819). Phase 2 is NOT a dedicated 'accept' endpoint -- completion is a SIDE-EFFECT of re-sending the patient through doConsultation when the destination clinic matches (PatientServiceImpl.java:431-438). There is no acceptedBy/acceptedOn capture.",
    "Drift: does the source consultation move to a TRANSFERED status on raise? YES -- confirmed con.setStatus('TRANSFERED') (PatientServiceImpl.java:2808). (Note spelling TRANSFERED, one R.)",
    "Drift: does cancel revert the source consultation? YES, but to IN-PROCESS, not PENDING (PatientResource.java:995). The planning doc's PENDING->CANCELLED transition does not describe the source-consultation revert at all.",
    "Drift: when is the destination clinician chosen? At ACCEPT (doConsultation cn parameter / PatientServiceImpl.java:490), NOT at raise. ConsultationTransfer entity has NO clinician field (domain/ConsultationTransfer.java:35-65). The planning doc implies clinician routing at transfer time -- legacy routes only to a CLINIC.",
    "Drift: the planning doc's 'clinic-clinician affiliation check at accept' DOES NOT EXIST. doConsultation validates only cn.isActive() (PatientServiceImpl.java:427-429). There is NO check that the chosen clinician is affiliated with / belongs to the destination clinic. NOT FOUND.",
    "Drift: NO optimistic-lock / @Version handling exists on ConsultationTransfer or Consultation (full entity reads: domain/ConsultationTransfer.java:35-65; domain/Consultation.java:47-110). Any planning-doc assumption of optimistic locking on transfer is invented.",
    "Drift: 'no open work' = exactly the four PENDING-status checks on LabTest/Radiology/Procedure/Prescription (PatientServiceImpl.java:2769-2800). It is NOT a generic 'all orders closed' check; orders in any non-PENDING status (e.g. COLLECTED, CANCELED) do not block.",
    "Drift: the legacy uses THREE separate order entities (LabTest, Radiology, Procedure) plus Prescription -- consistent with CLAUDE.md note and contradicting the planning doc's polymorphic ClinicalOrder (PatientServiceImpl.java:2759-2762).",
    "Drift: create/get/cancel transfer endpoints are effectively UNAUTHENTICATED at method level (annotations commented out or absent) -- the planning doc's named privilege codes for transfer do not gate these endpoints (PatientResource.java:572,595,983)."
  ],
  "openQuestions": [
    "CONFLICT: After a transfer is marked COMPLETED in doConsultation (PatientServiceImpl.java:436-437), the SAME method immediately checks (444-455) that the patient has NO consultation in {PENDING,TRANSFERED} or {PENDING,TRANSFERED,IN-PROCESS}. The old source consultation is still in status TRANSFERED (it is never moved out of TRANSFERED on completion), so this guard appears to THROW 'Patient has pending or held consultation, please consider freeing the patient', blocking the very re-send that just completed the transfer. Either (a) there is an out-of-band step that signs-out/frees the TRANSFERED consultation first, or (b) this is a latent bug making the happy-path transfer-accept unreachable. Needs runtime confirmation / domain-expert input. Citations: 436-437 vs 444-455.",
    "The TRANSFERED source consultation is never explicitly terminated on successful completion (no setStatus to SIGNED-OUT/COMPLETED in the completion branch). What is the intended terminal status of a source consultation after a transfer completes? It remains TRANSFERED indefinitely unless cancel reverts it. Confirm intended lifecycle end-state.",
    "free_consultation error text says 'only a TRANSFERED or IN-PROCESS consultation can be freed' but the code only frees IN-PROCESS and no-ops on TRANSFERED (PatientResource.java:687,757). Is freeing a TRANSFERED consultation supposed to be supported? Behaviour and message disagree.",
    "Same-clinic guard uses '==' on Long ids (PatientServiceImpl.java:2804) and same in doConsultation (431-435: c.getId() != conTrans...getClinic().getId()). For ids >127 this is reference comparison, not value comparison. Confirm whether this is intended or a latent defect to preserve-vs-fix (requires engagement-lead CR if fixed).",
    "reason field is captured on the transfer but never read/validated by any service path -- confirm whether destination clinic should see/act on the transfer reason (UI-only concern).",
    "No 'accept transfer' UI/endpoint distinct from normal patient send was found; confirm the operational workflow: who physically re-sends the transferred patient and via which screen (registration vs triage)."
  ]
}
```

## Area: outpatient-closure-deceased

```json
{
  "area": "outpatient-closure-deceased",
  "entities": [
    {
      "name": "DeceasedNote",
      "legacyFile": "domain/DeceasedNote.java:36-76",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (DeceasedNote.java:37-39)",
        "patientSummary : String : no JPA constraint on entity; REST enforces non-empty (DeceasedNote.java:40; PatientResource.java:5699-5701)",
        "causeOfDeath : String : no JPA constraint on entity; REST enforces non-empty (DeceasedNote.java:41; PatientResource.java:5699-5701)",
        "date : LocalDate : @Column(name=\"date_\"); supplied by client request body, copied verbatim (DeceasedNote.java:43-44; PatientResource.java:5768)",
        "time : LocalTime : @Column(name=\"time_\"); supplied by client request body, copied verbatim (DeceasedNote.java:45-46; PatientResource.java:5769)",
        "status : String : default \"PENDING\"; value set is PENDING -> APPROVED -> ARCHIVED (DeceasedNote.java:48; PatientResource.java:5766,5904,5923; UpdatePatient.java:577,581)",
        "admission : Admission : @OneToOne optional=true, nullable=true, updatable=false, OnDelete NO_ACTION; mutually exclusive with consultation (DeceasedNote.java:50-53)",
        "consultation : Consultation : @OneToOne optional=true, nullable=true, updatable=false, OnDelete NO_ACTION; mutually exclusive with admission (DeceasedNote.java:55-58)",
        "patient : Patient : @OneToOne optional=false, nullable=false, updatable=false, OnDelete NO_ACTION (DeceasedNote.java:60-63)",
        "createdBy : Long : @Column(name=\"created_by_user_id\") nullable=false updatable=false (DeceasedNote.java:65-66)",
        "createdOn : Long : @Column(name=\"created_on_day_id\") nullable=false updatable=false; this is the business-day id, not a date (DeceasedNote.java:67-68)",
        "createdAt : LocalDateTime : default now() (DeceasedNote.java:69)",
        "approvedBy : Long : @Column(name=\"approved_by_user_id\") nullable=true; on approval set to the createdBy value, NOT the approving user (DeceasedNote.java:71-72; PatientResource.java:5905,5924)",
        "approvedOn : Long : @Column(name=\"approved_on_day_id\") nullable=true; on approval set to the createdOn value, NOT the approving day (DeceasedNote.java:73-74; PatientResource.java:5906,5925)",
        "approvedAt : LocalDateTime : nullable; set to dayService.getTimeStamp() on approval (DeceasedNote.java:75; PatientResource.java:5907,5926)"
      ],
      "notes": "DeceasedNote is the ONLY entity that records a death in the legacy system. There is NO consultation closure() method and NO kind=DECEASED|REFERRAL discriminator (contradicts planning doc). One DeceasedNote is keyed to exactly ONE of admission OR consultation (enforced at PatientResource.java:5702-5707). Persistence happens in PatientResource (no service-layer involvement). Note: approvedBy/approvedOn are copied from createdBy/createdOn on approval (PatientResource.java:5905-5906,5924-5925), so they do NOT capture the approver's identity."
    },
    {
      "name": "ReferralPlan (clinical OPD/inpatient referral to external provider)",
      "legacyFile": "domain/ReferralPlan.java:35-80",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (ReferralPlan.java:36-38)",
        "referringDiagnosis : String : copied from request body (ReferralPlan.java:39; PatientResource.java:5520)",
        "history : String : copied from request body (ReferralPlan.java:40; PatientResource.java:5522)",
        "investigation : String : copied from request body (ReferralPlan.java:41; PatientResource.java:5523)",
        "management : String : copied from request body (ReferralPlan.java:42; PatientResource.java:5524)",
        "operationNote : String : copied from request body (ReferralPlan.java:43; PatientResource.java:5525)",
        "icuAdmissionNote : String : copied from request body (ReferralPlan.java:44; PatientResource.java:5526)",
        "generalRecommendation : String : copied from request body (ReferralPlan.java:45; PatientResource.java:5527)",
        "status : String : default \"PENDING\"; value set PENDING -> APPROVED -> ARCHIVED (ReferralPlan.java:47; PatientResource.java:5528,5627/5677; UpdatePatient.java:591)",
        "externalMedicalProvider : ExternalMedicalProvider : @ManyToOne optional=false nullable=false; the referral target (ReferralPlan.java:49-52)",
        "admission : Admission : @ManyToOne optional=true nullable=true updatable=false; mutually exclusive with consultation (ReferralPlan.java:54-57)",
        "consultation : Consultation : @ManyToOne optional=true nullable=true updatable=false; mutually exclusive with admission (ReferralPlan.java:59-62)",
        "patient : Patient : @ManyToOne optional=false nullable=false updatable=false (ReferralPlan.java:64-67)",
        "createdBy : Long : created_by_user_id nullable=false updatable=false (ReferralPlan.java:69-70)",
        "createdOn : Long : created_on_day_id nullable=false updatable=false (ReferralPlan.java:71-72)",
        "createdAt : LocalDateTime : default now() (ReferralPlan.java:73)",
        "approvedBy : Long : nullable; set to createdBy on approval (ReferralPlan.java:75-76; PatientResource.java:5628,5679)",
        "approvedOn : Long : nullable; set to createdOn on approval (ReferralPlan.java:77-78; PatientResource.java:5629,5680)",
        "approvedAt : LocalDateTime : nullable; set to dayService.getTimeStamp() on approval (ReferralPlan.java:79; PatientResource.java:5630,5681)"
      ],
      "notes": "Referral is a SEPARATE entity and SEPARATE workflow from death (contradicts planning doc which folds REFERRAL into a single closure() with kind). There is exactly ONE ReferralPlan.java (domain) and it is a CLINICAL referral to an ExternalMedicalProvider, NOT the insurance ReferralPlan the engagement context mentions. CRITICAL: referral does NOT set Patient.type=DECEASED; the OPD referral path sets Patient.type=\"OUTPATIENT\" (PatientResource.java:5674) and, at save time, also wipes insurance: patient.setPaymentType(\"CASH\") and patient.setInsurancePlan(null) (PatientResource.java:5505-5507)."
    },
    {
      "name": "ExternalMedicalProvider (referral destination)",
      "legacyFile": "domain/ExternalMedicalProvider.java:29-51",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) (ExternalMedicalProvider.java:30-32)",
        "code : String : @NotBlank @Column(unique=true) (ExternalMedicalProvider.java:33-35)",
        "name : String : @NotBlank @Column(unique=true) (ExternalMedicalProvider.java:36-38)",
        "address : String : (ExternalMedicalProvider.java:39)",
        "telephone : String : (ExternalMedicalProvider.java:40)",
        "email : String : (ExternalMedicalProvider.java:41)",
        "fax : String : (ExternalMedicalProvider.java:42)",
        "website : String : (ExternalMedicalProvider.java:43)",
        "active : boolean : default false (ExternalMedicalProvider.java:44)",
        "createdBy : Long : created_by_user_id nullable=false updatable=false (ExternalMedicalProvider.java:46-47)",
        "createdOn : Long : created_on_day_id nullable=false updatable=false (ExternalMedicalProvider.java:48-49)",
        "createdAt : LocalDateTime : default now() (ExternalMedicalProvider.java:50)"
      ],
      "notes": "The save_referral_plan endpoint requires an existing ExternalMedicalProvider by id (PatientResource.java:5398-5401) but does NOT check its active flag."
    },
    {
      "name": "Patient.type (death/closure flag carrier)",
      "legacyFile": "domain/Patient.java:63-64",
      "fields": [
        "type : String : @NotBlank (Patient.java:63-64). NOT a boolean. Observed value set across the codebase: OUTPATIENT, INPATIENT, OUTSIDER, DECEASED (PatientResource.java:411,426,496,1785-via-service,5901,5920,5624,5674; PatientServiceImpl.java:1785)"
      ],
      "notes": "CONFIRMS inc-03 finding and CONTRADICTS planning doc: death is recorded by setting the String field Patient.type=\"DECEASED\" (PatientResource.java:5901,5920), NOT by a boolean Patient.deceased=true. There is no Patient.deceased boolean field in the entity (Patient.java:55-74 region scanned; no such field)."
    }
  ],
  "stateMachine": [
    {
      "from": "(consultation status, e.g. PENDING/IN-PROCESS/TRANSFERED)",
      "to": "HELD",
      "trigger": "POST /patients/save_deceased_note for an OUTPATIENT (consultation branch) where no DeceasedNote yet exists for that consultation",
      "guard": "note.consultation.id != null AND note.admission.id == null AND patientSummary & causeOfDeath non-empty AND no existing DeceasedNote for this consultation; the consultation status is overwritten to HELD unconditionally (no status guard checked)",
      "citation": "PatientResource.java:5743-5754 (set HELD at 5753)"
    },
    {
      "from": "(admission status null or IN-PROCESS)",
      "to": "HELD (admission), and WardBed -> EMPTY",
      "trigger": "POST /patients/save_deceased_note for an INPATIENT (admission branch) where no DeceasedNote yet exists",
      "guard": "note.admission.id != null AND note.consultation.id == null; if admission.status==null it is first set IN-PROCESS then HELD; if IN-PROCESS it is set HELD and the ward bed freed",
      "citation": "PatientResource.java:5710-5732 (admission HELD at 5725, wardBed EMPTY at 5729)"
    },
    {
      "from": "none",
      "to": "DeceasedNote = PENDING",
      "trigger": "POST /patients/save_deceased_note (create branch)",
      "guard": "new note always saved with status PENDING regardless of inpatient/outpatient; if a DeceasedNote already exists for that admission/consultation it is reused (no new HELD transition re-applied)",
      "citation": "PatientResource.java:5766,5771; reuse at 5715-5717 / 5748-5750"
    },
    {
      "from": "consultation HELD",
      "to": "consultation SIGNED-OUT; Patient.type -> DECEASED; DeceasedNote -> APPROVED",
      "trigger": "GET /patients/get_deceased_summary (OUTPATIENT/consultation branch)",
      "guard": "DeceasedNote found by id AND its consultation.status == \"HELD\" AND all consultation PatientInvoice detail bills NOT in {UNPAID, VERIFIED} (else InvalidOperationException 'uncleared bills'); on success all related PatientInvoice set APPROVED",
      "citation": "PatientResource.java:5870-5887 (bill gate), 5912-5928 (con SIGNED-OUT 5915, type DECEASED 5920, note APPROVED 5923)"
    },
    {
      "from": "admission HELD",
      "to": "admission SIGNED-OUT; WardBed -> EMPTY; Patient.type -> DECEASED; DeceasedNote -> APPROVED",
      "trigger": "GET /patients/get_deceased_summary (INPATIENT/admission branch)",
      "guard": "DeceasedNote found by id AND its admission.status == \"HELD\" AND all admission PatientInvoice detail bills NOT in {UNPAID, VERIFIED}; on success admission invoices set APPROVED",
      "citation": "PatientResource.java:5851-5868 (bill gate), 5889-5908 (adm SIGNED-OUT 5892, wardBed EMPTY 5897, type DECEASED 5901, note APPROVED 5904)"
    },
    {
      "from": "DeceasedNote APPROVED",
      "to": "DeceasedNote ARCHIVED",
      "trigger": "Background sweep on day-update (UpdatePatient.update / day rollover)",
      "guard": "For every APPROVED DeceasedNote: if approvedAt != null and (now - approvedAt) >= 48 hours -> ARCHIVED; if approvedAt == null -> ARCHIVED immediately",
      "citation": "UpdatePatient.java:572-584"
    },
    {
      "from": "(consultation status, e.g. PENDING/IN-PROCESS)",
      "to": "consultation SIGNED-OUT; Patient.type stays/becomes OUTPATIENT; insurance cleared; ReferralPlan PENDING",
      "trigger": "POST /patients/save_referral_plan (OUTPATIENT/consultation branch), create path",
      "guard": "plan.consultation.id != null AND plan.admission.id == null AND ExternalMedicalProvider exists AND no existing PENDING ReferralPlan for this consultation AND no UNPAID LabTest/Radiology/Procedure/Prescription bills for this consultation (else InvalidOperationException 'uncleared ... bill(s)')",
      "citation": "PatientResource.java:5460-5516 (bill gates 5465-5499, con SIGNED-OUT 5501, patient CASH/insurance null 5505-5507, plan PENDING 5528)"
    },
    {
      "from": "(admission status)",
      "to": "admission STOPPED; ReferralPlan PENDING",
      "trigger": "POST /patients/save_referral_plan (INPATIENT/admission branch), create path",
      "guard": "plan.admission.id != null AND plan.consultation.id == null AND no existing PENDING ReferralPlan for this admission",
      "citation": "PatientResource.java:5446-5459 (admission STOPPED at 5451, plan PENDING 5528)"
    },
    {
      "from": "consultation SIGNED-OUT (referral)",
      "to": "consultation SIGNED-OUT (re-set); Patient.type -> OUTPATIENT; ReferralPlan -> APPROVED",
      "trigger": "GET /patients/get_referral_summary (consultation branch)",
      "guard": "ReferralPlan found by id; no UNPAID LabTest/Radiology/Procedure/Prescription bills for the consultation; con.status set SIGNED-OUT again unconditionally",
      "citation": "PatientResource.java:5633-5681 (bill gates 5634-5668, con SIGNED-OUT 5670, type OUTPATIENT 5674, plan APPROVED 5677)"
    },
    {
      "from": "admission STOPPED",
      "to": "admission SIGNED-OUT; WardBed -> EMPTY; Patient.type -> OUTPATIENT; ReferralPlan -> APPROVED",
      "trigger": "GET /patients/get_referral_summary (admission branch)",
      "guard": "ReferralPlan found by id AND admission.status == \"STOPPED\" AND no admission PatientInvoice detail bills in {UNPAID, VERIFIED}; admission invoices set APPROVED; admission discharge fields stamped from plan.createdBy/createdOn/createdAt",
      "citation": "PatientResource.java:5592-5632 (bill gate 5593-5608, adm SIGNED-OUT 5612, wardBed EMPTY 5620, type OUTPATIENT 5624, plan APPROVED 5627)"
    },
    {
      "from": "ReferralPlan APPROVED",
      "to": "ReferralPlan ARCHIVED",
      "trigger": "Background sweep on day-update (UpdatePatient.update / day rollover)",
      "guard": "For every APPROVED ReferralPlan: if approvedAt != null and (now - approvedAt) >= 48 hours -> ARCHIVED; if approvedAt == null -> ARCHIVED immediately",
      "citation": "UpdatePatient.java:586-594"
    }
  ],
  "rules": [
    {
      "rule": "Death recording requires BOTH a non-empty patientSummary AND a non-empty causeOfDeath; otherwise InvalidOperationException('Summary and cause of death are missing'). Note: code calls .isEmpty() on these, so a null body field would NPE rather than give the friendly message.",
      "citation": "PatientResource.java:5699-5701"
    },
    {
      "rule": "A DeceasedNote (and a ReferralPlan) must reference EXACTLY ONE of admission OR consultation. If both ids are null, or both are non-null, InvalidOperationException is thrown.",
      "citation": "PatientResource.java:5702-5707 (deceased); 5402-5407 (referral)"
    },
    {
      "rule": "Death approval (get_deceased_summary) is blocked if ANY related PatientInvoiceDetail's PatientBill status is UNPAID or VERIFIED -> InvalidOperationException('Could not get deceased summary. Patient have uncleared bills.'). On success, all related PatientInvoices are set to status APPROVED.",
      "citation": "PatientResource.java:5856-5868 (admission), 5875-5887 (consultation)"
    },
    {
      "rule": "Death approval only advances state when the linked admission/consultation is currently status HELD. If status is anything else, get_deceased_summary silently does nothing to type/status but still saves the (unchanged) note (the outer if-guards at 5891 and 5914 are not satisfied).",
      "citation": "PatientResource.java:5890-5891,5913-5914,5931"
    },
    {
      "rule": "On death approval the Patient is permanently marked by setting the String Patient.type = \"DECEASED\" (consultation branch and admission branch). No code path ever reverts type away from DECEASED.",
      "citation": "PatientResource.java:5901 (admission), 5920 (consultation)"
    },
    {
      "rule": "OPD referral SAVE is blocked if the consultation has ANY UNPAID LabTest, Radiology, Procedure, or Prescription bill -> InvalidOperationException('Could not save. Patient have uncleared ... bill(s)'). Note: this gate checks only UNPAID (not VERIFIED), unlike the death/inpatient gate which checks UNPAID or VERIFIED.",
      "citation": "PatientResource.java:5465-5499 (save), repeated at 5634-5668 (summary)"
    },
    {
      "rule": "OPD referral SAVE clears the patient's insurance/payment: patient.setPaymentType(\"CASH\") and patient.setInsurancePlan(null), and sets consultation status to SIGNED-OUT immediately at save time (before approval).",
      "citation": "PatientResource.java:5501-5507"
    },
    {
      "rule": "Inpatient referral SAVE sets admission status to STOPPED at save time; referral approval (get_referral_summary) then advances STOPPED -> SIGNED-OUT, frees the ward bed, sets Patient.type=OUTPATIENT, and stamps admission discharge fields from the plan's createdBy/createdOn/createdAt.",
      "citation": "PatientResource.java:5451-5452 (STOPPED), 5610-5631 (SIGNED-OUT path)"
    },
    {
      "rule": "Referral approval (admission branch) is blocked if any admission PatientInvoiceDetail bill is UNPAID or VERIFIED -> InvalidOperationException with the message text 'Could not get discharge summary. Patient have uncleared bills.' (note: message says 'discharge', a copy-paste artifact, in the referral endpoint).",
      "citation": "PatientResource.java:5597-5603"
    },
    {
      "rule": "approvedBy and approvedOn on both DeceasedNote and ReferralPlan are populated from createdBy/createdOn (the creator), NOT from the user/day performing the approval. Only approvedAt reflects the actual approval timestamp via dayService.getTimeStamp().",
      "citation": "PatientResource.java:5905-5907,5924-5926 (deceased); 5628-5630,5679-5681 (referral)"
    },
    {
      "rule": "NO booking guard checks Patient.type==\"DECEASED\". The send-to-doctor entry (do_consultation) only requires Patient.type==\"OUTPATIENT\"; a DECEASED patient fails this with the generic message 'Please change patient type to OUTPATIENT to continue with operation' (NOT a 'PATIENT_IS_DECEASED' error). doConsultation itself never inspects patient.type.",
      "citation": "PatientResource.java:535-536; PatientServiceImpl.java:425-455 (no DECEASED check)"
    },
    {
      "rule": "load_deceased_list and load_referral_list both return only notes/plans whose status is in {PENDING, APPROVED} (ARCHIVED ones are hidden).",
      "citation": "PatientResource.java:5826-5830 (deceased), 5567-5571 (referral)"
    }
  ],
  "numbering": "NONE. Neither DeceasedNote nor ReferralPlan has any human-facing document number, code, or sequence. Both use only the database IDENTITY primary key (DeceasedNote.java:37-39; ReferralPlan.java:36-38). No prefix, zero-padding, or fiscal-year reset exists for these entities. (ExternalMedicalProvider has a unique 'code' but it is operator-entered, not generated — ExternalMedicalProvider.java:33-35.)",
  "rbac": [
    "do_consultation (booking entry that implicitly excludes DECEASED via OUTPATIENT requirement): @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:509)",
    "switch_to_normal_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") (PatientResource.java:548)",
    "save_deceased_note: NO ACTIVE @PreAuthorize — the annotation is commented out: //@PreAuthorize(\"hasAnyAuthority('PATIENT-A','PATIENT-C','PATIENT-U')\") (PatientResource.java:5694)",
    "load_deceased_note: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5778)",
    "load_deceased_list: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5822)",
    "get_deceased_summary (the death-approval / type=DECEASED action): NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5838)",
    "save_referral_plan: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5393)",
    "load_referral_plan: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5537)",
    "load_referral_list: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5563)",
    "get_referral_summary: NO ACTIVE @PreAuthorize — commented out (PatientResource.java:5579, method getRefarralSummary at 5580)"
  ],
  "driftVsPlanningDoc": [
    "Planning doc invents a consultation closure() with kind=DECEASED|REFERRAL. LEGACY REALITY: there is no closure() method and no kind discriminator. Death is recorded by a standalone DeceasedNote entity (domain/DeceasedNote.java:36) via two endpoints (save_deceased_note PatientResource.java:5693, get_deceased_summary :5837); referral is an entirely SEPARATE ReferralPlan entity (domain/ReferralPlan.java:35) via save_referral_plan (:5392) and get_referral_summary (:5578).",
    "Planning doc claims closure sets Patient.deceased=true (boolean). LEGACY REALITY: there is no boolean; death sets the String field Patient.type=\"DECEASED\" (Patient.java:63-64; PatientResource.java:5901,5920). Confirms inc-03.",
    "Planning doc claims closure blocks future booking with a PATIENT_IS_DECEASED error. LEGACY REALITY: NOT FOUND. No code anywhere reads Patient.type==\"DECEASED\" to block booking (the only two DECEASED references in the whole tree are the writes at PatientResource.java:5901,5920). The only relevant gate is do_consultation requiring type==\"OUTPATIENT\" (PatientResource.java:535-536), which yields the generic message 'Please change patient type to OUTPATIENT to continue with operation' — there is no 'PATIENT_IS_DECEASED' constant or message.",
    "Planning doc treats REFERRAL as a closure that marks the patient deceased/closed. LEGACY REALITY: OPD referral does NOT mark deceased; it sets Patient.type=\"OUTPATIENT\" (PatientResource.java:5674), wipes insurance to CASH/null (:5505-5507), and the patient remains fully bookable afterward.",
    "Planning doc (per inc-05 brief) implies clean access control via privileges like CONSULTATION_START / PRESCRIPTION_CREATE. LEGACY REALITY for THIS area: the death and referral endpoints have NO active method-level security at all (all @PreAuthorize commented out, PatientResource.java:5393,5537,5563,5579,5694,5778,5822,5838). The booking endpoint that gates them uses the real codes PATIENT-ALL / PATIENT-CREATE / PATIENT-UPDATE (:509). The invented privilege names do not exist.",
    "Death-from-OPD and death-from-inpatient share one DeceasedNote entity but diverge: OPD uses consultation->HELD->SIGNED-OUT; inpatient uses admission->HELD->SIGNED-OUT plus ward-bed freeing. The planning doc's single uniform closure() does not capture this admission/consultation duality (PatientResource.java:5710-5763, 5848-5929)."
  ],
  "openQuestions": [
    "No booking guard rejects a DECEASED patient. Should the modernized OPD reproduce the exact legacy behaviour (a deceased patient is blocked only incidentally by the OUTPATIENT requirement, with a misleading message), or is a true PATIENT_IS_DECEASED guard an approved improvement? This requires an explicit change request from engagement-lead, not silent introduction.",
    "approvedBy/approvedOn are copied from createdBy/createdOn rather than the approving user/day on both DeceasedNote and ReferralPlan (PatientResource.java:5905-5906,5924-5925,5628-5629,5679-5680). Is this an intentional 'note creator == approver' design, or a legacy bug? Confirm with business-analyst whether the rebuild should faithfully replicate or correct it.",
    "The UNPAID-bill gate is inconsistent: death/inpatient gates reject UNPAID OR VERIFIED bills (PatientResource.java:5857,5876,5598), while OPD-referral save/summary gates reject only UNPAID (:5468,5637). Confirm which is the intended rule for the rebuilt closure gates.",
    "get_deceased_summary only acts when the linked admission/consultation status is exactly HELD; for any other status it silently no-ops while returning 200 and re-saving the unchanged note (PatientResource.java:5890-5891,5913-5914). Confirm whether this silent no-op is required behaviour or should raise an error.",
    "get_referral_summary (consultation branch) re-sets con.status=SIGNED-OUT unconditionally with no status guard and no ward/no-bed handling, whereas the admission branch guards on status==STOPPED (PatientResource.java:5610-5611 vs 5670). Confirm the intended guard for the OPD referral-approval step.",
    "The referral-approval admission-branch error message reads 'Could not get discharge summary' inside a referral endpoint (PatientResource.java:5599) — a copy-paste artifact. Confirm whether message text is part of 'exact process' to preserve or may be corrected.",
    "DeceasedNote.date/time are accepted verbatim from the client with no validation against the business day or createdAt (PatientResource.java:5768-5769). Confirm whether any date-of-death validation is expected.",
    "ARCHIVED sweep for both DeceasedNote and ReferralPlan runs in UpdatePatient.java (a day-rollover batch) at >=48h after approval, or immediately if approvedAt is null (UpdatePatient.java:572-594). Confirm this 48-hour archival window is in scope for inc-05 (it touches the day-update batch, which may belong to a different bounded context)."
  ]
}
```

## Area: worklists-rbac-numbering

```json
{
  "area": "worklists-rbac-numbering",
  "entities": [
    {
      "name": "Consultation",
      "legacyFile": "domain/Consultation.java:47-110",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) — domain/Consultation.java:48-50; NO document-number/'no' column exists on this entity",
        "paymentType : String : @NotBlank; comment CASH,DEBIT CARD,CREDIT CARD,MOBILE,INSURANCE — domain/Consultation.java:52-53",
        "membershipNo : String : nullable — domain/Consultation.java:54",
        "status : String : @NotBlank — domain/Consultation.java:55-56",
        "followUp : boolean : default false — domain/Consultation.java:57",
        "patient : Patient : @ManyToOne not-null, not-updatable — domain/Consultation.java:62-65",
        "patientBill : PatientBill : @OneToOne not-null, not-updatable (one consultation billed once) — domain/Consultation.java:70-73",
        "clinic : Clinic : @ManyToOne not-null, not-updatable — domain/Consultation.java:77-80",
        "clinician : Clinician : @ManyToOne not-null, UPDATABLE (patient can be reassigned to another clinician) — domain/Consultation.java:85-88",
        "visit : Visit : @ManyToOne not-null, not-updatable — domain/Consultation.java:93-97",
        "insurancePlan : InsurancePlan : @ManyToOne nullable, updatable — domain/Consultation.java:99-102",
        "createdBy/createdOn/createdAt : Long/Long/LocalDateTime : forensic stamps — domain/Consultation.java:104-108"
      ],
      "notes": "The OPD consultation entity. Identified ONLY by the IDENTITY surrogate id — there is NO consultation document number, NO 'no' field, NO prefix scheme. Contradicts planning-doc CONS{yyyyMMdd}-{seq}. Status is a free-form String (not enum). Worklists key off the clinician FK (settable=reassign) and the status String."
    },
    {
      "name": "ConsultationTransfer",
      "legacyFile": "domain/ConsultationTransfer.java:35-65",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) — domain/ConsultationTransfer.java:37-39; NO document number",
        "status : String : @Column(nullable=false) — domain/ConsultationTransfer.java:40-41",
        "reason : String : nullable — domain/ConsultationTransfer.java:43",
        "patient : Patient : @ManyToOne not-null — domain/ConsultationTransfer.java:45-48",
        "consultation : Consultation : @ManyToOne not-null — domain/ConsultationTransfer.java:50-53",
        "clinic : Clinic : @ManyToOne not-null, updatable (the DESTINATION clinic) — domain/ConsultationTransfer.java:55-58",
        "createdBy/createdOn/createdAt : forensic — domain/ConsultationTransfer.java:60-64"
      ],
      "notes": "Backs the pending-transfer queue. No document number. Pending-transfer worklist endpoint filters ONLY by status='PENDING' (api/PatientResource.java:599)."
    },
    {
      "name": "LabTest",
      "legacyFile": "domain/LabTest.java:43-154",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) — domain/LabTest.java:44-46; NO document number / 'no' field",
        "status : String : free-form — domain/LabTest.java:55",
        "paymentType : String — domain/LabTest.java:57",
        "consultation/nonConsultation/admission : @ManyToOne nullable (exactly one context) — domain/LabTest.java:65-78",
        "labTestType : LabTestType : @ManyToOne not-null — domain/LabTest.java:80-83",
        "patientBill : PatientBill : @OneToOne not-null — domain/LabTest.java:85-88",
        "patient : Patient : @ManyToOne not-null — domain/LabTest.java:90-93",
        "clinician : Clinician : @ManyToOne nullable, updatable — domain/LabTest.java:95-98",
        "lifecycle stamps ordered/accepted/held/rejected/collected/verified (by/on/at) + rejectComment — domain/LabTest.java:111-146"
      ],
      "notes": "Lab order. Confirms the planning-doc invention of a single polymorphic ClinicalOrder is WRONG: LabTest is a standalone entity (parallel to Radiology + Procedure). No document number. Lifecycle is encoded via the status String + the timestamp triplets."
    },
    {
      "name": "Radiology",
      "legacyFile": "domain/Radiology.java:44-51",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) — domain/Radiology.java:44; NO document number",
        "status : String — domain/Radiology.java:51"
      ],
      "notes": "Separate radiology-order entity (NOT a polymorphic order). Accept/reject/hold/verify endpoints mirror LabTest but radiology goes ACCEPTED→VERIFIED directly (no COLLECTED step) — api/PatientResource.java:4272-4295."
    },
    {
      "name": "Procedure",
      "legacyFile": "domain/Procedure.java:45",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) : NO document number",
        "note : String — domain/Procedure.java:45",
        "status : String : (status field present; set PENDING on creation at service/PatientServiceImpl.java:1313)"
      ],
      "notes": "Separate procedure-order entity. PENDING→ACCEPTED (accept_procedure) → VERIFIED (procedures/add_note sets VERIFIED when bill PAID/COVERED/VERIFIED) — api/PatientResource.java:4026-4067,3397-3416."
    },
    {
      "name": "Prescription",
      "legacyFile": "domain/Prescription.java:50",
      "fields": [
        "id : Long : @Id @GeneratedValue(IDENTITY) : NO document number",
        "status : String — domain/Prescription.java:50"
      ],
      "notes": "Separate prescription/medicine-order entity. Created with status PENDING (service/PatientServiceImpl.java; save sets ordered* stamps at api/PatientResource.java:2126-2128). Duplicate-drug-per-consultation blocked at api/PatientResource.java:2109-2111."
    }
  ],
  "stateMachine": [
    {
      "from": "(none)",
      "to": "PENDING",
      "trigger": "do_consultation -> patientService.doConsultation creates Consultation",
      "guard": "patient has no PENDING/TRANSFERED/IN-PROCESS consultation; patient.type must be OUTPATIENT; no active admission; clinician.isActive()==true",
      "citation": "service/PatientServiceImpl.java:494 (setStatus PENDING); guards at api/PatientResource.java:531-537 + service/PatientServiceImpl.java:427-455"
    },
    {
      "from": "PENDING",
      "to": "IN-PROCESS",
      "trigger": "open_consultation (or open_follow_up_consultation)",
      "guard": "status==PENDING AND patientBill.status in {PAID,COVERED} (open_consultation); for follow-up also allows bill status NONE",
      "citation": "api/PatientResource.java:884-887 (open_consultation), api/PatientResource.java:913-916 (open_follow_up_consultation)"
    },
    {
      "from": "IN-PROCESS",
      "to": "TRANSFERED",
      "trigger": "create_consultation_transfer -> createConsultationTransfer",
      "guard": "consultation.status==IN-PROCESS; no other PENDING transfer for patient; no PENDING lab/radiology/procedure/prescription; destination clinic != current clinic",
      "citation": "service/PatientServiceImpl.java:2756-2809 (con.setStatus(TRANSFERED) at 2808)"
    },
    {
      "from": "TRANSFERED",
      "to": "IN-PROCESS",
      "trigger": "cancel_consultation_transfer",
      "guard": "consultation.status==TRANSFERED AND a PENDING ConsultationTransfer exists for it",
      "citation": "api/PatientResource.java:989-996"
    },
    {
      "from": "PENDING",
      "to": "CANCELED",
      "trigger": "cancel_consultation",
      "guard": "status==PENDING only; cascades patientBill->CANCELED, refunds RECEIVED payment as REFUNDED + PatientCreditNote, deletes PatientInvoiceDetail",
      "citation": "api/PatientResource.java:611-672 (status set CANCELED at 618)"
    },
    {
      "from": "IN-PROCESS",
      "to": "SIGNED-OUT",
      "trigger": "free_consultation",
      "guard": "status==IN-PROCESS (requires patient registration no match); also any non-TRANSFERED path falls through to SIGNED-OUT; cancels UNPAID/null child bills",
      "citation": "api/PatientResource.java:687-765 (SIGNED-OUT set at 699 and 764)"
    },
    {
      "from": "PENDING",
      "to": "IN-PROCESS",
      "trigger": "switch_to_consultation_by_consultation_id (follow-up -> normal)",
      "guard": "consultation exists; sets followUp=false; patientBill.status set COVERED if insurancePlan present else UNPAID",
      "citation": "api/PatientResource.java:937-948"
    },
    {
      "from": "ConsultationTransfer:(create)",
      "to": "PENDING",
      "trigger": "createConsultationTransfer",
      "guard": "see consultation IN-PROCESS->TRANSFERED guards",
      "citation": "service/PatientServiceImpl.java:2811"
    },
    {
      "from": "ConsultationTransfer:PENDING",
      "to": "COMPLETED",
      "trigger": "do_consultation when a PENDING transfer exists for patient",
      "guard": "destination clinic of new consultation must equal transfer.clinic else error",
      "citation": "service/PatientServiceImpl.java:431-438"
    },
    {
      "from": "ConsultationTransfer:PENDING",
      "to": "CANCELED",
      "trigger": "cancel_consultation_transfer",
      "guard": "transfer.status==PENDING",
      "citation": "api/PatientResource.java:992-994"
    },
    {
      "from": "LabTest:(create)",
      "to": "PENDING",
      "trigger": "save_lab_test -> saveLabTest",
      "guard": "order created",
      "citation": "service/PatientServiceImpl.java:819"
    },
    {
      "from": "LabTest:PENDING|REJECTED",
      "to": "ACCEPTED",
      "trigger": "accept_lab_test",
      "guard": "status in {PENDING,REJECTED}; clears reject fields",
      "citation": "api/PatientResource.java:3896-3910"
    },
    {
      "from": "LabTest:PENDING|ACCEPTED",
      "to": "REJECTED",
      "trigger": "reject_lab_test",
      "guard": "status in {PENDING,ACCEPTED}",
      "citation": "api/PatientResource.java:3922-3935"
    },
    {
      "from": "LabTest:ACCEPTED",
      "to": "COLLECTED",
      "trigger": "collect_lab_test",
      "guard": "status==ACCEPTED",
      "citation": "api/PatientResource.java:3947-3956"
    },
    {
      "from": "LabTest:COLLECTED",
      "to": "VERIFIED",
      "trigger": "verify_lab_test",
      "guard": "status==COLLECTED",
      "citation": "api/PatientResource.java:3968-3981"
    },
    {
      "from": "LabTest:ACCEPTED",
      "to": "PENDING",
      "trigger": "hold_lab_test",
      "guard": "status==ACCEPTED (sets status back to PENDING)",
      "citation": "api/PatientResource.java:4013-4022"
    },
    {
      "from": "Radiology:(create)",
      "to": "PENDING",
      "trigger": "save_radiology -> saveRadiology",
      "guard": "order created",
      "citation": "service/PatientServiceImpl.java:1062"
    },
    {
      "from": "Radiology:PENDING|REJECTED",
      "to": "ACCEPTED",
      "trigger": "accept_radiology",
      "guard": "status in {PENDING,REJECTED}",
      "citation": "api/PatientResource.java:4208-4222"
    },
    {
      "from": "Radiology:PENDING|ACCEPTED",
      "to": "REJECTED",
      "trigger": "reject_radiology",
      "guard": "status in {PENDING,ACCEPTED}",
      "citation": "api/PatientResource.java:4234-4247"
    },
    {
      "from": "Radiology:ACCEPTED",
      "to": "PENDING",
      "trigger": "hold_radiology",
      "guard": "status==ACCEPTED",
      "citation": "api/PatientResource.java:4259-4268"
    },
    {
      "from": "Radiology:ACCEPTED",
      "to": "VERIFIED",
      "trigger": "verify_radiology",
      "guard": "status==ACCEPTED (NOTE: radiology verifies straight from ACCEPTED, no COLLECTED step unlike lab)",
      "citation": "api/PatientResource.java:4280-4293"
    },
    {
      "from": "Procedure:(create)",
      "to": "PENDING",
      "trigger": "save_procedure -> saveProcedure",
      "guard": "order created",
      "citation": "service/PatientServiceImpl.java:1313"
    },
    {
      "from": "Procedure:PENDING|REJECTED",
      "to": "ACCEPTED",
      "trigger": "accept_procedure",
      "guard": "status in {PENDING,REJECTED}",
      "citation": "api/PatientResource.java:4034-4048"
    },
    {
      "from": "Procedure:ACCEPTED",
      "to": "VERIFIED",
      "trigger": "procedures/add_note",
      "guard": "non-empty note AND patientBill.status in {PAID,COVERED,VERIFIED}",
      "citation": "api/PatientResource.java:3408-3412"
    },
    {
      "from": "Prescription:(create)",
      "to": "PENDING",
      "trigger": "save_prescription -> savePrescription",
      "guard": "no duplicate medicine for same consultation; OUTPATIENT for consultation context",
      "citation": "service/PatientServiceImpl.java:1482 method; status setStatus PENDING in service; api/PatientResource.java:2109-2111 duplicate guard"
    }
  ],
  "rules": [
    {
      "rule": "DOCTOR OPD RECEPTION QUEUE (load_pending_consultations_by_clinician_id): filters by clinician (FK, via clinicianId param) AND followUp==false AND status IN {PENDING}; then post-filters in Java to keep only rows whose patientBill.status is PAID or COVERED. So a doctor sees a pending normal consultation only after its consultation bill is settled.",
      "citation": "api/PatientResource.java:807-827 (repo findAllByClinicianAndFollowUpAndStatusIn(c, false, [PENDING]) at 817; bill filter 823)"
    },
    {
      "rule": "DOCTOR FOLLOW-UP QUEUE (load_follow_up_list_by_clinician_id): filters by clinician AND followUp==true AND status IN {PENDING,IN-PROCESS}; NO patientBill settlement filter is applied (all rows shown).",
      "citation": "api/PatientResource.java:841-851 (statuses PENDING,IN-PROCESS at 842-843; findAllByClinicianAndFollowUpAndStatusIn(c,true,...) at 844)"
    },
    {
      "rule": "DOCTOR IN-PROCESS QUEUE (load_in_process_consultations_by_clinician_id): filters by clinician AND followUp==false AND status IN {IN-PROCESS,TRANSFERED}; no bill filter.",
      "citation": "api/PatientResource.java:871-874"
    },
    {
      "rule": "NURSE OUTPATIENT QUEUE (get_nurse_outpatient_list): status IN {IN-PROCESS,PENDING}, then Java-filter keep patientBill.status IN {PAID,COVERED,VERIFIED,NONE}. NOT keyed to any nurse username/role — returns all matching consultations system-wide.",
      "citation": "api/PatientResource.java:3639-3649"
    },
    {
      "rule": "LAB ORDER WORKLIST is patient-class-split into 3 endpoints. Outpatient: take consultations status==IN-PROCESS, their LabTests, keep patient.type==OUTPATIENT AND test.patientBill.status IN {PAID,COVERED}. Inpatient: admissions status==IN-PROCESS, keep type==INPATIENT AND bill IN {PAID,COVERED,VERIFIED}. Outsider: nonConsultations status==IN-PROCESS, keep type==OUTSIDER AND bill IN {PAID,COVERED}. Result deduped to a Patient set (HashSet). INPATIENT path additionally accepts VERIFIED bills; OUT* paths do not.",
      "citation": "api/PatientResource.java:3668-3717 (outpatient 3672-3678, inpatient 3689-3695, outsider 3706-3712)"
    },
    {
      "rule": "RADIOLOGY ORDER WORKLIST mirrors lab exactly (outpatient/inpatient/outsider), same context status==IN-PROCESS, same patient.type split, same bill-status gates (INPATIENT also allows VERIFIED).",
      "citation": "api/PatientResource.java:3719-3768"
    },
    {
      "rule": "PROCEDURE ORDER WORKLIST has only outpatient + outsider endpoints (no dedicated inpatient procedure list endpoint found). Same status==IN-PROCESS context + type split + bill IN {PAID,COVERED}. NOTE legacy bug: getProcedureOutpatientList builds its self URI with path '.../get_lab_outpatient_list' (copy-paste), and getProcedureOutsiderList uses '.../get_lab_outsider_list'.",
      "citation": "api/PatientResource.java:3770-3801 (mislabelled URIs at 3783 and 3800)"
    },
    {
      "rule": "PHARMACY/PRESCRIPTION WORKLIST split outpatient/inpatient/outsider. Outpatient: consultations status==IN-PROCESS -> prescriptions, keep type==OUTPATIENT AND bill IN {PAID,COVERED}. Inpatient: admissions IN-PROCESS, type==INPATIENT, bill IN {PAID,COVERED,VERIFIED}. Outsider: nonConsultations IN-PROCESS, type==OUTSIDER, bill IN {PAID,COVERED}. A code comment notes a prior bug findAllByStatusOrFollowUp was removed.",
      "citation": "api/PatientResource.java:4338-4388 (outpatient 4343-4349 incl. bug comment 4342, inpatient 4377-4383, outsider 4360-4366)"
    },
    {
      "rule": "PENDING-TRANSFER QUEUE (get_consultation_transfers): single predicate status=='PENDING' over ALL ConsultationTransfer rows — not scoped to a clinic, clinician, or user.",
      "citation": "api/PatientResource.java:599"
    },
    {
      "rule": "Order/result worklists are NOT scoped by performing-user identity or department role anywhere; they are scoped purely by (patient.type) + (context status IN-PROCESS) + (patientBill.status). There is no per-lab/per-radiology-room assignment filter.",
      "citation": "api/PatientResource.java:3668-3801,4338-4388"
    },
    {
      "rule": "DOCTOR INPATIENT LIST (get_doctor_inpatient_list): admission.status IN {IN-PROCESS,STOPPED}, no other filter. NURSE INPATIENT LIST: admission.status=='IN-PROCESS' only.",
      "citation": "api/PatientResource.java:3614-3618 (doctor), 3629 (nurse)"
    },
    {
      "rule": "per-patient order view (get_lab_tests_by_patient_id) gathers the patient's IN-PROCESS consultation+nonConsultation+admission, their LabTests, and returns only those whose patientBill.status IN {PAID,COVERED,VERIFIED}.",
      "citation": "api/PatientResource.java:3812-3820"
    },
    {
      "rule": "NUMBERING: NO document number is generated for a consultation or for any clinical order (LabTest/Radiology/Procedure/Prescription). Each is identified solely by its IDENTITY surrogate id. No requestConsultationNo/requestLabTestNo/... service or setNo() call exists anywhere in com.orbix.api. The planning doc's CONS{yyyyMMdd}-{seq} and ORD{yyyyMMdd}-{seq} schemes DO NOT EXIST in the legacy.",
      "citation": "domain/Consultation.java:48-50 (id only, no no); grep for requestConsultationNo|requestLabTestNo|requestRadiologyNo|requestProcedureNo|requestPrescriptionNo across com.orbix.api = No matches found"
    }
  ],
  "numbering": "NONE for the OPD clinical objects. Consultation, ConsultationTransfer, LabTest, Radiology, Procedure and Prescription each carry only a database IDENTITY surrogate id (@GeneratedValue(strategy=GenerationType.IDENTITY)) and NO human-facing document number. Citations: domain/Consultation.java:48-50; domain/ConsultationTransfer.java:37-39; domain/LabTest.java:44-46; domain/Radiology.java:44; (Procedure/Prescription likewise IDENTITY-only). A repository-wide grep for requestConsultationNo|requestLabTestNo|requestRadiologyNo|requestProcedureNo|requestPrescriptionNo|consultationNoService and for setNo( on these entities returned NO matches. There is NO prefix, NO zero-padding, NO yyyyMMdd, NO sequence, NO fiscal-year reset. This DIRECTLY CONTRADICTS planning-doc 05-clinical-opd.md which invents CONS{yyyyMMdd}-{seq} and ORD{yyyyMMdd}-{seq}. (Note: PatientCreditNote DOES get a generated no via patientCreditNoteService.requestPatientCreditNoteNo() at api/PatientResource.java:648, but that is a billing artefact, NOT a consultation/order number.)",
  "rbac": [
    "do_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") — api/PatientResource.java:509",
    "switch_to_normal_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") — api/PatientResource.java:548",
    "cancel_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") — api/PatientResource.java:606",
    "free_consultation: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE','PATIENT-UPDATE')\") — api/PatientResource.java:681",
    "register: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')\") — api/PatientResource.java:289",
    "update: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')\") — api/PatientResource.java:379",
    "change_type: @PreAuthorize(\"hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')\") — api/PatientResource.java:399"
  ],
  "driftVsPlanningDoc": [
    "NUMBERING — planning doc invents CONS{yyyyMMdd}-{seq} for consultations and ORD{yyyyMMdd}-{seq} for orders. Legacy reality: NO document number exists for consultations or any order; all are IDENTITY-id only. Cite domain/Consultation.java:48-50, domain/LabTest.java:44-46; grep for requestConsultationNo/requestLabTestNo/... = No matches.",
    "RBAC privilege NAMES — planning doc invents CONSULTATION_START, ORDER_RAISE, PROCEDURE_ORDER_APPROVE, PRESCRIPTION_CREATE. These DO NOT EXIST. The only ACTIVE @PreAuthorize codes touching the consultation lifecycle are the coarse PATIENT-ALL / PATIENT-CREATE / PATIENT-UPDATE family on do_consultation/switch/cancel/free (api/PatientResource.java:509,548,606,681).",
    "RBAC coverage — planning doc implies fine-grained method security on order/prescription/diagnosis endpoints. Legacy reality: save_lab_test, save_radiology, save_procedure, save_prescription, save_working_diagnosis, save_final_diagnosis, accept/reject/hold/verify_lab_test, accept/reject/hold/verify_radiology, accept_procedure, and EVERY worklist endpoint (get_*_list, load_*_consultations_by_clinician_id, get_consultation_transfers) have NO active @PreAuthorize — they are either absent or commented out (e.g. api/PatientResource.java:572,595; the only commented privilege strings are stale 'PATIENT-A'/'PATIENT-C'/'PATIENT-U' and 'PRODUCT-CREATE'). Effective access control on the clinical workflow is authentication-only (no method-level authority check).",
    "RBAC '177 privilege codes' — false (matches the firm memory: '177' = count of @PreAuthorize SITES, not distinct codes; and most clinical sites are commented out anyway).",
    "ORDER MODEL — planning doc invents a polymorphic ClinicalOrder(kind=LAB_TEST|RADIOLOGY|PROCEDURE). Legacy reality: three separate entities LabTest.java / Radiology.java / Procedure.java with independent (and slightly different) state machines — radiology has no COLLECTED step, procedure verifies via add_note. Cite domain/LabTest.java:43, domain/Radiology.java:44, domain/Procedure.java:45; api/PatientResource.java:4272-4295 vs 3960-3981.",
    "DIAGNOSIS MODEL — planning doc invents one ConsultationDiagnosis(kind=WORKING|FINAL). Legacy reality: two separate endpoints/entities WorkingDiagnosis + FinalDiagnosis (save_working_diagnosis api/PatientResource.java:1650; save_final_diagnosis api/PatientResource.java:1770).",
    "CONSULTATION STATUS NAMES — planning doc invents clean BOOKED/IN_PROGRESS/COMPLETED. Legacy reality (free-form Strings): PENDING, IN-PROCESS (hyphen, not underscore), TRANSFERED (single-R legacy spelling), CANCELED, SIGNED-OUT. There is no BOOKED and no COMPLETED on Consultation. Cite api/PatientResource.java:494,884-887,2808,618,699.",
    "DOCTOR QUEUE SCOPING — planning doc implies role-based queues. Legacy reality: the doctor reception queue is scoped by clinicianId request param + followUp flag + status, with a Java-side patientBill PAID/COVERED filter; nurse/lab/radiology/pharmacy worklists are NOT scoped by the performing user at all — only by patient.type + context status==IN-PROCESS + patientBill.status. Cite api/PatientResource.java:807-827, 3639-3649, 3668-3717."
  ],
  "openQuestions": [
    "The doctor reception queue (load_pending_consultations_by_clinician_id) takes clinicianId as a REQUEST PARAM, not from the JWT principal, and has no @PreAuthorize. Should the modern build derive the clinician from the authenticated user, and should these queues gain authority checks? (Legacy = no enforcement; HDE/engagement-lead must approve any tightening as a change request.)",
    "Most clinical-workflow endpoints have NO active @PreAuthorize (commented or absent). Confirm with security-architect whether the modern target re-uses the coarse PATIENT-ALL/CREATE/UPDATE codes on do_consultation as the de-facto clinical authority, or introduces real per-action privileges (which would be NEW behaviour, not legacy reproduction).",
    "Procedure has no dedicated inpatient worklist endpoint (only outpatient + outsider). Is inpatient procedure ordering intentionally absent, or surfaced via a different (admission) screen? Needs domain confirmation.",
    "Several worklist self-URIs are copy-paste wrong (procedure lists return '.../get_lab_outpatient_list' / 'get_lab_outsider_list' URIs — api/PatientResource.java:3783,3800). These are Location-header cosmetics only; confirm no client depends on them before silently 'fixing'.",
    "Bill-status gating is inconsistent across patient classes: INPATIENT worklists also accept patientBill.status=='VERIFIED' while OUTPATIENT/OUTSIDER accept only PAID/COVERED (and nurse outpatient also accepts NONE). Confirm this asymmetry is intended business behaviour (inpatient bill verified-but-unpaid still serviceable) and must be preserved exactly.",
    "'TRANSFERED' / 'IN-PROCESS' / 'SIGNED-OUT' legacy spellings: confirm the modern build preserves the exact wire strings for any external integration, or maps them — mapping is a change request."
  ]
}
```
