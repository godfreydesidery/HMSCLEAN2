-- =====================================================================================
-- Zana HMIS — Increment 00 company-profile seed (increment-00 spec V3)
-- One placeholder row to support the trivial vertical slice GET /api/v1/company-profile.
-- =====================================================================================
INSERT INTO company_profiles (uid, name, address, phone, created_at, version) VALUES
  ('01J0SEEDCOMP00000000000001',
   'Zana Health Management Hospital',
   'P.O. Box 0000, Dar es Salaam, Tanzania',
   '+255 700 000 000',
   now(),
   0);
