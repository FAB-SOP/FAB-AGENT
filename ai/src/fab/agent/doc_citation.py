"""문서 근거를 요구하는 질문 — 「기술 문서 근거로 설명해줘」처럼 원리 · 문서를 묻는 질문은 원문(E-DOC)을 인용해야 접수한다.

실제 모델은 업무 근거가 대화에 다 있으면 지식 검색 없이 바로 제출했다(질문 작업도 절반 이하만 검색). 기본 분석까지 원문을
강제하면 비용 · 잡음이 늘어나므로 이런 질문에만 요구한다. 한 번 반려하고, 두 번째는 한계에 남기고 받는다(한도 안에 끝나게).
"""

from __future__ import annotations

import re

_ASKS = re.compile(r"(기술\s*)?문서|원문|원리|기술\s*자료|매뉴얼|논문|메커니즘")
LIMITATION = "문서 근거 미확인 — 질문이 기술 문서 근거를 요구했지만 원문(E-DOC)을 인용하지 못했습니다. 업무 데이터 근거로만 답했습니다."
REJECT = ("질문이 문서 · 원리 근거를 요구합니다. search_technical_documents로 원문을 찾아, 원문이 말하는 내용을 관측 사실이나 "
          "원인 후보 지지 근거에 넣고 그 evidenceIds에 E-DOC-* 를 붙이세요(answer 글의 괄호 인용만으로는 근거 목록에 묶이지 않습니다). "
          "맞는 원문이 없으면 그대로 다시 제출하면 한계에 「문서 근거 미확인」으로 남깁니다.")


def asks_for_documents(question: str | None) -> bool:
    return bool(_ASKS.search(question or ""))


def cites_document(result: dict) -> bool:
    cited = ({e for fact in result.get("observedFacts") or [] for e in fact.get("evidenceIds") or []}
             | {e for cause in result.get("candidateCauses") or []
                for e in (cause.get("supportingEvidenceIds") or []) + (cause.get("conflictingEvidenceIds") or [])})
    return any(str(e).startswith("E-DOC-") for e in cited)
