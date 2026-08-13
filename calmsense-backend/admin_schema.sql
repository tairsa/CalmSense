-- CalmSense - Admin app schema
-- Run this once in Supabase (Dashboard -> SQL Editor -> New query -> paste -> Run),
-- after supabase_schema.sql. These tables back the admin web app: admin login,
-- per-user versioned model weights, and per-user model state (active snapshot +
-- training cutoff).
--
-- The backend also supports a local JSON fallback for all of these (data/*.json),
-- so this file is only needed when running against Supabase.

-- ---------------------------------------------------------------------------
-- Admin accounts (for the admin web app). Separate from app end-users: an
-- admin is you/Tair, identified by email + password, authenticated with JWT.
-- ---------------------------------------------------------------------------
create table if not exists public.admin_users (
    id            bigint generated always as identity primary key,
    email         text        not null unique,
    name          text,
    password_hash text        not null,   -- pbkdf2$<iterations>$<salt_hex>$<hash_hex>
    is_active     boolean     not null default true,
    created_at    timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Versioned model weights. One row per snapshot. Every retrain / reset / the
-- initial baseline import writes a new row; nothing is overwritten, so we can
-- roll a user back to any earlier snapshot without refitting.
--
--   source:          'baseline'  - the shipped synthetic-trained model
--                    'trained'   - retrained from this user's feedback
--                    'rollback'  - a snapshot re-activated by a rollback
--   trained_through: the latest data timestamp included in this snapshot
--                    (null for the baseline). Used to pick a rollback target.
-- ---------------------------------------------------------------------------
create table if not exists public.model_weights (
    id               bigint generated always as identity primary key,
    user_id          text        not null,
    weights          jsonb       not null,        -- [w_hr, w_hrv, w_motion, w_reserved, bias]
    feature_names    jsonb,
    model_type       text        not null default 'logistic_regression',
    source           text        not null default 'trained',
    test_accuracy    double precision,
    training_samples integer,
    trained_through  timestamptz,
    note             text,
    created_at       timestamptz not null default now()
);

create index if not exists model_weights_user_id_idx
    on public.model_weights (user_id);
create index if not exists model_weights_user_created_idx
    on public.model_weights (user_id, created_at desc);

-- ---------------------------------------------------------------------------
-- Per-user model state: which snapshot is currently active, and the optional
-- training cutoff set by a rollback. When training_cutoff is set, retrains
-- ignore feedback/reports after that timestamp until it is cleared.
-- ---------------------------------------------------------------------------
create table if not exists public.user_model_state (
    user_id           text        primary key,
    active_weights_id bigint      references public.model_weights (id),
    training_cutoff   timestamptz,
    updated_at        timestamptz not null default now()
);

-- NOTE on security: the backend connects with the service_role key (RLS
-- bypassed). Keep all access to these tables on the FastAPI server. The admin
-- web app never talks to Supabase directly; it goes through the JWT-protected
-- /api/v1/admin endpoints.
