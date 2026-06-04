# Inc-06A 3-Lens Review — Resolutions

**Date:** 2026-06-04. Workflow `wf_b20e9da7-966` (code-reviewer + qa-test-engineer + security-architect, each adversarially self-verified). Raw: [11-inc06a-review-raw.json](11-inc06a-review-raw.json).
**Verdicts:** code APPROVE_WITH_GAPS · qa REQUEST_CHANGES · security APPROVE_WITH_GAPS (no BLOCKER).
**Resolution build:** `mvn clean verify` GREEN — 63 surefire + 618 failsafe, 0 failures.

The adversarial verifier pass filtered ~23 raw findings: it rejected the out-of-scope/pre-existing ones (qa F-07 COVERED/VERIFIED seam coverage; security SEC-06A-06 before/after audit, SEC-06A-07 BOLA/IDOR pre-existing, SEC-06A-08 non-issue).

## Production-code fixes applied (all GREEN)

| Finding | Severity | Fix |
|---|---|---|
| **F1 / SEC-02 / F-03** | MAJOR | The 10 MiB in-code cap was unreachable — Spring's default multipart resolver caps at 1 MB, so 1–10 MiB uploads were rejected with the wrong error before the controller. Added `spring.servlet.multipart.max-file-size: 11MB` / `max-request-size: 12MB` (above the 10 MiB app cap) so the in-code gate is the binding limit and returns the verbatim 422. New IT uploads 2 MiB → 201 (proves the resolver no longer pre-empts). |
| **SEC-01** | MAJOR | Inline download of uploaded bytes = stored-XSS on the app origin. New `AttachmentDownloadSupport`: only PDF/png/jpeg/gif render `inline`; everything else → `application/octet-stream` + `Content-Disposition: attachment`; always `X-Content-Type-Options: nosniff`. Both controllers route through it. |
| **F2 / SEC-04** | MINOR | Upload guard order diverged from legacy (size-first, status-before-count). Reordered both services to legacy: existence(404) → count==5 → status → size; corrected the javadoc. New IT asserts the status message fires on a non-COLLECTED order. |
| **SEC-03** | MINOR | Path-traversal 422 reflected the raw attacker filename. Removed the filename from the message; unit test asserts a distinctive token is not echoed. |
| **F3 / SEC-05** | MINOR | Download VERIFIED-gate is a NET-NEW PHI-safety control, not legacy parity (legacy download is ungated; 6021/6154 is the DELETE gate). Corrected `canDownloadAttachment` javadocs in LabTest/Radiology to mark it a ratified deviation with the correct citation. |
| **F4** | MINOR | C2 cancel-cascade javadoc mis-cited 434-494 (the change_type endpoint). Corrected: legacy cancel_consultation cancels only its own bill; the child cascade is NET-NEW/spec-mandated (ITEM6), matching the free-path effect (no credit note when no RECEIVED payment). |

## Test coverage added
- **F1**: lab 2 MiB upload → 201 (resolver-limit proof).
- **F2**: lab upload on non-COLLECTED → 422 status message (guard-order proof).
- **F-04**: assert `reportAmendedOnDayUid` in lab + radiology amend tests.
- **F-05**: radiology upload over-cap → 422 (`AttachmentUploadCapIT`).
- **F-06**: new `LocalDiskFileStorageTest` (plain unit) — store/read round-trip, dir auto-create, read-missing→404, path-traversal rejected (5 vectors) + no-filename-reflection, blank rejected, best-effort delete.

## Documented (not separately tested — mechanism already covered)
- **F-01** (radiology/procedure delete → credit-note assertions) and **F-02** (credit-note reference label on a paid cascade child): the credit-note + reference-label mechanism is proven end-to-end by the **C1** positive test (`delete_paidPendingLabTest_reversesBill_refunds_raisesCreditNote`) and `cancelCharge` is type-uniform across lab/radiology/procedure (same `BillingCommands.cancelCharge(billUid, ref, ctx)` call). The C2 cascade test proves the cascade fires and respects settled-vs-unsettled. Per-type duplication of the identical loop is low-signal; recorded here rather than near-duplicated.
- **F-08** (cascade covers radiology/procedure/prescription, not just lab): same rationale — `cancelUnsettledChildOrders` iterates all four repositories with identical logic; the lab leg is tested.
- **F5/F6/F7** (NITs): nanoTime+CREATE_NEW collision (theoretical, ownerUid+nanoTime collision negligible); disk-write-before-row-save orphan on rollback (best-effort delete + low probability; an orphaned file is harmless); synthetic JSON addAttachment endpoint sharing multipart messages (acceptable). Left as-is.

## Out of scope (verifier-rejected / deferred)
- **SEC-06A-07** BOLA/IDOR on download (no patient-scope authz) — PRE-EXISTING across clinical reads, not introduced by C7; folds into the deferred CR-INC05-03 audit/authz CR.
- **SEC-06A-06** before/after audit values for the new mutations — folds into the same audit-classification CR.
- **qa F-07** BillingQueries COVERED/VERIFIED direct assertion — low-value (the seam is a trivial passthrough; UNPAID path tested).
