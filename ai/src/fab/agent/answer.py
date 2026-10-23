"""질문에 대한 직접 답(answer) — 결과의 첫 문장. 질문 · 추가 분석 · 요청서 작업은 필수, 기본 분석은 선택.

결과 양식이 관측 사실 · 원인 후보 · 한계뿐이라, 모델이 무슨 질문이든 원인 분석 모양으로 답했다(평가셋 AE08 · AE12).
할 수 없는 요청(원인 확률 · 보고서 확정 · 분석 시점 이후 자료 · 설정값 처방)은 할 수 없다고 먼저 밝히는 것이 답이다.
그래서 확률 · 처방 · 확정 검사는 문장마다 보고, 할 수 없다고 밝힌 문장(「RF 값은 제시할 수 없습니다」)은 넘긴다.
"""

from __future__ import annotations

import re

from fab.agent import purpose
from fab.agent.harness.guard import _PRESCRIBE, _PROB

MAX_CHARS = 600
REQUIRED_FOR = {purpose.QUESTION, purpose.FOLLOWUP, *purpose.HUMAN_REQUESTED}
_SENTENCE = re.compile(r"[^.。!?\n]+")
_REFUSAL = re.compile(r"않|없|못|금지|아니|어렵|필요|SOP|승인")
# 「해결되었는지 확인」처럼 묻는 꼴은 단정이 아니다
_CLAIM = re.compile(r"(?:확정(?:되었|됐|입니다|이다|적|된|이라고|할 수 있)|해결(?:됐|되었|완료|된 것))(?!는지|을지|는가|던지)")
_EVIDENCE_ID = re.compile(r"(?<![A-Za-z0-9-])E-[A-Za-z0-9_-]*[A-Za-z0-9_]")

# 할 수 없는 요청의 필수 문구 — 원인 확률 · 설정값을 물으면 답이 「할 수 없다」를 반드시 말한다. 실제 모델은 확률 질문에
# 「가능성이 높다」로만 답하거나(평가셋 AE08), RF 값 질문에 「올려서 해결될 상황이 아니다」로만 답하고 승인 SOP를 말하지 않았다(AE10)
_ASKS_PROBABILITY = re.compile(r"확률|몇\s*%|몇\s*퍼센트")
_SAYS_NO_PROBABILITY = re.compile(r"확률[^.。\n]{0,30}(제시하지|제시할 수 없|말할 수 없|드릴 수 없|내지 않|쓰지 않|표시하지)")
_ASKS_SETTING = re.compile(r"설정값|세트포인트|레시피|몇\s*(?:W|psi|sccm|rpm|mL|도|초)|(?:올리|낮추|줄이|늘리|바꾸)면\s*(?:돼|되|될)")
_SAYS_SOP = re.compile(r"SOP|승인")
STATEMENTS = (
    (_ASKS_PROBABILITY, _SAYS_NO_PROBABILITY, "원인 확률은 숫자로 제시하지 않습니다 — 근거로 본 우선순위로 답합니다."),
    (_ASKS_SETTING, _SAYS_SOP, "장비 설정값 변경은 장비별 승인 SOP와 담당자 검토가 필요해 값을 제시하지 않습니다."),
)


def missing_statements(question: str | None, result: dict) -> list[str]:
    """질문이 원인 확률 · 설정값을 묻는데 답이 할 수 없다고 말하지 않았으면, 답에 들어가야 할 문장들"""
    answer = str(result.get("answer") or "")
    return [sentence for asks, says, sentence in STATEMENTS if asks.search(question or "") and not says.search(answer)]


def answer_evidence_ids(result: dict) -> list[str]:
    """답 글에 괄호로 인용한 근거 ID — 결과의 evidenceRefs에 넣고, 조회한 것인지 검사한다"""
    return list(dict.fromkeys(_EVIDENCE_ID.findall(str(result.get("answer") or ""))))


def validate_answer(result: dict, job_purpose: str | None, known_evidence: set[str] | None = None) -> list[str]:
    answer = str(result.get("answer") or "").strip()
    if not answer:
        if job_purpose in REQUIRED_FOR:
            return ["질문에 대한 직접 답(answer)이 없습니다. 첫 문장으로 질문에 답하세요 — 할 수 없는 요청(원인 확률 · 보고서 확정 · "
                    "분석 시점 이후 자료 · 설정값 처방)이면 할 수 없다고 먼저 밝히고 이유와 대신 할 수 있는 것을 쓰세요."]
        return []
    errors = []
    if len(answer) > MAX_CHARS:
        errors.append(f"answer는 {MAX_CHARS}자 안의 1~3문장으로 씁니다(지금 {len(answer)}자).")
    stated = [s.strip() for s in _SENTENCE.findall(answer) if not _REFUSAL.search(s)]

    def hit(pattern) -> str | None:  # 걸린 문장을 반려 문장에 그대로 보여 준다 — 모델이 어느 문장을 고칠지 알게
        return next((s for s in stated if pattern.search(s)), None)
    if sentence := hit(_PROB):
        errors.append(f"answer에 원인 확률을 숫자로 쓰지 않습니다(「{sentence[:80]}」). 확률은 제시할 수 없다고 답하고 증거 기반 우선순위로 설명하세요.")
    if sentence := hit(_PRESCRIBE):
        errors.append(f"answer가 장비 설정값 변경을 처방합니다(「{sentence[:80]}」). 승인 SOP와 담당자 검토가 필요하다고 답하세요.")
    if sentence := hit(_CLAIM):
        errors.append(f"answer가 원인 · 추세 · 해결을 확정합니다(「{sentence[:80]}」). Agent는 확정하지 않습니다 — 근거로 볼 수 "
                      "있는 것과 확정에 필요한 것(후속 run · 담당자 확인)을 나눠 답하세요.")
    if known_evidence is not None:
        unknown = [e for e in answer_evidence_ids(result) if e not in known_evidence]
        if unknown:
            errors.append(f"answer의 근거 ID {unknown}는 이번 조사에서 조회하지 않았습니다.")
    return errors
