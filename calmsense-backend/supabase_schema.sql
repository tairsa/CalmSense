-- CalmSense - Supabase schema
-- Run this once in your Supabase project: Dashboard -> SQL Editor -> New query
-- -> paste -> Run.
--
-- Columns mirror the JSON record written by the backend (models.SensorData):
--   user_id, panic_attack_detection, current_hr, current_hrv,
--   current_motion_intensity, timestamp.
-- "id" and "created_at" are added by the DB; the backend never sends them.

create table if not exists public.sensor_data (
    id                       bigint generated always as identity primary key,
    user_id                  text        not null,
    panic_attack_detection   boolean     not null,
    current_hr               double precision,
    current_hrv              double precision,
    hrv_source               text,
    current_motion_intensity double precision,
    timestamp                timestamptz,
    created_at               timestamptz not null default now()
);

-- Query sensor history per user quickly.
create index if not exists sensor_data_user_id_idx
    on public.sensor_data (user_id);

-- Labeled training signals from the user. Used to retrain the panic
-- classifier and to track model hit/miss rate per user.
create table if not exists public.panic_feedback (
    id                        bigint generated always as identity primary key,
    user_id                   text        not null,
    was_panic                 boolean     not null,
    severity                  smallint    check (severity between 1 and 10),
    detected_by_model         boolean     not null,
    current_hr                double precision,
    current_hrv               double precision,
    hrv_source               text,
    hrv_baseline              double precision,
    current_motion_intensity  double precision,
    model_probability         double precision,
    timestamp                 timestamptz,
    created_at                timestamptz not null default now()
);

create index if not exists panic_feedback_user_id_idx
    on public.panic_feedback (user_id);

-- Journaled panic-attack reports filled in by the user post-event. Used for
-- pattern tracking and to review with a therapist. Free-text columns and
-- GPS are sensitive — keep server-side access on the service_role key only.
create table if not exists public.panic_reports (
    id                        bigint generated always as identity primary key,
    user_id                   text        not null,
    timestamp                 timestamptz,
    severity                  smallint    not null check (severity between 1 and 10),
    detected_by_model         boolean     not null,
    feeling                   text,
    symptoms                  jsonb,
    activity_before           text,
    what_helped               text,
    duration_minutes          integer     check (duration_minutes between 0 and 1440),
    latitude                  double precision,
    longitude                 double precision,
    location_accuracy_m       double precision,
    current_hr                double precision,
    current_hrv               double precision,
    hrv_source               text,
    current_motion_intensity  double precision,
    created_at                timestamptz not null default now()
);

create index if not exists panic_reports_user_id_idx
    on public.panic_reports (user_id);
create index if not exists panic_reports_timestamp_idx
    on public.panic_reports ("timestamp");

-- Per-user profile row. `role` splits the app into two experiences:
--   'patient'   - default; sees monitor / breathing / reports / stats.
--   'therapist' - sees a dashboard of their consenting clients.
-- Created on first login when the user picks a role.
create table if not exists public.profiles (
    user_id      text        primary key,
    role         text        not null check (role in ('patient','therapist')),
    display_name text,
    created_at   timestamptz not null default now()
);

-- Short redeemable codes a therapist generates and shares with a client
-- out-of-band (WhatsApp / in person). Client enters the code in their app
-- to grant view access.
create table if not exists public.consent_codes (
    code         text        primary key,   -- e.g. "A7K-Q2M", human-friendly
    therapist_id text        not null,
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null,
    used_at      timestamptz,               -- null while still redeemable
    used_by      text                       -- patient user_id after redemption
);

create index if not exists consent_codes_therapist_id_idx
    on public.consent_codes (therapist_id);

-- Consent link. A row here means the patient has granted the therapist
-- permission to view their reports and sensor data. Deleting a row revokes
-- access; the underlying patient data is untouched.
create table if not exists public.therapist_patients (
    id           bigint      generated always as identity primary key,
    therapist_id text        not null,
    patient_id   text        not null,
    created_at   timestamptz not null default now(),
    unique (therapist_id, patient_id)
);

create index if not exists therapist_patients_therapist_id_idx
    on public.therapist_patients (therapist_id);
create index if not exists therapist_patients_patient_id_idx
    on public.therapist_patients (patient_id);

-- ===========================================================================
-- Admin + model-versioning tables.
--
-- storage.py has always referenced these (admin_users, model_weights,
-- user_model_state) but they were missing from this file, because the stack
-- had only ever run on the JSON fallback where tables are implicit. Without
-- them the admin dashboard cannot log in and no retrain/rollback can be saved.
--
-- Unlike the tables above these use GENERATED BY DEFAULT AS IDENTITY, so a
-- migration can insert explicit ids. That is required, not cosmetic:
-- user_model_state.active_weights_id points at model_weights.id, so those ids
-- have to survive the move from the JSON store.
-- ===========================================================================

create table if not exists public.admin_users (
    id            bigint      generated by default as identity primary key,
    email         text        not null unique,
    name          text,
    password_hash text        not null,
    is_active     boolean     not null default true,
    created_at    timestamptz not null default now()
);

