"""테스트는 진짜 backend(Spring fab-context-api) 하나에 붙는다 — Python 대역은 없다.

SPRING_URL이 있으면 그 서버를 쓰고, 없으면 jar를 빌드해 외부 Agent 모드(H2 · 시연 경로 켬)로 띄운 뒤 끝나면 내린다.
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
os.environ.pop("FAB_RESULT_EXTENSIONS", None)

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT.parent / "fab-context-api"
CONTRACTS = ROOT / "contracts"  # 팀 계약 사본 3개(원본: 팀 내부 fab-team-plan_draft/contracts)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def spring_url():
    url = os.getenv("SPRING_URL")
    if url:
        yield url
        return
    subprocess.run(["./gradlew", "bootJar", "-q"], cwd=BACKEND, check=True)
    jar = next(p for p in (BACKEND / "build" / "libs").glob("*.jar") if not p.name.endswith("-plain.jar"))
    port = _free_port()
    env = {**os.environ, "SPRING_PROFILES_ACTIVE": "h2", "AGENT_MODE": "external", "DEMO_ROUTES": "true", "PORT": str(port)}
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
