export const meta = {
  name: 'inc08-decisions',
  description: 'Resolve the 11 inc-08 open questions against the ratified charter + ADRs; adversarially check no decision contradicts a ratified ADR or smuggles an un-CR-ed deviation into the exact-process baseline',
  phases: [
    { title: 'Resolve', detail: 'one specialist decides each of the 11 questions against charter + ADRs' },
    { title: 'Challenge', detail: 'adversarial review: does any decision contradict a ratified ADR or hide a deviation?' },
    { title: 'Ratify', detail: 'engagement-lead consolidates into a DECISIONS-RATIFIED register' },
  ],
}

const DOCS = 'd:/My_Works/HMS/HMSCLEAN2/docs'
const RECON = `${DOCS}/delivery/increments/08-pharmacy-discovery/01-RECONCILIATION-AND-SCOPE.md`
const RAW = `${DOCS}/delivery/increments/08-pharmacy-discovery/00-discovery-raw.json`

const CHARTER = `
ENGAGEMENT CHARTER (binding, from project memory + build-plan.md):
- "MODERN DESIGN, EXACT PROCESS." The business PROCESS must stay identical to the legacy Zana HMIS.
  Data types and data model MAY modernize (double->BigDecimal pre-approved; ULID uid; Flyway; ProblemDetail).
- A deliberate DEVIATION from observed legacy behaviour requires a written, APPROVED change request (CR) BEFORE implementation.
  Deviations are NOT silently built into the exact-process baseline.
- RATIFIED ADRs ALREADY decide some questions. An ADR decision is binding and is NOT re-litigated here:
  you must CHECK each question against the actual ADR text and, if an ADR already decides it, the answer is "build per ADR-XXXX" (cite the section).
- Golden-master parity is the merge gate: the new system must reproduce legacy business RESULTS (money compared at 2dp).
- The inc-06 lesson: do NOT reproduce phantom/idealized features that have no legacy basis; and do NOT confuse a worklist FILTER with a hard service GATE.

KEY RATIFIED ADR FACTS (verified by reading the ADR files):
- ADR-0017 §2 (Concurrency): RATIFIED that stock decrement uses @Lock(PESSIMISTIC_WRITE) on stock_balance + lockFefoForDispense
  (FEFO batches in expiry-ascending order, NULL EXPIRY LAST), negative-stock guard AFTER the lock. Framed as "preserve the legacy
  negative-stock REFUSAL process, make it race-safe -- a correctness improvement, not a behaviour change." => Q4 and Q8 are
  LARGELY PRE-DECIDED by this ADR (pessimistic lock = yes; NULLS LAST = yes, which OVERRIDES legacy's silent null-expiry exclusion).
- ADR-0017 §1: @Version optimistic lock is the default for non-stock entity updates; stale write -> 409 STALE_ENTITY.
- ADR-0009 §5: MAX(id)+1 replaced by dedicated PG SEQUENCE per doc type; format PREFIX{yyyyMMdd EAT}-{seq} reproduced verbatim;
  single-insert (no legacy double-save). ADR-0009 §6: SPT collision -> SPTO (StoreToPharmacy TO) + PPTO (PharmacyToPharmacy TO),
  RATIFIED, but "product-owner sign-off on SPTO and PPTO is a release gate before the prefix Flyway seed migration." (CR-10 already
  seeded SPTO/PPTO in V14 -- so the migration side is done; the PO release-gate sign-off is an engagement-owner action.)
- REAL DRIFT FOUND: ADR-0009's sequence-name table says seq_sto_no / seq_ptp_no for the two TO types, but the AS-BUILT V13 migration
  seeded seq_spto_no / seq_ppto_no. The migrations already ran; the as-built names win. ADR-0009 must be corrected (doc fix), not the DB.
`

