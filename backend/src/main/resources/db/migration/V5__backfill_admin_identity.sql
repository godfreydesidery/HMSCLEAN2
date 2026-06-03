-- =====================================================================================
-- Zana HMIS — Increment 01 backfill admin identity row (07-DECISIONS-RATIFIED §B, D-8)
--
-- The admin user was seeded in V2 before the identity columns existed. This migration:
--   1. Sets user_no / first_name / last_name / nickname on the admin row.
--   2. Aligns seq_usr_no past the seeded admin (sequence value = 1 so next call returns 2).
--   3. Re-hashes the cost-10 admin password to cost-12 (D-8, ratified 2026-06-03).
--
-- D-8: BCrypt cost-12 hash of the documented dev password "password".
--   Generated with: new BCryptPasswordEncoder(12).encode("password")
--   The UPDATE is guarded by the exact old cost-10 hash so it is idempotent once applied.
--   The dev password "password" is already public in V2; nothing new leaks.
-- =====================================================================================

-- Backfill identity columns for the pre-existing admin row.
UPDATE users
SET user_no    = 'USR-000-001',
    first_name = 'System',
    last_name  = 'Administrator',
    nickname   = 'admin'
WHERE username = 'admin'
  AND user_no IS NULL;

-- Align the sequence: admin consumed slot 1, so the next user gets slot 2.
SELECT setval('seq_usr_no', 1, true);

-- D-8: Upgrade admin password hash from BCrypt cost-10 to cost-12.
-- Guard: only runs when the cost-10 hash from V2 is still present (idempotent).
UPDATE users
SET password_hash = '$2a$12$RUemFJKvxaT8U3252xOWCu052ViiiVwjEBLGNiCGhc.FGH9fnq8EG'
WHERE username = 'admin'
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
