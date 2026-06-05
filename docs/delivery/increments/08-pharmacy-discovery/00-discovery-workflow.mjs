export const meta = {
  name: 'inc08-pharmacy-discovery',
  description: 'Inc-08 discovery: extract legacy pharmacy/inventory/procurement behaviour and reconcile against the planning doc to find drift/phantom claims before any code',
  phases: [
    { title: 'Extract', detail: 'parallel legacy-analyst lanes over the legacy oracle (8 lanes) + 1 as-built lane' },
    { title: 'Reconcile', detail: 'adversarial doc-vs-legacy verdict per lane' },
    { title: 'Synthesize', detail: 'BA + solution-architect scope synthesis' },
  ],
}

// ---- shared constants -------------------------------------------------------
const LEGACY = 'D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api'
const PLANDOC = 'd:/My_Works/HMS/HMSCLEAN2/docs/delivery/increments/08-pharmacy-inventory-procurement.md'
const NEWROOT = 'd:/My_Works/HMS/HMSCLEAN2/backend/src/main/java/com/otapp/hmis'

const COMMON = `
You are extracting GROUND-TRUTH legacy behaviour from the Zana HMIS legacy codebase (Spring Boot 2.2.5).
The legacy code is the SPECIFICATION ORACLE — what it ACTUALLY does, not what any doc claims.
Legacy source root: ${LEGACY}
Read the actual .java files. EVERY finding MUST carry a file:line citation (relative to the legacy root or absolute).
Domain entities: ${LEGACY}/domain ; services: ${LEGACY}/service ; controllers: ${LEGACY}/api ; repos: ${LEGACY}/repositories.
Be precise about: state machines (every status value + every transition + its trigger method), guard/validation order,
whether a gate is a HARD service-layer reject vs a mere worklist FILTER, exactly WHEN stock-card/ledger rows are written
(which method, which transaction), numbering scheme (prefix + sequence source + MAX(id)+1 vs sequence), and arithmetic precision.
If the legacy does NOT implement something, say so explicitly ("NOT FOUND — searched X, Y") — a confirmed absence is a finding.
Do NOT speculate. Do NOT describe the new HMSCLEAN2 system. Output raw data, not prose for humans.`

const EXTRACT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['lane', 'summary', 'findings', 'absences'],
  properties: {
    lane: { type: 'string' },
    summary: { type: 'string', description: '3-5 sentence ground-truth summary of how this area actually works in legacy' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['topic', 'behaviour', 'cite'],
        properties: {
          topic: { type: 'string', description: 'e.g. "Prescription state machine", "FEFO ordering", "GRN doc number"' },
          behaviour: { type: 'string', description: 'exact behaviour observed in the legacy code' },
          cite: { type: 'string', description: 'file:line (or file:lineStart-lineEnd) proving it' },
        },
      },
    },
    absences: {
      type: 'array',
      description: 'things one might EXPECT but that are confirmed ABSENT in legacy (with where you searched)',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['expected', 'searched'],
        properties: { expected: { type: 'string' }, searched: { type: 'string' } },
      },
    },
  },
}

const RECONCILE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['lane', 'verdicts', 'newDriftFound'],
  properties: {
    lane: { type: 'string' },
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['planClaim', 'verdict', 'evidence'],
        properties: {
          planClaim: { type: 'string', description: 'the specific claim from the inc-08 planning doc being judged' },
          verdict: {
            type: 'string',
            enum: ['ACCURATE', 'ACCURATE_WITH_CORRECTIONS', 'DRIFT', 'PHANTOM', 'UNVERIFIABLE'],
            description: 'ACCURATE=matches legacy; ACCURATE_WITH_CORRECTIONS=right idea, details wrong; DRIFT=contradicts legacy; PHANTOM=describes something with no legacy basis; UNVERIFIABLE=lane evidence insufficient',
          },
          evidence: { type: 'string', description: 'the legacy cite(s) and reasoning that justify the verdict' },
          correction: { type: 'string', description: 'if not ACCURATE, what the planning doc SHOULD say' },
        },
      },
    },
    newDriftFound: {
      type: 'array',
      description: 'legacy behaviour the planning doc does NOT mention at all but that inc-08 must implement (or consciously skip)',
      items: { type: 'string' },
    },
  },
}

