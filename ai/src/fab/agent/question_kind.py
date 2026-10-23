"""질문 종류 — 수치 · 사실만 묻는 질문인가(답만), 원인 · 판단을 묻는 질문인가(지금처럼 원인 분석).

지시(「물은 것만 답한다」)만으로는 모델이 「CMP 공정에서 제거량 알려줘」에도 원인 후보 · 요청서를 붙였다(10-23 화면 · 재현).
그래서 Harness가 질문 글로 가르고, 답만 할 질문에는 원인 후보 · 할 일 후보 칸이 없는 양식과 요청서 없는 도구 목록을 준다.
헷갈리면 원인 질문으로 둔다 — 원인 · 판단을 묻는 말이 하나라도 있으면 답만으로 줄이지 않는다.
"""

from __future__ import annotations

import re

_ASKS_FACT = re.compile(r"알려|얼마|수치|값|몇|언제|이력|목록|보여|정확한|어느\s*정도")
_ASKS_JUDGEMENT = re.compile(r"원인|왜|이유|고장|문제|영향|확인해야|요청|점검|분석|조치|대책|가능성|배제|근거|어떻게|해야|판단|"
                             r"맞아|아니야|정상이야|이상이야|탓")


def answer_only(question: str | None) -> bool:
    text = question or ""
    return bool(_ASKS_FACT.search(text)) and not _ASKS_JUDGEMENT.search(text)


NOTE = ("[답만 하는 질문] 이 질문은 수치 · 사실을 묻는다. `answer`에 물은 값 · 사실을 근거 ID와 함께 쓰고, `observedFacts`에는 그 근거만 "
        "넣는다. 원인 후보 · 할 일 후보 · 요청서는 이번 답에 없다 — 묻지 않은 공정 · 원인 이야기를 덧붙이지 않는다.")
