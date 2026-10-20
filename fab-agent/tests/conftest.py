"""테스트는 진짜 backend(Spring fab-context-api) 하나에 붙는다 — Python 대역은 없다.

SPRING_URL이 있으면 그 서버를 쓰고, 없으면 jar를 빌드해 외부 Agent 모드(시연 경로 켬)로 띄운 뒤 끝나면 내린다.
DB는 compose와 같은 이미지(pgvector/pgvector:pg16)의 Postgres 컨테이너를 testcontainers로 새로 띄우고,
시작할 때 backend · agent 스키마를 비운다 — 매번 처음 시드에서 시작한다(Docker 필요).
"""

import json
import os
import socket
import subprocess
import time
from pathlib import Path

import httpx
import pytest

os.environ["FAB_LLM"] = "rule"  # 테스트는 API 키 없이 결정적으로 돈다
os.environ["EMBEDDER"] = "hash"  # 지식 MCP도 키 · 네트워크 없이 — 시험용 해시 임베딩(.env의 EMBEDDER보다 앞선다)
_REPO = Path(__file__).resolve().parents[2]
# 묶음 경로는 코드 기본값이 없다 — 테스트 설정이 넣는다(셸에 이미 있으면 그 값). Spring jar도 이 값을 물려받는다
os.environ.setdefault("FAB_PACK_DIR", str(_REPO / "data" / "fab-causal-pack-v2"))
os.environ.setdefault("FAB_RAG_DIR", str(_REPO / "FAB-실제문서-선별RAG-센서데이터-통합" / "fab-rag-real-documents-v1"))
os.environ.pop("FAB_RESULT_EXTENSIONS", None)

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT.parent / "fab-context-api"
CONTRACTS = ROOT / "contracts"  # 팀 계약 사본 3개(원본: 팀 내부 fab-team-plan_draft/contracts)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


PG_IMAGE = "pgvector/pgvector:pg16"  # compose.yaml의 postgres와 같은 이미지


@pytest.fixture(scope="session")
def pg_container():
    """테스트 전용 Postgres 컨테이너 하나 — backend · agent 스키마를 비운 상태로 넘긴다."""
    import psycopg
    from testcontainers.postgres import PostgresContainer

    with PostgresContainer(PG_IMAGE, username="fab", password="fab", dbname="fab", driver=None) as pg:
        with psycopg.connect(pg.get_connection_url(), autocommit=True) as con:
            con.execute("DROP SCHEMA IF EXISTS backend CASCADE")
            con.execute("DROP SCHEMA IF EXISTS agent CASCADE")
        yield pg


@pytest.fixture(scope="session")
def postgres(pg_container):
    """Spring용 (jdbc_url, user, password)"""
    host, port = pg_container.get_container_host_ip(), pg_container.get_exposed_port(5432)
    return f"jdbc:postgresql://{host}:{port}/fab?currentSchema=backend", "fab", "fab"


@pytest.fixture(scope="session")
def pg_dsn(pg_container):
    """psycopg용 DSN — 지식 색인(knowledge 스키마) 테스트가 쓴다"""
    return pg_container.get_connection_url()


@pytest.fixture(scope="session")
def spring_url(request):
    url = os.getenv("SPRING_URL")
    if url:
        yield url
        return
    db_url, db_user, db_password = request.getfixturevalue("postgres")
    subprocess.run(["./gradlew", "bootJar", "-q"], cwd=BACKEND, check=True)
    jar = next(p for p in (BACKEND / "build" / "libs").glob("*.jar") if not p.name.endswith("-plain.jar"))
    port = _free_port()
    env = {**os.environ, "DB_URL": db_url, "DB_USER": db_user, "DB_PASSWORD": db_password,
           "AGENT_MODE": "external", "DEMO_ROUTES": "true", "PORT": str(port)}
    env.pop("SPRING_PROFILES_ACTIVE", None)
    proc = subprocess.Popen(["java", "-jar", str(jar)], cwd=BACKEND, env=env, stdout=subprocess.DEVNULL,
                            stderr=subprocess.STDOUT)
    url = f"http://127.0.0.1:{port}"
    for _ in range(120):
        try:
            # 웹 서버가 먼저 열리고 시드(ApplicationRunner)는 그 뒤에 끝난다 — 사건이 보일 때까지 기다린다
            r = httpx.get(url + "/internal/incidents/EV-1009", timeout=2,
                          headers={"Authorization": "Bearer " + os.getenv("SERVICE_TOKEN", "dev-service-token")})
            if r.status_code == 200:
                break
        except httpx.HTTPError:
            pass
        if proc.poll() is not None:
            raise RuntimeError("Spring backend가 뜨지 못했습니다")
        time.sleep(1)
    os.environ["SPRING_URL"] = url
    yield url
    proc.terminate()
    proc.wait(timeout=30)


@pytest.fixture(scope="session")
def kb():
    from fab.rag.server import Knowledge

    return Knowledge()


@pytest.fixture(scope="session")
def contract_validators():
    """팀 계약 JSON Schema 검증기 — (agent-event, job-message). 계약 폴더가 없으면 건너뛴다."""
    if not CONTRACTS.exists():
        pytest.skip("contracts 폴더가 없습니다")
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource

    openapi = json.loads((CONTRACTS / "public-openapi.json").read_text(encoding="utf-8"))
    openapi["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    reg = Registry().with_resource("file:///c/public-openapi.json", Resource.from_contents(openapi))
    ev = json.loads((CONTRACTS / "schemas/agent-event.schema.json").read_text(encoding="utf-8"))
    ev["$id"] = "file:///c/schemas/agent-event.schema.json"
    job = json.loads((CONTRACTS / "schemas/job-message.schema.json").read_text(encoding="utf-8"))
    return Draft202012Validator(ev, registry=reg), Draft202012Validator(job)
