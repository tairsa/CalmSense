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

-- NOTE on security:
-- The backend connects with the service_role key, which bypasses Row Level
-- Security, so no RLS policies are required for the server to work. If you
-- ever let the Android app talk to Supabase directly with the anon key, you
-- MUST enable RLS and add policies first. For now, keep all DB access on the
-- FastAPI server.
