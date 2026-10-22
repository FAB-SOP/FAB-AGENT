"""지식 MCP 임베딩 — 네트워크 · 키 없이 검증한다(가짜 임베더 · 가짜 OpenAI 클라이언트).

- 문서는 embed_documents, 질의는 embed_query로만 임베딩한다
- OpenAI 임베더: 묶음 전송, 429 · 연결 오류 재시도, 오류 메시지에 키가 남지 않음
- EMBEDDER=openai인데 키가 없으면 시작 실패(해시로 떨어지지 않는다)
- Postgres 색인: 증분(두 번째 embedded=0), 차원별 표 분리, 검색은 지금 모델의 표만
"""

import hashlib
import types

import httpx2
import numpy as np
import openai
import pytest

from fab.rag import index as ix
from fab.rag.index import Chunk, EmbedderError, HashEmbedder, InMemoryStore, OpenAIEmbedder, PgVectorStore, make_embedder


class FakeEmbedder:
    """글자 해시로 dim차원 벡터를 만든다. 문서 · 질의 호출을 따로 센다."""

    def __init__(self, name="fake-a", dim=8):
        self.name, self.dim = name, dim
        self.doc_calls, self.query_calls = [], []

    def _vec(self, t):
        v = np.array([int(hashlib.md5(f"{t}:{i}".encode()).hexdigest(), 16) % 1000 - 500 for i in range(self.dim)], dtype=np.float32)
        return v / np.linalg.norm(v)

    def check(self):
        pass

    def embed_documents(self, texts):
        self.doc_calls.append(list(texts))
        return np.stack([self._vec(t) for t in texts]) if texts else np.zeros((0, self.dim), dtype=np.float32)

    def embed_query(self, text):
        self.query_calls.append(text)
        return self._vec(text)


def chunks(n=5, corpus="PLAYBOOK"):
    return [Chunk(f"C{i}", corpus, f"C{i}", f"본문 {i}", {"process": ["CMP"]}) for i in range(n)]


def test_store_uses_documents_for_index_and_query_for_search():
    e = FakeEmbedder()
    st = InMemoryStore(e)
    info = st.index(chunks())
    assert info["embedded"] == 5 and e.query_calls == [] and len(e.doc_calls) == 1
    hits = st.search("본문 3", "PLAYBOOK", {}, 2)
    assert e.query_calls == ["본문 3"] and len(e.doc_calls) == 1  # 질의는 embed_query 한 번, 문서 임베딩은 다시 안 한다
    assert hits[0][0].chunkId == "C3"


# ------------------------------------------------------------------ OpenAI 임베더 (가짜 클라이언트)
REQ = httpx2.Request("POST", "https://api.openai.com/v1/embeddings")
FAKE_KEY = "sk-test-DO-NOT-LEAK-1234"


def status_error(cls, code, msg=f"Incorrect API key provided: {FAKE_KEY}"):
    return cls(msg, response=httpx2.Response(code, request=REQ), body=None)


class FakeClient:
    """embeddings.create(model, input) — 정해 둔 오류를 차례로 낸 뒤 dim차원 벡터를 돌려준다"""

    def __init__(self, errors=(), dim=6):
        self.errors, self.dim, self.calls = list(errors), dim, []
        self.embeddings = types.SimpleNamespace(create=self.create)

    def create(self, model, input):
        self.calls.append(len(input))
        if self.errors:
            raise self.errors.pop(0)
        data = [types.SimpleNamespace(index=i, embedding=[float(len(t) + i + 1)] + [1.0] * (self.dim - 1)) for i, t in enumerate(input)]
        return types.SimpleNamespace(data=list(reversed(data)))  # 순서가 뒤섞여 와도 index로 맞춘다


@pytest.fixture(autouse=True)
def no_sleep(monkeypatch):
    monkeypatch.setattr(ix.time, "sleep", lambda s: None)


def test_openai_batches_documents_and_normalizes():
    c = FakeClient()
    e = OpenAIEmbedder("text-embedding-3-small", client=c)
    arr = e.embed_documents([f"t{i}" for i in range(OpenAIEmbedder.BATCH * 2 + 3)])
    assert c.calls == [OpenAIEmbedder.BATCH, OpenAIEmbedder.BATCH, 3]
    assert arr.shape == (OpenAIEmbedder.BATCH * 2 + 3, 6) and e.dim == 6
    assert np.allclose(np.linalg.norm(arr, axis=1), 1)
    assert e.embed_query("q").shape == (6,) and c.calls[-1] == 1


