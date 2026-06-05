export const meta = {
  name: 'inc07-decisions',
  description: 'Resolve the 13 inc-07 open questions against the ratified charter + ADRs + inc-08 precedents; adversarially check no decision contradicts an ADR or smuggles an un-CR-ed deviation into the exact-process baseline',
  phases: [
    { title: 'Resolve', detail: 'one specialist decides each of the 13 questions against charter + ADRs + precedents' },
    { title: 'Challenge', detail: 'adversarial review: ADR contradiction? smuggled deviation? owner action mis-flagged?' },
    { title: 'Ratify', detail: 'engagement-lead consolidates into a DECISIONS-RATIFIED register' },
  ],
}

const DISC = 'd:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/07-inpatient-discovery';
const RECON = `${DISC}/01-RECONCILIATION-AND-SCOPE.md`;
const RAW = `${DISC}/00-discovery-raw.json`;

const CHARTER = `
ENGAGEMENT CHARTER (binding, from project memory + build-plan.md):
- "MODERN DESIGN, EXACT PROCESS." The business PROCESS must stay identical to the legacy Zana HMIS.
  Data types/data model MAY modernize (double->BigDecimal pre-approved; ULID uid; Flyway; ProblemDetail; typed enums
  mapped 1:1 to legacy strings are acceptable behaviour-preserving modernization).
- A deliberate DEVIATION from observed legacy behaviour requires a written, APPROVED change request (CR) BEFORE
  implementation. Deviations are NOT silently built into the exact-process baseline.
- RATIFIED ADRs already decide some questions and are NOT re-litigated. BUT verify ratification: per docs/adr/README.md
  line 3, ALL ADRs are currently status "Proposed... pending ratification by the engagement lead before they become
  binding." So the charter no-re-litigate rule does NOT yet fire for any ADR — an ADR target that requires ratification
  is OWNER-GATED (parked) until the engagement lead ratifies it. This is exactly what happened in inc-08 (Q4 pessimistic
  lock + Q8 NULLS-LAST were re-classified from "ADR pre-decided" to parked-pending-ratification).
- Golden-master parity is the merge gate (legacy business RESULTS reproduced; money at 2dp).
- inc-06/inc-08 LESSON: do NOT reproduce phantom/idealized features with no legacy basis; do NOT confuse a worklist
  FILTER with a hard GATE; the "177 privilege codes" figure is a recurring phantom (ratified IAM = 35 codes).

KEY VERIFIED FACTS (read the ADR/code, do not trust the doc):
- ADR-0017 (concurrency/locking): STATUS PROPOSED. Mandates PESSIMISTIC_WRITE on stock decrement, but inc-08 RATIFIED
  DECISION (03-DECISIONS-RATIFIED Q4) PARKED the pessimistic lock as CR-08-Q4 pending ADR-0017 ratification — the
  as-built has @Version-only, NO pessimistic lock anywhere. inc-07 INHERITS this parked posture.
- ADR-0018 (jobs/ShedLock): STATUS PROPOSED. Its JOB-001 ward-day accrual is EXPLICITLY labelled (§Exact-process-impact)
  "a deliberate process improvement, not a divergence" / "not legacy fidelity" — i.e. NET-NEW by the ADR's own words.
  There is ZERO scheduling infra in HMSCLEAN2 (no @Scheduled/ShedLock/Quartz). NOTE the discovery found ADR-0018's
  premise ("legacy charges one flat amount at admission, does not re-accrue") is itself WRONG: legacy DOES re-accrue per
  rolling-24h via a polling Thread; so JOB-001's golden-master must reconcile the legacy elapsed-24h total, and ADR-0018
  needs an upstream correction.
- DECEASED: PatientType.DECEASED is the SOLE deceased marker (NO boolean Patient.deceased — registration/domain/
  PatientType.java:37-40, CR-05). PatientDeceasedEvent + PatientClosureListener pattern EXISTS. So a deceased terminal
  reuses that event pattern; there is NO PATIENT_DECEASED error code and NO admit-time deceased check in legacy.
- ErrorCode: only INSUFFICIENT_STOCK exists among the inc-07-needed codes. PATIENT_DECEASED, ADMISSION_BILLS_OUTSTANDING,
  SELF_APPROVAL_FORBIDDEN are ABSENT and tie to NET-NEW CRs.
- ServiceKind has WARD (per-stay, NOT per-day; no WARD_DAY) and NO CONSUMABLE value. recordClinicalCharge carries
  inpatient(boolean). SettlementPolicy.requiresPrepayment returns false for inpatient (settle at discharge).
- Stock seams (inc-08): StockService.decrementFefo / StoreStockService.decrementFefo are package-private (no NamedInterface);
  only published pharmacy::api type is PharmacyStockCredit (transfer-flavoured). NO ConsumableStockBalance / decrementForIssue
  exists. Legacy inpatient consumable issue is BILLING-ONLY (touches no stock).
`;

