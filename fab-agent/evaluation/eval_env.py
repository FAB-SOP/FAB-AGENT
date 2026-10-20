"""실제 모델 평가용 환경 — compose와 따로 둔다(compose의 Postgres · 작업을 건드리지 않는다).

  평가용 Postgres: docker 컨테이너 fab-eval-pg (호스트 55433, pgvector/pgvector:pg16, --rm)
  평가용 Spring : bootJar를 포트 8097 · AGENT_MODE=external · DEMO_ROUTES=true · DDL_AUTO=create로 띄운다(띄울 때마다 처음 시드)
  평가 쪽 프로세스: SPRING_URL=http://localhost:8097, PG_DSN 비움(지식 색인 · 체크포인트는 메모리)

다른 평가 스크립트(real_eval.py · scenario_eval.py · compare_models.py)가 import해서 쓴다. 직접 실행하면 띄운 뒤 끝낼 때까지 기다린다.
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path

import httpx

FAB_AGENT = Path(__file__).resolve().parents[1]
REPO = FAB_AGENT.parent
BACKEND = REPO / "fab-context-api"
RUNS = Path(__file__).resolve().parent / "runs"  # 평가 산출물(trace · 결과 원문) — git에 올리지 않는다
PG_NAME, PG_PORT, SPRING_PORT = "fab-eval-pg", 55433, 8097
PG_IMAGE = "pgvector/pgvector:pg16"
BASE = f"http://localhost:{SPRING_PORT}"
PACK = REPO / "data" / "fab-causal-pack-v2"
RAG = REPO / "FAB-실제문서-선별RAG-센서데이터-통합" / "fab-rag-real-documents-v1"


def client_env(model: str = "gpt-4.1-mini", fallback: str = "gpt-4o-mini", embedder: str = "openai", llm: str = "openai") -> None:
    """평가 쪽(이 프로세스) 환경 — .env보다 먼저 정한다(load_env는 이미 있는 값을 덮지 않는다). 키는 .env에서만 온다"""
    os.environ.update({"SPRING_URL": BASE, "PG_DSN": "", "FAB_PACK_DIR": str(PACK), "FAB_RAG_DIR": str(RAG),
                       "EMBEDDER": embedder, "FAB_LLM": llm, "FAB_MODEL": model, "FAB_FALLBACK_MODEL": fallback})
    os.environ.pop("FAB_RESULT_EXTENSIONS", None)


def start_postgres() -> None:
    subprocess.run(["docker", "rm", "-f", PG_NAME], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--rm", "--name", PG_NAME, "-e", "POSTGRES_USER=fab", "-e", "POSTGRES_PASSWORD=fab",
                    "-e", "POSTGRES_DB=fab", "-p", f"{PG_PORT}:5432", PG_IMAGE], check=True, capture_output=True)
    for _ in range(60):
        if subprocess.run(["docker", "exec", PG_NAME, "pg_isready", "-U", "fab"], capture_output=True).returncode == 0:
            time.sleep(1)
            return
        time.sleep(1)
    raise RuntimeError("평가용 Postgres가 뜨지 않았습니다")


def stop_postgres() -> None:
    subprocess.run(["docker", "stop", PG_NAME], capture_output=True)


def _jar() -> Path:
    subprocess.run(["./gradlew", "-q", "bootJar"], cwd=BACKEND, check=True)
    return next(p for p in (BACKEND / "build" / "libs").glob("*.jar") if not p.name.endswith("-plain.jar"))


class Spring:
    """평가용 Spring 하나 — start()마다 테이블을 새로 만들고 시드한다"""

    def __init__(self, log: Path):
        self.log, self.proc, self.jdbc = log, None, None

    def start(self) -> str:
        self.stop()
        env = {**os.environ, "DB_URL": f"jdbc:postgresql://localhost:{PG_PORT}/fab?currentSchema=backend", "DB_USER": "fab",
               "DB_PASSWORD": "fab", "PORT": str(SPRING_PORT), "AGENT_MODE": "external", "DEMO_ROUTES": "true",
               "DDL_AUTO": "create", "FAB_PACK_DIR": str(PACK), "FAB_RAG_DIR": str(RAG)}
        env.pop("SPRING_PROFILES_ACTIVE", None)
        self.log.parent.mkdir(parents=True, exist_ok=True)
        f = open(self.log, "a", encoding="utf-8")
        self.proc = subprocess.Popen(["java", "-jar", str(_jar())], cwd=BACKEND, env=env, stdout=f, stderr=subprocess.STDOUT)
        for _ in range(120):
            try:
                r = httpx.get(BASE + "/internal/incidents/EV-1009", timeout=2,
                              headers={"Authorization": "Bearer " + os.getenv("SERVICE_TOKEN", "dev-service-token")})
                if r.status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            if self.proc.poll() is not None:
                raise RuntimeError(f"평가용 Spring이 뜨지 못했습니다 — {self.log}")
            time.sleep(1)
        m = re.findall(r"jdbc:postgresql://[^\s\]]+", self.log.read_text(encoding="utf-8", errors="replace"))
        self.jdbc = m[-1] if m else None
        if not self.jdbc or f":{PG_PORT}/" not in self.jdbc:
            self.stop()
            raise RuntimeError(f"평가용 Spring이 평가용 DB가 아닌 곳에 붙었습니다: {self.jdbc}")
        return self.jdbc

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait(timeout=30)
        self.proc = None


@contextmanager
def environment(run_dir: Path):
    """평가용 Postgres + Spring을 띄우고, 끝나면 둘 다 정리한다"""
    start_postgres()
    spring = Spring(run_dir / "spring.log")
    try:
        yield spring
    finally:
        spring.stop()
        stop_postgres()


if __name__ == "__main__":
    d = RUNS / time.strftime("%Y%m%d-%H%M%S")
    with environment(d) as s:
        print("평가용 Spring:", BASE, "JDBC:", s.start())
        input("Enter를 누르면 정리합니다 ")