def test_openai_retries_429_and_transient_then_succeeds():
    c = FakeClient([status_error(openai.RateLimitError, 429, "rate limited"), openai.APIConnectionError(request=REQ),
                    status_error(openai.InternalServerError, 503, "busy")])
    e = OpenAIEmbedder(client=c)
    e.check()
    assert e.dim == 6 and c.calls == [1, 1, 1, 1]


@pytest.mark.parametrize("errors, cause", [
    ([status_error(openai.AuthenticationError, 401)], "인증 실패"),
    ([openai.APITimeoutError(request=REQ)] * 4, "네트워크 오류"),
    ([status_error(openai.RateLimitError, 429)] * 4, "요청 한도 초과"),
])
def test_openai_errors_name_the_cause_without_the_key(errors, cause):
    e = OpenAIEmbedder(client=FakeClient(errors))
    with pytest.raises(EmbedderError) as ei:
        e.check()
    msg = str(ei.value)
    assert cause in msg and FAKE_KEY not in msg and "sk-" not in msg
    assert ei.value.__cause__ is None and ei.value.__suppress_context__  # SDK 예외(키 일부가 들어 있을 수 있다)를 잇지 않는다


def test_openai_without_key_fails_startup(monkeypatch):
    monkeypatch.setenv("EMBEDDER", "openai")
    monkeypatch.setenv("OPENAI_API_KEY", "")
    with pytest.raises(EmbedderError, match="OPENAI_API_KEY가 없습니다"):
        make_embedder()
    from fab.rag.server import Knowledge

    with pytest.raises(EmbedderError):  # 지식 MCP 자체가 뜨지 않는다 — 해시로 바꾸지 않는다
        Knowledge()


def test_unknown_embedder_fails_and_hash_is_explicit(monkeypatch):
    monkeypatch.setenv("EMBEDDER", "bogus")
    with pytest.raises(EmbedderError, match="지원하지 않습니다"):
        make_embedder()
    monkeypatch.setenv("EMBEDDER", "hash")
    assert isinstance(make_embedder(), HashEmbedder)


# ------------------------------------------------------------------ Postgres 색인 (증분 · 차원별 표)
def test_pgvector_incremental_and_tables_per_dim(pg_dsn):
    import psycopg

    with psycopg.connect(pg_dsn, autocommit=True) as con:
        con.execute("DROP SCHEMA IF EXISTS knowledge CASCADE")
    cs = chunks()
    a = FakeEmbedder("fake-a", 8)
    st = PgVectorStore(a, pg_dsn)
    first = st.index(cs)
    assert (first["table"], first["embedded"], first["otherTables"]) == ("knowledge.chunks_d8", 5, [])
    assert st.index(cs)["embedded"] == 0  # 두 번째 기동 — 본문 · 모델이 같으면 다시 임베딩하지 않는다
    cs[2].text = "바뀐 본문"
    assert st.index(cs)["embedded"] == 1
    assert st.index(cs[:4])["embedded"] == 0  # 빠진 조각은 지운다
    assert PgVectorStore(FakeEmbedder("fake-a2", 8), pg_dsn).index(cs[:4])["embedded"] == 4  # 같은 차원 · 다른 모델

    b = FakeEmbedder("fake-b", 4)
    sb = PgVectorStore(b, pg_dsn)
    info = sb.index(cs[:4])
    assert (info["table"], info["embedded"], info["otherTables"]) == ("knowledge.chunks_d4", 4, ["knowledge.chunks_d8"])
    hits = sb.search("본문 1", "PLAYBOOK", {}, 1)
    assert hits[0][0].chunkId == "C1" and b.query_calls == ["본문 1"]
    with psycopg.connect(pg_dsn) as con:  # 옛 표는 그대로 남는다(hash로 다시 띄우면 그대로 쓴다)
        assert con.execute("SELECT count(*) FROM knowledge.chunks_d8").fetchone()[0] == 4