const QUESTIONS = [
  { id: 'Q1', topic: 'MAR as net-new feature', agent: 'healthcare-domain-expert',
    text: `Closed-loop MedicationAdministration/MAR (routeUid, administeredAt, doseGiven, patientResponse) is confirmed PHANTOM (no MAR in legacy; the M15 "tracked only dispensedAt" premise is wrong — no dispensedAt field). Decide: drop MAR from the exact-process baseline (inpatient med tracking = the legacy free-text PatientPrescriptionChart dosing note, which inc-07 STILL owns the deferred write path for: linked prescription GIVEN + admission IN-PROCESS + nurse uid), OR raise MAR as a NET-NEW clinical-safety CR for owner sign-off? Charter default: drop from baseline, build the legacy PatientPrescriptionChart dosing-note write path, park MAR as CR-07-MAR.` },
  { id: 'Q2', topic: 'Ward-day accrual mechanism', agent: 'solution-architect',
    text: `The WardDayAccrualJob @Scheduled+ShedLock cron is NET-NEW (ADR-0018 §Exact-process-impact says so explicitly; zero scheduling infra exists; ADR-0018 is status PROPOSED, not ratified). Legacy re-accrues per rolling-24h via a polling Thread (NOT a calendar cron; ADR-0018's "one flat charge" premise is wrong). Decide: is the ward accrual baseline (a) reproduce the legacy per-24h re-accrual total/status semantics with WHATEVER mechanism, gated on the owner RATIFYING ADR-0018 + adding scheduling infra (OWNER_RELEASE_GATE), or (b) something buildable now? Classify the cron+ShedLock as OWNER_RELEASE_GATE pending ADR-0018 ratification. Also flag the ADR-0018 premise correction (legacy DOES re-accrue) and that the golden-master must reconcile the legacy elapsed-24h total, documenting the midnight-vs-24h variance.` },
  { id: 'Q3', topic: 'Pessimistic lock vs parked CR-08-Q4', agent: 'solution-architect',
    text: `inc-07 inherits @Version-only concurrency (inc-08 PARKED the pessimistic lock as CR-08-Q4 pending ADR-0017 ratification; ADR-0017 is status PROPOSED). The bed-claim race (two admits to the same EMPTY bed) is the new concern. Decide: accept the parked @Version-only posture for admission/bed/ward-bill (baseline), OR is the bed-claim race severe enough to lift the lock for this aggregate (a deviation needing ADR-0017 ratification + owner sign-off)? Charter default: inherit the parked posture; the bed-claim race is handled by @Version optimistic lock (409 STALE_ENTITY) in the baseline; a pessimistic lock is OWNER_RELEASE_GATE under CR-08-Q4/ADR-0017.` },
  { id: 'Q4', topic: 'Second-approver discharge gate (M17)', agent: 'security-architect',
    text: `The second-approver SELF_APPROVAL_FORBIDDEN gate (approvedBy != createdBy) is PHANTOM: legacy approver is ALWAYS copied from the creator (single-actor cashier-side approval). Decide: baseline reproduces single-actor approval (approver = creator OK), and the second-approver SoD control is a NET-NEW CR (CR-07-SoD) needing a new privilege (DISCHARGE-PLAN-APPROVE) AND an IAM operation-vocabulary decision (legacy purge loop deletes *-DISCHARGE/*-WRITE suffixes). Charter default: reproduce single-actor; park the SoD gate + SELF_APPROVAL_FORBIDDEN as CR-07-SoD.` },
  { id: 'Q5', topic: 'Deceased-readmit guard (DISCH-4)', agent: 'healthcare-domain-expert',
    text: `The deceased-readmit guard + PATIENT_DECEASED error is PHANTOM: legacy has no deceased boolean (PatientType.DECEASED string only), no PATIENT_DECEASED code, no admit-time deceased check. BUT legacy DOES already block re-admission while a PENDING/IN-PROCESS admission is open, and blocks outpatient consultation while admitted. Decide: baseline reproduces (a) the open-admission re-admit block + (b) the deceased terminal setting PatientType=DECEASED via the existing PatientDeceasedEvent/PatientClosureListener; the explicit admit-time deceased-block + PATIENT_DECEASED RFC7807 type is NET-NEW (CR-07-deceased-guard). Charter default: reproduce the open-admission block + DECEASED-via-event; park the admit-time deceased guard as a CR.` },
  { id: 'Q6', topic: 'Deceased flag modelling', agent: 'solution-architect',
    text: `Confirm DECEASED stays a PatientType enum value (NO boolean Patient.deceased, per CR-05 / PatientType.java:37-40) and the deceased terminal sets PatientType=DECEASED via the existing PatientDeceasedEvent published to PatientClosureListener (the inc-04/06 pattern), NOT a new boolean field. This is an architecture confirmation (no legacy-process deviation). Specify the event seam (inpatient publishes PatientDeceasedEvent in-tx; registration's PatientClosureListener flips the type — no inpatient->registration compile edge).` },
  { id: 'Q7', topic: 'Referral FK masterdata', agent: 'data-architect',
    text: `REFERRAL needs a provider reference. Legacy ReferralPlan already enforces an FK (nullable=false) to a provider; but in HMSCLEAN2 the ExternalMedicalProvider masterdata entity DOES NOT EXIST (clinical ReferralPlan V28 carries external_medical_provider_uid as a MANDATORY LOOSE ref, no FK, table not built). Decide: (a) build an ExternalMedicalProvider masterdata entity + seed + real FK (closest to legacy intent, but net-new masterdata table — is it in scope or a CR?), or (b) keep the legacy loose uid (no FK) consistent with how clinical V28 already models it (the as-built precedent)? Charter/consistency default: keep the loose uid (matches the as-built clinical ReferralPlan precedent); building provider masterdata is a CR. Flag the DISCH-5 "legacy was free-text" premise is WRONG (legacy uses an FK).` },
  { id: 'Q8', topic: 'WardTransfer', agent: 'business-analyst',
    text: `WardTransfer entity + /transfer-ward + TRANSFERRED state is PHANTOM (legacy has NO ward-to-ward transfer at all; both bed FKs updatable=false). Decide: confirm WardTransfer is NET-NEW and EXCLUDED from inc-07 exact-process scope (no exact-process AC); park as a candidate CR (CR-07-ward-transfer) for a later increment or defer entirely. No owner action needed to proceed with the baseline exclusion.` },
  { id: 'Q9', topic: 'WardTypeInsurancePlan active-flag-ignored quirk', agent: 'healthcare-domain-expert',
    text: `Legacy ward insurance eligibility IGNORES the active flag (an inactive-but-covered plan stays eligible). Decide: reproduce the quirk verbatim in the baseline (exact-process), or CR-fix it (deviation)? Charter default: reproduce verbatim (exact-process); a fix is a CR. Note the ward pricing also involves a max-price-OR-exact-plan-match loop + a TOP-UP supplementary bill (currently a deferred billing-engine gap per PriceLookupImpl) — flag that the top-up split must be built or its absence consciously carried.` },
  { id: 'Q10', topic: 'AdmissionBed not closed at discharge (legacy leak)', agent: 'data-architect',
    text: `Legacy leaves the final AdmissionBed OPENED at discharge (only closed on accrual rollover) — a leak. Decide: reproduce the leak verbatim for exact parity, or CR-fix by closing it at discharge (deviation)? Charter default: reproduce verbatim (exact-process baseline); the close-at-discharge fix is a CR. Do NOT silently merge WardBed and AdmissionBed (they are distinct: WardBed=physical master, AdmissionBed=occupancy/billing ledger).` },
  { id: 'Q11', topic: 'Consumable qty=1 quirk + mislabeled credit-note ref', agent: 'business-analyst',
    text: `Legacy consumable issue hard-codes invoiceDetail.qty=1 in BOTH bill branches even though the bill qty = chart.qty; the consumable delete credit-note reference is mislabeled "Canceled lab test". Decide: preserve both verbatim for parity (exact-process), or CR-fix as latent bugs (deviation)? Charter default: reproduce verbatim (the golden-master must match legacy output); raise a documented CR noting they are suspected latent bugs for the owner to optionally fix later. legacy-analyst to confirm intentional vs bug.` },
  { id: 'Q12', topic: 'Consumables draw from stock', agent: 'solution-architect',
    text: `Legacy inpatient consumable issue is BILLING-ONLY (touches no stock). Decide: confirm the exact-process baseline keeps consumables billing-only (NO stock decrement, NO new seam), OR approve the NET-NEW CR (CR-07-consumable-stock) that makes inpatient consumable issue decrement inc-08 stock — which then requires a NEW published CONSUMABLE_ISSUE decrement seam over StockService/StoreStockService (debitTransferOut is transfer-flavoured and unsuitable as-is). Charter default: billing-only baseline; the stock-decrement is a CR with a new seam. (Also kills the D5 last-unit-409 concurrency test as exact-process.)` },
  { id: 'Q13', topic: 'ServiceKind for consumables', agent: 'solution-architect',
    text: `Inpatient consumable charge has no ServiceKind (WARD is per-stay; no CONSUMABLE). Legacy prices consumables off Medicine.price / MedicineInsurancePlan. Decide: charge inpatient consumables as ServiceKind.MEDICINE (recommended — matches the legacy 'Medication' billItem + MedicineInsurancePlan path most closely; no schema change), OR add a NET-NEW ServiceKind.CONSUMABLE (a new pricing kind requiring a service_prices CHECK migration + CR)? Charter default: charge as MEDICINE (closest legacy fidelity, no CR); ServiceKind.CONSUMABLE is the CR alternative.` },
];

