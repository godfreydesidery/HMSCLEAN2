-- =====================================================================================
-- V38 — lab_tests + radiologies: post-VERIFIED report-amendment audit trail (inc-06A C6).
--
-- Ratified ITEM4 policy (audited-amend): legacy add_report (PatientResource.java:3183-3197
-- radiology, :3381-3395 lab) has NO order-status guard, so a VERIFIED clinical report is
-- silently overwritable forever with NO amendment trail (Envers is a confirmed phantom dep —
-- no DB catch-net). Rather than reproduce that patient-safety defect, a post-VERIFIED report
-- change is allowed ONLY via an explicit amend path that:
--   (1) retains the PRIOR report narrative (append-only — prior_report column), and
--   (2) records WHO/WHEN amended (amend audit triplet).
--
-- This is a NET-NEW capability beyond pure legacy parity, recorded as a ratified deviation
-- (engagement-owner choice, 2026-06-04). result/range/level/unit remain immutable after VERIFIED;
-- only the report narrative is amendable, and only through this audited path.
--
-- Reversible: a down-migration would DROP these four columns from each table (no data transform).
-- =====================================================================================

ALTER TABLE lab_tests
    ADD COLUMN prior_report                TEXT,
    ADD COLUMN report_amended_by_user_uid  VARCHAR(26),
    ADD COLUMN report_amended_on_day_uid   VARCHAR(26),
    ADD COLUMN report_amended_at           TIMESTAMPTZ;

ALTER TABLE radiologies
    ADD COLUMN prior_report                TEXT,
    ADD COLUMN report_amended_by_user_uid  VARCHAR(26),
    ADD COLUMN report_amended_on_day_uid   VARCHAR(26),
    ADD COLUMN report_amended_at           TIMESTAMPTZ;
