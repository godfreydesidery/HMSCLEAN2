export const meta = {
  name: 'inc06-discovery',
  description: 'Inc-06 discovery + reconciliation: prove the genuine remaining scope vs the inc-05 as-built code and the (drifted) planning doc',
  phases: [
    { title: 'Inventory', detail: 'as-built inventory of inc-00..05 (our codebase)' },
    { title: 'Extract', detail: 'legacy extractions of lab/radiology/procedure/theatre + adjacent contexts' },
    { title: 'Verify', detail: 'adversarial verification of each legacy extraction' },
    { title: 'Reconcile', detail: 'built-vs-legacy gap analysis + planning-doc drift audit' },
    { title: 'Scope', detail: 'solution-architect scope recommendation for the real inc-06' },
  ],
}

// ---------------------------------------------------------------------------
// Grounding constants — accurate pointers verified by the main loop before this run.
// ---------------------------------------------------------------------------
const HMSCLEAN2 = 'd:/My_Works/HMS/HMSCLEAN2';
const LEGACY = 'D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api';
const LEGACY_NOTE = `LEGACY SOURCE OF TRUTH: ${LEGACY}
- Order/result lifecycle for lab/radiology/procedure lives INSIDE PatientResource.java (6455 lines, 411 lab/rad/proc refs).
- Type master-data + insurance-plan pricing: LabTestType*/RadiologyType*/ProcedureType* + *PlanResource.
- Theatre is ONLY a master-data type (TheatreResource.java) set on a procedure (model.setTheatre(...)); there is NO separate theatre-scheduling / operative-record / surgeon-approval subsystem.
- Attachments: LabTestAttachmentRepository, RadiologyAttachmentRepository.
Cite EXACT file:line for every claim. If something does NOT exist in legacy, say so explicitly ("ABSENT in legacy: <thing>").`;

const ASBUILT_NOTE = `OUR AS-BUILT CODEBASE: ${HMSCLEAN2}/backend (Spring Modulith, com.otapp.hmis).
inc-05 (Clinical/OPD) already shipped, on main. The clinical module ALREADY contains:
- LabTestController, RadiologyController, ProcedureController under /api/v1/clinical with FULL order lifecycle.
  Lab: order/accept/reject/collect/verify/hold/result/report/attachments(add,list,delete)/worklist/list.
  Radiology: order/accept/reject/verify/hold/result/attachments/worklist/list (ACCEPTED->VERIFIED direct, COLLECTED dead).
  Procedure: order/accept/note(->VERIFIED)/update/delete/worklist/list (states PENDING/ACCEPTED/REJECTED/VERIFIED, NO APPROVED).
- Domain entities LabTest/Radiology/Procedure with results as flat COLUMNS (NOT polymorphic ClinicalOrder; NO per-analyte LabResultLine).
- settled flag + ConsultationSettlementListener (billing BillSettledEvent -> flip local settled).
Inc-05 ratified docs: ${HMSCLEAN2}/docs/delivery/increments/05-clinical-discovery/ (esp. 11-DECISIONS-RATIFIED, 17-review-resolutions).
ADR-0022 governs the clinical module. Highest Flyway = V37.`;

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------
const INVENTORY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['context', 'builtCapabilities', 'endpoints', 'entities', 'notes'],
  properties: {
    context: { type: 'string' },
    builtCapabilities: { type: 'array', items: { type: 'string' },
      description: 'Discrete capabilities already shipped, each with the file that proves it.' },
    endpoints: { type: 'array', items: { type: 'string' },
      description: 'HTTP method + path already exposed.' },
    entities: { type: 'array', items: { type: 'string' },
      description: 'Domain entities/tables already built (name + key fields/states).' },
    notes: { type: 'string', description: 'Anything notable about HOW it was built (state names, deferrals).' },
  },
};