create table if not exists public.model_weights (
    id               bigint      generated by default as identity primary key,
    user_id          text        not null,
    weights          jsonb       not null,
    feature_names    jsonb,
    model_type       text,
    test_accuracy    double precision,
    training_samples integer,
    trained_through  text,
    source           text,
    note             text,
    created_at       timestamptz not null default now()
);

-- list_model_snapshots() filters by user_id and orders by created_at desc.
create index if not exists model_weights_user_created_idx
    on public.model_weights (user_id, created_at desc);

-- user_id is the PRIMARY KEY because storage.upsert_user_model_state() calls
-- upsert(..., on_conflict="user_id"), which needs a unique constraint on it.
create table if not exists public.user_model_state (
    user_id           text        primary key,
    active_weights_id bigint      references public.model_weights (id) on delete set null,
    training_cutoff   text,
    updated_at        timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Row Level Security
--
-- Enabled on every table with NO policies, which denies everything to the anon
-- and authenticated roles. The backend is unaffected: it connects with the
-- service_role key, which bypasses RLS by design.
--
-- The original note below said policies "are not required", which was true
-- while the API was reachable only over the private tailnet. Now that it is
-- published on the internet, defence in depth is cheap: if the anon key ever
-- leaks, it still yields nothing. These tables hold heart-rate history, panic
-- journals and GPS coordinates.
--
-- If you ever let the Android app talk to Supabase directly, you must ADD
-- explicit per-user policies first — enabling RLS alone will just block it.
-- ---------------------------------------------------------------------------
alter table public.sensor_data         enable row level security;
alter table public.panic_feedback      enable row level security;
alter table public.panic_reports       enable row level security;
alter table public.profiles            enable row level security;
alter table public.consent_codes       enable row level security;
alter table public.therapist_patients  enable row level security;
alter table public.admin_users         enable row level security;
alter table public.model_weights       enable row level security;
alter table public.user_model_state    enable row level security;


-- ---------------------------------------------------------------------------
-- Migration for databases created before hrv_source existed. Safe to re-run.
--
-- Deliberately NOT backfilled. Existing rows are a mix of real_ibi and
-- bpm_derived readings and there is no record of which is which, so any
-- backfill would be a guess written down as fact. Null means unknown, and a
-- query that cares about provenance should exclude those rows rather than
-- trust them.
-- ---------------------------------------------------------------------------
alter table public.sensor_data    add column if not exists hrv_source text;
alter table public.panic_feedback add column if not exists hrv_source text;
alter table public.panic_reports  add column if not exists hrv_source text;


-- ---------------------------------------------------------------------------
-- Migration for databases created before hrv_baseline existed. Safe to re-run.
--
-- The phone's resting-HRV baseline for the row's hrv_source, so retraining can
-- use the drop from the user's own normal. Run BEFORE shipping a phone build
-- that sends it; until then those inserts fail and stay queued on the phone.
-- Older phones omit the key, so they are unaffected either way.
-- ---------------------------------------------------------------------------
alter table public.panic_feedback add column if not exists hrv_baseline double precision;


-- ---------------------------------------------------------------------------
-- admin_user_stats(): per-user activity, counted in the database. Safe to
-- re-run.
--
-- The admin Users page and dashboard used to download every sensor row,
-- 1,000 per request, just to count them: 60 s, then a dropped connection and
-- a 500 once the table passed a few hundred thousand rows (2026-10-03). This
-- returns one row per user instead.
-- ---------------------------------------------------------------------------
create or replace function public.admin_user_stats()
returns table (
    user_id          text,
    sensor_count     bigint,
    feedback_count   bigint,
    report_count     bigint,
    last_seen        timestamptz,
    hrv_real_ibi     bigint,
    hrv_bpm_derived  bigint,
    hrv_no_hrv       bigint,
    hrv_unknown      bigint
)
language sql stable
set search_path = public
as $$
    with s as (
        select user_id, count(*) as n, max(coalesce("timestamp", created_at)) as t,
               count(*) filter (where hrv_source = 'real_ibi')    as real_ibi,
               count(*) filter (where hrv_source = 'bpm_derived') as bpm_derived,
               count(*) filter (where hrv_source = 'none')        as no_hrv,
               count(*) filter (where hrv_source is null)         as unknown
        from sensor_data group by user_id
    ),
    f as (select user_id, count(*) as n, max(coalesce("timestamp", created_at)) as t
          from panic_feedback group by user_id),
    r as (select user_id, count(*) as n, max(coalesce("timestamp", created_at)) as t
          from panic_reports group by user_id)
    select u.user_id,
           coalesce(s.n, 0), coalesce(f.n, 0), coalesce(r.n, 0),
           greatest(s.t, f.t, r.t),
           coalesce(s.real_ibi, 0), coalesce(s.bpm_derived, 0),
           coalesce(s.no_hrv, 0), coalesce(s.unknown, 0)
    from (select user_id from s union select user_id from f union select user_id from r) u
    left join s using (user_id)
    left join f using (user_id)
    left join r using (user_id)
    where u.user_id is not null
    order by u.user_id;
$$;

-- Admin only. A new function is executable by PUBLIC, and Supabase exposes it
-- over the REST API - so without this the public anon key in the phone app
-- could list every user id.
revoke all on function public.admin_user_stats() from public, anon, authenticated;
grant execute on function public.admin_user_stats() to service_role;
