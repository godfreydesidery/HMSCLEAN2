-- =====================================================================================
-- Increment 02 — P5 System/config seeds (build-spec §4 seed-scope)
--
-- Seed scope = system/config only (11-DECISIONS-RATIFIED seed-scope note).
-- All INSERTs are idempotent: ON CONFLICT (code|kind) DO NOTHING.
-- UIDs are fixed ULIDs so re-runs are safe.
--
-- Legend:
--   md_currencies  — one default TZS row
--   md_document_types — all real legacy prefixes + CR-10 SPTO/PPTO fix
--     NO row may carry prefix 'SPT' (CR-10 ratified)
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- md_currencies: TZS default
-- Legacy has no Currency concept; this single config row declares the system currency.
-- (05-extract-stakeholders-system §2; CR-07-currency; 11-DECISIONS-RATIFIED seed-scope)
-- -------------------------------------------------------------------------------------
INSERT INTO md_currencies (uid, code, name, is_default, created_at, version)
VALUES ('01J0SEEDCURR00000000000001', 'TZS', 'Tanzanian Shilling', TRUE, now(), 0)
ON CONFLICT (code) DO NOTHING;

-- -------------------------------------------------------------------------------------
-- md_document_types: registry of all document stream prefixes (CR-09, CR-10)
-- Real legacy prefixes (05-extract-stakeholders-system §4):
--   GRN  — Goods Received Note
--   LPO  — Local Purchase Order
--   PCN  — Patient Credit Note
--   PRL  — Payroll
--   PGRN — Store→Pharmacy Received Note
--   PPRN — Pharmacy→Pharmacy Received Note
--   PPR  — Pharmacy→Pharmacy Requisition Order
--   PSR  — Pharmacy→Store Requisition Order
--   MRNO — Medical Record Number
-- CR-10 fixed prefixes (replace legacy 'SPT' collision defect for both TO streams):
--   SPTO — Store→Pharmacy Transfer Order   (legacy emitted 'SPT' — defect fixed)
--   PPTO — Pharmacy→Pharmacy Transfer Order (legacy emitted 'SPT' — defect fixed)
-- -------------------------------------------------------------------------------------
INSERT INTO md_document_types (uid, kind, prefix, created_at, version) VALUES
    ('01J0SEEDDTYPE0000000000001', 'GOODS_RECEIVED_NOTE',          'GRN',  now(), 0),
    ('01J0SEEDDTYPE0000000000002', 'LOCAL_PURCHASE_ORDER',         'LPO',  now(), 0),
    ('01J0SEEDDTYPE0000000000003', 'PATIENT_CREDIT_NOTE',          'PCN',  now(), 0),
    ('01J0SEEDDTYPE0000000000004', 'PAYROLL',                      'PRL',  now(), 0),
    ('01J0SEEDDTYPE0000000000005', 'STORE_TO_PHARMACY_TO',         'SPTO', now(), 0),
    ('01J0SEEDDTYPE0000000000006', 'PHARMACY_TO_PHARMACY_TO',      'PPTO', now(), 0),
    ('01J0SEEDDTYPE0000000000007', 'STORE_TO_PHARMACY_RN',         'PGRN', now(), 0),
    ('01J0SEEDDTYPE0000000000008', 'PHARMACY_TO_PHARMACY_RN',      'PPRN', now(), 0),
    ('01J0SEEDDTYPE0000000000009', 'PHARMACY_TO_PHARMACY_RO',      'PPR',  now(), 0),
    ('01J0SEEDDTYPE0000000000010', 'PHARMACY_TO_STORE_RO',         'PSR',  now(), 0),
    ('01J0SEEDDTYPE0000000000011', 'MEDICAL_RECORD_NUMBER',        'MRNO', now(), 0)
ON CONFLICT (kind) DO NOTHING;

-- Expected row count: md_currencies=1, md_document_types=11
-- Reconciliation: assert COUNT(*) matches before declaring seed valid.