const EXTRACTION_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['context', 'capabilities', 'absentInLegacy', 'confidence'],
  properties: {
    context: { type: 'string' },
    capabilities: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['name', 'description', 'citation'],
        properties: {
          name: { type: 'string' },
          description: { type: 'string', description: 'Exact legacy behaviour: states, guards, numbering, math, messages.' },
          citation: { type: 'string', description: 'EXACT file:line(s).' },
        },
      },
    },
    absentInLegacy: { type: 'array', items: { type: 'string' },
      description: 'Things the planning doc claims but that are ABSENT in legacy (phantom features).' },
    confidence: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW'] },
  },
};

const VERIFY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['verdict', 'corrections', 'unverifiableClaims', 'summary'],
  properties: {
    verdict: { type: 'string', enum: ['ACCURATE', 'ACCURATE_WITH_CORRECTIONS', 'MATERIALLY_WRONG'] },
    corrections: { type: 'array', items: { type: 'string' },
      description: 'Specific corrections with the citation that disproves/sharpens the claim.' },
    unverifiableClaims: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  },
};

const RECONCILE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['alreadyBuilt', 'genuineGaps', 'planningDocDrift', 'recommendation'],
  properties: {
    alreadyBuilt: { type: 'array', items: { type: 'string' },
      description: 'Capabilities the inc-06 doc lists that are ALREADY shipped (with where).' },
    genuineGaps: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['gap', 'legacyCitation', 'belongsToContext', 'sizeEstimate'],
        properties: {
          gap: { type: 'string', description: 'A genuinely-missing capability that exists in legacy but is NOT yet built.' },
          legacyCitation: { type: 'string' },
          belongsToContext: { type: 'string', description: 'Which bounded context this really belongs to.' },
          sizeEstimate: { type: 'string', enum: ['SMALL', 'MEDIUM', 'LARGE'] },
        },
      },
    },
    planningDocDrift: { type: 'array', items: { type: 'string' },
      description: 'Each phantom/contradictory claim in the inc-06 planning doc, with why it is wrong.' },
    recommendation: { type: 'string' },
  },
};

const SCOPE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['verdict', 'recommendedScope', 'rejectedFromPlanningDoc', 'topologicalNote', 'openQuestionsForOwner', 'rationale'],
  properties: {
    verdict: { type: 'string', enum: ['INC06_IS_LRP_REMAINDER', 'INC06_SHOULD_BE_NEXT_CONTEXT', 'INC06_IS_MOSTLY_DONE_THIN_REMAINDER'] },
    recommendedScope: { type: 'array', items: { type: 'string' },
      description: 'The concrete, ordered scope items for the next increment.' },
    rejectedFromPlanningDoc: { type: 'array', items: { type: 'string' } },
    topologicalNote: { type: 'string', description: 'Where this sits in the build order and what it depends on.' },
    openQuestionsForOwner: { type: 'array', items: { type: 'string' },
      description: 'Decisions only the engagement owner can make.' },
    rationale: { type: 'string' },
  },
};

// ---------------------------------------------------------------------------
// Phase 1 — as-built inventory (parallel; our codebase)
// ---------------------------------------------------------------------------
phase('Inventory');
const inventoryTargets = [
  { ctx: 'Laboratory (as-built in clinical)', hint: 'Read LabTestController/LabTest domain/repository; enumerate every endpoint, state, result/attachment capability.' },
  { ctx: 'Radiology (as-built in clinical)', hint: 'Read RadiologyController/Radiology domain/repository; enumerate endpoints, states, attachment/result capability.' },
  { ctx: 'Procedure+Theatre (as-built in clinical)', hint: 'Read ProcedureController/Procedure domain; enumerate endpoints, states, theatreUid handling, note->VERIFIED.' },
  { ctx: 'Attachments + settlement (as-built)', hint: 'How are attachments stored (bytea? filename?)? How does settled flip? Is there any generic attachment table? Read clinical domain + ConsultationSettlementListener.' },
  { ctx: 'Cross-increment build state', hint: 'Read docs/delivery/build-plan.md + increment specs + each increment discovery folder; list which of the 14 bounded contexts are BUILT vs NOT (Pharmacy, Inventory, HR/Payroll, Assets, Reporting, Inpatient/Admission, etc.).' },
];
const inventory = await parallel(inventoryTargets.map(t => () =>
  agent(
    `You are inventorying the EXISTING HMSCLEAN2 build. ${ASBUILT_NOTE}\n\n` +
    `TASK: Inventory the as-built state of: ${t.ctx}.\n${t.hint}\n\n` +
    `Be exhaustive and precise — cite the file that proves each capability. ` +
    `This inventory is used to avoid re-building or duplicating what inc-05 already shipped.`,
    { label: `inventory:${t.ctx}`, phase: 'Inventory', schema: INVENTORY_SCHEMA, agentType: 'Explore' }
  )
)).then(rs => rs.filter(Boolean));

