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


# 원문에 없는 관계 — 실제 모델은 원문(E-DOC)을 근거로 「압력이 낮으면 제거량이 준다」고 썼는데, 인용한 청크는 단차 측정 이야기였다.
# 원문을 근거로 두 용어 사이 관계를 말한 문장인데 인용한 원문 글에 두 용어가 함께 나오지 않으면 걸러 낸다(같이 나오면 받는다 — 느슨한 검사)
TERMS = {"압력": ("압력", "pressure"), "제거": ("제거", "removal", "remov", "polish rate", "mrr"), "슬러리": ("슬러리", "slurry"),
         "유량": ("유량", "flow"), "속도": ("속도", "velocity", "speed", "rpm"), "온도": ("온도", "temperature")}
_SENTENCE = re.compile(r"((?:[^.。!?\n]|\.\d)+)([.。!?]?)")
_DOC_ID = re.compile(r"E-DOC-[A-Za-z0-9_-]+")
_OTHER_ID = re.compile(r"E-(?!DOC-)[A-Z]+-[A-Za-z0-9_-]+")  # 조회 데이터 · 점검 문서 근거 — 함께 인용하면 그 근거가 관계를 받친다
_DOC_WORDS = re.compile(r"기술\s*문서|원문|논문|공개\s*문헌")  # 「점검 문서」 · 「이 문서」(팀 시연 점검 문서)는 원문이 아니다
_BACKREF = re.compile(r"^\s*(?:이\s*내용|이는|이\s*관계|이\s*원리|이러한\s*내용)")
_RELATION = re.compile(r"감소|줄|떨어|낮아|늘|높아|비례|영향|초래|유발|이어|좌우|결정")
_DISCLAIM = re.compile(r"(?:원문|문서)에\s*(?:는\s*)?없|(?:관계|언급|문장|근거)(?:[^.\n]){0,12}(?:없|않)|나와\s*있지\s*않|명시\s*(?:안|되지\s*않)|"
                       r"확인되지\s*않|어렵")
DOC_CLAIM_REJECT = ("인용한 원문에는 이 관계가 없습니다 — {detail}. 원문이 말한 범위만 쓰고, 이 관계는 원문에 없다고 밝히거나 "
                    "점검 문서 · 조회 데이터 근거로 적으세요.")
DOC_CLAIM_LIMITATION = "인용한 원문에 없는 관계를 말한 문장 {count}개를 뺐습니다(원문 과장)."


def _terms(sentence: str) -> list[str]:
    return [term for term in TERMS if term in sentence]


def _supported(pair: tuple[str, str], ids: list[str], chunks: dict[str, str]) -> bool:
    texts = [chunks[i].lower() for i in ids if i in chunks] or [text.lower() for text in chunks.values()]
    return any(all(any(word in text for word in TERMS[term]) for term in pair) for text in texts)


def _check(sentence: str, ids: list[str], chunks: dict[str, str]) -> str | None:
    terms = _terms(sentence)
    if len(terms) < 2 or not _RELATION.search(sentence) or _DISCLAIM.search(sentence):
        return None
    pairs = [(a, b) for i, a in enumerate(terms) for b in terms[i + 1:]]
    if any(_supported(pair, ids, chunks) for pair in pairs):
        return None
    return f"{', '.join(ids) or '인용한 원문'}에 「{'」·「'.join(terms)}」가 함께 나오지 않습니다"


def unsupported_relations(result: dict, chunks: dict[str, str]) -> list[tuple[str, str]]:
    """원문을 근거로 관계를 말했는데 인용한 원문 글이 받쳐 주지 않는 문장 — (문장, 이유). chunks: 이번 작업에서 본 E-DOC 글"""
    cited = [e for fact in result.get("observedFacts") or [] for e in fact.get("evidenceIds") or [] if str(e).startswith("E-DOC-")]
    found: list[tuple[str, str]] = []
    sentences = [m.group(1).strip() for m in _SENTENCE.finditer(str(result.get("answer") or "")) if m.group(1).strip()]
    for i, sentence in enumerate(sentences):
        following = sentences[i + 1] if i + 1 < len(sentences) else ""
        pointed = bool(_BACKREF.search(following)) and bool(_DOC_ID.search(following) or _DOC_WORDS.search(following))
        if _OTHER_ID.search(sentence) or not (_DOC_ID.search(sentence) or _DOC_WORDS.search(sentence) or pointed):
            continue
        why = _check(sentence, _DOC_ID.findall(sentence) or _DOC_ID.findall(following) or cited, chunks)
        if why:
            found.append((sentence, why))
    for fact in result.get("observedFacts") or []:
        ids = [e for e in fact.get("evidenceIds") or [] if str(e).startswith("E-DOC-")]
        backed = any(not str(e).startswith("E-DOC-") for e in fact.get("evidenceIds") or [])
        why = _check(str(fact.get("text") or ""), ids, chunks) if ids and not backed else None
        if why:
            found.append((str(fact.get("text")), why))
    return found
