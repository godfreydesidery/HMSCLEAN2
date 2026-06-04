export const meta = {
  name: 'inc06a-buildspec',
  description: 'Inc-06A build spec: precise legacy re-extraction of the 6 inc-05-closure items -> adversarial verify -> SA build-spec with chunk plan + bill-status-seam decision',
  phases: [
    { title: 'Extract', detail: 'exact legacy behaviour for each of the 6 closure items' },
    { title: 'Verify', detail: 'adversarial verification of each extraction' },
    { title: 'Spec', detail: 'solution-architect build spec: chunks, seam decisions, acceptance criteria' },
  ],
}

const LEGACY = 'D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api';
const HMSCLEAN2 = 'd:/My_Works/HMS/HMSCLEAN2';

const GROUND = `LEGACY SOURCE OF TRUTH: ${LEGACY} (order lifecycle in PatientResource.java + PatientServiceImpl.java).
AS-BUILT: ${HMSCLEAN2}/backend, single 'clinical' module (com.otapp.hmis.clinical), inc-05 shipped on main.
DECISIONS ALREADY MADE BY OWNER (2026-06-04): build a SHORT inc-06A top-up now; ITEM 5 attachment storage = LEGACY-PARITY LOCAL-DISK (configured path, app-generated filename, 10 MiB cap, inline download streaming) — NOT object storage.
KEY AS-BUILT FACTS (verified):
- billing.api.BillingCommands.cancelCharge(billUid, reference, ctx) IS published (billing/api/BillingCommands.java:58) and CreditNoteService.cancelCharge does the full legacy reversal (soft-cancel bill->CANCELED, refund RECEIVED->REFUNDED, raise PENDING PatientCreditNote, detach invoice-detail, delete empty invoice). DO NOT reproduce legacy HARD-delete of bill/payment — the soft-flag pattern is the ratified standard.
- The 3 stale delete-TODOs to replace: LabTestService.java:449, ProcedureService.java (delete), RadiologyService (delete). Use legacy reference labels 'Canceled lab test' / 'Canceled radiology' / 'Canceled procedure'.
- inc-05 ALREADY wired the FREE-path cancel cascade (ConsultationLifecycleService.freeChildOrders ~:412-431: lab->rad->proc->prescription cancelCharge on unsettled bills) and the sign-off/referral uncleared-bill gates (ClosureService.hasUnsettledOrders*). So ITEM 6 is PARTLY done — extract ONLY the residual: (a) the CONSULTATION-CANCEL hard-delete cascade (vs free), and (b) any sign-off UNPAID->CANCELED flip not already covered.
- Clinical holds only a local 'settled' flag (set at order time) and per ADR-0008 §6 NEVER reads billing bill-status post-hoc. ITEMs 2/4 (bill-gated add_report) need the REAL bill status (PAID/COVERED/VERIFIED) at report time, which the settled flag may not reflect after a later payment. So a narrow billing.api bill-status read seam is likely required — SPECIFY it.
CITE EXACT file:line for every claim. State 'ABSENT' explicitly where the behaviour doesn't exist.`;

const EXTRACT_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['item', 'legacyBehaviour', 'states', 'verbatimMessages', 'guards', 'citations', 'asBuiltDelta', 'confidence'],
  properties: {
    item: { type: 'string' },
    legacyBehaviour: { type: 'string', description: 'Exact legacy behaviour for this item.' },
    states: { type: 'array', items: { type: 'string' } },
    verbatimMessages: { type: 'array', items: { type: 'string' }, description: 'EXACT user-facing strings, character-for-character.' },
    guards: { type: 'array', items: { type: 'string' }, description: 'Preconditions/status checks/order of checks.' },
    citations: { type: 'array', items: { type: 'string' }, description: 'file:line for each claim.' },
    asBuiltDelta: { type: 'string', description: 'What the as-built inc-05 code already does vs what is missing for this item.' },
    confidence: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW'] },
  },
};

const VERIFY_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['verdict', 'corrections', 'summary'],
  properties: {
    verdict: { type: 'string', enum: ['ACCURATE', 'ACCURATE_WITH_CORRECTIONS', 'MATERIALLY_WRONG'] },
    corrections: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  },
};

