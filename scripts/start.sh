#!/bin/sh
set -eu

alembic upgrade head

exec uvicorn device_manager.api:app \
  --host "${HOST:-0.0.0.0}" \
  --port "${PORT:-8000}" \
  --proxy-headers \
  --forwarded-allow-ips "${FORWARDED_ALLOW_IPS:-127.0.0.1}"
