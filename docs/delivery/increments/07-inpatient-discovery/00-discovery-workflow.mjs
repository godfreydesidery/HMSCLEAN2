export const meta = {
  name: 'inc07-inpatient-discovery',
  description: 'Inc-07 discovery: extract legacy inpatient/nursing/admission/discharge behaviour + the inc-08 stock-integration reality, reconcile against the planning doc to find drift/phantom claims before any code',
  phases: [
    { title: 'Extract', detail: 'parallel legacy-analyst lanes over the legacy oracle (7 lanes) + 1 as-built lane' },
    { title: 'Reconcile', detail: 'adversarial doc-vs-legacy verdict per lane' },
    { title: 'Synthesize', detail: 'solution-architect scope verdict (inc-06/inc-08 RECONCILIATION format)' },
  ],
}

const LEGACY = 'D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api';
const PLANDOC = 'd:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/07-inpatient-nursing.md';
const NEWROOT = 'd:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis';

const COMMON = `
You are extracting GROUND-TRUTH legacy behaviour from the Zana HMIS legacy codebase (Spring Boot 2.2.5).
The legacy code is the SPECIFICATION ORACLE — what it ACTUALLY does, not what any doc claims.
Legacy source root: ${LEGACY}
The admission/nursing/discharge logic mostly lives in the LARGE controllers/services
api/PatientResource.java (6455 lines) and service/PatientServiceImpl.java (3446 lines) — grep them for the
relevant method names; the thin domain/*ServiceImpl.java files are mostly ward/consumable masterdata CRUD.
EVERY finding MUST carry a file:line citation. Be precise about: the EXACT entity field set + types
(is status a typed enum or a free-text String?), every state value + transition + its trigger method,
whether a gate is a HARD service reject vs a worklist FILTER, when stock/charge writes happen (which method,
which tx), and numbering schemes. If the legacy does NOT implement something the doc claims (e.g. a separate
MAR/WardTransfer/FluidBalance entity, a typed DischargePlan.kind, a ward-day accrual JOB), say so explicitly
("NOT FOUND — searched X, Y") — a confirmed ABSENCE is a critical finding (this is how inc-06 was found ~80%
phantom and inc-08's doc drifted toward an idealized modern design). Do NOT speculate. Output raw data.`;

const EXTRACT_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['lane', 'summary', 'findings', 'absences'],
  properties: {
    lane: { type: 'string' },
    summary: { type: 'string', description: '3-6 sentence ground-truth summary of how this area actually works' },
    findings: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['topic', 'behaviour', 'cite'],
        properties: {
          topic: { type: 'string' },
          behaviour: { type: 'string', description: 'exact behaviour observed in the legacy code' },
          cite: { type: 'string', description: 'file:line proving it' },
        },
      },
    },
    absences: {
      type: 'array',
      description: 'things the planning doc expects but that are confirmed ABSENT in legacy (with where you searched)',
      items: {
        type: 'object', additionalProperties: false,
        required: ['expected', 'searched'],
        properties: { expected: { type: 'string' }, searched: { type: 'string' } },
      },
    },
  },
};

const RECONCILE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['lane', 'verdicts', 'newDriftFound'],
  properties: {
    lane: { type: 'string' },
    verdicts: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['planClaim', 'verdict', 'evidence'],
        properties: {
          planClaim: { type: 'string' },
          verdict: { type: 'string', enum: ['ACCURATE', 'ACCURATE_WITH_CORRECTIONS', 'DRIFT', 'PHANTOM', 'UNVERIFIABLE'] },
          evidence: { type: 'string', description: 'legacy cite(s) + reasoning' },
          correction: { type: 'string', description: 'if not ACCURATE, what the doc SHOULD say' },
        },
      },
    },
    newDriftFound: { type: 'array', items: { type: 'string' }, description: 'legacy behaviour the doc omits' },
  },
};

