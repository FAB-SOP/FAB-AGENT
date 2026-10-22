"""보고서 확정 뒤 AI 요약(SUMMARY 작업) — 조사 그래프 · checkpointer를 쓰지 않는 별도 경로(C5 · C6).

확정 스냅샷만 읽고 근거 기반 1~2줄을 만든다. 모델 요약이 검사를 두 번 못 넘으면 규칙 문장(LLM 없음)으로 낸다.
"""

from fab.agent.summary.guard import validate_summary
from fab.agent.summary.rule import rule_summary
from fab.agent.summary.run import SUMMARY_TOOL, make_summary

__all__ = ["SUMMARY_TOOL", "make_summary", "rule_summary", "validate_summary"]