const QUESTIONS = [
  { id: 'Q1', topic: 'Dispense hard-gate vs worklist filter', agent: 'healthcare-domain-expert',
    text: `Legacy clinical issue_medicine has NO terminal bill-status check -- payment is enforced ONLY as a worklist FILTER (PatientResource.java:4347,4364,4381,4410), and the terminal is bypassable by direct call. The planning doc claims a HARD service-layer sell() gate (D2). ADRs are SILENT on this specific gate. Decide: reproduce filter-only enforcement (exact-process), or add a hard dispense gate via BillingQueries.getBillStatus (a security/integrity DEVIATION needing a CR)? Consider patient-safety and financial-integrity, but the charter default is reproduce-legacy + CR-the-improvement. State clinical impact of each path.` },
  { id: 'Q2', topic: 'Pharmacy session scoping / pharmacy_staff', agent: 'security-architect',
    text: `Legacy has NO pharmacist->pharmacy affiliation, NO server-side scoping; the operating pharmacy is client-supplied per call (PHARM-1 confirmed accurate). The planning doc's pharmacy_staff M:N table enforced as a write-time 403 gate "seeded in I01" is PHANTOM (D13 -- no such table, no check). Decide: reproduce client-trust (exact-process), or introduce a net-new pharmacy_staff affiliation 403 gate (deviation -> CR)? If CR: which increment owns the schema/seed, and is it acceptable that pharmacy would become stricter than stores (which use only a soft filter)? Note legacy default-open posture (N18) is a separate security hardening item.` },
  { id: 'Q3', topic: 'Three-way match', agent: 'business-analyst',
    text: `ThreeWayMatch (LPO=GRN=SupplierInvoice qty, payment-release gate, /three-way-match/validate endpoint) is confirmed PHANTOM (D11): zero legacy basis, no SupplierInvoice entity, no invoice-qty field, no payment-release gate. Legacy reconciliation is two-way (receivedQty<=orderedQty) + batch-sum==receivedQty. Decide: drop three-way match from inc-08 entirely (exact-process: build only the real two-way + batch-sum reconciliation), or raise it as a NEW-FEATURE CR for the owner (net-new SupplierInvoice + payment-release workflow)? Charter default: drop from baseline, park as a candidate CR.` },
  { id: 'Q4', topic: 'Pessimistic locking on stock decrement', agent: 'solution-architect',
    text: `Legacy stock decrement is a check-then-act race with ZERO locks. The planning doc/ADR-0017 §2 mandate PESSIMISTIC_WRITE + lockFefoForDispense. CHECK: is this already RATIFIED by ADR-0017? If yes, the decision is "build per ADR-0017 §2" (NOT an open question, NOT a new CR) -- confirm and cite. Confirm the @Version-on-transitions + pessimistic-on-decrement split, and that ADR-0017 already justifies it as exact-process-preserving (same refusal behaviour, race-safe).` },
  { id: 'Q5', topic: 'RBAC granularity + 177-vs-35 figure', agent: 'security-architect',
    text: `Legacy uses single coarse codes per document family (LOCAL_PURCHASE_ORDER-ALL, GOODS_RECEIVED_NOTE-ALL), NO segregation of duties, and leaves whole flows UNGATED (entire OTC flow, GRN list, all Supplier endpoints) with no anyRequest().authenticated() (D12, J, N18). The "177-code" figure is the inc-06 phantom recurring (ratified IAM record = 35 distinct codes; 177 = @PreAuthorize SITES). Decide: (a) reproduce coarse gating verbatim (exact-process), or (b) introduce granular per-transition SoD codes + close the default-open posture (deviation -> CR). Re-derive the true code count from the I01 fixture before any acceptance criteria cite a number. Confirm whether PRESCRIPTION-ALL even exists. Charter default: reproduce coarse gating; closing default-open is an approved SECURITY hardening (cite that the legacy posture is a vulnerability, not a process), but per-transition SoD is a CR.` },
  { id: 'Q6', topic: 'SPT->SPTO/PPTO sign-off + sequence-name fix', agent: 'solution-architect',
    text: `ADR-0009 §6 RATIFIES SPT->SPTO/PPTO, gated on a PRODUCT-OWNER release sign-off. CR-10 already seeded SPTO/PPTO in V14 (md_document_types). Decide: confirm this is "build per ADR-0009 §6" with the PO sign-off being an ENGAGEMENT-OWNER action (flag it, don't decide it for them). ALSO ratify the REAL drift fix: ADR-0009's sequence-name table (seq_sto_no/seq_ptp_no) conflicts with the as-built V13 (seq_spto_no/seq_ppto_no) -- the as-built names win; ADR-0009 must be corrected (doc-only fix). Specify the exact ADR-0009 edit.` },
  { id: 'Q7', topic: 'p2p destination batch creation', agent: 'healthcare-domain-expert',
    text: `Legacy NEVER creates destination PharmacyMedicineBatch rows on pharmacy<->pharmacy RN (commented out, PharmacyToPharmacyRNServiceImpl.java:221-232) -- transferred stock loses batch/expiry traceability at the destination. Contrast: the pharmacy<->STORE RN DOES create destination batches. Decide: reproduce the gap (exact-process: no destination batches on p2p, stock increments without lot/expiry), or fix it (create destination batches like the store path -- a clinical-safety DEVIATION needing a CR)? State the clinical risk of the gap (expiry tracking lost on inter-pharmacy moves).` },
  { id: 'Q8', topic: 'FEFO null-expiry semantics', agent: 'healthcare-domain-expert',
    text: `Legacy SILENTLY EXCLUDES null-expiry lots whenever any dated lot exists (they can be stranded forever) and uses lowest-id as secondary sort. ADR-0017 §2 says lockFefoForDispense orders "expiry ascending, NULL EXPIRY LAST" -- which would DISPENSE null-expiry lots after dated ones (the opposite of legacy exclusion). CHECK: does ADR-0017 already decide this? If yes, "build per ADR-0017" (NULLS LAST) is the ratified answer and it OVERRIDES the legacy exclusion -- confirm this is an intended, ADR-sanctioned fix and note it must be disclosed as a behaviour change (a stranded-lot gets dispensed). Also pin the secondary sort to id ASC. Decide FEFO-on-OTC separately: legacy OTC NEVER consumed batches (deductBatch commented out) -- so OTC FEFO is a DEVIATION -> CR (do not apply the unified FEFO to OTC in the baseline).` },
  { id: 'Q9', topic: 'OTC numbering + pricing', agent: 'business-analyst',
    text: `Legacy OTC: order no='PSO/'+id (no date, no Formater); customer no has TWO divergent formats ('PCST'+id vs 'PCST/'+year+'/'+id); pricing is flat Medicine.price*qty with paymentType HARDCODED CASH (incoming value ignored); bills hang off a GENERAL dummy patient. Decide: keep PSO/PCST formats and flat-CASH pricing verbatim (exact-process), or normalize numbering / honor real payment mode (deviations -> CR)? Note the consequence: any "OTC revenue by payment mode" report is all-CASH unless the hardcode is changed via CR (this is BILL-5, already flagged as a NEW gap). Charter default: reproduce verbatim; park normalization as CRs.` },
  { id: 'Q10', topic: 'PPR/PSR request-order numbering', agent: 'solution-architect',
    text: `Legacy RO numbers (PPR pharmacy<->pharmacy RO, PSR pharmacy<->store RO) are CLIENT-SUPPLIED (frontend previews via request_no and posts back; server never setNo). The other 6 doc types (TO/RN/GRN/LPO) are server-assigned. ADR-0009 §5 describes server-side single-insert numbering generally. Decide: move PPR/PSR to server-authoritative DocumentNumberService.next() inside the insert tx (a behaviour change to the request contract, but aligns all 8 types and removes a client-trust surface -- is this within ADR-0009's "make numbering concurrency-safe" mandate or a deviation needing a CR?), or preserve the preview-then-supply contract verbatim? Weigh: client-supplied numbers reintroduce the duplicate-number risk ADR-0009 set out to kill.` },
  { id: 'Q11', topic: 'DocumentNumberService ownership', agent: 'solution-architect',
    text: `DocumentType enum + DocumentNumberService currently live in billing.application with only the PCN constant. inc-08 needs 8 more doc types. If they stay billing-owned, the inventory module gets an inventory->billing dependency just for numbering. Decide: promote DocumentType/DocumentNumberService to the SHARED KERNEL (recommended -- numbering is a cross-cutting concern, avoids inventory->billing coupling), or extend the billing-owned enum? This is a pure architecture decision (no legacy-process impact). Specify the module the service should live in and the Spring Modulith implications. Author a one-paragraph ADR-addendum recommendation.` },
]