log(`Resolving ${QUESTIONS.length} inc-07 open questions against the ratified charter + ADRs + inc-08 precedents.`);

const RESOLVE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['id', 'decision', 'classification', 'rationale', 'adrCitation', 'crNeeded', 'buildImpact', 'ownerActionRequired'],
  properties: {
    id: { type: 'string' },
    decision: { type: 'string', description: 'the concrete resolution — what inc-07 will actually do' },
    classification: { type: 'string', enum: ['ADR_PRE_DECIDED', 'EXACT_PROCESS_REPRODUCE', 'APPROVED_HARDENING', 'DEVIATION_NEEDS_CR', 'OWNER_RELEASE_GATE', 'ARCHITECTURE_DECISION'] },
    rationale: { type: 'string' },
    adrCitation: { type: 'string', description: 'the ADR section / precedent that governs this, or "none — ADRs silent"' },
    crNeeded: { type: 'string', description: 'if a deviation: the one-line CR title to raise; else "none"' },
    buildImpact: { type: 'string', description: 'what 07a/07b/07c must build (or NOT build) as a result' },
    ownerActionRequired: { type: 'string', description: 'any action only the engagement/product owner can take; else "none"' },
  },
};

const resolved = await pipeline(
  QUESTIONS,
  (q) => agent(
    `${CHARTER}\n\nYou are the ${q.agent} resolving inc-07 open question ${q.id} (${q.topic}).\nRead the reconciliation doc at ${RECON} (and ${RAW} if you need raw cites) for context.\n\nQUESTION ${q.id}:\n${q.text}\n\nResolve it applying the charter strictly: a ratified ADR pre-decides (but verify ratification — all ADRs are Proposed, so an ADR target needing ratification is OWNER_RELEASE_GATE, not ADR_PRE_DECIDED); a tempting improvement with no legacy basis is the reproduce-legacy baseline + a DEVIATION_NEEDS_CR; an architecture placement with no process impact is ARCHITECTURE_DECISION; an owner-only sign-off is OWNER_RELEASE_GATE (do NOT decide it for them). Be concrete about what 07a/07b/07c build or must NOT build.`,
    { label: `resolve:${q.id}`, phase: 'Resolve', agentType: q.agent, schema: RESOLVE_SCHEMA },
  ),
  (decision, q) => {
    if (!decision) return null;
    return agent(
      `${CHARTER}\n\nYou are the code-reviewer / tech-lead acting as an ADVERSARIAL challenger on an inc-07 decision. Be skeptical.\nA specialist resolved open question ${q.id} (${q.topic}):\n${JSON.stringify(decision, null, 2)}\n\nChallenge on THREE axes:\n1. Does it CONTRADICT an ADR, or wrongly claim ADR_PRE_DECIDED when the ADR is only Proposed (re-classify to OWNER_RELEASE_GATE if so — this is the inc-08 ADR-0017/0018 lesson)?\n2. Does it SMUGGLE A DEVIATION into the exact-process baseline without a CR? (MAR, ward-accrual-cron, pessimistic lock, second-approver gate, deceased admit-guard, consumables-from-stock, WardTransfer, new ServiceKind, ExternalMedicalProvider masterdata, *-WRITE/*-DISCHARGE privilege codes — ALL must be reproduce-legacy-baseline + parked-CR or owner-gated, NOT silently built.)\n3. Is the classification correct and any owner action correctly flagged (not silently decided for the owner)?\nReturn UPHELD or REVISE with the specific correction. Default REVISE if anything beyond verified legacy behaviour is built without a CR, or an ADR is mis-cited.`,
      { label: `challenge:${q.id}`, phase: 'Challenge', agentType: 'code-reviewer',
        schema: { type: 'object', additionalProperties: false, required: ['id', 'verdict', 'reason', 'correction'],
          properties: { id: { type: 'string' }, verdict: { type: 'string', enum: ['UPHELD', 'REVISE'] }, reason: { type: 'string' }, correction: { type: 'string' } } } },
    ).then(ch => ({ id: q.id, topic: q.topic, decision, challenge: ch }));
  },
);

