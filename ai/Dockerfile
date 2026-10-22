FROM python:3.12-slim AS runtime
COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev
ENV PATH="/app/.venv/bin:$PATH"
# 서비스마다 command를 바꾼다: fab-business-mcp / fab-rag-mcp / fab-worker
# 평가 정답(evaluation/ground_truth.json)은 이 단계에 넣지 않는다 — Agent · MCP 컨테이너에 정답이 없어야 한다
CMD ["fab-rag-mcp"]

# 점검 · 평가 전용 (compose의 smoke) — 사람 역할만 하고 Agent를 돌리지 않는다
FROM runtime AS tools
COPY evaluation ./evaluation