// ---------------------------------------------------------------------------
// Phases 2+3 — legacy extraction -> adversarial verify, PIPELINED per context.
// Each context's extraction is verified the moment it finishes (no barrier).
// ---------------------------------------------------------------------------
phase('Extract');
const extractionTargets = [
  { ctx: 'Legacy Laboratory order/result lifecycle',
    hint: 'In PatientResource.java: lab test ordering, accept/reject/collect/verify/hold, result+report entry, worklist queue (incl. PatientResource.java:3668-3717), attachments (LabTestAttachmentRepository), any batching, any document numbering. EXACT states + guards + verbatim messages.' },
  { ctx: 'Legacy Radiology order/result lifecycle',
    hint: 'In PatientResource.java: radiology ordering, accept/reject/verify/hold, result/report, worklist, attachments (RadiologyAttachmentRepository), the dead collect_radiology111. EXACT states + guards + messages.' },
  { ctx: 'Legacy Procedure + Theatre',
    hint: 'In PatientResource.java + ProcedureType*/TheatreResource: procedure ordering, accept, add_note->verify, the bill gate, theatre assignment (model.setTheatre). Confirm whether APPROVED/operative-record/surgeon-role exist (expected ABSENT). EXACT states + guards.' },
  { ctx: 'Legacy department-side EXTRAS not in OPD ordering',
    hint: 'Anything in lab/radiology/procedure that is a DEPARTMENT action distinct from the doctor OPD order flow inc-05 built: e.g. dedicated technician worklists/queues, result-amendment, batch/bulk actions, lab-machine interface, report printing, per-day rollover, status reports. Cite file:line; mark ABSENT where the doc invents it.' },
  { ctx: 'Legacy type master-data + insurance-plan pricing for L/R/P',
    hint: 'LabTestType/Range/Plan, RadiologyType/Plan, ProcedureType/Plan, Theatre resources+services. Confirm what inc-02 master-data ALREADY covers vs any per-plan pricing nuance not yet built. Cite file:line.' },
  { ctx: 'Legacy build-order: what truly comes next',
    hint: 'Survey the legacy resources NOT yet built (Pharmacy, Inventory/Procurement, HR/Payroll, Assets, Reporting, Inpatient/Admission/Ward nursing). For each, one line on its scale + its dependencies, to inform what the next REAL increment should be. Cite the resource files.' },
];

const reconcileInputs = await pipeline(
  extractionTargets,
  // Stage 1: extract from legacy
  (t) => agent(
    `You are a Legacy Systems Analyst (read-only process archaeologist). ${LEGACY_NOTE}\n\n` +
    `TASK: Extract the EXACT legacy behaviour for: ${t.ctx}.\n${t.hint}\n\n` +
    `Output exact states, guards, numbering schemes, billing math, and VERBATIM user-facing messages, each with file:line. ` +
    `Crucially: list anything the inc-06 planning doc claims but that is ABSENT in legacy.`,
    { label: `extract:${t.ctx}`, phase: 'Extract', schema: EXTRACTION_SCHEMA, agentType: 'legacy-analyst' }
  ),
  // Stage 2: adversarial verify (fires per-context as soon as its extraction lands)
  (extraction, t) => extraction == null ? null :
    agent(
      `You are an adversarial verifier. ${LEGACY_NOTE}\n\n` +
      `Another analyst produced this extraction for "${t.ctx}". Try to REFUTE it. ` +
      `Open the cited files, check each file:line, and flag any claim that is wrong, imprecise, or unverifiable. ` +
      `Pay special attention to ABSENCE claims (phantom features) — confirm they really are absent. ` +
      `Default to skepticism.\n\nEXTRACTION:\n${JSON.stringify(extraction, null, 2)}`,
      { label: `verify:${t.ctx}`, phase: 'Verify', schema: VERIFY_SCHEMA, agentType: 'legacy-analyst' }
    ).then(verification => ({ context: t.ctx, extraction, verification }))
).then(rs => rs.filter(Boolean));

