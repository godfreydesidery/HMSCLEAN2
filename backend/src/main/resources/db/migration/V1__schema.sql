-- =====================================================================================
-- Zana HMIS — Increment 00 baseline schema (ADR-0003, ADR-0006, ADR-0007, ADR-0014)
--
-- Conventions (DIRECTIVE 2 — all table names PLURAL, snake_case):
--   id      BIGINT GENERATED ALWAYS AS IDENTITY   -- internal, never exposed
--   uid     VARCHAR(26) NOT NULL UNIQUE              -- public ULID (ADR-0003)
--   audit columns: created_at/updated_at (timestamptz, UTC), created_by/updated_by, version
-- Constraint naming: pk_<table>, fk_<table>_<ref>, uq_<table>_<cols>, idx_<table>_<cols>.
-- Money is NUMERIC(19,2); quantity is NUMERIC(19,6) (ADR-0003/ADR-0009).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Audit log (ADR-0007) — append-only. No version/optimistic-lock column; insert-only.
-- -------------------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    uid             VARCHAR(26)     NOT NULL,
    entity_type     VARCHAR(120) NOT NULL,
    entity_uid      VARCHAR(26),
    action          VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE','READ','UPDATE','DELETE')),
    actor_username  VARCHAR(80)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    checksum        VARCHAR(64)     NOT NULL,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT uq_audit_logs_uid UNIQUE (uid)
);
CREATE INDEX idx_audit_logs_entity_uid  ON audit_logs (entity_uid);
CREATE INDEX idx_audit_logs_actor       ON audit_logs (actor_username);
CREATE INDEX idx_audit_logs_entity_type ON audit_logs (entity_type);

-- -------------------------------------------------------------------------------------
-- Business days (ADR-0009 §7) — already plural.
-- -------------------------------------------------------------------------------------
CREATE TABLE business_days (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    uid           VARCHAR(26)    NOT NULL,
    business_date DATE        NOT NULL,
    opened_at     TIMESTAMPTZ NOT NULL,
    closed_at     TIMESTAMPTZ,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(80),
    updated_by    VARCHAR(80),
    version       BIGINT      NOT NULL,
    CONSTRAINT pk_business_days PRIMARY KEY (id),
    CONSTRAINT uq_business_days_uid UNIQUE (uid)
);
CREATE INDEX idx_business_days_status ON business_days (status);

-- -------------------------------------------------------------------------------------
-- Company profiles (increment-00 vertical slice)
-- -------------------------------------------------------------------------------------
CREATE TABLE company_profiles (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    uid        VARCHAR(26)     NOT NULL,
    name       VARCHAR(200) NOT NULL,
    address    VARCHAR(400),
    phone      VARCHAR(40),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(80),
    updated_by VARCHAR(80),
    version    BIGINT       NOT NULL,
    CONSTRAINT pk_company_profiles PRIMARY KEY (id),
    CONSTRAINT uq_company_profiles_uid UNIQUE (uid)
);

-- -------------------------------------------------------------------------------------
-- IAM: privileges / roles / users (ADR-0006) + join tables + refresh tokens
-- (DIRECTIVE 2: the redundant iam_ prefix is dropped — the module is the package, not the table.)
-- -------------------------------------------------------------------------------------
CREATE TABLE privileges (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    uid        VARCHAR(26)    NOT NULL,
    code       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(80),
    updated_by VARCHAR(80),
    version    BIGINT      NOT NULL,
    CONSTRAINT pk_privileges PRIMARY KEY (id),
    CONSTRAINT uq_privileges_uid  UNIQUE (uid),
    CONSTRAINT uq_privileges_code UNIQUE (code)
);

CREATE TABLE roles (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    uid        VARCHAR(26)    NOT NULL,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(80),
    updated_by VARCHAR(80),
    version    BIGINT      NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_uid  UNIQUE (uid),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    uid           VARCHAR(26)     NOT NULL,
    username      VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(80),
    updated_by    VARCHAR(80),
    version       BIGINT       NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_uid      UNIQUE (uid),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE role_privileges (
    role_id      BIGINT NOT NULL,
    privilege_id BIGINT NOT NULL,
    CONSTRAINT pk_role_privileges PRIMARY KEY (role_id, privilege_id),
    CONSTRAINT fk_role_privileges_role      FOREIGN KEY (role_id)      REFERENCES roles (id),
    CONSTRAINT fk_role_privileges_privilege FOREIGN KEY (privilege_id) REFERENCES privileges (id)
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    uid        VARCHAR(26)    NOT NULL,
    user_uid   VARCHAR(26)    NOT NULL,
    token_hash VARCHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(80),
    updated_by VARCHAR(80),
    version    BIGINT      NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_uid  UNIQUE (uid),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_uid);
