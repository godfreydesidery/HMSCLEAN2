-- =====================================================================================
-- Zana HMIS — Increment 00 IAM seed (ADR-0006, ADR-0013)
--
-- Privilege codes extracted verbatim from the legacy @PreAuthorize gates at
--   D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api
-- by grepping hasAnyAuthority('...') (active AND commented gates) and de-duplicating.
-- The commented-out junk token ("dfgh") in the legacy SecurityConfig is excluded; every
-- real privilege code is seeded. This is the complete distinct legacy authorization set
-- (35 codes); the "177" figure in the spec counts annotation sites, not distinct codes.
--
-- One ADMIN role is granted ALL seeded privileges. One admin user is seeded.
--
-- Tables are PLURAL (DIRECTIVE 2): privileges / roles / users / role_privileges / user_roles.
--
-- DEV ADMIN CREDENTIALS (documented default — change in every real environment):
--   username: admin
--   password: password
-- The stored hash below is a BCrypt hash of "password". Production seeds a per-env
-- hash via a follow-on migration / admin bootstrap; never ship "password" beyond dev.
-- =====================================================================================

-- Helper note: created_at/version are NOT NULL; uid is a deterministic 26-char ULID-shaped
-- placeholder per seeded row (Crockford base32, lexicographically valid). Application-created
-- rows use real monotonic ULIDs; these static seed uids only need to be unique CHAR(26).

INSERT INTO privileges (uid, code, created_at, version) VALUES
  ('01J0SEEDPRIV00000000000001', 'ADMIN-ACCESS',                now(), 0),
  ('01J0SEEDPRIV00000000000002', 'DAY-ACCESS',                  now(), 0),
  ('01J0SEEDPRIV00000000000003', 'EMPLOYEE-ALL',                now(), 0),
  ('01J0SEEDPRIV00000000000004', 'PAYROLL-ALL',                 now(), 0),
  ('01J0SEEDPRIV00000000000005', 'PAYROLL-CREATE',              now(), 0),
  ('01J0SEEDPRIV00000000000006', 'PAYROLL-UPDATE',              now(), 0),
  ('01J0SEEDPRIV00000000000007', 'ROLE-ALL',                    now(), 0),
  ('01J0SEEDPRIV00000000000008', 'ROLE-CREATE',                 now(), 0),
  ('01J0SEEDPRIV00000000000009', 'ROLE-U',                      now(), 0),
  ('01J0SEEDPRIV0000000000000A', 'USER-ALL',                    now(), 0),
  ('01J0SEEDPRIV0000000000000B', 'USER-UPDATE',                 now(), 0),
  ('01J0SEEDPRIV0000000000000C', 'GOO-ALL',                     now(), 0),
  ('01J0SEEDPRIV0000000000000D', 'GOODS_RECEIVED_NOTE-ALL',     now(), 0),
  ('01J0SEEDPRIV0000000000000E', 'GOODS_RECEIVED_NOTE-CREATE',  now(), 0),
  ('01J0SEEDPRIV0000000000000F', 'GOODS_RECEIVED_NOTE-UPDATE',  now(), 0),
  ('01J0SEEDPRIV0000000000000G', 'GOODS_RECEIVED_NOTE-APPROVE', now(), 0),
  ('01J0SEEDPRIV0000000000000H', 'LOCAL_PURCHASE_ORDER-ALL',    now(), 0),
  ('01J0SEEDPRIV0000000000000J', 'LOCAL_PURCHASE_ORDER-CREATE', now(), 0),
  ('01J0SEEDPRIV0000000000000K', 'LOCAL_PURCHASE_ORDER-UPDATE', now(), 0),
  ('01J0SEEDPRIV0000000000000M', 'MEDICINE_STOCK-UPDATE',       now(), 0),
  ('01J0SEEDPRIV0000000000000N', 'ITEM_STOCK-UPDATE',           now(), 0),
  ('01J0SEEDPRIV0000000000000P', 'PROCUREMENT-ACCESS',          now(), 0),
  ('01J0SEEDPRIV0000000000000Q', 'PHARMACY_ORDER-ALL',          now(), 0),
  ('01J0SEEDPRIV0000000000000R', 'PHARMACY_ORDER-CREATE',       now(), 0),
  ('01J0SEEDPRIV0000000000000S', 'PHARMACY_ORDER-UPDATE',       now(), 0),
  ('01J0SEEDPRIV0000000000000T', 'STORE_ORDER-ALL',             now(), 0),
  ('01J0SEEDPRIV0000000000000V', 'BILL-A',                      now(), 0),
  ('01J0SEEDPRIV0000000000000W', 'SUPPLIER_PRICE_LIST-ALL',     now(), 0),
  ('01J0SEEDPRIV0000000000000X', 'PATIENT-ALL',                 now(), 0),
  ('01J0SEEDPRIV0000000000000Y', 'PATIENT-CREATE',              now(), 0),
  ('01J0SEEDPRIV0000000000000Z', 'PATIENT-UPDATE',              now(), 0),
  ('01J0SEEDPRIV00000000000010', 'PATIENT-A',                   now(), 0),
  ('01J0SEEDPRIV00000000000011', 'PATIENT-C',                   now(), 0),
  ('01J0SEEDPRIV00000000000012', 'PATIENT-U',                   now(), 0),
  ('01J0SEEDPRIV00000000000013', 'PRODUCT-CREATE',              now(), 0);

-- ADMIN role
INSERT INTO roles (uid, name, created_at, version) VALUES
  ('01J0SEEDROLE00000000000001', 'ADMIN', now(), 0);

-- Grant every seeded privilege to ADMIN
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN privileges p
WHERE r.name = 'ADMIN';

-- Admin user — BCrypt hash of "password" (documented dev default; rotate everywhere real)
INSERT INTO users (uid, username, password_hash, enabled, created_at, version) VALUES
  ('01J0SEEDUSER00000000000001',
   'admin',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   TRUE,
   now(),
   0);

-- Assign ADMIN role to admin user
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.username = 'admin' AND r.name = 'ADMIN';