const LANES = [
  {
    key: 'admission-lifecycle',
    extract: `LANE: Admission lifecycle + ward/bed assignment + ward-to-ward transfer.
Trace admit -> (ward/bed assign) -> discharge/deceased/referred in PatientResource.java + PatientServiceImpl.java
(grep "admit", "admission", "discharge", "deceased", "referred", "transfer", "bed").
Extract: the EXACT Admission.status values + every transition + trigger method (domain/Admission.java has status as
a free-text String — confirm the actual string values used). How is a bed claimed/released (AdmissionBed entity)?
Is there a WardTransfer entity/record for ward-to-ward moves, or is the bed just reassigned in place? What blocks
admit (deceased patient? existing open admission?)?`,
    claims: `Planning doc claims to verify:
- Admission states ADMITTED -> DISCHARGED/DECEASED/REFERRED/TRANSFERRED (typed?); wardUid/bedUid/admittedAt/
  dischargedAt/billsCleared/businessDayId fields.
- WardBed FREE/OCCUPIED/RESERVED/OUT_OF_SERVICE claimed on admit, released on terminal.
- WardTransfer immutable record (prior/new ward+bed, reason, transferredAt) — DOES THIS ENTITY EXIST?
- deceased patient guard on admit() (DISCH-4).`,
  },
  {
    key: 'discharge-plan',
    extract: `LANE: DischargePlan + the discharge/deceased/referral flow + the approver gate.
Read domain/DischargePlan.java + its create/approve in PatientResource/PatientServiceImpl. Extract: is DischargePlan
ONE entity with all-kind fields (history/investigation/management/operationNote/icuAdmissionNote/generalRecommendation),
or does it have a typed kind discriminator {DISCHARGE,DECEASED,REFERRAL}? What are the actual status values + transitions?
Is there a SECOND-APPROVER gate (approvedBy != createdBy)? Is discharge blocked on unpaid bills (billsCleared)?
Where is timeOfDeath/causeOfDeath captured (a deceased flow)? Is referral facility a FK to a provider entity or free-text?`,
    claims: `Planning doc claims:
- DischargePlan kind in {DISCHARGE,DECEASED,REFERRAL}, states PENDING->APPROVED, approvedBy != createdBy (M17 gate).
- DISCHARGE requires history/investigation/management/recommendations; DECEASED requires timeOfDeath+causeOfDeath +
  sets Patient.deceased=true; REFERRAL requires referralFacilityUid FK to ExternalMedicalProvider (DISCH-5).
- CASH discharge blocked if billsCleared=false; approver worklist endpoint (DISCH-1).
FLAG whether the typed kind, the second-approver gate, the deceased flag, and the referral FK each have a legacy basis.`,
  },
  {
    key: 'nursing-charts',
    extract: `LANE: Nursing charts — vitals, care plan, progress notes, dressing, AND fluid-balance / care-activity.
Read domain/PatientVital, PatientNursingChart, PatientNursingCarePlan, PatientNursingProgressNote, PatientDressingChart
+ their services. Extract the EXACT field set of each. CRITICAL: are FLUID BALANCE (intake/urine/drainage) and CARE
ACTIVITY (feeding/repositioning/blood-sugar) SEPARATE entities, or are they columns on PatientNursingChart? Is there a
WoundStatus enum on dressing, or free-text? Are progress notes typed (DOCTOR/NURSING/OBSERVATION/HANDOVER) or free-text?
Are these charts immutable/append-only or editable?`,
    claims: `Planning doc claims SIX separate chart aggregates: AdmissionVitalsEntry, NursingCarePlanItem (ACTIVE->RESOLVED/
CANCELLED), NursingProgressNote (kind DOCTOR/NURSING/OBSERVATION/HANDOVER), DressingChartEntry (WoundStatus enum),
FluidBalanceEntry (intake/urine/drainage mL, shift totals derived), CareActivityEntry (FEEDING/REPOSITIONING/BED_BATH/
BLOOD_SUGAR). ADMIT-1/ADMIT-2 claim fluid-balance + care-activity were "added late in V72" and must be first-class.
FLAG which of the 6 are real separate entities vs columns vs phantom; whether the enums/typed-kinds exist in legacy.`,
  },
  {
    key: 'mar-medication',
    extract: `LANE: Medication Administration (MAR) on inpatients.
Search the WHOLE legacy codebase for any MedicationAdministration / MAR / "administered" dose tracking on admissions
(grep "administ", "MAR", "MedicationAdmin", PatientNursingChart for dose-given columns, the prescription/consumable
charts). Does a per-administered-dose MAR record exist AT ALL, or does the legacy only track pharmacy dispensedAt +
the PatientConsumableChart? Report exactly what medication-administration tracking exists for inpatients.`,
    claims: `Planning doc claims MedicationAdministration (MAR) is a MANDATORY aggregate (M15): per-administered-dose with
prescriptionLineUid, administeredAt, doseGiven, routeUid, administeredByUsername, patientResponse; links to Prescription
by uid. The doc says the prior build "tracked only pharmacy dispensedAt" (M15). DETERMINE: does ANY MAR entity exist in
legacy, or is M15 a NET-NEW feature with no legacy basis (i.e. PHANTOM as exact-process, a new-feature CR)?`,
  },
  {
    key: 'consumable-stock',
    extract: `LANE: Consumable issue + stock decrement + charge accrual on inpatients.
Read domain/Consumable, domain/PatientConsumableChart + ConsumableServiceImpl + PatientConsumableServiceImpl + the
consumable issue path in PatientResource/PatientServiceImpl. Extract: how is a consumable issued to an admission? Where
does the stock decrement happen (which stock entity — is there a ConsumableStockBalance, or does it decrement Consumable.qty
or a store/pharmacy stock)? Is there a charge accrued, and where/how? Is there any locking? What stock entity does the
inpatient consumable actually draw from?`,
    claims: `Planning doc claims: ConsumableIssue line (consumableUid, qty, unitCost snapshot, sourceKind PHARMACY/STORE,
sourceLocationUid) decrements ConsumableStockBalance PESSIMISTICALLY in the same tx as the chart entry; charge accrued
synchronously via billing.api.recordClinicalCharge. Dependency claim: "inc-08 ConsumableStockBalance and
ConsumableStockService.decrementForIssue must exist". NOTE: inc-08 (just built) created PharmacyMedicine/StoreItem/StockBatch
+ StockService — there is NO ConsumableStockBalance or decrementForIssue. FLAG: what stock model does the legacy inpatient
consumable actually use, and does the doc's claimed inc-08 dependency exist?`,
  },
  {
    key: 'ward-accrual-billing',
    extract: `LANE: Ward charges / ward-day billing + the bills-cleared discharge gate.
Search PatientResource/PatientServiceImpl for how ward/admission charges are created (grep "ward", "WardType", "bed",
"admission" near bill/charge creation). CRITICAL: does the legacy charge ONE flat ward amount at admission, or does it
accrue per-day? Is there ANY scheduled job (@Scheduled/cron/ShedLock) for daily ward accrual? How is the ward price
resolved (WardType.price? WardTypeInsurancePlan?)? How is "bills cleared" determined for the discharge gate, and is it
a hard gate or a filter?`,
    claims: `Planning doc claims: WardDayAccrualJob (JOB-001) — a Spring @Scheduled + ShedLock daily cron producing one
WARD InvoiceLine per night, idempotent on (admission_uid, business_date); priced via ServicePrice(kind=WARD). It admits
"the legacy charges one flat amount at admission" but wants the accrual model to produce the same total. FLAG: the daily
accrual JOB + ShedLock + per-night lines are almost certainly NET-NEW (legacy = one flat charge at admission). Verify the
legacy ward-charge mechanism and whether ServicePrice(kind=WARD) vs WardType.price/WardTypeInsurancePlan is the real source.`,
  },
  {
    key: 'rbac-numbering',
    extract: `LANE: RBAC (@PreAuthorize) on inpatient endpoints + admission numbering.
Inspect the admission/discharge/nursing/MAR/consumable endpoints in PatientResource.java for @PreAuthorize codes (which
privilege codes, if any?). Also: how is an Admission numbered (any prefix/sequence, or none)? Recall inc-06/inc-08 found
legacy clinical/pharmacy endpoints were largely UNGATED and the ratified IAM record is 35 codes (NOT 177). Report the
ACTUAL privilege codes on these endpoints and the admission numbering scheme.`,
    claims: `Planning doc claims privilege codes ADMISSION-CREATE, ADMISSION-DISCHARGE, NURSING-CHART-WRITE, MAR-WRITE,
DISCHARGE-PLAN-APPROVE etc. "seeded before tested". Verify: do these codes exist in the legacy @PreAuthorize set / the
ratified 35-code IAM seed, or are they invented? Is the inpatient surface gated at all in legacy? (inc-06/08 found mostly-
ungated lifecycle endpoints + the "177" figure is a recurring phantom — the real seed is 35 codes.)`,
  },
];

