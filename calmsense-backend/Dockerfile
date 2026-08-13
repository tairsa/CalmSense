# CalmSense backend image.
#
# Works on both linux/amd64 (cloud: GCP/AWS/Azure) and linux/arm64
# (Raspberry Pi 5). All deps — including numpy/pandas/scikit-learn — ship
# prebuilt manylinux/aarch64 wheels for cp312, so no compiler is needed.
#
# Build (native):        docker build -t calmsense-backend .
# Build (Pi from x86):   docker buildx build --platform linux/arm64 -t calmsense-backend .
# Run:                   docker run -p 8000:8000 -v calmsense-data:/app/data calmsense-backend

FROM python:3.12-slim

# - PYTHONUNBUFFERED so logs stream out immediately (good for `docker logs`)
# - PYTHONDONTWRITEBYTECODE to avoid .pyc clutter in the image
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Install dependencies first so this layer caches across code changes.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the application code (see .dockerignore for what's excluded).
COPY . .

# Run as a non-root user; make the runtime data dir writable by it.
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /app/data \
    && chown -R appuser:appuser /app/data
USER appuser

EXPOSE 8000

# Persist the JSON fallback store (used when Supabase isn't configured).
VOLUME ["/app/data"]

# Liveness probe hitting the app's /health endpoint (no curl needed).
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')" || exit 1

# No --reload in production.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
