"""지식 MCP 검색 평가 — 세 검색 도구마다 임베더(hash · openai)별 Hit@3 · MRR을 잰다.

  uv run python evaluation/retrieval_eval.py                 # hash와 openai 둘 다(openai는 .env의 OPENAI_API_KEY 필요)
  uv run python evaluation/retrieval_eval.py --embedder hash  # 키 없이
  uv run python evaluation/retrieval_eval.py --details        # 질의마다 순위

질의셋: evaluation/retrieval_queries.json. 색인은 메모리에 새로 만든다(PG_DSN을 쓰지 않는다 — compose DB를 건드리지 않는다).
도구와 같은 검색 함수(Knowledge.search — 분석 시점 이후 사례 제외 포함)를 상위 10개로 불러 순위를 본다.
키가 필요해 pytest가 아니라 수동 실행이다.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

QUERIES = Path(__file__).with_name("retrieval_queries.json")
CORPUS = {"search_technical_documents": "TECH", "search_applicable_playbooks": "PLAYBOOK", "search_past_cases": "CASE"}
DEPTH = 10


def hit(item: dict, expect: dict) -> bool:
    for key, want in expect.items():
        have = item.get(key)
        if isinstance(have, list) and set(have) & set(want):
            return True
        if have in want:
            return True
    return False


def _rank(kb, query: str, corpus: str, expect: dict) -> int | None:
    items = kb.search(query, corpus, {}, DEPTH)
    for i, it in enumerate(items):
        c = kb.chunks[it["chunkId"]]
        if hit({**it, "causeId": c.meta.get("causeId"), "documentId": c.meta.get("documentId")}, expect):
            return i + 1
    return None


def run(embedder: str, queries: list[dict]) -> list[dict]:
    os.environ["EMBEDDER"] = embedder
    from fab.rag.server import Knowledge

    kb = Knowledge()
    rows = []
    for q in queries:
        items = kb.search(q["query"], CORPUS[q["tool"]], {}, DEPTH)
        for it in items:  # 메타데이터 일부는 search 결과에 없으니 조각에서 채운다
            c = kb.chunks[it["chunkId"]]
            it.setdefault("causeId", c.meta.get("causeId"))
            it.setdefault("documentId", c.meta.get("documentId"))
        rank = next((i + 1 for i, it in enumerate(items) if hit(it, q["expect"])), None)
        rows.append({**q, "rank": rank, "top": [it["chunkId"] for it in items[:3]], "model": kb.index_info["embedModel"]})
        if q.get("agentQuery"):  # 같은 뜻을 영어 용어와 함께 쓴 질의 — 따로 센다
            rows.append({**q, "id": q["id"] + "a", "query": q["agentQuery"], "variant": "agent",
                         "rank": _rank(kb, q["agentQuery"], CORPUS[q["tool"]], q["expect"]), "top": [],
                         "model": kb.index_info["embedModel"]})
    return rows


def table(results: dict[str, list[dict]]) -> str:
    out = ["| 도구 | 임베더 | 질의 | Hit@3 | MRR | 바꿔 쓴 질의 Hit@3 |", "|---|---|---|---|---|---|"]
    for tool in CORPUS:
        for emb, rows in results.items():
            rs = [r for r in rows if r["tool"] == tool and not r.get("variant")]
            para = [r for r in rs if r.get("paraphrase")]
            h3 = sum(1 for r in rs if r["rank"] and r["rank"] <= 3) / len(rs)
            mrr = sum(1 / r["rank"] for r in rs if r["rank"]) / len(rs)
            ph = f"{sum(1 for r in para if r['rank'] and r['rank'] <= 3)}/{len(para)}" if para else "—"
            out.append(f"| {tool} | {rs[0]['model']} | {len(rs)} | {h3:.2f} | {mrr:.3f} | {ph} |")
            ag = [r for r in rows if r["tool"] == tool and r.get("variant") == "agent"]
            if ag:
                h3 = sum(1 for r in ag if r["rank"] and r["rank"] <= 3) / len(ag)
                mrr = sum(1 / r["rank"] for r in ag if r["rank"]) / len(ag)
                out.append(f"| └ 영어 병기(agentQuery) | {rs[0]['model']} | {len(ag)} | {h3:.2f} | {mrr:.3f} | — |")
    return "\n".join(out)


def main() -> None:
    from fab.common.env import load_env

    ap = argparse.ArgumentParser()
    ap.add_argument("--embedder", nargs="+", default=["hash", "openai"], choices=["hash", "openai"])
    ap.add_argument("--details", action="store_true")
    args = ap.parse_args()
    load_env()
    os.environ.pop("PG_DSN", None)
    queries = json.loads(QUERIES.read_text(encoding="utf-8"))["queries"]
    from fab.rag.index import EmbedderError

    results = {}
    for emb in args.embedder:
        try:
            results[emb] = run(emb, queries)
        except EmbedderError as e:
            print(f"[{emb}] 건너뜀: {e}", file=sys.stderr)
    if not results:
        raise SystemExit(1)
    print(table(results))
    if args.details:
        for emb, rows in results.items():
            print(f"\n### {emb}")
            for r in rows:
                mark = "✔" if r["rank"] and r["rank"] <= 3 else "✘"
                print(f"{mark} {r['id']} 순위 {r['rank'] or '10위 밖'}  {r['query']}  → {', '.join(r['top'])}")


if __name__ == "__main__":
    main()
