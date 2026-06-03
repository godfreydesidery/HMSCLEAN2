-- =====================================================================================
-- Zana HMIS — Increment 01 backfill admin identity row (07-DECISIONS-RATIFIED §B, D-8)
--
-- The admin user was seeded in V2 before the identity columns existed. This migration:
--   1. Sets user_no / first_name / last_name / nickname on the admin row.
--   2. Aligns seq_usr_no past the seeded admin (sequence value = 1 so next call returns 2).
--
-- TODO(main-loop): optional cost-12 re-hash of admin seed
--   The hash below is the BCrypt cost-10 hash of "password" from V2. To upgrade to cost-12
--   generate: new BCryptPasswordEncoder(12).encode("password") and replace the placeholder.
--   The UPDATE is guarded by the exact old hash so it is idempotent once rotated.
--   The dev password "password" is already public in V2; nothing new leaks.
--
-- UPDATE users
-- SET password_hash = '$2a$12$REPLACE_WITH_REAL_COST12_HASH'
-- WHERE username = 'admin'
--   AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
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
