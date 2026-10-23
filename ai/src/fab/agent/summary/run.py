"""요약 만들기 — 모델에게 확정 스냅샷을 주고 submit_summary로 1~2줄을 받는다. 검사에 걸리면 사유를 돌려주고 한 번 더,
그래도 걸리면 규칙 문장. 규칙 모델(FAB_LLM=rule)은 처음부터 규칙 문장이다."""

from __future__ import annotations

import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from fab.agent.harness.retry import call_with_recovery
from fab.agent.summary.guard import display_id, section_texts, validate_summary
from fab.agent.summary.rule import rule_summary

PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "summary.md"
MODEL_TRIES = 2
SUMMARY_TOOL = {"type": "function", "function": {
    "name": "submit_summary",
    "description": "확정 보고서의 요약 1~2줄을 제출한다. 숫자 · 근거는 보고서에 있는 것만, 원인 확률 · 설정값 처방 금지.",
    "parameters": {"type": "object", "properties": {
        "lines": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 2,
                  "description": "요약 문장 1~2줄, 줄당 120자 이내"},
        "evidenceIds": {"type": "array", "items": {"type": "string"},
                        "description": "요약이 기대는 근거 ID — 확정 보고서의 evidenceIds 중에서"}},
        "required": ["lines", "evidenceIds"]}}}


def _is_rule(model) -> bool:
    return str(getattr(model, "name", "")).startswith("rule")


async def _ask(primary, fallback, messages, tracer):
    with tracer.span("llm", node="summary") as span_record:
        reply, used = await call_with_recovery(primary, fallback, messages, [SUMMARY_TOOL], tracer)
        usage = getattr(reply, "usage_metadata", None) or {}
        name = getattr(primary if used == "primary" else fallback, "name", used)
        meta = getattr(reply, "response_metadata", None) or {}
        span_record.update(model=used, modelName=name, apiModel=meta.get("model_name") or name,
                           tokens=usage.get("total_tokens", 0), inputTokens=usage.get("input_tokens", 0),
                           outputTokens=usage.get("output_tokens", 0))
    return reply


async def make_summary(snapshot: dict, primary, fallback, tracer) -> dict:
    """{lines, evidenceIds, generatedBy: model | rule}"""
    if _is_rule(primary):
        return rule_summary(snapshot)
    facts = {"displayId": display_id(snapshot), "reportVersion": snapshot.get("reportVersion"), "sections": section_texts(snapshot),
             "classification": snapshot.get("classification"), "evidenceIds": snapshot.get("evidenceIds")}
    messages = [SystemMessage(content=PROMPT.read_text(encoding="utf-8")),
                HumanMessage(content="[확정 보고서]\n" + json.dumps(facts, ensure_ascii=False, indent=1))]
    for _ in range(MODEL_TRIES):
        reply = await _ask(primary, fallback, messages, tracer)
        call = next((c for c in reply.tool_calls or [] if c["name"] == "submit_summary"), None)
        if call is None:
            messages += [reply, HumanMessage(content="[Harness] 요약은 submit_summary로 제출하세요.")]
            continue
        lines, evidence_ids = list(call["args"].get("lines") or []), list(call["args"].get("evidenceIds") or [])
        errors = validate_summary(lines, evidence_ids, snapshot)
        if not errors:
            return {"lines": lines, "evidenceIds": evidence_ids, "generatedBy": "model"}
        tracer.event("guard_reject", tool="submit_summary", errors=errors)
        messages += [reply, ToolMessage(content="[REJECTED] " + " / ".join(errors), tool_call_id=call["id"], name="submit_summary")]
    return rule_summary(snapshot)