log(`Inc-07 discovery: ${LANES.length} legacy extraction lanes + 1 as-built lane, each reconciled adversarially.`);

phase('Extract');
const asBuiltPromise = agent(
  `Inventory the AS-BUILT HMSCLEAN2 codebase (increments 00-08, merged to main) for everything inc-07 Inpatient/Nursing
depends on or reuses. New backend root: ${NEWROOT}. The inpatient module is a confirmed EMPTY STUB (only package-info.java)
— confirm and do NOT treat as a gap.
Report with file:line cites:
1. STOCK INTEGRATION (the load-bearing dependency): what does the inc-08 pharmacy/inventory stock API actually expose?
   Read ${NEWROOT}/pharmacy/api/PharmacyStockCredit.java, pharmacy/application/StockService.java,
   inventory/application/StoreStockService.java. Is there a ConsumableStockBalance or decrementForIssue (the doc's claimed
   dependency)? What is the REAL way inc-07 would decrement stock for an inpatient consumable issue (which method/seam)?
   Is there a pessimistic lock available, or only @Version (inc-08 parked the lock as CR-08-Q4)?
2. BILLING SEAM: billing::api.recordClinicalCharge (ChargeRequest — does ServiceKind have a WARD value? a CONSUMABLE value?),
   the settled-flag/SettlementDispatcher/BillSettledEvent pattern (for the billsCleared discharge gate), billing::api worklist/
   getBillStatus seams. Cite billing/api/*.
3. MASTERDATA: are Ward/WardType/WardBed/WardCategory + ward pricing (ServicePrice kind=WARD? WardTypeInsurancePlan?) seeded
   (check V6 org-units + V8/V9)? Is there an ExternalMedicalProvider masterdata entity (for referral FK, DISCH-5)?
4. CLINICAL: the Consultation aggregate (admission is triggered from a consultation) + the Prescription aggregate (MAR would
   link to it) + the PatientPrescriptionChart (inc-05 noted an inpatient drug-admin chart deferred to admissions) — what is
   the clinical::api read surface for these? Is admissionUid already a loose ref on Prescription (inc-05 deferred admission)?
5. SHARED: AuditableEntity, Money, TxAuditContext, BusinessDay, ErrorCode values present (SELF_APPROVAL_FORBIDDEN?
   ADMISSION_BILLS_OUTSTANDING? PATIENT_DECEASED? STOCK_INSUFFICIENT?), ShedLock/Quartz presence (for the ward job),
   shared.event, shared.audit.
6. Patient.deceased flag: does it exist on the registration Patient, and is there a deceased guard anywhere?
7. The highest existing Flyway version (inc-07 starts after it).
Output raw structured data.`,
  {
    label: 'as-built:inc00-08', phase: 'Extract', agentType: 'legacy-analyst',
    schema: {
      type: 'object', additionalProperties: false,
      required: ['stockIntegration', 'billingSeam', 'masterdata', 'clinical', 'sharedKernel', 'patientDeceased', 'maxFlyway', 'notes'],
      properties: {
        stockIntegration: { type: 'string' },
        billingSeam: { type: 'string' },
        masterdata: { type: 'string' },
        clinical: { type: 'string' },
        sharedKernel: { type: 'string' },
        patientDeceased: { type: 'string' },
        maxFlyway: { type: 'string' },
        notes: { type: 'array', items: { type: 'string' } },
      },
    },
  },
).then(r => ({ asBuilt: r }));