log(`Resolving ${QUESTIONS.length} inc-08 open questions against the ratified charter + ADRs.`)

const RESOLVE_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['id', 'decision', 'classification', 'rationale', 'adrCitation', 'crNeeded', 'buildImpact', 'ownerActionRequired'],
  properties: {
    id: { type: 'string' },
    decision: { type: 'string', description: 'the concrete resolution -- what inc-08 will actually do' },
    classification: { type: 'string', enum: ['ADR_PRE_DECIDED', 'EXACT_PROCESS_REPRODUCE', 'APPROVED_HARDENING', 'DEVIATION_NEEDS_CR', 'OWNER_RELEASE_GATE', 'ARCHITECTURE_DECISION'],
      description: 'how this question resolves under the charter' },
    rationale: { type: 'string' },
    adrCitation: { type: 'string', description: 'the ADR section that decides/governs this, or "none -- ADRs silent"' },
    crNeeded: { type: 'string', description: 'if a deviation: the one-line CR title to raise; else "none"' },
    buildImpact: { type: 'string', description: 'what 08a/08b must build (or NOT build) as a result' },
    ownerActionRequired: { type: 'string', description: 'any action only the engagement/product owner can take (sign-off, scope approval); else "none"' },
  },
}

// Resolve -> Challenge, pipelined (each decision is challenged as soon as it is made).
const resolved = await pipeline(
  QUESTIONS,
  (q) => agent(
    `${CHARTER}\n\nYou are the ${q.agent} resolving inc-08 open question ${q.id} (${q.topic}).\nRead the reconciliation doc at ${RECON} (and ${RAW} if you need the raw cites) for the full context of this question.\n\nQUESTION ${q.id}:\n${q.text}\n\nResolve it. Apply the charter strictly: if a ratified ADR already decides it, your answer is "build per ADR-XXXX §Y" (classification ADR_PRE_DECIDED) -- do not re-open it. If it is a tempting improvement with no legacy basis or that contradicts legacy, the baseline answer is reproduce-legacy and the improvement becomes a DEVIATION_NEEDS_CR (or APPROVED_HARDENING only if it fixes a security vulnerability the charter would not call "process"). If only the owner can sign it off, classify OWNER_RELEASE_GATE and do NOT decide it for them. Be concrete about what 08a/08b build or must NOT build.`,
    { label: `resolve:${q.id}`, phase: 'Resolve', agentType: q.agent, schema: RESOLVE_SCHEMA },
  ),
  (decision, q) => {
    if (!decision) return null
    return agent(
      `${CHARTER}\n\nYou are the code-reviewer / tech-lead acting as an ADVERSARIAL challenger on an inc-08 decision. Be skeptical.\nA specialist resolved open question ${q.id} (${q.topic}) as follows:\n${JSON.stringify(decision, null, 2)}\n\nChallenge it on THREE axes:\n1. Does the decision CONTRADICT a ratified ADR? (If ADR_PRE_DECIDED, verify the ADR actually says what they claim -- the reconciliation found one case where ADR-0009's sequence names were WRONG vs the as-built migration, so do not trust ADR text blindly; cross-check against the as-built where relevant.)\n2. Does the decision SMUGGLE A DEVIATION into the exact-process baseline without a CR? (e.g. building a hard gate where legacy only filtered, applying FEFO to OTC, creating destination batches, adding SoD codes -- all of these MUST be CR'd, not baseline.)\n3. Is the classification correct, and is any required owner action correctly flagged (not silently decided for the owner)?\nReturn a verdict: UPHELD (decision is charter-compliant) or REVISE (with the specific correction). Default to REVISE if the decision builds anything beyond verified legacy behaviour without a CR, or mis-cites an ADR.`,
      { label: `challenge:${q.id}`, phase: 'Challenge', agentType: 'code-reviewer',
        schema: {
          type: 'object', additionalProperties: false,
          required: ['id', 'verdict', 'reason', 'correction'],
          properties: {
            id: { type: 'string' },
            verdict: { type: 'string', enum: ['UPHELD', 'REVISE'] },
            reason: { type: 'string' },
            correction: { type: 'string', description: 'if REVISE: the specific charter-compliant correction; else "none"' },
          },
        },
      },
    ).then(ch => ({ id: q.id, topic: q.topic, decision, challenge: ch }))
  },
)

