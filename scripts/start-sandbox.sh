#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
umask 077
mkdir -p .sandbox/work
if [[ ! -f .sandbox/manager-token ]]; then
  openssl rand -hex 32 > .sandbox/manager-token
fi
chmod 700 .sandbox
chmod 600 .sandbox/manager-token
export SANDBOX_WORK_ROOT="${SANDBOX_WORK_ROOT:-$PWD/.sandbox/work}"
if [[ "${1:-}" == "--prepare-only" ]]; then
  exit 0
fi
docker compose up -d --build sandbox-manager
