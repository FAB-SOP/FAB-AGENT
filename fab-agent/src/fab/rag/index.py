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
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_corpora(pack: Path | None = None, rag: Path | None = None) -> list[Chunk]:
    pack, rag = pack or pack_dir(), rag or rag_dir()
    return [*_technical_chunks(rag), *_playbook_chunks(pack), *_case_chunks(pack)]


def _technical_chunks(rag: Path) -> list[Chunk]:
    """TECH — 실제 원문 조각"""
    source = rag / "rag" / "source-chunks.jsonl"
    if not source.exists():  # 공개 저장소에는 없다(출판자 저작물) — 팀 내부로 받아 이 경로에 둔다
        print(f"[지식 MCP] 실제 원문 조각이 없어 기술 문서 검색은 비어 있습니다: {source}", file=sys.stderr)
        return []
    return [Chunk(row["chunkId"], "TECH", row["documentId"], row["text"], {
        "title": row["title"], "publisher": row.get("publisher"), "sourceUrl": row.get("sourceUrl"),
        "originalPdfPage": row.get("originalPdfPage"), "sectionScope": row.get("sectionScope"),
        "retrievalTier": row.get("retrievalTier"), "process": row.get("process") or [],
        "applicabilityLimits": row.get("applicabilityLimits"),
        "figuresEquationsTablesRequireSourceCheck": row.get("figuresEquationsTablesRequireSourceCheck", False),
        "approvedForProductionAction": False, "synthetic": False, "origin": "REAL_SOURCE_EXCERPT",
        "documentId": row["documentId"], "version": row.get("version"), "contentHash": row.get("contentHash")})
        for row in _lines(source)]


def _playbook_chunks(pack: Path) -> list[Chunk]:
    """PLAYBOOK — 팀 시연 점검 문서(TEAM_SYNTHETIC만)"""
    causes = {cause["causeId"]: cause
              for cause in json.loads((pack / "knowledge" / "cause-matrix.json").read_text(encoding="utf-8"))}
    chunks: list[Chunk] = []
    for row in _lines(pack / "knowledge" / "rag-chunks.jsonl"):
        if row.get("origin") != "TEAM_SYNTHETIC":
            continue
        cause_id = row["chunkId"].removeprefix("DEMO-")
        cause = causes.get(cause_id, {})
        process = row.get("process") or cause.get("process") or "ALL"
        # 본문에 센서 ID가 없어(「헤드 압력」처럼 한국어만) 원인 매트릭스의 ID · 후보명 · 센서를 머리줄로 붙여 색인한다
        sensors = ', '.join(cause.get('sensorIds') or []) or '직접 태그 없음'
        head = f"[{cause_id} · {cause.get('candidate', '')} · 센서 {sensors}] " if cause else ""
        chunks.append(Chunk(row["chunkId"], "PLAYBOOK", row["chunkId"], head + row["text"], {
            "causeId": cause_id if cause else None, "candidate": cause.get("candidate"), "sensorIds": cause.get("sensorIds", []),
            "process": [process] if process != "ALL" else ["CVD", "ETCH", "CMP"], "sourceIds": row.get("sourceIds", []),
            "approvedForProductionAction": False, "synthetic": True, "origin": "TEAM_SYNTHETIC",
            "documentType": row.get("documentType"), "version": row.get("version")}))
    return chunks


def _case_text(row: dict) -> str:
    return " / ".join([row["title"], "증상: " + row["symptom"], "근거: " + "; ".join(row.get("evidence", [])),
                       "대안 확인: " + "; ".join(row.get("alternativeChecks", [])), "조치(가상): " + row["action"],
                       "결과(가상): " + row["outcome"], "남은 불확실성: " + row["remainingUncertainty"]])


def _case_chunks(pack: Path) -> list[Chunk]:
    """CASE — 가상 과거 사례"""
    chunks: list[Chunk] = []
    for row in _lines(pack / "knowledge" / "synthetic-cases.jsonl"):
        process = row.get("process") or "ALL"
        chunks.append(Chunk(row["caseId"], "CASE", row["caseId"], _case_text(row), {
            "title": row["title"], "causeIds": row.get("causeIds", []),
            "process": [process] if process != "ALL" else ["CVD", "ETCH", "CMP"],
            "resolutionStatus": row.get("resolutionStatus"), "caseDate": row.get("caseDate"),
            "availableAt": row.get("availableAt"), "approvedForProductionAction": False, "synthetic": True,
            "origin": row.get("origin", "TEAM_SYNTHETIC"), "version": row.get("version")}))
    return chunks