const laneResults = await pipeline(
  LANES,
  (lane) => agent(`${COMMON}\n\n${lane.extract}`, {
    label: `extract:${lane.key}`, phase: 'Extract', agentType: 'legacy-analyst', schema: EXTRACT_SCHEMA,
  }),
  (extracted, lane) => {
    if (!extracted) return null;
    return agent(
      `You are a Business Analyst doing ADVERSARIAL reconciliation for the Zana HMIS inc-07 build.
The inc-07 planning doc is at ${PLANDOC} — read the relevant section yourself.
Below are GROUND-TRUTH legacy findings (file:line-cited) for lane "${lane.key}". Judge each planning-doc claim against the
legacy ground truth. Be skeptical; default to DRIFT/PHANTOM when the legacy evidence does not clearly support the claim.
Remember the inc-06 lesson (that doc was ~80% phantom: typed states/entities asserted over a system that didn't implement them)
and the inc-08 lesson (the doc idealized toward a modern design — three-way match, pessimistic lock, "177" codes were all
phantom/unratified). Expect the SAME classes of drift here: separate MAR/WardTransfer/FluidBalance/CareActivity entities that
may be columns or absent; a typed DischargePlan.kind that may be one all-fields entity; a ward-day accrual JOB+ShedLock that is
almost certainly NET-NEW (legacy = one flat charge); ServicePrice(kind=WARD) vs WardTypeInsurancePlan; the "decrementForIssue/
ConsumableStockBalance" inc-08 dependency that does NOT exist under that name; invented privilege codes / "177".
A modern re-modelling that preserves behaviour is ACCURATE_WITH_CORRECTIONS; a feature with no legacy basis is PHANTOM (park as
a CR); a claim contradicting legacy is DRIFT.

${lane.claims}

GROUND-TRUTH LEGACY FINDINGS for lane "${lane.key}":
${JSON.stringify(extracted, null, 2)}`,
      { label: `reconcile:${lane.key}`, phase: 'Reconcile', agentType: 'business-analyst', schema: RECONCILE_SCHEMA },
    ).then(rec => ({ lane: lane.key, extract: extracted, reconcile: rec }));
  },
);

