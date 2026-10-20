"""지식 코퍼스 읽기 · 임베딩 · 벡터 저장소.

코퍼스 셋 (동료 납품 묶음 — 적재 정책 rag/ingestion-policy.json을 따른다):
  - TECH     실제 원문 조각 100개   fab-rag-real-documents-v1/rag/source-chunks.jsonl (원문 · OCR, synthetic=false)
  - PLAYBOOK 팀 시연 점검 문서 15개  fab-causal-pack-v1/knowledge/rag-chunks.jsonl 중 origin=TEAM_SYNTHETIC만
             (공개 요약 9개는 실제 원문과 겹쳐 뺀다)
  - CASE     가상 과거 사례 7건     fab-causal-pack-v1/knowledge/synthetic-cases.jsonl
evaluation/ · originals/ · selected/ · 원시 센서는 색인하지 않는다.

- EMBEDDER=openai(compose · 시연 기본): OpenAIEmbedder — 의미 검색(EMBED_MODEL, 기본 text-embedding-3-small · 1536차원).
  키가 없거나 첫 호출이 실패하면 EmbedderError로 **시작에 실패**한다(해시로 조용히 떨어지지 않는다)
- EMBEDDER=hash(pytest · 키 없는 점검): HashEmbedder — 외부 API 없이 도는 글자 n-gram 해시(키워드 겹침에 가깝다)
- 문서는 embed_documents(묶음), 질의는 embed_query로 임베딩한다
- InMemoryStore: 로컬 기본값 / PgVectorStore: PG_DSN이 있을 때 knowledge.chunks_d{차원}
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
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


def index_text(c: Chunk) -> str:
    """임베딩에 넣는 글 — 조각 본문 그대로(팀 점검 문서는 load_corpora가 붙인 [원인ID · 후보명 · 센서] 머리줄 포함).

    원문 조각 앞에 제목 · 절 범위를 붙여 보았지만(2026-10-20 검색 평가) Hit@3이 오르지 않고 MRR이 내려 붙이지 않는다.
    """
    return c.text


def index_hash(c: Chunk) -> str:
    """증분 색인 키 — 임베딩에 넣는 글이 본문과 같으면 contentHash 그대로, 다르면 그 글의 sha256"""
    t = index_text(c)
    return c.contentHash if t == c.text else "sha256:" + hashlib.sha256(t.encode()).hexdigest()


def analysis_as_of(pack: Path | None = None) -> str:
    return json.loads(((pack or pack_dir()) / "knowledge" / "agent-contract.json").read_text(encoding="utf-8"))["analysisAsOf"]


# ------------------------------------------------------------------ 임베딩
class EmbedderError(RuntimeError):
    """임베더를 쓸 수 없다 — 지식 MCP는 시작하지 않는다. 메시지에는 원인만 쓰고 키 값은 쓰지 않는다."""


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

    def check(self) -> None:
        return None

    def embed_documents(self, texts: list[str]) -> np.ndarray:
        return np.stack([self._vec(t) for t in texts]) if texts else np.zeros((0, self.dim), dtype=np.float32)

    def embed_query(self, text: str) -> np.ndarray:
        return self._vec(text)


class OpenAIEmbedder:
    """OpenAI 임베딩 — 문서는 BATCH개씩 묶어 보내고, 429 · 일시 오류(연결 · 시간 초과 · 5xx)는 짧게 재시도한다.

    오류 메시지는 원인 분류만 담는다. SDK 예외 문구(가린 키 일부가 들어 있을 수 있다)는 옮기지 않고 연결도 끊는다(from None).
    """

    BATCH = 64
    RETRIES = 3  # 첫 시도 뒤 재시도 횟수
    BACKOFF = (0.5, 1.5, 3.0)

    def __init__(self, model: str | None = None, client=None):
        self.name = model or os.getenv("EMBED_MODEL") or "text-embedding-3-small"
        self.dim: int | None = None
        if client is None:
            if not os.getenv("OPENAI_API_KEY"):
                raise EmbedderError("EMBEDDER=openai인데 OPENAI_API_KEY가 없습니다 — fab-agent/.env에 키를 넣거나, "
                                    "키 없이 점검하려면 EMBEDDER=hash로 띄우세요.")
            from openai import OpenAI

            client = OpenAI(max_retries=0, timeout=float(os.getenv("EMBED_TIMEOUT", "30")))
        self._client = client

    def _call(self, texts: list[str]) -> np.ndarray:
        import openai

        for attempt in range(self.RETRIES + 1):
            try:
                res = self._client.embeddings.create(model=self.name, input=texts)
                arr = np.array([d.embedding for d in sorted(res.data, key=lambda d: d.index)], dtype=np.float32)
                return arr / np.linalg.norm(arr, axis=1, keepdims=True)
            except openai.AuthenticationError:
                raise EmbedderError("OpenAI 인증 실패(401) — OPENAI_API_KEY가 틀렸거나 만료됐습니다.") from None
            except openai.PermissionDeniedError:
                raise EmbedderError(f"OpenAI 권한 없음(403) — 이 키로 {self.name}를 쓸 수 없습니다.") from None
            except (openai.NotFoundError, openai.BadRequestError) as e:
                raise EmbedderError(f"OpenAI 요청 거절({e.status_code}) — EMBED_MODEL={self.name}을 확인하세요.") from None
            except (openai.RateLimitError, openai.APIConnectionError, openai.InternalServerError) as e:
                if attempt < self.RETRIES:
                    time.sleep(self.BACKOFF[attempt])
                    continue
                if isinstance(e, openai.RateLimitError):
                    why = "요청 한도 초과(429) — 잠시 뒤 다시 띄우거나 사용 한도를 확인하세요."
                elif isinstance(e, openai.APIConnectionError):
                    why = "네트워크 오류 — OpenAI API에 연결하지 못했습니다(시간 초과 포함)."
                else:
                    why = f"OpenAI 서버 오류({e.status_code})."
                raise EmbedderError(f"{why} {self.RETRIES + 1}번 시도했습니다.") from None
            except openai.APIStatusError as e:
                raise EmbedderError(f"OpenAI 오류({e.status_code}).") from None
        raise AssertionError("unreachable")

    def check(self) -> None:
        """시작 확인 — 짧은 질의 하나를 임베딩해 키 · 네트워크 · 모델을 확인하고 차원을 안다"""
        self.dim = int(self.embed_query("지식 MCP 시작 확인").shape[0])

    def embed_documents(self, texts: list[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dim or 0), dtype=np.float32)
        parts = [self._call(texts[i : i + self.BATCH]) for i in range(0, len(texts), self.BATCH)]
        arr = np.concatenate(parts)
        self.dim = int(arr.shape[1])
        return arr

    def embed_query(self, text: str) -> np.ndarray:
        return self._call([text])[0]


def make_embedder():
    """EMBEDDER=openai | hash. 비어 있으면 hash(알림 한 줄). openai인데 쓸 수 없으면 EmbedderError — 해시로 바꾸지 않는다"""
    kind = (os.getenv("EMBEDDER") or "").strip().lower()
    if kind == "openai":
        e = OpenAIEmbedder()
    elif kind in ("hash", ""):
        if not kind:
            print("[지식 MCP] EMBEDDER가 없어 시험용 해시 임베딩(hash-ngram-v1)을 씁니다. 의미 검색은 EMBEDDER=openai.",
                  file=sys.stderr)
        e = HashEmbedder()
    else:
        raise EmbedderError(f"EMBEDDER={kind}는 지원하지 않습니다 — openai 또는 hash.")
    e.check()
    return e


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
        self.mat = self.embedder.embed_documents([index_text(c) for c in chunks])
        return {"chunks": len(chunks), "embedded": len(chunks), "embedModel": self.embedder.name, "dim": int(self.mat.shape[1])}

    def search(self, query: str, corpus: str, filters: dict, top_k: int) -> list[tuple[Chunk, float]]:
        q = self.embedder.embed_query(query)
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
    """임베딩 차원마다 테이블을 따로 둔다(knowledge.chunks_d768 등). chunkId · contentHash로 증분 upsert, 사라진 조각은 지운다.

    검색은 지금 임베더의 표 · embed_model 행만 본다. 다른 차원 표(예: hash 시절 chunks_d768)는 남겨 두고 healthz의
    otherTables로 알린다 — EMBEDDER=hash로 다시 띄울 때 그대로 쓰인다. 지우려면 `fab-rag-index prune`.
    """

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
            todo = [c for c in chunks if have.get(c.chunkId) != (index_hash(c), self.embedder.name)]
            vecs = self.embedder.embed_documents([index_text(c) for c in todo]) if todo else []
            for c, v in zip(todo, vecs):
                con.execute(
                    f"""INSERT INTO {table} (chunk_id, corpus, doc_id, text, meta, embed_model, embedding, content_hash)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                       ON CONFLICT (chunk_id) DO UPDATE SET corpus=EXCLUDED.corpus, doc_id=EXCLUDED.doc_id,
                       text=EXCLUDED.text, meta=EXCLUDED.meta, embed_model=EXCLUDED.embed_model,
                       embedding=EXCLUDED.embedding, content_hash=EXCLUDED.content_hash""",
                    (c.chunkId, c.corpus, c.docId, c.text, json.dumps(c.meta, ensure_ascii=False), self.embedder.name,
                     "[" + ",".join(f"{x:.6f}" for x in v) + "]", index_hash(c)))
            ids = [c.chunkId for c in chunks]  # 빠진 조각(옛 문서 등)은 남기지 않는다
            con.execute(f"DELETE FROM {table} WHERE NOT (chunk_id = ANY(%s)) OR corpus IS NULL", (ids,))
            others = [t for t in knowledge_tables(con) if t != table]
        self.by_id = {c.chunkId: c for c in chunks}
        return {"chunks": len(chunks), "embedded": len(todo), "embedModel": self.embedder.name, "dim": dim, "table": table,
                "otherTables": others}

    def _dim(self, chunks: list[Chunk]) -> int:
        """표 이름을 정하려고 차원을 먼저 안다 — 해시는 설정값, OpenAI는 시작 확인(check)에서 안 값"""
        return getattr(self.embedder, "dim", None) or int(self.embedder.embed_query(index_text(chunks[0])).shape[0])

    def search(self, query, corpus, filters, top_k):
        q = self.embedder.embed_query(query)
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


def knowledge_tables(con) -> list[str]:
    rows = con.execute("""SELECT table_name FROM information_schema.tables
                          WHERE table_schema = 'knowledge' AND table_name LIKE 'chunks\\_d%' ORDER BY table_name""")
    return [f"knowledge.{r[0]}" for r in rows]


def admin_main(argv: list[str] | None = None) -> None:
    """fab-rag-index status | prune — 지식 색인 표 보기 · 쓰지 않는 표 지우기 (PG_DSN 필요, 임베딩 API는 부르지 않는다)

    prune은 지금 설정(EMBEDDER · EMBED_MODEL)의 모델 행이 있는 표만 남기고 나머지 chunks_d* 표를 지운다.
    """
    import argparse

    import psycopg

    from fab.common.env import load_env

    load_env()
    ap = argparse.ArgumentParser(prog="fab-rag-index")
    ap.add_argument("command", choices=["status", "prune"])
    ap.add_argument("--yes", action="store_true", help="prune에서 확인 없이 지운다")
    args = ap.parse_args(argv)
    dsn = os.getenv("PG_DSN") or "postgresql://fab:fab@localhost:55432/fab"
    kind = (os.getenv("EMBEDDER") or "hash").lower()
    model = (os.getenv("EMBED_MODEL") or "text-embedding-3-small") if kind == "openai" else HashEmbedder.name
    with psycopg.connect(dsn, autocommit=True) as con:
        tables = {t: dict(con.execute(f"SELECT embed_model, count(*) FROM {t} GROUP BY 1").fetchall())
                  for t in knowledge_tables(con)}
        for t, models in tables.items():
            mark = "사용 중" if model in models else "쓰지 않음"
            print(f"{t:28} {mark:6} " + ", ".join(f"{m} {n}행" for m, n in models.items()))
        if args.command == "status":
            return
        drop = [t for t, models in tables.items() if model not in models]
        if not drop:
            print(f"지울 표가 없습니다(현재 모델 {model}).")
            return
        if not args.yes and input(f"{', '.join(drop)}를 지웁니다(현재 모델 {model}). 계속할까요? [y/N] ").strip().lower() != "y":
            print("취소했습니다.")
            return
        for t in drop:
            con.execute(f"DROP TABLE {t}")
            print(f"지움: {t}")
