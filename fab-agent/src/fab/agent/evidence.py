"""도구 결과 해석 헬퍼 — 대화(ToolMessage)에 쌓인 도구 결과를 도구 이름별로 모으고, run · 특징 · 시계열 · 검색 결과를 찾는다.

브리프(brief.py)와 규칙 모델(models/rule.py)이 같이 쓴다. 숫자는 도구 결과(Spring 계산값)를 그대로 옮기기만 한다.
"""

from __future__ import annotations

import json

from langchain_core.messages import ToolMessage

PROCESS_SEQ = {"CVD": 1, "ETCH": 2, "CMP": 3}


def tool_results(messages, *, keep_text: bool = False) -> dict[str, list[dict]]:
    """ToolMessage(JSON 도구 결과)를 도구 이름별로 모은다.

    keep_text=False(브리프): 이름 없는 메시지와 잘린 결과처럼 JSON이 아닌 것은 건너뛴다.
    keep_text=True(규칙 모델): JSON이 아니면 {"text": 원문}으로 남긴다(오류 문장도 '불렀다'는 사실로 쓴다).
    """
    out: dict[str, list[dict]] = {}
    for m in messages:
        if not isinstance(m, ToolMessage) or (not keep_text and not m.name):
            continue
        try:
            out.setdefault(m.name, []).append(json.loads(m.content))
        except (ValueError, TypeError):
            if keep_text:
                out.setdefault(m.name, []).append({"text": m.content})
    return out


def tool_text(messages) -> str:
    """도구 결과 원문을 이어 붙인다 — guard가 「조회한 숫자만 썼는지」 볼 때 쓴다"""
    return "\n".join(str(m.content) for m in messages if isinstance(m, ToolMessage))


def data(env: dict) -> dict:
    return (env or {}).get("data") or {}


def first_evidence_id(res: dict) -> str:
    return (res.get("evidenceRefs") or [{}])[0].get("evidenceId", "")


def runs_by_process(seen: dict) -> dict[str, dict]:
    """get_run_and_metrology 결과(가용한 것만)를 공정별로 — 근거 ID(_ev)와 공정 순서(_seq)를 붙인다"""
    out = {}
    for r in seen.get("get_run_and_metrology", []):
        d = r.get("data") or {}
        if d.get("available"):
            out[d["processCode"]] = d | {"_ev": first_evidence_id(r), "_seq": PROCESS_SEQ.get(d["processCode"], 0)}
    return out


def runs_by_id(seen: dict) -> dict[str, dict]:
    """get_run_and_metrology 결과를 runId별로 — available이 없으면 가용으로 본다"""
    runs: dict[str, dict] = {}
    for env in seen.get("get_run_and_metrology", []):
        d = data(env)
        if d.get("runId") and d.get("available", True):
            runs[d["runId"]] = d
    return runs


def features_of(seen: dict, run_id: str):
    for r in seen.get("get_run_features", []):
        if (r.get("data") or {}).get("runId") == run_id:
            return r | {"_ev": first_evidence_id(r)}
    return None


def trace_of(seen: dict, run_id: str, sensor: str):
    for r in seen.get("get_sensor_trace", []):
        d = r.get("data") or {}
        if d.get("runId") == run_id and d.get("sensorId") == sensor:
            return r
    return None


def search_result(seen: dict, tool: str):
    """검색 도구의 첫 결과 — 항목의 chunkId로 근거 ID를 찾는 _ev_of를 붙인다"""
    for r in seen.get(tool, []):
        items = (r.get("data") or {}).get("items")
        if items is not None:
            ev = {i["chunkId"]: i["evidenceId"] for i in items}
            return r | {"_ev_of": lambda i, ev=ev: ev[i["chunkId"]]}
    return None
