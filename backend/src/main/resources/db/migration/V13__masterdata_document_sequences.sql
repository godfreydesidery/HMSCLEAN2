-- =====================================================================================
-- Increment 02 — P5 Document-number sequences (build-spec §4, CR-09)
--
-- DB sequences replace the legacy MAX(id)+1 counter mechanism for all document streams
-- except IAM (seq_usr_no already exists in V4 — do NOT recreate it).
--
-- Each sequence starts at 1. No document numbers are generated in inc-02; these exist
-- only so that document-generating services in future increments can call nextval().
--
-- Legacy citations:
--   05-extract-stakeholders-system §4 (all prefix/counter pairs)
--   04-extract-pricing-insurance §4 (seq_grn_no, seq_lpo_no)
--   CR-09: DB sequences replacing MAX(id)+1 (ratified 11-DECISIONS-RATIFIED)
--   CR-10: SPTO/PPTO (seq_spto_no/seq_ppto_no) — NOT SPT collision (ratified)
--
-- NOT created: seq_usr_no (V4 already owns it)
-- Employee/patient numbers: derived from entity id after save — no sequence needed here
-- =====================================================================================

CREATE SEQUENCE IF NOT EXISTS seq_grn_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS seq_lpo_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS seq_pcn_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS seq_prl_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Store→Pharmacy Transfer Order (CR-10: SPTO replaces legacy SPT)
CREATE SEQUENCE IF NOT EXISTS seq_spto_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Pharmacy→Pharmacy Transfer Order (CR-10: PPTO replaces legacy SPT collision)
CREATE SEQUENCE IF NOT EXISTS seq_ppto_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Store→Pharmacy Received Note
CREATE SEQUENCE IF NOT EXISTS seq_pgrn_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Pharmacy→Pharmacy Received Note
CREATE SEQUENCE IF NOT EXISTS seq_pprn_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Pharmacy→Pharmacy Requisition Order
CREATE SEQUENCE IF NOT EXISTS seq_ppr_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Pharmacy→Store Requisition Order
CREATE SEQUENCE IF NOT EXISTS seq_psr_no
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- Medical Record Number
CREATE SEQUENCE IF NOT EXISTS seq_mrno
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;
