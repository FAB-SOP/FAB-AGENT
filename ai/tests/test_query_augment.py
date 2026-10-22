"""한·영 용어 병기(augment_query) — 실제 원문(TECH) 검색 질의 전처리."""
import os

from fab.rag.server import augment_query, generic_terms, load_terms

TERMS = load_terms()


def test_adds_english_terms_next_to_korean():
    out = augment_query("관리한계 이탈과 규격 이탈 차이", TERMS)
    assert "관리한계(control limit)" in out and "규격(specification)" in out
    assert out.startswith("관리한계(")  # 한국어는 지우지 않는다


def test_longest_term_wins_and_no_duplicate_english():
    out = augment_query("질량유량제어기 교정 오차", TERMS)
    assert "질량유량제어기(mass flow controller MFC)" in out
    assert out.count("mass flow") == 1  # '질량유량'으로 다시 붙이지 않는다
    assert "교정(calibration)" in out


def test_skips_when_english_already_present():
    q = "관리 한계(control limit)와 규격 한계(specification limit) 이탈 차이"
    assert augment_query(q, TERMS) == q


def test_each_term_once_and_env_switch_off(monkeypatch):
    out = augment_query("압력 압력 압력", TERMS)
    assert out.count("(pressure)") == 1
    monkeypatch.setenv("FAB_QUERY_AUGMENT", "0")
    assert augment_query("관리한계", TERMS) == "관리한계"


def test_terms_file_values_are_english():
    for ko, en in TERMS.items():
        assert en.isascii() and en.strip(), (ko, en)


def test_generic_terms_from_document_frequency():
    docs = {"D1": "process pressure etch", "D2": "process pressure", "D3": "process",
            "D4": "process pressure", "D5": "MFC calibration"}
    terms = {"공정": "process", "압력": "pressure", "식각": "etch", "질량유량계": "mass flow controller MFC"}
    assert generic_terms(terms, docs, min_docs=4) == frozenset({"공정"})
    assert generic_terms(terms, docs, min_docs=3) == frozenset({"공정", "압력"})


def test_generic_only_when_no_specific_term():
    terms = {"공정": "process", "경계층": "boundary layer"}
    generic = frozenset({"공정"})
    assert augment_query("경계층 공정", terms, generic) == "경계층(boundary layer) 공정"
    assert augment_query("공정 변화", terms, generic) == "공정(process) 변화"


def test_alternatives_do_not_reduce_to_single_common_word():
    from fab.rag.server import _alternatives
    assert _alternatives("ICP power") == ["ICP power", "ICP"]
    assert _alternatives("mass flow controller MFC") == ["mass flow controller MFC", "mass flow controller", "MFC"]
    assert _alternatives("boundary layer") == ["boundary layer"]


def test_every_term_value_appears_in_source_documents():
    """사전의 영어 값은 원문에 실제로 나오는 구절 또는 약어여야 한다(원문 조각이 없으면 건너뜀)."""
    import json
    import pytest
    from fab.common.env import rag_dir
    from fab.rag.server import _alternatives, _term_pattern
    path = rag_dir() / "rag" / "source-chunks.jsonl"
    if not path.exists():
        pytest.skip("source-chunks.jsonl 없음(공개 저장소 제외 파일)")
    text = " ".join(json.loads(line)["text"] for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    missing = [(ko, en) for ko, en in TERMS.items() if not any(_term_pattern(a).search(text) for a in _alternatives(en))]
    assert not missing, missing