// The 8 extraction lanes, each paired with the planning-doc claims it must later be reconciled against.
const LANES = [
  {
    key: 'rx-lifecycle',
    extract: `LANE: Prescription dispensing lifecycle + CASH pay gate.
Trace how a doctor's prescription is dispensed in the pharmacy. Start at PharmacyServiceImpl.java and PharmacyResource.java / PharmacistResource.java.
Extract: the EXACT set of prescription/dispensing status values and every transition (accept/hold/verify/approve/sell/reject/cancel or whatever the legacy actually calls them), which method triggers each, and on which entity the status lives (is there a 'Prescription' aggregate in pharmacy at all, or is dispensing driven off the clinical/consultation Prescription + a separate sale record?).
CRITICAL: find the CASH pay gate. Is payment enforced as a HARD reject on the terminal dispense/sell, or merely as a worklist filter? At which exact method+line? Distinguish CASH vs insured/COVERED handling, and INPATIENT vs OUTPATIENT vs OUTSIDER. Find issuePharmacy vs salesPharmacy split if it exists.`,
    claims: `Planning doc claims to verify:
- Prescription status: PENDING → ACCEPTED → HELD → VERIFIED → APPROVED → SOLD; terminal REJECTED, CANCELLED. Per-line payStatus UNPAID → PAID.
- accept() callable on PENDING regardless of pay-status; sell() HARD-rejects unless every line payStatus=PAID; non-CASH treated as COVERED, sell() proceeds without pay check. "This is a hard service-layer gate, not a worklist filter."
- worklist defaults settledOnly=true for OUTPATIENT/OUTSIDER CASH; INPATIENT CASH visible at VERIFIED (insurer verifies at discharge).
- issuePharmacyUid vs salesPharmacyUid split per line; stock decrement hits salesPharmacyUid, dispense stamps issuePharmacyUid.
- PHARM-2/M3/M23: prior build allowed accept()/hold() on unpaid; only markSold had the gate. (Note legacy method names may be markSold/issued not sell.)`,
  },
  {
    key: 'otc-saleorder',
    extract: `LANE: OTC / OUTSIDER PharmacySaleOrder.
Read PharmacySaleOrder.java, PharmacySaleOrderDetail.java, PharmacySaleOrderModel.java, PharmacySaleOrderRepository.java and any service handling OTC sales (likely in PharmacyServiceImpl or PharmacistServiceImpl, and PharmacyResource/PharmacistResource endpoints).
Extract: the OTC sale-order status lifecycle (is it really "identical to Prescription"?), how a walk-in/OUTSIDER customer sale is created and dispensed, PharmacyCustomer handling, per-detail sell vs whole-order sell, and how stock is decremented for OTC. Cite endpoints.`,
    claims: `Planning doc claims to verify:
- PharmacySaleOrder (OTC/OUTSIDER, no doctor Rx required) + PharmacySaleOrderDetail with "identical status lifecycle to Prescription".
- Endpoints: POST /sale-orders, GET .../uid/{uid}, POST .../details, POST .../details/uid/{detailUid}/sell.
- Stock decremented via UI; OUTSIDER pharmacy sale order created/dispensed.`,
  },
  {
    key: 'fefo-stock',
    extract: `LANE: FEFO stock model, batches, stock cards, conversion coefficients, negative-stock guard.
Read PharmacyMedicine.java, PharmacyMedicineBatch.java, PharmacyStockCard.java, PharmacyMedicineBatchRepository.java, PharmacyStockCardRepository.java, and the decrement logic in PharmacyServiceImpl + transfer services.
Extract EXACTLY: how stock balance is tracked (a running balance field? derived? a StockBalance entity?), how batches are chosen on dispense (FEFO by expiry? is there expiry ordering / NULLS handling?), whether there is DB locking (SELECT FOR UPDATE / pessimistic) on the decrement and where, the negative-stock guard and its position relative to the lock, the stock-card row structure (what movement types exist, is runningBalance stored per row?), and unit conversion coefficients (ItemMedicineCoefficient-like? where multiplied? rounding?). Note: legacy likely has NO StockBalance entity (balance may be on PharmacyMedicine) and NO real pessimistic lock — report what is ACTUALLY there.`,
    claims: `Planning doc claims to verify:
- StockBatch (FEFO per (pharmacyUid,itemUid): batchNo, expiryDate nullable, receivedQty/remainingQty NUMERIC(19,6)).
- StockBalance entity (running balance per (pharmacyUid,itemUid); PESSIMISTIC_WRITE on decrement).
- StockMovement append-only ledger (RECEIPT, DISPENSE, TRANSFER_OUT, TRANSFER_IN, ADJUSTMENT, WASTAGE) carrying runningBalance.
- lockFefoForDispense acquires SELECT FOR UPDATE ordered expiry_date ASC NULLS LAST; negative-stock guard AFTER lock.
- ItemMedicineCoefficient NUMERIC(19,6); transferQty.multiply(coefficient) full BigDecimal precision, no intermediate rounding; 1/3 * 9 = 3.000000.
Report whether each ENTITY actually exists in legacy or is a modern re-modelling of legacy fields.`,
  },
  {
    key: 'p2p-transfers',
    extract: `LANE: Pharmacy-to-Pharmacy RO/TO/RN three-document transfer.
Read PharmacyToPharmacyRO/RODetail, PharmacyToPharmacyTO/TODetail, PharmacyToPharmacyRN/RNDetail, PharmacyToPharmacyBatch, and the three services PharmacyToPharmacyROServiceImpl, PharmacyToPharmacyTOServiceImpl, PharmacyToPharmacyRNServiceImpl.
Extract: the full state machine of EACH of the three documents, the request→order→note flow, which document the goods-issue happens on, and — CRITICAL — EXACTLY when stock-card / batch / balance rows are written on BOTH source and destination. Is it on RN complete, or on TO goods-issue, or on RO approve? Cite the exact method+line. Capture the document number prefixes used (PPR/PPTO/PPRN or legacy SPT?).`,
    claims: `Planning doc claims to verify:
- PharmacyToPharmacyRO/TO/RN three-document dance; StockMovement rows written ONLY on RN COMPLETED, in the same tx as RN.complete(); zero movement rows on source until RN COMPLETED.
- Doc numbers: PPR{yyyyMMdd}-{seq} (seq_ppr_no), PPTO{yyyyMMdd}-{seq} (seq_ptp_no, replaces legacy SPT), PPRN{yyyyMMdd}-{seq} (seq_pprn_no).
- Coefficient applied on cross-unit movement.
- ADR-0009 §6: PPTO must NOT use legacy SPT prefix.`,
  },
  {
    key: 'ps-sp-transfers',
    extract: `LANE: Pharmacy↔Store transfers (PharmacyToStoreRO, StoreToPharmacyTO, StoreToPharmacyRN).
Read PharmacyToStoreRO/RODetail + PharmacyToStoreROServiceImpl; StoreToPharmacyTO/TODetail + StoreToPharmacyTOServiceImpl; StoreToPharmacyRN/RNDetail + StoreToPharmacyRNServiceImpl; StoreToPharmacyBatch; StoreItem/StoreItemBatch/StoreStockCard.
Extract: the document flow pharmacy-requests → store-issues → pharmacy-receives, each document's states, exactly WHEN store stock decrements and pharmacy stock increments (which method/tx), and the document number prefixes (PSR / SPTO / PGRN vs legacy SPT). Note whether store stock uses the same StoreStockCard ledger discipline as pharmacy.`,
    claims: `Planning doc claims to verify:
- PharmacyToStoreRO + StoreToPharmacyRN on the pharmacy↔store side; StoreToPharmacyTO (store prepares shipment) in inventory module.
- Doc numbers: PSR{yyyyMMdd}-{seq} (seq_psr_no); SPTO{yyyyMMdd}-{seq} (seq_sto_no, replaces legacy SPT); PGRN{yyyyMMdd}-{seq} (seq_pgrn_no).
- StoreItem/StoreItemBatch + StoreStockBalance + StoreStockMovement ledger, same FEFO discipline.
- ADR-0009 §6: SPTO must NOT use legacy SPT.`,
  },
  {
    key: 'grn-lpo-procurement',
    extract: `LANE: GRN + LPO + three-way match + Supplier procurement.
Read GoodsReceivedNote/Detail/DetailBatch + GoodsReceivedNoteServiceImpl; LocalPurchaseOrder/Detail + LocalPurchaseOrderServiceImpl; Supplier/SupplierItemPrice/SupplierItemPriceList/ItemSupplier + their services; Purchase.java.
Extract: LPO state machine (DRAFT/PENDING/VERIFIED/APPROVED/SUBMITTED/RECEIVED/REJECTED/RETURNED — what are the REAL values+transitions?); GRN state machine; EXACTLY when GRN approval credits store stock (which method, is it one atomic @Transactional that writes StoreItemBatch + balance + stock-card + flips LPO to RECEIVED?); whether a THREE-WAY MATCH (LPO qty vs GRN qty vs supplier invoice qty) actually exists in legacy or is a modern addition; supplier price list structure. Cite GRN/LPO doc-number generation.`,
    claims: `Planning doc claims to verify:
- LPO: DRAFT→PENDING→VERIFIED→APPROVED→SUBMITTED→RECEIVED; terminal REJECTED, RETURNED.
- GRN: PENDING→VERIFIED→APPROVED; terminal REJECTED, RETURNED. Stock credited to StoreItemBatch + StoreStockBalance + StoreStockMovement(RECEIPT) + LPO→RECEIVED, all in ONE @Transactional on GRN.approve(). No async event boundary.
- Doc numbers: GRN{yyyyMMdd}-{seq} (seq_grn_no), LPO{yyyyMMdd}-{seq} (seq_lpo_no).
- ThreeWayMatch: LPO orderedQty == GRN receivedQty == SupplierInvoice qty per line; payment release gated on PASS. Supplier invoice qty input at GRN detail level.
FLAG explicitly whether three-way match has ANY legacy basis or is a phantom modern feature.`,
  },
  {
    key: 'numbering',
    extract: `LANE: Document numbering across ALL pharmacy/inventory/procurement document types.
Search every relevant service for how the document 'no' field is generated (PharmacyToPharmacy RO/TO/RN, PharmacyToStore RO, StoreToPharmacy TO/RN, GRN, LPO, PharmacySaleOrder).
For EACH document type extract: the literal prefix string, the date format if any, and the sequence source — is it a DB sequence, a dedicated counter table, or the legacy MAX(id)+1 / count()+1 race pattern? Cite the exact line that builds each number. CRITICAL: confirm whether the legacy uses the SPT prefix for BOTH PharmacyToPharmacyTO and StoreToPharmacyTO (the documented collision, legacy finding D).`,
    claims: `Planning doc + ADR-0009 claims to verify:
- Eight doc types each get {PREFIX}{yyyyMMdd EAT}-{seq} via a dedicated PostgreSQL sequence through DocumentNumberService.next(DocType), inside the insert tx (no double-save).
- Prefixes: GRN, LPO, PPR, PPTO, PPRN, PSR, SPTO, PGRN.
- Legacy finding C: legacy used MAX(id)+1 race. Legacy finding D: legacy SPT collides for both TO types.
Report the ACTUAL legacy prefix and sequence mechanism for each, and confirm/deny the SPT collision.`,
  },
  {
    key: 'rbac-scoping',
    extract: `LANE: RBAC / privilege gating + pharmacy session scoping.
Inspect the legacy controllers (PharmacyResource, PharmacistResource, GoodsReceivedNoteResource, LocalPurchaseOrderResource, StoreResource, StorePersonResource, SupplierResource, SupplierItemPriceResource) for authorization: are there @PreAuthorize / hasAuthority annotations on the pharmacy/procurement endpoints, and if so what privilege codes? Or are endpoints merely authenticated?
Also: how does the legacy know WHICH pharmacy a user operates? Is there a pharmacy_staff / Pharmacist→Pharmacy affiliation, a server-side session, or a pharmacyUid passed on each call? Cite the mechanism. This decides PHARM-1 (no server-side session scoping).`,
    claims: `Planning doc claims to verify:
- All pharmacy/procurement endpoints annotated with @PreAuthorize using codes from the 177-code set (PRESCRIPTION-ALL, GOODS_RECEIVED_NOTE-CREATE, LOCAL_PURCHASE_ORDER-ALL, etc.).
- PHARM-1: front-end passes pharmacyUid on every dispensing/stock/transfer call; backend validates the authenticated user is affiliated with the named pharmacy via an M:N pharmacy_staff table; NO server-side session state.
Note from inc-06 discovery: legacy clinical lifecycle endpoints carried NO @PreAuthorize and IAM had only 35 codes (not 177). Check whether pharmacy endpoints differ.`,
  },
]