const items = resolved.filter(Boolean)

phase('Ratify')
const register = await agent(
  `${CHARTER}\n\nYou are the engagement-lead consolidating the resolved + adversarially-challenged inc-08 open questions into a DECISIONS-RATIFIED register.\nFor each item: if the challenge verdict was UPHELD, ratify the decision; if REVISE, apply the challenger's correction and ratify the corrected decision. Produce a clean, owner-facing register.\nAlso produce: (a) a consolidated list of CRs to raise (every DEVIATION_NEEDS_CR), (b) a consolidated list of OWNER ACTIONS REQUIRED (release gates / scope approvals only the owner can give), (c) the net effect on the 08a/08b build scope (what is now IN the exact-process baseline, what is parked as a CR), and (d) any ADR corrections to make (e.g. the ADR-0009 sequence-name fix).\n\nRESOLVED + CHALLENGED ITEMS:\n${JSON.stringify(items, null, 2)}`,
  { label: 'ratify:register', phase: 'Ratify', agentType: 'engagement-lead',
    schema: {
      type: 'object', additionalProperties: false,
      required: ['ratifiedDecisions', 'changeRequests', 'ownerActionsRequired', 'baselineScopeEffect', 'adrCorrections', 'readyToFreezeBuildSpec'],
      properties: {
        ratifiedDecisions: { type: 'array', items: {
          type: 'object', additionalProperties: false,
          required: ['id', 'topic', 'ratifiedDecision', 'classification'],
          properties: { id: {type:'string'}, topic:{type:'string'}, ratifiedDecision:{type:'string'}, classification:{type:'string'} },
        } },
        changeRequests: { type: 'array', items: { type: 'string' }, description: 'CR titles to raise (parked, not in baseline)' },
        ownerActionsRequired: { type: 'array', items: { type: 'string' } },
        baselineScopeEffect: { type: 'string', description: 'markdown: what is IN the 08a/08b exact-process baseline vs parked as CR' },
        adrCorrections: { type: 'array', items: { type: 'string' } },
        readyToFreezeBuildSpec: { type: 'string', description: 'YES/NO + what (if anything) still blocks freezing the 08a build spec' },
      },
    },
  },
)

return { items, register }
