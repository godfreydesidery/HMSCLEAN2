export const meta = {
  name: 'inc06a-review',
  description: 'Inc-06A 3-lens adversarial review: code-reviewer + qa-test-engineer + security-architect over the diff, each verdict adversarially self-checked',
  phases: [
    { title: 'Review', detail: 'three independent lenses over the inc-06A diff' },
  ],
}

const ROOT = 'd:/My_Works/HMS/HMSCLEAN2';
const DIFF = `${ROOT}/docs/delivery/increments/06-lrp-discovery/10-inc06a-diff.patch`;
const SPEC = `${ROOT}/docs/delivery/increments/06-lrp-discovery/03-INC06A-BUILD-SPEC.md`;
const LEGACY = 'D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api';

const CONTEXT = `INC-06A is a 7-chunk clinical-order top-up closing inc-05 deferrals (Zana HMIS modernization, "modern design, EXACT legacy process"). mvn clean verify is GREEN (615 failsafe, 0 failures).
The diff to review: ${DIFF} (backend only). Build spec + ratified decisions: ${SPEC}. As-built code root: ${ROOT}/backend. Legacy source of truth: ${LEGACY} (order lifecycle in PatientResource.java + PatientServiceImpl.java).
THE 7 CHUNKS:
- C1 ITEM1: L/R/P delete -> billing.api.cancelCharge (soft-cancel bill + RECEIVED->credit-note; refs "Canceled lab test/radiology/procedure"); delete-guard messages -> legacy verbatim "Could not delete, only a PENDING <x> can be deleted". CR-10 j=j++ bug NOT reproduced (ratified). Order row still hard-deleted.
- C2 ITEM6: consultation cancel() now cascades cancelCharge to unsettled child-order bills (lab/rad/proc/prescription), ref "Canceled consultation"; refactored shared cancelUnsettledChildOrders(consultation, reference, ctx).
- C3 ITEM3: save_reason_for_rejection (lab+rad) — re-callable rejectComment edit, guard status==REJECTED else 422 "Could not save. Only allowed for rejected tests"; HTTP 422 (as-built convention, ratified deviation from legacy 409).
- C4: NEW billing.api.BillingQueries.getBillStatus(billUid)->BillStatus read seam (impl pkg-private in billing.application). ADR-0008 §6 NARROWLY relaxed (addendum committed) for the add_report bill-gate ONLY. No module cycle (ModularityTest GREEN).
- C5 ITEM2: radiology stand-alone bill-gated add_report (NEW) + lab add_report corrected to the bill-status gate {PAID,COVERED,VERIFIED} (was COLLECTED order-status); verbatim "Could not add report. Payment not verified". Reads LIVE bill status via C4 (the order-time settled flag is insufficient). add_report blocks VERIFIED-order overwrite (routes to C6 amend).
- C6 ITEM4: post-VERIFIED audited-amend path (ratified: NOT legacy silent overwrite). Flyway V38 adds prior_report + report_amended_by/on/at to lab_tests+radiologies. amendReport retains prior narrative + stamps amend triplet; guard status==VERIFIED + bill-gate. Clinical stays authentication-only (the spec's "privilege-gated" note = deferred net-new RBAC, NOT built — flag if you disagree).
- C7 ITEM5: legacy-parity LOCAL-DISK attachment storage. shared.storage FileStoragePort + LocalDiskFileStorage (path-traversal hardening, CREATE_NEW, temp-dir fallback, best-effort unlink-on-delete) + AttachmentStorageProperties (hmis.attachments). Multipart upload + inline download endpoints (lab+rad); upload cap 10485760 -> 422 "File exceeds maximum file size allowed"; download VERIFIED-gate -> 422 "Could not download. <x> is not verified". Generated opaque filename (legacy patientNo-scheme NOT reproduced — would cycle clinical->registration; documented). delete-block message -> verbatim "Could not delete. <Lab Test|Radiology> already verified". Radiology add-gate messages aligned to lab verbatim.
KNOWN/RATIFIED DEVIATIONS (do NOT re-flag as bugs, but DO confirm they're sound): soft-flag bill cancel (not legacy hard-delete); CR-10 fix; HTTP 422 not 409; opaque filename (no patientNo); path-traversal hardening; unlink-on-delete; ADR-0008 §6 add_report relaxation; audited-amend (not legacy overwrite); authentication-only (no new RBAC).`;

