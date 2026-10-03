"""
Synthetic training data generator for the panic-attack detector.

Why synthetic? Real labeled panic-attack data with HR/HRV/motion at minute
resolution is not freely available. WESAD uses "stress" labels, not panic.
Until we collect real labeled data from CalmSense users, we generate
training examples from physiological priors documented in the literature:

  - Baseline (resting):    HR 60-80 bpm,  HRV 40-65 ms, motion 0.0-0.5
  - Mild stress:           HR 80-105,     HRV 25-45,    motion 0.0-1.0
  - Light activity:        HR 85-115,     HRV 25-50,    motion 0.8-3.0
  - Panic attack:          HR 115-175,    HRV  5-25,    motion 0.0-1.5
  - Exercise (NOT panic):  HR 110-180,    HRV 15-35,    motion 2.0-8.0

Motion is in the watch's own units: RMS of wrist linear acceleration over ~3 s,
in m/s^2 (HrMonitoringService), clamped to MOTION_MAX. The ranges are anchored
to 8,105 real watch samples from June 2026, where the median was 0.24 m/s^2 at
HR < 70 and 1.7-2.5 m/s^2 at HR 85-115: a wrist moves a lot even when the body
is sedentary. Light activity (chores, slow walking) is its own class because
that middle ground is most of a waking day and was previously unrepresented.
Panic allows restlessness and trembling (to 1.5) but not walking pace.

Critical: panic and exercise both have elevated HR and depressed HRV.
The motion feature is what lets the model distinguish them. Without it,
exercise would constantly trigger panic alerts.

Usage:
    python generate_data.py
    -> writes ml/training_data.csv (~6250 rows by default)

Output columns: hr, hrv, motion, hrv_rel, label  (label: 0 = no panic, 1 = panic)

hrv_rel is ln(hrv / the person's resting baseline). The model is trained on it
instead of absolute HRV, because absolute HRV is not comparable across people
or across measurement sources: a bpm-derived estimate reads ~10 ms where a true
RMSSD from beat-to-beat intervals reads ~50 ms for the same resting person, and
the model has no way to tell them apart. A drop relative to the person's own
baseline *of the same source* means the same thing either way.

Each sample draws a resting baseline (BASELINE_LO..HI ms) and scales the
profile's HRV prior by baseline / RESTING_MID, i.e. the same priors as above,
expressed relative to that person's normal instead of a population average.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import random
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class Profile:
    name: str
    hr_lo: float
    hr_hi: float
    hrv_lo: float
    hrv_hi: float
    motion_lo: float
    motion_hi: float
    label: int  # 0 = no panic, 1 = panic


PROFILES: list[Profile] = [
    Profile("resting",  60.0,  80.0, 40.0, 65.0, 0.0, 0.5, label=0),
    Profile("stress",   80.0, 105.0, 25.0, 45.0, 0.0, 1.0, label=0),
    Profile("light",    85.0, 115.0, 25.0, 50.0, 0.8, 3.0, label=0),
    Profile("panic",   115.0, 175.0,  5.0, 25.0, 0.0, 1.5, label=1),
    Profile("exercise",110.0, 180.0, 15.0, 35.0, 2.0, 8.0, label=0),
]

# Upper clamp on motion (m/s^2), shared with the phone and the retrainer:
# beyond this it is unambiguously not a panic and extra range adds nothing.
MOTION_MAX = 10.0


# Resting-baseline spread across people (true RMSSD scale), and the midpoint of
# the resting HRV prior that the absolute priors above are relative to.
BASELINE_LO, BASELINE_HI = 30.0, 80.0
RESTING_MID = 52.5


def sample(profile: Profile, rng: random.Random) -> tuple[float, float, float, float, int]:
    """One synthetic sample (hr, hrv, motion, hrv_rel, label) with mild gaussian
    jitter on top of uniform draws."""
    hr = rng.uniform(profile.hr_lo, profile.hr_hi) + rng.gauss(0, 2.0)
    baseline = rng.uniform(BASELINE_LO, BASELINE_HI)
    hrv = (rng.uniform(profile.hrv_lo, profile.hrv_hi) + rng.gauss(0, 1.5)) * baseline / RESTING_MID
    motion = rng.uniform(profile.motion_lo, profile.motion_hi) + rng.gauss(0, 0.05)

    # Clamp to physiologically plausible bounds
    hr = max(40.0, min(220.0, hr))
    hrv = max(1.0, min(120.0, hrv))
    motion = max(0.0, min(MOTION_MAX, motion))

    hrv_rel = math.log(hrv / baseline)

    return round(hr, 2), round(hrv, 2), round(motion, 3), round(hrv_rel, 4), profile.label


def generate(n_per_profile: int, seed: int) -> Iterable[tuple[float, float, float, float, int]]:
    rng = random.Random(seed)
    for profile in PROFILES:
        for _ in range(n_per_profile):
            yield sample(profile, rng)


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate synthetic CalmSense training data.")
    parser.add_argument("--per-profile", type=int, default=1250,
                        help="Samples per profile (default: 1250 → ~6250 total)")
    parser.add_argument("--seed", type=int, default=42, help="RNG seed for reproducibility.")
    parser.add_argument("--out", default=None, help="Output CSV path (default: ml/training_data.csv).")
    args = parser.parse_args()

    out_path = args.out or os.path.join(os.path.dirname(__file__), "training_data.csv")

    rows = list(generate(args.per_profile, args.seed))
    random.Random(args.seed).shuffle(rows)  # mix profiles so split is stratified-ish

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["hr", "hrv", "motion", "hrv_rel", "label"])
        writer.writerows(rows)

    n_panic = sum(1 for r in rows if r[-1] == 1)
    print(f"Wrote {len(rows)} rows to {out_path}")
    print(f"  panic samples: {n_panic} ({100 * n_panic / len(rows):.1f}%)")
    print(f"  non-panic samples: {len(rows) - n_panic} ({100 * (len(rows) - n_panic) / len(rows):.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
