-- =====================================================================================
-- Zana HMIS — Increment 01 IAM delta schema (additive only; V1-V3 are immutable)
-- Sources: build-spec §3, 07-DECISIONS-RATIFIED.md §A, §B, §C
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 4a. users: legacy identity columns absent from increment-00 minimal table
--     (legacy: User.java firstName/middleName/lastName/nickname/code)
-- -------------------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN user_no      VARCHAR(11),
    ADD COLUMN first_name   VARCHAR(80),
    ADD COLUMN middle_name  VARCHAR(80),
    ADD COLUMN last_name    VARCHAR(80),
    ADD COLUMN nickname     VARCHAR(80);

ALTER TABLE users
    ADD CONSTRAINT uq_users_user_no UNIQUE (user_no);

ALTER TABLE users
    ADD CONSTRAINT ck_users_user_no_format
        CHECK (user_no IS NULL OR user_no ~ '^USR-[0-9]{3}-[0-9]{3}$');

-- NOT unique — matches legacy User.java (no unique constraint on nickname there)
CREATE INDEX idx_users_nickname ON users (nickname);

-- -------------------------------------------------------------------------------------
-- 4b. user_no sequence — modern safe derivation, same output as legacy MAX(id)+1
--     (CR-06: mechanism modernized; USR-NNN-NNN format preserved exactly)
-- -------------------------------------------------------------------------------------
CREATE SEQUENCE seq_usr_no AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE;

-- -------------------------------------------------------------------------------------
-- 4c. roles.owner discriminator (legacy Role.owner; reserved-name guard depends on it)
--     UPDATE only the seeded ADMIN role to SYSTEM; all others remain ORGANIZATION.
-- -------------------------------------------------------------------------------------
ALTER TABLE roles
    ADD COLUMN owner VARCHAR(20) NOT NULL DEFAULT 'ORGANIZATION';

ALTER TABLE roles
    ADD CONSTRAINT ck_roles_owner CHECK (owner IN ('SYSTEM', 'ORGANIZATION'));

UPDATE roles SET owner = 'SYSTEM' WHERE name = 'ADMIN';

-- -------------------------------------------------------------------------------------
-- 4d. privileges.category tag (live vs dead; NO codes added/removed — V2 set intact)
--     Dead codes (9) from build-spec §1 / 07-DECISIONS-RATIFIED §A.1.
-- -------------------------------------------------------------------------------------
ALTER TABLE privileges
    ADD COLUMN category VARCHAR(12) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE privileges
    ADD CONSTRAINT ck_privileges_category CHECK (category IN ('ACTIVE', 'DEAD'));

UPDATE privileges
SET category = 'DEAD'
WHERE code IN (
    'BILL-A',
    'GOO-ALL',
    'PATIENT-A',
    'PATIENT-C',
    'PATIENT-U',
    'PROCUREMENT-ACCESS',
    'PRODUCT-CREATE',
    'ROLE-CREATE',
    'ROLE-U'
);

-- -------------------------------------------------------------------------------------
-- 4e. refresh_tokens: reuse-detection forensics (D-2 / CR-10)
--     Self-FK on replaced_by_uid; CHECK ensures revoked_at is set iff revoked=TRUE.
-- -------------------------------------------------------------------------------------
ALTER TABLE refresh_tokens
    ADD COLUMN revoked_at      TIMESTAMPTZ,
    ADD COLUMN replaced_by_uid VARCHAR(26);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_uid) REFERENCES refresh_tokens (uid);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT ck_refresh_tokens_revoked_consistency
        CHECK (
            (revoked = TRUE  AND revoked_at IS NOT NULL) OR
            (revoked = FALSE AND revoked_at IS NULL)
        );

-- Partial index on live (non-revoked) tokens for efficient revocation queries
CREATE INDEX idx_refresh_tokens_user_live ON refresh_tokens (user_uid) WHERE revoked = FALSE;

-- -------------------------------------------------------------------------------------
-- 4f. Personnel extension tables — six entities (07-DECISIONS-RATIFIED §C)
--     AMB-1: UNIQUE(user_id) per table enforces one-extension-per-user-per-type at DB.
--     NO clinicians_clinics / store_persons_stores — deferred (Clinic/Store don't exist yet).
-- -------------------------------------------------------------------------------------

CREATE TABLE clinicians (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_clinicians            PRIMARY KEY (id),
    CONSTRAINT uq_clinicians_uid        UNIQUE (uid),
    CONSTRAINT uq_clinicians_user_id    UNIQUE (user_id),
    CONSTRAINT fk_clinicians_user       FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE nurses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_nurses                PRIMARY KEY (id),
    CONSTRAINT uq_nurses_uid            UNIQUE (uid),
    CONSTRAINT uq_nurses_user_id        UNIQUE (user_id),
    CONSTRAINT fk_nurses_user           FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE pharmacists (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_pharmacists           PRIMARY KEY (id),
    CONSTRAINT uq_pharmacists_uid       UNIQUE (uid),
    CONSTRAINT uq_pharmacists_user_id   UNIQUE (user_id),
    CONSTRAINT fk_pharmacists_user      FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE cashiers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_cashiers              PRIMARY KEY (id),
    CONSTRAINT uq_cashiers_uid          UNIQUE (uid),
    CONSTRAINT uq_cashiers_user_id      UNIQUE (user_id),
    CONSTRAINT fk_cashiers_user         FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE store_persons (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_store_persons         PRIMARY KEY (id),
    CONSTRAINT uq_store_persons_uid     UNIQUE (uid),
    CONSTRAINT uq_store_persons_user_id UNIQUE (user_id),
    CONSTRAINT fk_store_persons_user    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE managements (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    uid         VARCHAR(26)  NOT NULL,
    code        VARCHAR(20),
    type        VARCHAR(60),
    first_name  VARCHAR(80),
    middle_name VARCHAR(80),
    last_name   VARCHAR(80),
    nickname    VARCHAR(80),
    active      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    version     BIGINT       NOT NULL,
    user_id     BIGINT,
    CONSTRAINT pk_managements           PRIMARY KEY (id),
    CONSTRAINT uq_managements_uid       UNIQUE (uid),
    CONSTRAINT uq_managements_user_id   UNIQUE (user_id),
    CONSTRAINT fk_managements_user      FOREIGN KEY (user_id) REFERENCES users (id)
);