// ---------------------------------------------------------------------------
// Phase 4 — reconciliation (barrier: needs the FULL inventory + ALL verified extractions)
// ---------------------------------------------------------------------------
phase('Reconcile');
const reconciliation = await agent(
  `You are the Business Analyst reconciling the inc-06 planning doc against reality.\n\n` +
  `THE INC-06 PLANNING DOC (KNOWN TO BE HEAVILY DRIFTED) claims: polymorphic ClinicalOrder w/ kind discriminator; ` +
  `Lab/Rad PENDING->ACCEPTED->COMPLETED; Procedure PENDING->APPROVED(surgeon/anaesthetist)->COMPLETED w/ required OperativeRecord; ` +
  `per-analyte LabResultLine + server-side reference-range flag computation; LabBatch grouping + seq_lab_batch_no; ` +
  `generic ADR-0015 attachment table + StoragePort/MinIO/S3 + ClamAV; 177 @PreAuthorize privilege codes; theatre scheduling subsystem.\n\n` +
  `AS-BUILT INVENTORY (what inc-00..05 already shipped):\n${JSON.stringify(inventory, null, 2)}\n\n` +
  `VERIFIED LEGACY EXTRACTIONS:\n${JSON.stringify(reconcileInputs, null, 2)}\n\n` +
  `TASK: Produce the authoritative reconciliation. For EVERY inc-06 doc claim decide: (a) ALREADY BUILT in inc-05 ` +
  `(under legacy-accurate names), (b) a GENUINE GAP that exists in legacy but is not yet built, or (c) PLANNING-DOC DRIFT ` +
  `(phantom/contradicts verified legacy). Be specific and cite. The genuineGaps list is the most important output.`,
  { label: 'reconcile:gap-analysis', phase: 'Reconcile', schema: RECONCILE_SCHEMA, agentType: 'business-analyst' }
);

// ---------------------------------------------------------------------------
// Phase 5 — scope recommendation (solution-architect synthesis)
// ---------------------------------------------------------------------------
phase('Scope');
const scope = await agent(
  `You are the Solution Architect deciding the TRUE scope of the next increment ("inc-06").\n\n` +
  `Context: inc-05 already shipped the full lab/radiology/procedure OPD order+result+attachment+worklist loops under ` +
  `legacy-accurate state names. The inc-06 planning doc is heavily drifted. We honour "modern design, EXACT legacy process".\n\n` +
  `RECONCILIATION:\n${JSON.stringify(reconciliation, null, 2)}\n\n` +
  `LEGACY BUILD-ORDER SURVEY + EXTRACTIONS:\n${JSON.stringify(reconcileInputs.map(r => ({ context: r.context, absentInLegacy: r.extraction?.absentInLegacy, capabilities: r.extraction?.capabilities?.map(c => c.name) })), null, 2)}\n\n` +
  `DECIDE: Is there enough genuine L/R/P/Theatre remainder to justify an inc-06, or is that work essentially DONE and the ` +
  `next increment should be the next un-built bounded context (and which one, topologically)? Give a concrete ordered scope, ` +
  `what to REJECT from the planning doc, the topological note, and the open questions only the engagement owner can answer.`,
  { label: 'scope:recommendation', phase: 'Scope', schema: SCOPE_SCHEMA, agentType: 'solution-architect' }
);

return {
  inventory,
  extractions: reconcileInputs,
  reconciliation,
  scope,
};
