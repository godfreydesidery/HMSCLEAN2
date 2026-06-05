export const meta = {
  name: 'inc08a-buildspec',
  description: 'Draft the FREEZABLE portion of the inc-08a build spec (the items the ratified decisions cleared with no owner gate), with every acceptance criterion anchored to a legacy cite + the ratified decision, and every owner-gated item explicitly marked BLOCKED',
  phases: [
    { title: 'Draft', detail: 'specialists draft the freezable 08a spec sections in parallel' },
    { title: 'Check', detail: 'solution-architect + code-reviewer verify no smuggled deviation; blocked items correctly marked' },
    { title: 'Assemble', detail: 'engagement-lead assembles the final freezable build spec' },
  ],
}

const DISC = 'd:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/08-pharmacy-discovery'
const RECON = `${DISC}/01-RECONCILIATION-AND-SCOPE.md`
const DECISIONS = `${DISC}/03-DECISIONS-RATIFIED.md`
const RAW = `${DISC}/00-discovery-raw.json`
const PLANDOC = 'd:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/08-pharmacy-inventory-procurement.md'
const NEWROOT = 'd:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis'

const GROUND = `
You are drafting part of the inc-08a (pharmacy dispensing + stock core) build spec for the Zana HMIS rebuild.
BINDING INPUTS (read them):
- Ratified decisions: ${DECISIONS} (THE authority on what is in-baseline vs parked-as-CR vs owner-gated).
- Reconciliation & ground-truth legacy model: ${RECON}.
- Raw discovery (lane extracts with legacy file:line cites): ${RAW} — quote these cites in acceptance criteria.
- The original planning doc: ${PLANDOC} — treat as DRIFTED; only build what the ratified decisions confirm.
- As-built HMSCLEAN2 code root: ${NEWROOT} (clinical Prescription, billing::api seams, shared kernel, shared.documentnumber [just promoted in Q11], masterdata schemas). The pharmacy/inventory modules are empty stubs.

CHARTER: "modern design, exact process". Reproduce verified legacy behaviour VERBATIM in the baseline. NEVER write a deviation into the baseline — those are parked as CRs (named in the decisions doc).
HARD RULES from the ratified decisions you MUST honour:
- Q1: dispense pay enforcement is a WORKLIST FILTER ONLY (PAID|COVERED for OUT/OUTSIDER; +VERIFIED for INPATIENT). The dispense terminal has NO bill-status check. Do NOT spec a hard dispense gate (owner-gated; if ever elected it is SettlementPolicy local-flag, never BillingQueries.getBillStatus).
- Q2: pharmacyUid is a REQUIRED, server-VALIDATED per-call param used ONLY to select the stock source; NO user-affiliation check, NO pharmacy_staff table.
- Q4 (stock locking) + Q8 (FEFO NULLS-LAST): BLOCKED pending ADR-0017 ratification + HDE sign-off. Baseline reproduces the legacy null-expiry EXCLUSION; pin id-ASC tiebreak. Mark the lock + NULLS-LAST as BLOCKED, do not freeze them.
- Q5: reuse ONLY the 35 seeded privilege codes; reproduce COARSE gating verbatim; deny-by-default closure is approved hardening (labelled net-new, not a parity assertion). NEVER cite "177".
- Q9 OTC: flat Medicine.price*qty, paymentType=CASH literal, GENERAL dummy patient; verbatim PSO/{n}, PCST{n}, PCST/{year}/{n} format strings but suffix BACKED BY A DEDICATED SEQUENCE not the raw PK (CR-09-NUM1 gates this — mark it). No FEFO on OTC (CR-08-FEFO-ON-OTC parked).
- A new clinical::api published prescription read+dispense port is a PREREQUISITE (PrescriptionPort is intra-module today).
Output structured data only.`

const SECTION_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['section', 'overview', 'acceptanceCriteria', 'blockedItems', 'cites'],
  properties: {
    section: { type: 'string' },
    overview: { type: 'string', description: 'markdown: what this slice builds, the legacy-accurate behaviour' },
    acceptanceCriteria: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['id', 'criterion', 'legacyCite', 'decisionRef', 'frozen'],
        properties: {
          id: { type: 'string', description: 'e.g. AC-RX-01' },
          criterion: { type: 'string', description: 'a testable Given/When/Then or assertion' },
          legacyCite: { type: 'string', description: 'the legacy file:line that proves the expected behaviour, or "n/a — modern data-type only"' },
          decisionRef: { type: 'string', description: 'which ratified Q-decision authorizes this (e.g. Q1, Q8-baseline)' },
          frozen: { type: 'boolean', description: 'true = freezable now; false = BLOCKED pending an owner/HDE/ADR gate' },
        },
      },
    },
    blockedItems: { type: 'array', items: { type: 'string' }, description: 'items in this section that CANNOT freeze + which gate blocks them' },
    cites: { type: 'array', items: { type: 'string' }, description: 'key legacy file:line cites used' },
  },
}

