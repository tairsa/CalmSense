"""
Train a logistic regression classifier for panic-attack detection.

Pipeline:
  1. Load training_data.csv (run generate_data.py first if missing).
  2. Train sklearn LogisticRegression on raw features (no standardization,
     so the Android client can apply the model with a single dot product).
  3. Evaluate on a held-out test split. Print accuracy and confusion matrix.
  4. Write coefficients to ml/model_weights.json in the format the API expects.

Usage:
    python train_model.py
    python train_model.py --csv path/to/other_data.csv

Output JSON shape (matches the existing /api/v1/sensor-data response):

    {
      "weights": [w_hr, w_hrv, w_motion, w_reserved, bias],
      "feature_names": ["hr", "hrv", "motion", "reserved", "bias"],
      "model_type": "logistic_regression",
      "trained_at": "<ISO 8601>",
      "training_samples": <int>,
      "test_accuracy": <float>,
      "notes": "<free text>"
    }

The 4th slot ("reserved") is set to 0.0 to keep the array length compatible
with the existing 5-element contract Alex defined in main.py. It is reserved
for a future feature (e.g. recent-panic memory).

Decision rule on the client side:

    z = w_hr*hr + w_hrv*hrv + w_motion*motion + bias
    p_panic = 1 / (1 + exp(-z))
    is_panic = p_panic > 0.5
"""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, confusion_matrix, classification_report
from sklearn.model_selection import train_test_split


FEATURE_COLS = ["hr", "hrv", "motion"]
LABEL_COL = "label"


def load_data(csv_path: str) -> tuple[np.ndarray, np.ndarray]:
    if not os.path.exists(csv_path):
        raise FileNotFoundError(
            f"Training data not found: {csv_path}\n"
            f"Run: python generate_data.py"
        )
    df = pd.read_csv(csv_path)
    missing = [c for c in FEATURE_COLS + [LABEL_COL] if c not in df.columns]
    if missing:
        raise ValueError(f"CSV missing columns: {missing}. Found: {list(df.columns)}")
    X = df[FEATURE_COLS].to_numpy(dtype=float)
    y = df[LABEL_COL].to_numpy(dtype=int)
    return X, y


def train_and_evaluate(X: np.ndarray, y: np.ndarray, seed: int = 42) -> tuple[LogisticRegression, dict]:
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=seed, stratify=y
    )
    model = LogisticRegression(
        solver="liblinear",
        C=1.0,
        max_iter=1000,
        random_state=seed,
    )
    model.fit(X_train, y_train)
    y_pred = model.predict(X_test)

    metrics = {
        "test_accuracy": float(accuracy_score(y_test, y_pred)),
        "confusion_matrix": confusion_matrix(y_test, y_pred).tolist(),
        "report": classification_report(y_test, y_pred, target_names=["no_panic", "panic"]),
        "n_train": int(len(X_train)),
        "n_test": int(len(X_test)),
    }
    return model, metrics


def main() -> int:
    here = os.path.dirname(__file__)
    parser = argparse.ArgumentParser(description="Train CalmSense logistic regression panic detector.")
    parser.add_argument("--csv", default=os.path.join(here, "training_data.csv"),
                        help="Path to training CSV (default: ml/training_data.csv)")
    parser.add_argument("--out", default=os.path.join(here, "model_weights.json"),
                        help="Path to write trained weights JSON (default: ml/model_weights.json)")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    print(f"Loading {args.csv} ...")
    X, y = load_data(args.csv)
    print(f"  X shape: {X.shape}, label balance: panic={int(y.sum())}, no_panic={int((y == 0).sum())}")

    print("Training LogisticRegression ...")
    model, metrics = train_and_evaluate(X, y, seed=args.seed)

    coefs = model.coef_[0]
    bias = float(model.intercept_[0])
    print(f"  test_accuracy = {metrics['test_accuracy']:.4f}")
    print(f"  confusion_matrix (rows=true, cols=pred):")
    for row in metrics["confusion_matrix"]:
        print(f"    {row}")
    print()
    print(metrics["report"])

    # Pack into the 5-slot array shape used by the existing /api/v1/sensor-data endpoint.
    # Slot order MUST match: [hr, hrv, motion, reserved, bias]
    weights_array = [
        float(coefs[0]),  # hr
        float(coefs[1]),  # hrv
        float(coefs[2]),  # motion
        0.0,              # reserved (future feature)
        bias,
    ]

    out = {
        "weights": weights_array,
        "feature_names": ["hr", "hrv", "motion", "reserved", "bias"],
        "model_type": "logistic_regression",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "training_samples": int(len(X)),
        "test_accuracy": metrics["test_accuracy"],
        "notes": (
            "Trained on synthetic data generated from physiological priors. "
            "See ml/generate_data.py for the priors and ml/README.md for details."
        ),
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
