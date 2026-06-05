-- =====================================================================================
-- Increment 07d — Administration routes masterdata (CR-07-MAR prerequisite)
--
-- New table:
--   administration_routes  — admin-managed controlled vocabulary for the medication
--                            administration route (IV, PO, IM, SC, PR, SL, INH, TOP…),
--                            referenced by the net-new MedicationAdministration (MAR) aggregate.
--
-- NET-NEW — there is NO legacy equivalent (legacy MAR did not exist; the legacy dosing-note
-- path carried free text only). Owner ruled a first-class masterdata table (not an enum, not
-- free text) so routes are addable without a redeploy. CR-07-MAR prerequisite, ruled 2026-06-05.
--
-- Design rules:
--   * ULID uid VARCHAR(26) + BIGINT GENERATED ALWAYS AS IDENTITY (ADR-0003, ADR-0005)
--   * audit columns: created_at/updated_at/created_by/updated_by/version (AuditableEntity)
--   * mirrors the ward_categories shape (code/name/description/active)
--
-- inc-07 07d / CR-07-MAR
-- =====================================================================================

CREATE TABLE administration_routes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)     NOT NULL,
    code        VARCHAR(40)     NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT          NOT NULL,
    CONSTRAINT pk_administration_routes      PRIMARY KEY (id),
    CONSTRAINT uq_administration_routes_uid  UNIQUE (uid),
    CONSTRAINT uq_administration_routes_code UNIQUE (code),
    CONSTRAINT uq_administration_routes_name UNIQUE (name)
);