// Freezable 08a sections, each drafted by the right specialist.
const SECTIONS = [
  { key: 'prereq-clinical-api', agent: 'solution-architect',
    prompt: `SECTION: Prerequisite — new clinical::api published prescription read + dispense port.
Spec the cross-module seam pharmacy needs: read a prescription by uid, a pharmacy worklist contract (the FILTER), and a dispense/decrement seam — mirroring the billing::api / SettlementDispatcher pattern (no reverse edge, no cycle; add allowed edge pharmacy -> clinical::api). The as-built PrescriptionPort is intra-module (clinical.application); clinical::api today exposes only consultation contracts. Define the published interface(s), the DTOs that must NOT leak entities, and the Spring Modulith declaration. Note the as-built PrescriptionRepository worklist comment that deliberately omits the settled filter — reconcile it with the legacy FILTER behaviour (flag to BA).` },
  { key: 'rx-dispense', agent: 'backend-engineer',
    prompt: `SECTION: Clinical prescription dispense (08a core).
Spec the NOT-GIVEN->GIVEN dispense: guards in legacy order (exists; status==NOT-GIVEN; issued valid; issued==qty all-or-nothing), set issuePharmacy, hard negative-stock guard, decrement aggregate stock, write a stock-card OUT row, FEFO batch consumption + PrescriptionBatch lot-trace rows. Pharmacy worklist as the FILTER (Q1). Route medicine pricing through billing::api.recordClinicalCharge(kind=MEDICINE). The pessimistic LOCK and FEFO NULLS-LAST are BLOCKED (Q4/Q8) — spec the baseline as legacy null-expiry EXCLUSION + id-ASC tiebreak, and mark the lock/NULLS-LAST acceptance criteria frozen:false. Reuse ErrorCode (PAY_BEFORE_SERVICE exists; INSUFFICIENT_STOCK to add). NO terminal bill-status check.` },
  { key: 'stock-model', agent: 'data-architect',
    prompt: `SECTION: Pharmacy stock model + schema (V39+).
Spec the PostgreSQL schema + JPA design for the pharmacy aggregate stock (PharmacyMedicine-equivalent scalar balance), StockBatch (FEFO; receivedQty/remainingQty NUMERIC(19,6); expiryDate nullable), and the StockMovement/stock-card ledger (append-only; movement-type enum mapped onto the legacy free-text reference strings; runningBalance snapshot). Per the ratified D16 correction this is a re-model of the legacy single-ledger + scalar-stock — get data-architect sign-off framing and a parity note (single-ledger + scalar semantics preserved). NO separate StockBalance behaviour beyond the scalar. Coefficient NUMERIC(19,6). Opening-stock eager creation (N15) — spec reproduce vs note. Pin: migrations start at V39; doc sequences already in V13. Include indexes for the FEFO query (expiry ASC, id ASC) and the worklist.` },
  { key: 'otc-saleorder', agent: 'backend-engineer',
    prompt: `SECTION: OTC PharmacySaleOrder lifecycle (08a).
Spec the walk-in lifecycle: header PENDING->APPROVED->ARCHIVED (+PENDING->CANCELED); PENDING->APPROVED is a side effect of paying the linked PatientBill (no standalone approve); detail has TWO fields (fulfilment status + payStatus); dispense hard-gates on header==APPROVED only; flat Medicine.price*qty pricing, paymentType=CASH literal (incoming ignored), GENERAL dummy patient; 24h auto-cancel/auto-archive sweeps; aggregate decrement + stock-card OUT, NO FEFO/batch consumption (CR-08-FEFO-ON-OTC parked). Numbering: verbatim PSO/{n}, PCST{n}, PCST/{year}/{n} format strings — but the suffix MUST be backed by a dedicated sequence (seq_pso_no/seq_pcst_no, new V39+), NOT the raw PK; this is CR-09-NUM1 (ADR-0014 §1 forbids exposing id) so mark those acceptance criteria frozen:false (BLOCKED pending CR-09-NUM1 approval). The all-CASH ADR-0010 reporting consequence must be stated.` },
  { key: 'rbac-scoping', agent: 'security-architect',
    prompt: `SECTION: RBAC + pharmacy scoping for 08a endpoints.
Spec the @PreAuthorize mapping reusing ONLY the 35 seeded privilege codes, reproducing COARSE legacy gating verbatim (pharmacy admin ADMIN-ACCESS + MEDICINE_STOCK-UPDATE; note the legacy OTC flow was UNGATED — decide the deny-by-default minimum). Deny-by-default closure (authenticated() minimum on every endpoint) is approved ADR-0006 hardening — label it net-new, NOT a 403-on-wrong-role parity assertion on formerly-ungated flows. pharmacyUid is REQUIRED + server-validated to resolve, used only to select stock source, NO affiliation check (Q2). NEVER cite "177" — the truth is 35 codes. PRESCRIPTION-ALL is a never-gated catalogue string; the dispense-gate code is an OPEN item tied to Q1. Per-transition SoD is parked (CR-08-SoD).` },
]

