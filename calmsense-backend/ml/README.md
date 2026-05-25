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

| Profile  | HR (bpm)  | HRV (ms) | Motion    | Label     |
| -------- | --------- | -------- | --------- | --------- |
| Resting  | 60-80     | 40-65    | 0.00-0.10 | no panic  |
| Stress   | 80-105    | 25-45    | 0.00-0.20 | no panic  |
| Panic    | 115-175   | 5-25     | 0.00-0.30 | **panic** |
| Exercise | 110-180   | 15-35    | 0.50-1.00 | no panic  |

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
  "weights": [w_hr, w_hrv, w_motion, w_reserved, bias],
  "feature_names": ["hr", "hrv", "motion", "reserved", "bias"],
  "model_type": "logistic_regression",
  "trained_at": "2026-04-27T13:45:00+00:00",
  "training_samples": 5000,
  "test_accuracy": 0.99,
  "notes": "..."
}
```

The reserved slot is `0.0` and is held for a future feature
(e.g. recent-panic memory).

## Client decision rule

Given a single reading `(hr, hrv, motion)` and the weights array `w`:

```
z = w[0]*hr + w[1]*hrv + w[2]*motion + w[4]    # w[3] is reserved (0.0)
p_panic = 1 / (1 + exp(-z))
is_panic = p_panic > 0.5
```

This replaces the current Android-side rule
`hr > 120 && hrv < 20 && !moving` with a single dot product the app can
compute locally each tick.
