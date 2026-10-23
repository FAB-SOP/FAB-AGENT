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
    results: dict[str, list[dict]] = {}
    for message in messages:
        if not isinstance(message, ToolMessage) or (not keep_text and not message.name):
            continue
        try:
            results.setdefault(message.name, []).append(json.loads(message.content))
        except (ValueError, TypeError):
            if keep_text:
                results.setdefault(message.name, []).append({"text": message.content})
    return results


def tool_text(messages) -> str:
    """도구 결과 원문을 이어 붙인다 — guard가 「조회한 숫자만 썼는지」 볼 때 쓴다"""
    return "\n".join(str(message.content) for message in messages if isinstance(message, ToolMessage))


def looked_up_text(messages, selected_inputs=None) -> str:
    """조회한 글 — 업무 · 지식 도구 결과와 작업자가 고른 조사 결과. 질문 글은 넣지 않는다.
    제출 · 제안의 반려 문장도 도구 메시지라 뺀다 — 반려 문장에 적은 ID가 「조회한 것」이 되면 안 된다"""
    from fab.agent.tools import HARNESS_NAMES  # 순환 import 방지

    looked_up = [m for m in messages if isinstance(m, ToolMessage) and m.name not in HARNESS_NAMES]
    return "\n".join([tool_text(looked_up), str(selected_inputs or "")])


def data(env: dict) -> dict:
    return (env or {}).get("data") or {}


def first_evidence_id(res: dict) -> str:
    return (res.get("evidenceRefs") or [{}])[0].get("evidenceId", "")


def runs_by_process(seen: dict) -> dict[str, dict]:
    """get_run_and_metrology 결과(가용한 것만)를 공정별로 — 근거 ID(_ev)와 공정 순서(_seq)를 붙인다"""
    runs = {}
    for result in seen.get("get_run_and_metrology", []):
        run = result.get("data") or {}
        if run.get("available"):
            runs[run["processCode"]] = run | {"_ev": first_evidence_id(result), "_seq": PROCESS_SEQ.get(run["processCode"], 0)}
    return runs


def runs_by_id(seen: dict) -> dict[str, dict]:
    """get_run_and_metrology 결과를 runId별로 — available이 없으면 가용으로 본다"""
    runs: dict[str, dict] = {}
    for envelope in seen.get("get_run_and_metrology", []):
        run = data(envelope)
        if run.get("runId") and run.get("available", True):
            runs[run["runId"]] = run
    return runs


def features_of(seen: dict, run_id: str):
    for result in seen.get("get_run_features", []):
        if (result.get("data") or {}).get("runId") == run_id:
            return result | {"_ev": first_evidence_id(result)}
    return None


def trace_of(seen: dict, run_id: str, sensor: str):
    for result in seen.get("get_sensor_trace", []):
        trace_data = result.get("data") or {}
        if trace_data.get("runId") == run_id and trace_data.get("sensorId") == sensor:
            return result
    return None


def search_result(seen: dict, tool: str):
    """검색 도구의 첫 결과 — 항목의 chunkId로 근거 ID를 찾는 _ev_of를 붙인다"""
    for result in seen.get(tool, []):
        items = (result.get("data") or {}).get("items")
        if items is not None:
            evidence_by_chunk = {i["chunkId"]: i["evidenceId"] for i in items}
            return result | {"_ev_of": lambda i, evidence_by_chunk=evidence_by_chunk: evidence_by_chunk[i["chunkId"]]}
    return None