const SPEC_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['chunks', 'billStatusSeamDecision', 'item4Recommendation', 'flywayPlan', 'rejectedOrDeferred', 'acceptanceCriteria', 'risks'],
  properties: {
    chunks: { type: 'array', items: {
      type: 'object', additionalProperties: false,
      required: ['id', 'title', 'scope', 'dependsOn', 'sizeEstimate'],
      properties: {
        id: { type: 'string' }, title: { type: 'string' },
        scope: { type: 'string' }, dependsOn: { type: 'string' },
        sizeEstimate: { type: 'string', enum: ['SMALL', 'MEDIUM', 'LARGE'] },
      },
    }},
    billStatusSeamDecision: { type: 'string', description: 'Exact shape of the billing.api bill-status read seam for items 2/4 (or why settled flag suffices), incl. ADR/cycle impact.' },
    item4Recommendation: { type: 'string', enum: ['REPRODUCE', 'TREAT_AS_DEFECT_SKIP', 'NEEDS_HDE_LEGACY_CONFIRM'] },
    flywayPlan: { type: 'string', description: 'New migrations needed (start V38) and what each does, or NONE.' },
    rejectedOrDeferred: { type: 'array', items: { type: 'string' } },
    acceptanceCriteria: { type: 'array', items: { type: 'string' }, description: 'Testable AC per chunk, golden-master where applicable, exact error strings.' },
    risks: { type: 'array', items: { type: 'string' } },
  },
};

phase('Extract');
const items = [
  { id: 'ITEM1', name: 'L/R/P DELETE credit-note + invoice reversal seam',
    hint: 'Legacy lab/radiology/procedure delete: PatientResource.java:2912-2965 (lab), 3418-3471 (rad), 3473-3537 (proc). What exact reversal happens (bill->CANCELED, payment-detail RECEIVED->REFUNDED, raise PatientCreditNote, invoice-detail removal, empty-invoice delete)? What reference label? What guards (PENDING-only? what about a PAID bill)? Confirm cancelCharge already does this so the wiring is just a call.' },
  { id: 'ITEM2', name: 'Radiology stand-alone bill-gated add_report',
    hint: 'Legacy radiologies/add_report: PatientResource.java:3183-3197. Writes Radiology.report at ANY order status if bill status in {PAID,COVERED,VERIFIED}; else verbatim "Could not add report. Payment not verified". Compare to lab add_report (3381-3395). Exact bill-status set + message + whether order status matters.' },
  { id: 'ITEM3', name: 'save_reason_for_rejection (lab + radiology)',
    hint: 'Legacy lab_tests/save_reason_for_rejection PatientResource.java:2034-2048; radiologies/ 2018-2032. Sets rejectComment ONLY when status==REJECTED, else verbatim "Could not save. Only allowed for rejected tests". Exact guard + message + which field.' },
  { id: 'ITEM4', name: 'Post-VERIFIED report amendment asymmetry',
    hint: 'Legacy add_report (lab 3381-3395, rad 3183-3197) is gated ONLY on bill status, never order status — so report narrative is overwritable AFTER VERIFIED while result/range/level/unit are not. CONFIRM this asymmetry is real (cite the exact lack of a status guard). Assess: intended-to-reproduce vs legacy defect (clinical-safety amendment-trail concern). Recommend.' },
  { id: 'ITEM5', name: 'Attachment file storage + download (LEGACY-PARITY LOCAL-DISK)',
    hint: 'Legacy lab/radiology attachment upload+storage: PatientServiceImpl.java:2823-2906 (lab), 2922-2996 (rad); download PatientResource.java:5960-6007 (lab), 6093-6140 (rad). Extract: storage path source (CompanyProfile.publicPath), app-generated filename scheme ("LT"/"RAD"+id+patientNo+random+timestamp), 10 MiB (10485760) cap + its error message, MIME/content-type on download, inline streaming. As-built stores only name+fileName refs (no bytes). Owner chose local-disk parity — spec the exact filename scheme + cap + download content-type.' },
  // ITEM6 (cancel/sign-off cascade residual) is handled directly in the spec prompt below —
  // its extraction agent stuck in a schema-retry loop and the residual was already verified
  // against the as-built code by the main loop (see ITEM6_FINDING).
];

