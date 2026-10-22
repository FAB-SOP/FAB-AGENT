#!/usr/bin/env bash
set -euo pipefail
# 프론트까지 포함한 로컬 배포 — ai/compose.yaml에 이 폴더의 compose.web.yaml을 덧붙인다
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
ENV_FILE="$HERE/.env"
[[ -f "$ENV_FILE" ]] || ENV_FILE="$HERE/.env.example"
if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <compose arguments>"
  echo "Example: $0 up -d --build --wait"
  exit 0
fi
exec docker compose --project-name "${DEPLOY_PROJECT_NAME:-fab-deploy}" \
  --env-file "$ENV_FILE" \
  -f "$ROOT/ai/compose.yaml" -f "$HERE/compose.web.yaml" "$@"