// ---------------------------------------------------------------------------
log(`Inc-08 discovery: ${LANES.length} legacy extraction lanes + 1 as-built lane, each reconciled adversarially.`)

// As-built lane runs in parallel with the extraction lanes (independent of the legacy oracle).
phase('Extract')
const asBuiltPromise = agent(
  `Inventory the AS-BUILT HMSCLEAN2 codebase (increments 00-06, already merged to main) for anything relevant to inc-08 Pharmacy/Inventory/Procurement.
New backend root: ${NEWROOT}. The pharmacy and inventory modules are confirmed EMPTY STUBS (only package-info.java) — confirm that and DO NOT treat their absence as a gap.
What matters and MUST be reported with file:line cites:
1. The clinical Prescription aggregate that inc-08 depends on: read ${NEWROOT}/clinical/domain/Prescription.java, PrescriptionStatus.java, PrescriptionBatch.java, PatientPrescriptionChart.java. What status values does PrescriptionStatus actually have? Does it include ACCEPTED/HELD/VERIFIED/APPROVED/SOLD, or only the clinical-side states? Is there a per-line payStatus? What does the clinical module expose for pharmacy to read by uid (a clinical.api port)?
2. The billing seam inc-08 needs: does billing.api expose recordClinicalCharge() and a settled-flag / SettlementDispatcher pattern? Cite billing/api/*. Does a BillingQueries.getBillStatus read seam exist (it was added in inc-06A)?
3. DocumentNumberService: does it exist, what is its API, and which sequences/doc-types are already registered (check db/migration V13__masterdata_document_sequences.sql and V12)? Are any pharmacy/inventory sequences (seq_grn_no, seq_lpo_no, seq_ppr_no, etc.) already seeded?
4. Masterdata seeded for inc-08: are Pharmacy, Store, Medicine, Supplier, units/coefficients present (check V7__masterdata_inventory.sql)? Is there a pharmacy_staff affiliation table?
5. Shared kernel pieces inc-08 will reuse: AuditableEntity, Money, TxAuditContext, BusinessDay, ErrorCode enum (which codes exist — INSUFFICIENT_STOCK? STALE_ENTITY? PRESCRIPTION_UNPAID?).
6. The current max Flyway version (so inc-08 knows where to start).
Output raw structured data, not prose.`,
  {
    label: 'as-built:inc00-06',
    phase: 'Extract',
    agentType: 'legacy-analyst',
    schema: {
      type: 'object',
      additionalProperties: false,
      required: ['prescriptionAggregate', 'billingSeam', 'documentNumbering', 'masterdata', 'sharedKernel', 'maxFlyway', 'notes'],
      properties: {
        prescriptionAggregate: { type: 'string', description: 'PrescriptionStatus values, payStatus presence, clinical.api read surface — with cites' },
        billingSeam: { type: 'string', description: 'recordClinicalCharge, settled flag, SettlementDispatcher, BillingQueries.getBillStatus — with cites' },
        documentNumbering: { type: 'string', description: 'DocumentNumberService API + already-registered sequences/doctypes — with cites' },
        masterdata: { type: 'string', description: 'Pharmacy/Store/Medicine/Supplier/coefficient/pharmacy_staff seeding — with cites' },
        sharedKernel: { type: 'string', description: 'AuditableEntity/Money/TxAuditContext/BusinessDay + ErrorCode values present — with cites' },
        maxFlyway: { type: 'string', description: 'highest existing VNN migration' },
        notes: { type: 'array', items: { type: 'string' }, description: 'anything else relevant to inc-08 scoping' },
      },
    },
  },
).then(r => ({ asBuilt: r }))