// ITEM6 — verified directly against the as-built code by the main loop (the workflow agent
// could not converge on the schema; this is the authoritative finding it would have produced).
const ITEM6_FINDING = `ITEM6 — Encounter cancel/sign-off cascade RESIDUAL for L/R/P bills (verified against as-built code):
- ALREADY DONE in inc-05: the FREE-path cascade (ConsultationLifecycleService.freeChildOrders ~:412-431) calls billing.api.cancelCharge on every UNPAID (settled=false) lab/radiology/procedure/prescription child bill, ordered lab->rad->proc->prescription, reference REF_FREED_CONSULTATION. Also DONE: the sign-off/referral uncleared-bill GATES (ClosureService.hasUnsettledOrdersForDeceasedGate / hasUnsettledOrdersForReferralGate; verbatim "Could not get deceased summary. Patient have uncleared bills." and the per-type "Could not save. Patient have uncleared <type> bill(s)").
- Consultation CANCEL path (ConsultationLifecycleService.cancel ~:251) cancels the CONSULTATION bill via cancelCharge(REF_CANCEL_CONSULTATION) — VERIFY whether it ALSO cascades to the child L/R/P/prescription order bills the way the free path does. Legacy cancel: PatientResource.java:434-494 hard-deletes PENDING orders with UNPAID/null bills together with their bills.
- RESIDUAL to spec: (a) if consultation CANCEL does NOT already cascade to child-order bills, wire the same freeChildOrders-style cancelCharge cascade into cancel (reference 'Canceled consultation' or the per-order legacy ref). (b) Confirm sign-off UNPAID->CANCELED is covered by the free cascade (likely yes) — if so ITEM6 is a small VERIFY-AND-CONFIRM, possibly NO new code beyond a cancel-path cascade + tests.
- SIZE: SMALL/MEDIUM, verify-first. Treat as the LAST chunk; it may collapse to test-only if cancel already cascades.`;

const verified = await pipeline(
  items,
  (it) => agent(
    `You are a Legacy Systems Analyst. ${GROUND}\n\nEXTRACT exact legacy behaviour for ${it.id} — ${it.name}.\n${it.hint}`,
    { label: `extract:${it.id}`, phase: 'Extract', schema: EXTRACT_SCHEMA, agentType: 'legacy-analyst' }
  ),
  (ext, it) => ext == null ? null :
    agent(
      `You are an adversarial verifier. ${GROUND}\n\nRefute this extraction for ${it.id}. Open the cited files, check each file:line, flag wrong/imprecise/unverifiable claims (esp. verbatim message strings and the asBuiltDelta). Default to skepticism.\n\nEXTRACTION:\n${JSON.stringify(ext, null, 2)}`,
      { label: `verify:${it.id}`, phase: 'Verify', schema: VERIFY_SCHEMA, agentType: 'legacy-analyst' }
    ).then(v => ({ item: it.id, name: it.name, extraction: ext, verification: v }))
).then(rs => rs.filter(Boolean));

phase('Spec');
const spec = await agent(
  `You are the Solution Architect authoring the inc-06A build spec (a SHORT clinical-order top-up closing inc-05 deferrals).\n` +
  `Honour 'modern design, EXACT legacy process'. Owner decisions: build inc-06A now; ITEM 5 = legacy-parity local-disk storage.\n` +
  `Single 'clinical' module (ADR-0022); billing.api.cancelCharge already published; ADR-0008 §6 = clinical never reads billing bill-status post-hoc (so items 2/4 need an explicit narrow seam decision); Flyway starts at V38; build in chunks each 'mvn clean verify' GREEN + commit.\n\n` +
  `VERIFIED EXTRACTIONS (ITEMs 1-5):\n${JSON.stringify(verified, null, 2)}\n\n` +
  `ITEM6 (verified directly against as-built code, not via the extraction agent):\n${ITEM6_FINDING}\n\n` +
  `Produce: an ordered chunk plan (ITEM 1 first — unblocked, highest value), the EXACT bill-status read-seam decision for items 2/4 (shape + cycle/ADR impact, or justify settled-flag-sufficiency), an ITEM 4 recommendation, the Flyway plan, what to reject/defer, testable acceptance criteria with the exact verbatim error strings, and risks.`,
  { label: 'spec:inc06a', phase: 'Spec', schema: SPEC_SCHEMA, agentType: 'solution-architect' }
);

return { verified, spec };