const FINDINGS_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['lens', 'verdict', 'findings', 'summary'],
  properties: {
    lens: { type: 'string' },
    verdict: { type: 'string', enum: ['APPROVE', 'APPROVE_WITH_NITS', 'APPROVE_WITH_GAPS', 'REQUEST_CHANGES'] },
    findings: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['id', 'severity', 'title', 'detail', 'location', 'inScope', 'recommendation'],
        properties: {
          id: { type: 'string' },
          severity: { type: 'string', enum: ['BLOCKER', 'MAJOR', 'MINOR', 'NIT'] },
          title: { type: 'string' },
          detail: { type: 'string', description: 'What is wrong and why; cite legacy file:line or as-built file:line.' },
          location: { type: 'string', description: 'file:line in the as-built code.' },
          inScope: { type: 'boolean', description: 'true if this is an inc-06A change (fix now); false if pre-existing/other-increment.' },
          recommendation: { type: 'string' },
        },
      },
    },
    summary: { type: 'string' },
  },
};

const VERIFY_SCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['confirmedFindings', 'rejectedFindings', 'note'],
  properties: {
    confirmedFindings: { type: 'array', items: { type: 'string' }, description: 'finding ids that survive adversarial re-check (genuinely real + in scope).' },
    rejectedFindings: { type: 'array', items: { type: 'string' }, description: 'finding ids refuted (false positive / out of scope / already handled) + why.' },
    note: { type: 'string' },
  },
};

phase('Review');

const lenses = [
  { key: 'code', agent: 'code-reviewer',
    prompt: 'Review for correctness bugs, exact-process drift (esp. VERBATIM error-message fidelity vs legacy — this is golden-master parity), transaction/atomicity correctness (cancelCharge runs in-tx before delete; settlement seam), Modulith boundary integrity, the C4 read-seam shape, and the hand-written mapper field-order correctness (LabTestDto/RadiologyDto vs mapper). Confirm the ratified deviations are implemented soundly.' },
  { key: 'qa', agent: 'qa-test-engineer',
    prompt: 'Review TEST coverage + behaviour-parity. For each chunk, is the happy path AND the guard/422 path tested with the EXACT verbatim message? Gaps: cancelCharge credit-note assertions, the cascade settled-vs-unsettled split, amend prior-narrative retention, the download VERIFIED-gate, the cap, the bill-status-seam being live-not-stale. Flag any assertion that uses is4xx instead of the exact status/detail. Note test-isolation risks (singleton Testcontainer commits).' },
  { key: 'security', agent: 'security-architect',
    prompt: 'Review for PHI/security: the new clinical->billing read seam (is the §6 relaxation truly scoped?); attachment path-traversal hardening (is LocalDiskFileStorage actually safe — string check + startsWith + CREATE_NEW? any bypass?); the download endpoint VERIFIED-gate (SEC-04 — does it actually prevent unverified PHI image leak?); multipart size cap as DoS control; filename injection in Content-Disposition; audit coverage of the new mutations (amend, upload, delete, cancelCharge). Authentication-only model — acceptable or a gap?' },
];

const results = await pipeline(
  lenses,
  (l) => agent(
    `You are the ${l.agent}. ${CONTEXT}\n\nYOUR LENS: ${l.prompt}\n\nRead the diff file and open the cited as-built + legacy files as needed. Be specific, cite file:line, and mark each finding inScope (an inc-06A change to fix now) vs out-of-scope. Default to skepticism but do not invent issues.`,
    { label: `review:${l.key}`, phase: 'Review', schema: FINDINGS_SCHEMA, agentType: l.agent }
  ),
  // adversarial self-verify: a second pass tries to refute this lens's own findings
  (review, l) => review == null ? null :
    agent(
      `You are an adversarial verifier (independent of the ${l.agent}). ${CONTEXT}\n\n` +
      `Another reviewer produced these findings. For EACH, open the cited files and decide if it genuinely survives scrutiny (real, in-scope, not already handled by a ratified deviation or an existing test). REJECT false positives, out-of-scope items, and anything the build spec already ratified. Default to rejecting unless the finding is clearly real.\n\nFINDINGS:\n${JSON.stringify(review, null, 2)}`,
      { label: `verify:${l.key}`, phase: 'Review', schema: VERIFY_SCHEMA, agentType: l.agent }
    ).then(v => ({ lens: l.key, review, verification: v }))
).then(rs => rs.filter(Boolean));

return { results };
