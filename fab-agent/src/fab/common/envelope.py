"""도구 결과 공통 형식 — contracts/schemas/tool-result.schema.json 과 같은 모양.

{data, evidenceRefs, dataVersion, warnings, synthetic}
"""

from __future__ import annotations

from typing import Any

from fastmcp.exceptions import ToolError


def envelope(
    data: Any,
    *,
    data_version: str,
    evidence: list[dict] | None = None,
    warnings: list[str] | None = None,
    synthetic: bool = True,
) -> dict:
    return {
        "data": data if isinstance(data, dict) else {"items": data},
        "evidenceRefs": evidence or [],
        "dataVersion": data_version,
        "warnings": warnings or [],
        "synthetic": synthetic,
    }


def evidence_ref(evidence_id: str, source_type: str, source_id: str, data_version: str) -> dict:
    return {
        "evidenceId": evidence_id,
        "sourceType": source_type,
        "sourceId": source_id,
        "dataVersion": data_version,
        "synthetic": True,
    }


class FabToolError(ToolError):
    """모델이 읽는 오류. 메시지 앞에 [CODE]를 붙이고, 재시도 가능 여부와 힌트를 함께 준다."""

    def __init__(self, code: str, message: str, *, hint: str = "", retryable: bool = False):
        self.code = code
        self.retryable = retryable
        self.hint = hint
        self.raw = message
        tail = " 자동 재시도 대상입니다." if retryable else " 자동 재시도 대상이 아닙니다."
        text = f"[{code}] {message}"
        if hint:
            text += f" {hint}"
        super().__init__(text + tail)


RETRYABLE_CODES = {"UNAVAILABLE", "RATE_LIMITED", "TIMEOUT"}


def parse_error_code(text: str) -> str | None:
    """'[CODE] ...' 형식의 오류 문장에서 CODE를 꺼낸다."""
    if text.startswith("[") and "]" in text:
        return text[1 : text.index("]")]
    return None
