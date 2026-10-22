"""ai/.env 를 읽는다. 이미 셸에 있는 환경 변수가 우선한다(override=False)."""

from __future__ import annotations

from pathlib import Path

from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[3]

def _required_dir(var: str, what: str, example: str) -> Path:
    """묶음 경로 — 기본값은 없다(원본은 compose 환경 변수). 상대 경로는 ai 폴더 기준으로 푼다(.env에 상대 경로를 적을 수 있다)"""
    import os

    value = os.getenv(var)
    if not value:
        raise RuntimeError(f"{var}({what})가 없습니다 — compose는 넣어 준다. 직접 실행할 때는 ai/.env에 {var}={example}")
    path = Path(value)
    return path if path.is_absolute() else (PROJECT_ROOT / path).resolve()


def pack_dir() -> Path:
    """데이터 팩 v2 조립본(causal-pack-v2.1-scenarios, ../data/build_pack_v2.py가 조립) — FAB_PACK_DIR"""
    return _required_dir("FAB_PACK_DIR", "데이터 팩 경로", "../data/fab-causal-pack-v2")


def rag_dir() -> Path:
    """실제 원문 선별 RAG(fab-rag-real-documents-v1) — FAB_RAG_DIR"""
    return _required_dir("FAB_RAG_DIR", "실제 원문 RAG 경로", "../data/fab-rag-real-documents-v1")


def load_env() -> None:
    load_dotenv(PROJECT_ROOT / ".env", override=False)
