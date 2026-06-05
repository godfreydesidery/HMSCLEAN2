-- =====================================================================================
-- Increment 07a-3 — IAM seed delta: disposition APPROVE privileges (CR-07-SoD)
--
-- Three new privileges gating the second-approver endpoints for the three inpatient
-- disposition workflows (inc-07 07a-3, CR-07-SoD owner-APPROVED):
--
--   DISCHARGE-PLAN-APPROVE  — approve the discharge plan endpoint
--   REFERRAL-PLAN-APPROVE   — approve the admission referral plan endpoint
--   DECEASED-NOTE-APPROVE   — approve the admission deceased note endpoint
--
-- These survive the legacy purge loop: APPROVE is a canonical operation suffix
-- (matching GOODS_RECEIVED_NOTE-APPROVE already seeded in V2).
--
-- ULIDs follow the V2 pattern: deterministic 26-char ULID-shaped placeholders.
-- The pattern '01J0SEEDPRIV00000000000014' continues the V2 sequence.
--
-- All three are granted to the ADMIN role via the role_privileges cross-join.
--
-- Legacy citations:
--   CR-07-SoD (owner-APPROVED, inc-07): net-new SoD gate — legacy had no APPROVE privilege
--   for inpatient dispositions; single-actor approved their own notes.
--   PatientResource.java:5342-5390 (discharge), :5593-5685 (referral), :5837-5934 (deceased).
-- =====================================================================================

INSERT INTO privileges (uid, code, created_at, version) VALUES
  ('01J0SEEDPRIV00000000000014', 'DISCHARGE-PLAN-APPROVE', now(), 0),
  ('01J0SEEDPRIV00000000000015', 'REFERRAL-PLAN-APPROVE',  now(), 0),
  ('01J0SEEDPRIV00000000000016', 'DECEASED-NOTE-APPROVE',  now(), 0);

-- Grant all three new privileges to the ADMIN role
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN privileges p
WHERE r.name = 'ADMIN'
  AND p.code IN ('DISCHARGE-PLAN-APPROVE', 'REFERRAL-PLAN-APPROVE', 'DECEASED-NOTE-APPROVE');