const items = resolved.filter(Boolean);

phase('Ratify');
const register = await agent(
  `${CHARTER}\n\nYou are the engagement-lead consolidating the resolved + adversarially-challenged inc-07 open questions into a DECISIONS-RATIFIED register.\nFor each item: if challenge UPHELD, ratify the decision; if REVISE, apply the challenger's correction and ratify the corrected decision. Produce a clean owner-facing register, plus: (a) consolidated CRs to raise (every DEVIATION_NEEDS_CR), (b) OWNER ACTIONS REQUIRED (release gates / scope approvals / ADR ratifications only the owner can give), (c) the net effect on the 07a/07b/07c build scope (what is IN the exact-process baseline vs parked as CR/owner-gated), (d) any ADR corrections (e.g. ADR-0018's wrong "one flat charge" premise).\n\nRESOLVED + CHALLENGED ITEMS:\n${JSON.stringify(items, null, 2)}`,
  { label: 'ratify:register', phase: 'Ratify', agentType: 'engagement-lead',
    schema: {
      type: 'object', additionalProperties: false,
      required: ['ratifiedDecisions', 'changeRequests', 'ownerActionsRequired', 'baselineScopeEffect', 'adrCorrections', 'readyToFreezeBuildSpec'],
      properties: {
        ratifiedDecisions: { type: 'array', items: {
          type: 'object', additionalProperties: false,
          required: ['id', 'topic', 'ratifiedDecision', 'classification'],
          properties: { id: {type:'string'}, topic:{type:'string'}, ratifiedDecision:{type:'string'}, classification:{type:'string'} } } },
        changeRequests: { type: 'array', items: { type: 'string' } },
        ownerActionsRequired: { type: 'array', items: { type: 'string' } },
        baselineScopeEffect: { type: 'string' },
        adrCorrections: { type: 'array', items: { type: 'string' } },
        readyToFreezeBuildSpec: { type: 'string' },
      } } },
);

return { items, register };
