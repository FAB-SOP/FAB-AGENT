#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE="$ROOT/deploy/.env"
[[ -f "$ENV_FILE" ]] || ENV_FILE="$ROOT/deploy/.env.example"
if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <compose arguments>"
  echo "Example: $0 up -d --build --wait"
  exit 0
fi
exec docker compose --project-name "${DEPLOY_PROJECT_NAME:-fab-deploy}" \
  --env-file "$ENV_FILE" \
  -f "$ROOT/fab-agent/compose.yaml" -f "$ROOT/compose.web.yaml" "$@"