// Pipeline: each legacy lane extracts, then immediately reconciles against its doc claims.
// No barrier — lane B reconciles while lane C is still extracting.
const laneResults = await pipeline(
  LANES,
  // Stage 1: extract ground truth from the legacy oracle.
  (lane) => agent(`${COMMON}\n\n${lane.extract}`, {
    label: `extract:${lane.key}`,
    phase: 'Extract',
    agentType: 'legacy-analyst',
    schema: EXTRACT_SCHEMA,
  }),
  // Stage 2: adversarial reconciliation of this lane's findings against the planning doc.
  (extracted, lane) => {
    if (!extracted) return null
    return agent(
      `You are a Business Analyst doing ADVERSARIAL reconciliation for the Zana HMIS inc-08 build.
The inc-08 planning doc is at ${PLANDOC} — read the relevant section yourself.
Below are GROUND-TRUTH legacy findings (with file:line cites) extracted for lane "${lane.key}".
Your job: judge each planning-doc claim against the legacy ground truth. Be skeptical and default to DRIFT/PHANTOM when the legacy evidence does not clearly support the claim.
Remember the inc-06 lesson: that planning doc was ~80% already-built-or-phantom (polymorphic ClinicalOrder, COMPLETED/CANCELLED states, LabResultLine, object-store attachments, 177 @PreAuthorize codes were all PHANTOM). Expect the SAME class of drift here — especially around entity re-modelling (StockBalance/StockMovement may be modern names for legacy fields on PharmacyMedicine/PharmacyStockCard), the "hard gate vs filter" distinction, three-way match, the 177-code RBAC claim, and pessimistic locking.
A modern re-modelling of a legacy field is ACCURATE_WITH_CORRECTIONS (not DRIFT) IF the behaviour is preserved — note it. A feature with NO legacy basis is PHANTOM. A claim that contradicts what the legacy actually does is DRIFT.

${lane.claims}

GROUND-TRUTH LEGACY FINDINGS for lane "${lane.key}":
${JSON.stringify(extracted, null, 2)}`,
      {
        label: `reconcile:${lane.key}`,
        phase: 'Reconcile',
        agentType: 'business-analyst',
        schema: RECONCILE_SCHEMA,
      },
    ).then(rec => ({ lane: lane.key, extract: extracted, reconcile: rec }))
  },
)

