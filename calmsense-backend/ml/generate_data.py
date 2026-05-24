"""
Synthetic training data generator for the panic-attack detector.

Why synthetic? Real labeled panic-attack data with HR/HRV/motion at minute
resolution is not freely available. WESAD uses "stress" labels, not panic.
Until we collect real labeled data from CalmSense users, we generate
training examples from physiological priors documented in the literature:

  - Baseline (resting):    HR 60-80 bpm,  HRV 40-65 ms, motion 0.00-0.10
  - Mild stress:           HR 80-105,     HRV 25-45,    motion 0.00-0.20
  - Panic attack:          HR 115-175,    HRV  5-25,    motion 0.00-0.30
  - Exercise (NOT panic):  HR 110-180,    HRV 15-35,    motion 0.50-1.00

Critical: panic and exercise both have elevated HR and depressed HRV.
The motion feature is what lets the model distinguish them. Without it,
exercise would constantly trigger panic alerts.

Usage:
    python generate_data.py
    -> writes ml/training_data.csv (~5000 rows by default)

Output columns: hr, hrv, motion, label  (label: 0 = no panic, 1 = panic)
"""

from __future__ import annotations

import argparse
import csv
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
    Profile("resting",  60.0,  80.0, 40.0, 65.0, 0.00, 0.10, label=0),
    Profile("stress",   80.0, 105.0, 25.0, 45.0, 0.00, 0.20, label=0),
    Profile("panic",   115.0, 175.0,  5.0, 25.0, 0.00, 0.30, label=1),
    Profile("exercise",110.0, 180.0, 15.0, 35.0, 0.50, 1.00, label=0),
]


def sample(profile: Profile, rng: random.Random) -> tuple[float, float, float, int]:
    """One synthetic sample with mild gaussian jitter on top of uniform draws."""
    hr = rng.uniform(profile.hr_lo, profile.hr_hi) + rng.gauss(0, 2.0)
    hrv = rng.uniform(profile.hrv_lo, profile.hrv_hi) + rng.gauss(0, 1.5)
    motion = rng.uniform(profile.motion_lo, profile.motion_hi) + rng.gauss(0, 0.02)

    # Clamp to physiologically plausible bounds
    hr = max(40.0, min(220.0, hr))
    hrv = max(1.0, min(120.0, hrv))
    motion = max(0.0, min(1.0, motion))

    return round(hr, 2), round(hrv, 2), round(motion, 3), profile.label


def generate(n_per_profile: int, seed: int) -> Iterable[tuple[float, float, float, int]]:
    rng = random.Random(seed)
    for profile in PROFILES:
        for _ in range(n_per_profile):
            yield sample(profile, rng)


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate synthetic CalmSense training data.")
    parser.add_argument("--per-profile", type=int, default=1250,
                        help="Samples per profile (default: 1250 → ~5000 total)")
    parser.add_argument("--seed", type=int, default=42, help="RNG seed for reproducibility.")
    parser.add_argument("--out", default=None, help="Output CSV path (default: ml/training_data.csv).")
    args = parser.parse_args()

    out_path = args.out or os.path.join(os.path.dirname(__file__), "training_data.csv")

    rows = list(generate(args.per_profile, args.seed))
    random.Random(args.seed).shuffle(rows)  # mix profiles so split is stratified-ish

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["hr", "hrv", "motion", "label"])
        writer.writerows(rows)

    n_panic = sum(1 for r in rows if r[3] == 1)
    print(f"Wrote {len(rows)} rows to {out_path}")
    print(f"  panic samples: {n_panic} ({100 * n_panic / len(rows):.1f}%)")
    print(f"  non-panic samples: {len(rows) - n_panic} ({100 * (len(rows) - n_panic) / len(rows):.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