const asBuilt = await asBuiltPromise;
const lanes = laneResults.filter(Boolean);

phase('Synthesize');
const synthesis = await agent(
  `You are the Solution Architect synthesizing inc-07 (Inpatient & Nursing) discovery into a scope verdict, in the exact style
of the inc-06/inc-08 RECONCILIATION-AND-SCOPE docs. You have: (a) an as-built inventory of HMSCLEAN2 (inc-00..08), and
(b) ${lanes.length} legacy extraction+reconciliation lane results with file:line cites + per-claim verdicts.

Produce a structured verdict with sections:
1. VERDICT headline (the inpatient module is an empty stub, so this is a REAL full build like inc-08 — say so; note how the
   planning doc drifted, citing the worst phantoms).
2. ALREADY BUILT (do NOT rebuild) — what inc-00..08 provides that inc-07 reuses (the REAL inc-08 stock seam — correct the
   doc's wrong "ConsumableStockBalance/decrementForIssue" dependency to whatever actually exists; billing charge/settled seam;
   masterdata Ward/WardType/pricing; clinical Consultation/Prescription; shared kernel + ShedLock-or-not).
3. GROUND-TRUTH LEGACY MODEL — the corrected legacy-accurate description of admission/discharge/nursing/consumable/ward-charge,
   superseding the doc where they conflict, with cites.
4. PLANNING-DOC DRIFT (rejected/corrected) — every DRIFT and PHANTOM verdict consolidated (esp. WardTransfer entity, typed
   DischargePlan.kind, separate MAR entity, separate FluidBalance/CareActivity entities, the ward-day accrual JOB+ShedLock,
   ServicePrice(kind=WARD), the inc-08 dependency name, invented privilege codes / "177").
5. CONFIRMED-ACCURATE planning-doc claims — what survived (build as written).
6. NEW DRIFT (legacy behaviour the doc omits).
7. RECOMMENDED SCOPE & SEQUENCE — what to build as exact-process baseline vs park as CRs; whether to split inc-07 (e.g. 07a
   admission+discharge+ward, 07b nursing charts, 07c consumable+ward-charge); the architecture prerequisites (e.g. an inpatient
   stock-decrement seam over the inc-08 engine; ServiceKind.WARD/CONSUMABLE; a billing billsCleared dispatcher; ErrorCodes).
8. OPEN QUESTIONS FOR THE ENGAGEMENT OWNER — the decisions only the owner/HDE can make (esp. anything where reproducing legacy
   conflicts with a modern ADR or where a doc feature is net-new: MAR-as-new-feature, ward-day-accrual-JOB vs flat-charge,
   pessimistic lock vs inc-08's parked CR-08-Q4, second-approver gate, deceased flag, referral FK).

AS-BUILT INVENTORY:
${JSON.stringify(asBuilt.asBuilt, null, 2)}

LANE RESULTS (extract + reconcile):
${JSON.stringify(lanes, null, 2)}`,
  {
    label: 'synthesis:scope-verdict', phase: 'Synthesize', agentType: 'solution-architect',
    schema: {
      type: 'object', additionalProperties: false,
      required: ['verdict', 'alreadyBuilt', 'groundTruthModel', 'drift', 'confirmedAccurate', 'newDrift', 'recommendedScope', 'openQuestions'],
      properties: {
        verdict: { type: 'string' },
        alreadyBuilt: { type: 'array', items: { type: 'string' } },
        groundTruthModel: { type: 'string' },
        drift: { type: 'array', items: { type: 'string' } },
        confirmedAccurate: { type: 'array', items: { type: 'string' } },
        newDrift: { type: 'array', items: { type: 'string' } },
        recommendedScope: { type: 'string' },
        openQuestions: { type: 'array', items: { type: 'string' } },
      },
    },
  },
);

return { asBuilt: asBuilt.asBuilt, lanes, synthesis };