const asBuilt = await asBuiltPromise
const lanes = laneResults.filter(Boolean)

// Synthesis: solution-architect produces the inc-06-style RECONCILIATION-AND-SCOPE verdict.
phase('Synthesize')
const synthesis = await agent(
  `You are the Solution Architect synthesizing inc-08 (Pharmacy/Inventory/Procurement) discovery into a scope verdict, in the exact style of the inc-06 RECONCILIATION-AND-SCOPE doc.
You have: (a) an as-built inventory of the HMSCLEAN2 codebase (inc-00..06), and (b) ${lanes.length} legacy extraction+reconciliation lane results with file:line cites and per-claim verdicts.

Produce a structured verdict with these sections:
1. VERDICT headline (e.g. INC08_IS_A_REAL_FULL_BUILD vs INC08_IS_MOSTLY_DONE — based on the as-built: the pharmacy/inventory modules are empty stubs, so this is almost certainly a REAL full build, unlike inc-06; SAY SO and contrast with inc-06).
2. ALREADY BUILT (do NOT rebuild) — what inc-00..06 already provides that inc-08 reuses (clinical Prescription, billing seams, DocumentNumberService, masterdata, shared kernel).
3. GROUND-TRUTH LEGACY MODEL — the corrected, legacy-accurate description of how pharmacy/inventory ACTUALLY works (state machines, stock model, transfers, procurement, numbering), superseding the planning doc where they conflict. Cite legacy file:line.
4. PLANNING-DOC DRIFT (rejected/corrected) — every DRIFT and PHANTOM verdict from the lanes, consolidated. Be specific: which claimed entities/states/gates have no legacy basis or contradict it.
5. CONFIRMED-ACCURATE planning-doc claims — what survived reconciliation and should be built as written.
6. NEW DRIFT (legacy behaviour the doc omits) — things the doc fails to mention that inc-08 must implement or consciously skip.
7. RECOMMENDED SCOPE & SEQUENCE for the inc-08 build (and whether to split 08a dispensing/stock vs 08b store/procurement/transfers, per the build-plan risk note).
8. OPEN QUESTIONS FOR THE ENGAGEMENT OWNER — the decisions only the owner can make (esp. anything where reproducing legacy exactly conflicts with a modern ADR, like three-way match, pessimistic locking, RBAC codes, the SPT→SPTO/PPTO rename).

AS-BUILT INVENTORY:
${JSON.stringify(asBuilt.asBuilt, null, 2)}

LANE RESULTS (extract + reconcile):
${JSON.stringify(lanes, null, 2)}`,
  {
    label: 'synthesis:scope-verdict',
    phase: 'Synthesize',
    agentType: 'solution-architect',
    schema: {
      type: 'object',
      additionalProperties: false,
      required: ['verdict', 'alreadyBuilt', 'groundTruthModel', 'drift', 'confirmedAccurate', 'newDrift', 'recommendedScope', 'openQuestions'],
      properties: {
        verdict: { type: 'string' },
        alreadyBuilt: { type: 'array', items: { type: 'string' } },
        groundTruthModel: { type: 'string', description: 'the corrected legacy-accurate model, markdown, with cites' },
        drift: { type: 'array', items: { type: 'string' }, description: 'rejected/corrected planning-doc claims' },
        confirmedAccurate: { type: 'array', items: { type: 'string' } },
        newDrift: { type: 'array', items: { type: 'string' } },
        recommendedScope: { type: 'string', description: 'markdown: scope + 08a/08b split recommendation + sequence' },
        openQuestions: { type: 'array', items: { type: 'string' } },
      },
    },
  },
)

return { asBuilt: asBuilt.asBuilt, lanes, synthesis }
