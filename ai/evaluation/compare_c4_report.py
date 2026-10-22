"""C4 전후 비교 집계 — compare_c4.py의 out.json 둘을 같은 규칙(지금 판 fab.agent.stance)으로 센다.

  uv run python evaluation/compare_c4_report.py runs/<before>/out.json runs/<after>/out.json > 비교.md
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

from fab.agent.stance import REFUTED, SUPPORTED, UNDETERMINED, cause_stance

SEARCH_PREFIX = ("E-DOC-", "E-PB-", "E-CASE-")
LABEL = {SUPPORTED: "지지", REFUTED: "반증", UNDETERMINED: "판정 보류"}


def measure(out: dict) -> dict:
    rows = [row for row in out["rows"] if not row.get("error")]
    answered = [row for row in rows if row.get("result")]
    causes = [(row, cause) for row in answered for cause in row["result"].get("candidateCauses") or []]
    stances = Counter(cause_stance(cause) for _, cause in causes)
    per_answer = [[cause_stance(cause) for cause in row["result"].get("candidateCauses") or []] for row in answered]
    tops = [min(row["result"]["candidateCauses"], key=lambda c: c.get("priority") or 99) for row in answered
            if row["result"].get("candidateCauses")]
    conflicting = [(row, e) for row, cause in causes for e in cause.get("conflictingEvidenceIds") or []]
    refs = lambda row: {ref["evidenceId"] for ref in row["result"].get("evidenceRefs") or []}  # noqa: E731
    cited = [e for row in answered for e in ({e for fact in row["result"].get("observedFacts") or [] for e in fact.get("evidenceIds") or []}
                                            | {e for cause in row["result"].get("candidateCauses") or []
                                               for e in (cause.get("supportingEvidenceIds") or []) + (cause.get("conflictingEvidenceIds") or [])})]
    texts = out.get("toolTexts") or []
    search = [t for t in texts if t["search"]]
    stats = out["stats"]
    return {
        "입력(오류 · 답 없음)": f"{len(out['rows'])} ({len(out['rows']) - len(rows)} · {len(rows) - len(answered)})",
        "멈춤(한도 · 상한)": f"{sum(1 for row in answered if any('끝나기 전에 멈췄' in l for l in row['result'].get('limitations') or []))}"
                          f" · {'예' if out.get('stopped') else '아니오'}",
        "답당 원인 후보(평균)": round(len(causes) / max(1, len(answered)), 2),
        "후보 수 분포": dict(sorted(Counter(len(s) for s in per_answer).items())),
        "지지 · 반증 · 판정 보류": " · ".join(str(stances[k]) for k in (SUPPORTED, REFUTED, UNDETERMINED)),
        "반증이 있는 답": sum(1 for s in per_answer if REFUTED in s),
        "판정 보류가 있는 답": sum(1 for s in per_answer if UNDETERMINED in s),
        "1순위가 반증인 답": sum(1 for top in tops if cause_stance(top) == REFUTED),
        "반대 근거(개) · evidenceRefs에서 찾아짐": f"{len(conflicting)} · {sum(1 for row, e in conflicting if e in refs(row))}",
        "검색 근거 인용(개 · 문서 수)": f"{sum(1 for e in cited if e.startswith(SEARCH_PREFIX))} · "
                                 f"{len({e for e in cited if e.startswith(SEARCH_PREFIX)})}",
        "검색 결과(호출 · 잘림 · 잃은 근거 ID)": f"{len(search)} · {sum(t['truncated'] for t in search)} · {sum(t['lostIds'] for t in search)}",
        "다른 도구 결과 잘림": sum(t["truncated"] for t in texts if not t["search"]),
        "Guard 반려": out.get("guardRejects", 0),
        "업무 후보(개)": sum(len(row.get("workSuggestions") or []) for row in answered),
        "모델 호출 · 입력/출력 토큰": f"{stats['llmCalls']} · {stats['inputTokens']:,}/{stats['outputTokens']:,}",
        "비용(달러, 모델만)": stats["costUsd"],
        "모델 시간(초)": stats["llmSeconds"],
    }


def main() -> None:
    outs = [json.loads(Path(path).read_text(encoding="utf-8")) for path in sys.argv[1:3]]
    before, after = (measure(out) for out in outs)
    print("| 항목 | C4 이전 (6778906) | C4 뒤 (184ac83) |\n|---|---|---|")
    for key in before:
        print(f"| {key} | {before[key]} | {after[key]} |")
    print(f"\n- 이전 판 소스: `{outs[0]['fab']}`\n- 지금 판 소스: `{outs[1]['fab']}`")


if __name__ == "__main__":
    main()