def index_text(chunk: Chunk) -> str:
    """임베딩에 넣는 글 — 조각 본문 그대로(팀 점검 문서는 load_corpora가 붙인 [원인ID · 후보명 · 센서] 머리줄 포함).

    원문 조각 앞에 제목 · 절 범위를 붙여 보았지만(2026-10-20 검색 평가) Hit@3이 오르지 않고 MRR이 내려 붙이지 않는다.
    """
    return chunk.text


def index_hash(chunk: Chunk) -> str:
    """증분 색인 키 — 임베딩에 넣는 글이 본문과 같으면 contentHash 그대로, 다르면 그 글의 sha256"""
    embedded_text = index_text(chunk)
    return chunk.contentHash if embedded_text == chunk.text else "sha256:" + hashlib.sha256(embedded_text.encode()).hexdigest()


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
        vector = np.zeros(self.dim, dtype=np.float32)
        normalized = re.sub(r"\s+", " ", text.lower())
        grams = [normalized[i : i + size] for size in (2, 3) for i in range(len(normalized) - size + 1)]
        grams += re.findall(r"[a-z0-9_.]+", normalized)
        for gram in grams:
            digest = int(hashlib.md5(gram.encode()).hexdigest(), 16)
            vector[digest % self.dim] += 1.0 if (digest >> 8) % 2 else -1.0
        norm = np.linalg.norm(vector)
        return vector / norm if norm else vector

    def check(self) -> None:
        return None

    def embed_documents(self, texts: list[str]) -> np.ndarray:
        return np.stack([self._vec(text) for text in texts]) if texts else np.zeros((0, self.dim), dtype=np.float32)

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
                response = self._client.embeddings.create(model=self.name, input=texts)
                ordered = sorted(response.data, key=lambda item: item.index)
                vectors = np.array([item.embedding for item in ordered], dtype=np.float32)
                return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)
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
        vectors = np.concatenate(parts)
        self.dim = int(vectors.shape[1])
        return vectors

    def embed_query(self, text: str) -> np.ndarray:
        return self._call([text])[0]


def make_embedder():
    """EMBEDDER=openai | hash. 비어 있으면 hash(알림 한 줄). openai인데 쓸 수 없으면 EmbedderError — 해시로 바꾸지 않는다"""
    kind = (os.getenv("EMBEDDER") or "").strip().lower()
    if kind == "openai":
        embedder = OpenAIEmbedder()
    elif kind in ("hash", ""):
        if not kind:
            print("[지식 MCP] EMBEDDER가 없어 시험용 해시 임베딩(hash-ngram-v1)을 씁니다. 의미 검색은 EMBEDDER=openai.",
                  file=sys.stderr)
        embedder = HashEmbedder()
    else:
        raise EmbedderError(f"EMBEDDER={kind}는 지원하지 않습니다 — openai 또는 hash.")
    embedder.check()
    return embedder


