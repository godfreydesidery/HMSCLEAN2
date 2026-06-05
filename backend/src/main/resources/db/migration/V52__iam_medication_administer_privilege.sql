-- =====================================================================================
-- Increment 07d — IAM seed delta: MEDICATION-ADMINISTER privilege (CR-07-MAR)
--
-- One new privilege gating the closed-loop medication-administration (MAR) create endpoint
-- (inc-07 07d, CR-07-MAR owner-APPROVED, prerequisites ruled 2026-06-05):
--
--   MEDICATION-ADMINISTER  — record a medication administration against a GIVEN prescription
--
-- NET-NEW — there is NO legacy MAR and NO legacy privilege for it. The owner ruled the standard
-- PHI/audit posture: AuditableEntity + SHA-256 AuditRecorder on CREATE, gated behind this single
-- new privilege (a create privilege, not an APPROVE — no second-approver gate on MAR).
--
-- ULID follows the V47 pattern: deterministic 26-char ULID-shaped placeholder continuing the
-- '01J0SEEDPRIV...' sequence ('...0017' is the next free value after V47's '...0016').
--
-- Granted to the ADMIN role via the role_privileges cross-join (same as V47).
-- =====================================================================================

INSERT INTO privileges (uid, code, created_at, version) VALUES
  ('01J0SEEDPRIV00000000000017', 'MEDICATION-ADMINISTER', now(), 0);

-- Grant the new privilege to the ADMIN role
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN privileges p
WHERE r.name = 'ADMIN'
  AND p.code = 'MEDICATION-ADMINISTER';
