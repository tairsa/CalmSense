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
    current_motion_intensity  double precision,
    created_at                timestamptz not null default now()
);

create index if not exists panic_reports_user_id_idx
    on public.panic_reports (user_id);
create index if not exists panic_reports_timestamp_idx
    on public.panic_reports ("timestamp");

-- NOTE on security:
-- The backend connects with the service_role key, which bypasses Row Level
-- Security, so no RLS policies are required for the server to work. If you
-- ever let the Android app talk to Supabase directly with the anon key, you
-- MUST enable RLS and add policies first. For now, keep all DB access on the
-- FastAPI server.