# ------------------------------------------------------------------ 저장소
def matches(chunk: Chunk, corpus: str, filters: dict) -> bool:
    """corpus와 메타데이터 필터 — process는 목록 포함, 나머지는 같은 값 또는 목록 포함"""
    if chunk.corpus != corpus:
        return False
    for k, want in filters.items():
        if want is None:
            continue
        have = chunk.meta.get(k)
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
        self.mat = self.embedder.embed_documents([index_text(chunk) for chunk in chunks])
        return {"chunks": len(chunks), "embedded": len(chunks), "embedModel": self.embedder.name, "dim": int(self.mat.shape[1])}

    def search(self, query: str, corpus: str, filters: dict, top_k: int) -> list[tuple[Chunk, float]]:
        query_vector = self.embedder.embed_query(query)
        scores = self.mat @ query_vector
        results = []
        for i in np.argsort(-scores):
            chunk = self.chunks[i]
            if matches(chunk, corpus, filters):
                results.append((chunk, float(scores[i])))
                if len(results) >= top_k:
                    break
        return results


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
            stored = con.execute(f"SELECT chunk_id, content_hash, embed_model FROM {table}")
            have = {chunk_id: (content_hash, embed_model) for chunk_id, content_hash, embed_model in stored}
            todo = [chunk for chunk in chunks if have.get(chunk.chunkId) != (index_hash(chunk), self.embedder.name)]
            vectors = self.embedder.embed_documents([index_text(chunk) for chunk in todo]) if todo else []
            for chunk, vector in zip(todo, vectors):
                con.execute(
                    f"""INSERT INTO {table} (chunk_id, corpus, doc_id, text, meta, embed_model, embedding, content_hash)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                       ON CONFLICT (chunk_id) DO UPDATE SET corpus=EXCLUDED.corpus, doc_id=EXCLUDED.doc_id,
                       text=EXCLUDED.text, meta=EXCLUDED.meta, embed_model=EXCLUDED.embed_model,
                       embedding=EXCLUDED.embedding, content_hash=EXCLUDED.content_hash""",
                    (chunk.chunkId, chunk.corpus, chunk.docId, chunk.text, json.dumps(chunk.meta, ensure_ascii=False),
                     self.embedder.name,
                     "[" + ",".join(f"{value:.6f}" for value in vector) + "]", index_hash(chunk)))
            ids = [chunk.chunkId for chunk in chunks]  # 빠진 조각(옛 문서 등)은 남기지 않는다
            con.execute(f"DELETE FROM {table} WHERE NOT (chunk_id = ANY(%s)) OR corpus IS NULL", (ids,))
            others = [table_name for table_name in knowledge_tables(con) if table_name != table]
        self.by_id = {chunk.chunkId: chunk for chunk in chunks}
        return {"chunks": len(chunks), "embedded": len(todo), "embedModel": self.embedder.name, "dim": dim, "table": table,
                "otherTables": others}

    def _dim(self, chunks: list[Chunk]) -> int:
        """표 이름을 정하려고 차원을 먼저 안다 — 해시는 설정값, OpenAI는 시작 확인(check)에서 안 값"""
        return getattr(self.embedder, "dim", None) or int(self.embedder.embed_query(index_text(chunks[0])).shape[0])

    def search(self, query, corpus, filters, top_k):
        query_vector = self.embedder.embed_query(query)
        query_literal = "[" + ",".join(f"{value:.6f}" for value in query_vector) + "]"
        sql = f"""SELECT chunk_id, 1 - (embedding <=> %s::vector) AS score FROM knowledge.chunks_d{len(query_vector)}
                 WHERE embed_model = %s AND corpus = %s ORDER BY embedding <=> %s::vector LIMIT %s"""
        with self.psycopg.connect(self.dsn) as con:
            rows = con.execute(sql, [query_literal, self.embedder.name, corpus, query_literal, max(top_k * 10, 50)]).fetchall()
        results = []
        for chunk_id, score in rows:
            chunk = self.by_id.get(chunk_id)
            if chunk and matches(chunk, corpus, filters):
                results.append((chunk, float(score)))
                if len(results) >= top_k:
                    break
        return results


def make_store(embedder):
    dsn = os.getenv("PG_DSN")
    return PgVectorStore(embedder, dsn) if dsn else InMemoryStore(embedder)


def knowledge_tables(con) -> list[str]:
    rows = con.execute("""SELECT table_name FROM information_schema.tables
                          WHERE table_schema = 'knowledge' AND table_name LIKE 'chunks\\_d%' ORDER BY table_name""")
    return [f"knowledge.{row[0]}" for row in rows]


def admin_main(argv: list[str] | None = None) -> None:
    """fab-rag-index status | prune — 지식 색인 표 보기 · 쓰지 않는 표 지우기 (PG_DSN 필요, 임베딩 API는 부르지 않는다)

    prune은 지금 설정(EMBEDDER · EMBED_MODEL)의 모델 행이 있는 표만 남기고 나머지 chunks_d* 표를 지운다.
    """
    import argparse

    import psycopg

    from fab.common.env import load_env

    load_env()
    parser = argparse.ArgumentParser(prog="fab-rag-index")
    parser.add_argument("command", choices=["status", "prune"])
    parser.add_argument("--yes", action="store_true", help="prune에서 확인 없이 지운다")
    args = parser.parse_args(argv)
    dsn = os.getenv("PG_DSN") or "postgresql://fab:fab@localhost:55432/fab"
    kind = (os.getenv("EMBEDDER") or "hash").lower()
    model = (os.getenv("EMBED_MODEL") or "text-embedding-3-small") if kind == "openai" else HashEmbedder.name
    with psycopg.connect(dsn, autocommit=True) as con:
        tables = {table: dict(con.execute(f"SELECT embed_model, count(*) FROM {table} GROUP BY 1").fetchall())
                  for table in knowledge_tables(con)}
        for table, models in tables.items():
            mark = "사용 중" if model in models else "쓰지 않음"
            print(f"{table:28} {mark:6} " + ", ".join(f"{row_model} {row_count}행" for row_model, row_count in models.items()))
        if args.command == "status":
            return
        drop = [table for table, models in tables.items() if model not in models]
        if not drop:
            print(f"지울 표가 없습니다(현재 모델 {model}).")
            return
        if not args.yes and input(f"{', '.join(drop)}를 지웁니다(현재 모델 {model}). 계속할까요? [y/N] ").strip().lower() != "y":
            print("취소했습니다.")
            return
        for table in drop:
            con.execute(f"DROP TABLE {table}")
            print(f"지움: {table}")
