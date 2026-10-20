"""fab-agent/.env 를 읽는다. 이미 셸에 있는 환경 변수가 우선한다(override=False)."""

from __future__ import annotations

from pathlib import Path

from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 동료 납품 묶음 — 데이터 팩 · 실제 원문 RAG의 유일한 원본 (Spring backend와 같은 폴더를 본다)
BUNDLE = PROJECT_ROOT.parent / "FAB-실제문서-선별RAG-센서데이터-통합"


# 데이터 팩 v2(causal-pack-v2-scenarios) — v1 + L006 수정 + sensorEnvelopes. ../data/build_pack_v2.py가 조립한다
PACK_V2 = PROJECT_ROOT.parent / "data" / "fab-causal-pack-v2"


def pack_dir() -> Path:
    """데이터 팩 — 기본은 v2 조립본. FAB_PACK_DIR로 바꾼다"""
    import os

    return Path(os.getenv("FAB_PACK_DIR", PACK_V2))


def rag_dir() -> Path:
    """실제 원문 선별 RAG(fab-rag-real-documents-v1) — FAB_RAG_DIR로 바꾼다"""
    import os

    return Path(os.getenv("FAB_RAG_DIR", BUNDLE / "fab-rag-real-documents-v1"))


def load_env() -> None:
    load_dotenv(PROJECT_ROOT / ".env", override=False)
