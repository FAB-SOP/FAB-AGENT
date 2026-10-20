"""지식 코퍼스 읽기 · 임베딩 · 벡터 저장소.

코퍼스 셋 (동료 납품 묶음 — 적재 정책 rag/ingestion-policy.json을 따른다):
  - TECH     실제 원문 조각 100개   fab-rag-real-documents-v1/rag/source-chunks.jsonl (원문 · OCR, synthetic=false)
  - PLAYBOOK 팀 시연 점검 문서 15개  fab-causal-pack-v1/knowledge/rag-chunks.jsonl 중 origin=TEAM_SYNTHETIC만
             (공개 요약 9개는 실제 원문과 겹쳐 뺀다)
  - CASE     가상 과거 사례 7건     fab-causal-pack-v1/knowledge/synthetic-cases.jsonl
evaluation/ · originals/ · selected/ · 원시 센서는 색인하지 않는다.

- HashEmbedder: 외부 API 없이 도는 글자 n-gram 해시 임베딩(로컬 · 테스트용 — 키워드 검색에 가깝다)
- OpenAIEmbedder: EMBEDDER=openai 이고 OPENAI_API_KEY가 있을 때
- InMemoryStore: 로컬 기본값 / PgVectorStore: PG_DSN이 있을 때 knowledge.chunks_d{차원}
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from fab.common.env import pack_dir, rag_dir


@dataclass
class Chunk:
    chunkId: str
    corpus: str  # TECH | PLAYBOOK | CASE
    docId: str
    text: str
    meta: dict = field(default_factory=dict)

    @property
    def contentHash(self) -> str:
        """증분 색인 키 — 원문 조각은 납품된 contentHash, 나머지는 색인 본문의 sha256"""
        return self.meta.get("contentHash") or "sha256:" + hashlib.sha256(self.text.encode()).hexdigest()

    @property
    def resourceUri(self) -> str:
        return f"case://{self.docId}" if self.corpus == "CASE" else f"doc://{self.chunkId}"


def _lines(path: Path) -> list[dict]:
    return [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]


def load_corpora(pack: Path | None = None, rag: Path | None = None) -> list[Chunk]:
    pack, rag = pack or pack_dir(), rag or rag_dir()
    chunks: list[Chunk] = []
    source = rag / "rag" / "source-chunks.jsonl"
    if not source.exists():  # 공개 저장소에는 없다(출판자 저작물) — 팀 내부로 받아 이 경로에 둔다
        import sys

        print(f"[지식 MCP] 실제 원문 조각이 없어 기술 문서 검색은 비어 있습니다: {source}", file=sys.stderr)
    for r in _lines(source) if source.exists() else []:
        chunks.append(Chunk(r["chunkId"], "TECH", r["documentId"], r["text"], {
            "title": r["title"], "publisher": r.get("publisher"), "sourceUrl": r.get("sourceUrl"),
            "originalPdfPage": r.get("originalPdfPage"), "sectionScope": r.get("sectionScope"),
            "retrievalTier": r.get("retrievalTier"), "process": r.get("process") or [],
            "applicabilityLimits": r.get("applicabilityLimits"),
            "figuresEquationsTablesRequireSourceCheck": r.get("figuresEquationsTablesRequireSourceCheck", False),
            "approvedForProductionAction": False, "synthetic": False, "origin": "REAL_SOURCE_EXCERPT",
            "documentId": r["documentId"], "version": r.get("version"), "contentHash": r.get("contentHash")}))
    causes = {c["causeId"]: c for c in json.loads((pack / "knowledge" / "cause-matrix.json").read_text(encoding="utf-8"))}
    for r in _lines(pack / "knowledge" / "rag-chunks.jsonl"):
        if r.get("origin") != "TEAM_SYNTHETIC":
            continue
        cid = r["chunkId"].removeprefix("DEMO-")
        c = causes.get(cid, {})
        proc = r.get("process") or c.get("process") or "ALL"
        # 본문에 센서 ID가 없어(「헤드 압력」처럼 한국어만) 원인 매트릭스의 ID · 후보명 · 센서를 머리줄로 붙여 색인한다
        head = f"[{cid} · {c.get('candidate', '')} · 센서 {', '.join(c.get('sensorIds') or []) or '직접 태그 없음'}] " if c else ""
        chunks.append(Chunk(r["chunkId"], "PLAYBOOK", r["chunkId"], head + r["text"], {
            "causeId": cid if c else None, "candidate": c.get("candidate"), "sensorIds": c.get("sensorIds", []),
            "process": [proc] if proc != "ALL" else ["CVD", "ETCH", "CMP"], "sourceIds": r.get("sourceIds", []),
            "approvedForProductionAction": False, "synthetic": True, "origin": "TEAM_SYNTHETIC",
            "documentType": r.get("documentType"), "version": r.get("version")}))
    for r in _lines(pack / "knowledge" / "synthetic-cases.jsonl"):
        text = " / ".join([r["title"], "증상: " + r["symptom"], "근거: " + "; ".join(r.get("evidence", [])),
                           "대안 확인: " + "; ".join(r.get("alternativeChecks", [])), "조치(가상): " + r["action"],
                           "결과(가상): " + r["outcome"], "남은 불확실성: " + r["remainingUncertainty"]])
        proc = r.get("process") or "ALL"
        chunks.append(Chunk(r["caseId"], "CASE", r["caseId"], text, {
            "title": r["title"], "causeIds": r.get("causeIds", []),
            "process": [proc] if proc != "ALL" else ["CVD", "ETCH", "CMP"],
            "resolutionStatus": r.get("resolutionStatus"), "caseDate": r.get("caseDate"),
            "availableAt": r.get("availableAt"), "approvedForProductionAction": False, "synthetic": True,
            "origin": r.get("origin", "TEAM_SYNTHETIC"), "version": r.get("version")}))
    return chunks


def analysis_as_of(pack: Path | None = None) -> str:
    return json.loads(((pack or pack_dir()) / "knowledge" / "agent-contract.json").read_text(encoding="utf-8"))["analysisAsOf"]


# ------------------------------------------------------------------ 임베딩
class HashEmbedder:
    name = "hash-ngram-v1"

    def __init__(self, dim: int = 768):
        self.dim = dim

    def _vec(self, text: str) -> np.ndarray:
        v = np.zeros(self.dim, dtype=np.float32)
        t = re.sub(r"\s+", " ", text.lower())
        grams = [t[i : i + n] for n in (2, 3) for i in range(len(t) - n + 1)]
        grams += re.findall(r"[a-z0-9_.]+", t)
        for g in grams:
            h = int(hashlib.md5(g.encode()).hexdigest(), 16)
            v[h % self.dim] += 1.0 if (h >> 8) % 2 else -1.0
        n = np.linalg.norm(v)
        return v / n if n else v

    def embed(self, texts: list[str]) -> np.ndarray:
        return np.stack([self._vec(t) for t in texts])


class OpenAIEmbedder:
    def __init__(self, model: str | None = None):
        from langchain_openai import OpenAIEmbeddings

        self.name = model or os.getenv("EMBED_MODEL", "text-embedding-3-small")
        self._e = OpenAIEmbeddings(model=self.name)
        self.dim = None

    def embed(self, texts: list[str]) -> np.ndarray:
        arr = np.array(self._e.embed_documents(texts), dtype=np.float32)
        self.dim = arr.shape[1]
        return arr / np.linalg.norm(arr, axis=1, keepdims=True)


def make_embedder():
    if os.getenv("EMBEDDER") == "openai" and os.getenv("OPENAI_API_KEY"):
        return OpenAIEmbedder()
    return HashEmbedder()


# ------------------------------------------------------------------ 저장소
def matches(c: Chunk, corpus: str, filters: dict) -> bool:
    """corpus와 메타데이터 필터 — process는 목록 포함, 나머지는 같은 값 또는 목록 포함"""
    if c.corpus != corpus:
        return False
    for k, want in filters.items():
        if want is None:
            continue
        have = c.meta.get(k)
        if isinstance(have, list):
            if want not in have:
                return False
        elif have != want:
            return False
    return True


class InMemoryStore:
    def __init__(self, embedder):
        self.embedder = embedder
        self.chunks: list[Chunk] = []
        self.mat: np.ndarray | None = None

    def index(self, chunks: list[Chunk]) -> dict:
        self.chunks = chunks
        self.mat = self.embedder.embed([c.text for c in chunks])
        return {"chunks": len(chunks), "embedModel": self.embedder.name, "dim": int(self.mat.shape[1])}

    def search(self, query: str, corpus: str, filters: dict, top_k: int) -> list[tuple[Chunk, float]]:
        q = self.embedder.embed([query])[0]
        scores = self.mat @ q
        out = []
        for i in np.argsort(-scores):
            c = self.chunks[i]
            if matches(c, corpus, filters):
                out.append((c, float(scores[i])))
                if len(out) >= top_k:
                    break
        return out


class PgVectorStore:
    """임베딩 차원마다 테이블을 따로 둔다(knowledge.chunks_d768 등). chunkId · contentHash로 증분 upsert, 사라진 조각은 지운다."""

    def __init__(self, embedder, dsn: str):
        import psycopg

        self.embedder, self.dsn, self.psycopg = embedder, dsn, psycopg

    def index(self, chunks: list[Chunk]) -> dict:
        """적재 정책(incrementalUpdate)대로 chunkId · contentHash로 upsert — 본문 · 임베딩 모델이 같으면 다시 임베딩하지 않는다"""
        dim = self._dim(chunks)
        table = f"knowledge.chunks_d{dim}"
        with self.psycopg.connect(self.dsn, autocommit=True) as con:
            con.execute("CREATE EXTENSION IF NOT EXISTS vector")
            con.execute("CREATE SCHEMA IF NOT EXISTS knowledge")
            con.execute(f"""CREATE TABLE IF NOT EXISTS {table} (
                chunk_id text PRIMARY KEY, corpus text, doc_id text, text text,
                meta jsonb, embed_model text, embedding vector({dim}))""")
            con.execute(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS corpus text")
            con.execute(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS content_hash text")
            have = {cid: (h, m) for cid, h, m in con.execute(f"SELECT chunk_id, content_hash, embed_model FROM {table}")}
            todo = [c for c in chunks if have.get(c.chunkId) != (c.contentHash, self.embedder.name)]
            vecs = self.embedder.embed([c.text for c in todo]) if todo else []
            for c, v in zip(todo, vecs):
                con.execute(
                    f"""INSERT INTO {table} (chunk_id, corpus, doc_id, text, meta, embed_model, embedding, content_hash)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                       ON CONFLICT (chunk_id) DO UPDATE SET corpus=EXCLUDED.corpus, doc_id=EXCLUDED.doc_id,
                       text=EXCLUDED.text, meta=EXCLUDED.meta, embed_model=EXCLUDED.embed_model,
                       embedding=EXCLUDED.embedding, content_hash=EXCLUDED.content_hash""",
                    (c.chunkId, c.corpus, c.docId, c.text, json.dumps(c.meta, ensure_ascii=False), self.embedder.name,
                     "[" + ",".join(f"{x:.6f}" for x in v) + "]", c.contentHash))
            ids = [c.chunkId for c in chunks]  # 빠진 조각(옛 문서 등)은 남기지 않는다
            con.execute(f"DELETE FROM {table} WHERE NOT (chunk_id = ANY(%s)) OR corpus IS NULL", (ids,))
        self.by_id = {c.chunkId: c for c in chunks}
        return {"chunks": len(chunks), "embedded": len(todo), "embedModel": self.embedder.name, "dim": dim, "table": table}

    def _dim(self, chunks: list[Chunk]) -> int:
        """표 이름을 정하려고 차원을 먼저 안다 — 해시는 설정값, OpenAI는 한 조각만 임베딩해 본다"""
        return getattr(self.embedder, "dim", None) or int(self.embedder.embed([chunks[0].text]).shape[1])

    def search(self, query, corpus, filters, top_k):
        q = self.embedder.embed([query])[0]
        qs = "[" + ",".join(f"{x:.6f}" for x in q) + "]"
        sql = f"""SELECT chunk_id, 1 - (embedding <=> %s::vector) AS score FROM knowledge.chunks_d{len(q)}
                 WHERE embed_model = %s AND corpus = %s ORDER BY embedding <=> %s::vector LIMIT %s"""
        with self.psycopg.connect(self.dsn) as con:
            rows = con.execute(sql, [qs, self.embedder.name, corpus, qs, max(top_k * 10, 50)]).fetchall()
        out = []
        for cid, score in rows:
            c = self.by_id.get(cid)
            if c and matches(c, corpus, filters):
                out.append((c, float(score)))
                if len(out) >= top_k:
                    break
        return out


def make_store(embedder):
    dsn = os.getenv("PG_DSN")
    return PgVectorStore(embedder, dsn) if dsn else InMemoryStore(embedder)