log(`Drafting ${SECTIONS.length} freezable inc-08a build-spec sections.`)

const drafted = await pipeline(
  SECTIONS,
  (s) => agent(`${GROUND}\n\n${s.prompt}`, { label: `draft:${s.key}`, phase: 'Draft', agentType: s.agent, schema: SECTION_SCHEMA }),
  (section, s) => {
    if (!section) return null
    return agent(
      `${GROUND}\n\nYou are the code-reviewer auditing a drafted inc-08a build-spec section "${s.key}" for charter compliance. Be adversarial.\nCheck every acceptance criterion:\n1. Does it SMUGGLE a deviation into the frozen baseline? (a hard dispense gate, FEFO-on-OTC, NULLS-LAST as frozen, pessimistic lock as frozen, pharmacy_staff, server-authoritative RO numbers, per-transition SoD, OTC PK-suffix exposure, "177" codes — ALL of these must be either absent or marked frozen:false with the correct gate.)\n2. Is every frozen:true criterion backed by a real legacy cite (or a legitimate modern data-type change) AND authorized by a ratified decision?\n3. Are the BLOCKED items (Q4 lock, Q8 NULLS-LAST, CR-09-NUM1 OTC suffix) correctly marked frozen:false?\nReturn UPHELD or REVISE with specific corrections.\n\nDRAFTED SECTION:\n${JSON.stringify(section, null, 2)}`,
      { label: `check:${s.key}`, phase: 'Check', agentType: 'code-reviewer',
        schema: { type: 'object', additionalProperties: false, required: ['section','verdict','reason','corrections'],
          properties: { section:{type:'string'}, verdict:{type:'string',enum:['UPHELD','REVISE']}, reason:{type:'string'}, corrections:{type:'array',items:{type:'string'}} } } },
    ).then(chk => ({ key: s.key, section, check: chk }))
  },
)

const sections = drafted.filter(Boolean)

phase('Assemble')
const spec = await agent(
  `${GROUND}\n\nYou are the engagement-lead assembling the FINAL freezable inc-08a build spec from the drafted + code-reviewer-checked sections. Apply every REVISE correction before assembling.\nProduce a single coherent build-spec document body (markdown) with: a scope statement (what 08a freezes now vs what is BLOCKED and by which gate), the prerequisite (clinical::api port + Q11 shared DocumentNumberService already done), each section's overview + a numbered acceptance-criteria table (mark BLOCKED criteria clearly), a consolidated BLOCKED-UNTIL list (the 4 owner/HDE/ADR gates), the V39+ migration list, and a Definition-of-Done for the freezable slice. Anchor acceptance criteria to legacy cites. Be precise that this is the EXACT-PROCESS baseline; every parked CR is named.\n\nCHECKED SECTIONS:\n${JSON.stringify(sections, null, 2)}`,
  { label: 'assemble:08a-spec', phase: 'Assemble', agentType: 'engagement-lead',
    schema: { type: 'object', additionalProperties: false,
      required: ['title','scope','specBody','blockedUntil','migrations','definitionOfDone','readyToFreeze'],
      properties: {
        title: { type: 'string' },
        scope: { type: 'string' },
        specBody: { type: 'string', description: 'the full markdown build-spec body' },
        blockedUntil: { type: 'array', items: { type: 'string' } },
        migrations: { type: 'array', items: { type: 'string' } },
        definitionOfDone: { type: 'array', items: { type: 'string' } },
        readyToFreeze: { type: 'string', description: 'which sections freeze now vs wait' },
      } } },
)

return { sections, spec }
