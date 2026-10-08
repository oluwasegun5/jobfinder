# syntax=docker/dockerfile:1
# Base images are pinned by tag AND digest (ADR 0041). Bump both together; a scheduled workflow (base-images.yml)
# reports when a newer digest exists for the tag.

FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS build
RUN pip install --no-cache-dir uv==0.8.17
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app

COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project

COPY app ./app
RUN uv sync --frozen --no-dev

FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /app /app
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1
USER app

EXPOSE 8000
# /health requires the service token like every other route.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=10 \
  CMD ["python", "-c", "import os, urllib.request; urllib.request.urlopen(urllib.request.Request('http://localhost:8000/health', headers={'X-Service-Token': os.environ['AI_SERVICE_TOKEN']}), timeout=4)"]
CMD ["uvicorn", "app.asgi:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
