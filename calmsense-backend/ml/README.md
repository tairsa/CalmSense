# ML Training Pipeline

Trains a logistic regression panic-attack detector and writes its coefficients
to a JSON file the FastAPI service serves to clients.

## Files

| File                  | Purpose                                                     |
| --------------------- | ----------------------------------------------------------- |
| `generate_data.py`    | Generates `training_data.csv` from physiological priors.    |
| `train_model.py`      | Trains LogisticRegression, writes `model_weights.json`.     |
| `training_data.csv`   | Generated. Not committed — recreate any time.               |
| `model_weights.json`  | Output of training. Loaded by `main.py` at server startup.  |

## End-to-end run

From inside `calmsense-backend/` with the venv active:

```bat
pip install -r requirements.txt
cd ml
python generate_data.py
python train_model.py
cd ..
```

Then restart the FastAPI server. On startup it reads
`ml/model_weights.json` and starts returning the trained weights from
`GET /api/v1/sensor-data` (with `"source": "trained_global"` instead of
`"default"`).

## Why synthetic data?

Real labeled panic-attack data with HR/HRV/motion at minute resolution is
not freely available. WESAD (the standard physiological dataset) uses
"stress" labels rather than "panic" — and including it would mean shipping
multiple GB of subject pickle files. Until CalmSense collects real labeled
data from its own users, training data is generated from priors documented
in the literature:

| Profile        | HR (bpm)  | HRV (ms) | Motion (m/s²) | Label     |
| -------------- | --------- | -------- | ------------- | --------- |
| Resting        | 60-80     | 40-65    | 0.0-0.5       | no panic  |
| Stress         | 80-105    | 25-45    | 0.0-1.0       | no panic  |
| Light activity | 85-115    | 25-50    | 0.8-3.0       | no panic  |
| Panic          | 115-175   | 5-25     | 0.0-1.5       | **panic** |
| Exercise       | 110-180   | 15-35    | 2.0-8.0       | no panic  |

Motion is the watch's own measurement - wrist linear-acceleration RMS over
~3 s, in m/s², clamped to 10 - so the phone and per-user retraining use it
unconverted. The ranges are anchored to 8,105 real samples from June 2026
(median 0.24 m/s² at HR < 70, 1.7-2.5 m/s² at HR 85-115).

The model does **not** use HRV in milliseconds. Each sample also draws a
personal resting baseline (30-80 ms) and the HRV priors are scaled to it; the
feature is `hrv_rel = ln(hrv / baseline)`, the drop from that person's normal.
Absolute HRV is not comparable across people, nor across measurement sources:
the bpm-derived estimate the watch falls back to reads ~10 ms for a resting
person whose true RMSSD is ~50 ms, and on raw HRV that resting person looked
like a panic attack. The phone keeps a separate baseline per source.

Critical detail: panic and exercise both have elevated HR and depressed
HRV. The motion feature is what lets the model distinguish them. Without
it, exercise would constantly trigger panic alerts.

This is documented in the project report under "Future Work" along with a
plan to swap in WESAD (or real CalmSense user data) once available.

## Output format

`model_weights.json` matches the existing API contract — a 5-element
array — for backwards compatibility:

```json
{
  "weights": [w_hr, 0.0, w_motion, w_hrv_rel, bias],
  "feature_names": ["hr", "hrv", "motion_ms2", "hrv_rel", "bias"],
  "model_type": "logistic_regression",
  "trained_at": "2026-04-27T13:45:00+00:00",
  "training_samples": 5000,
  "test_accuracy": 0.99,
  "notes": "..."
}
```

Slot 1 (raw HRV) is always `0.0`. A phone build that predates `hrv_rel`
ignores slot 3, so it runs a heart-rate + motion model rather than a wrong one.

## Client decision rule

Given a single reading `(hr, hrv, motion)`, the user's baseline for that HRV
source, and the weights array `w`:

```
hrv_rel = clamp(ln(hrv / baseline), -3, 1)
z = w[0]*hr + w[2]*motion + w[3]*hrv_rel + w[4]
p_panic = 1 / (1 + exp(-z))
is_panic = p_panic > 0.5
```

This replaces the current Android-side rule
`hr > 120 && hrv < 20 && !moving` with a single dot product the app can
compute locally each tick.
