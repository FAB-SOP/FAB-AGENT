"""보고서 작업의 잠긴 칸 — 사람이 고친 칸은 Agent가 쓰지 않는다. backend가 이전 판(baseReportVersion)의 원문을 그대로 옮긴다.

작업 메시지 input.report = {baseReportVersion, lockedSections: [칸 이름]}. Agent는 잠긴 칸에 LOCKED_MARK를 넣어 보내고(모델이 썼어도 덮어쓴다),
backend가 저장할 때 그 칸을 이전 판 원문으로 바꾼다 — 사람 수정이 AI 초안에 덮이지 않게 두 쪽에서 지킨다.
"""

from __future__ import annotations

from fab.agent.tools import REPORT_SECTIONS

LOCKED_MARK = "[사람이 고친 칸 — 이전 판 유지]"


def locked_of(job: dict) -> list[str]:
    """REPORT 작업 입력의 잠긴 칸 — 다섯 칸 이름 안의 것만, 순서대로"""
    if job.get("kind") != "REPORT":
        return []
    names = set(((job.get("input") or {}).get("report") or {}).get("lockedSections") or [])
    return [key for key in REPORT_SECTIONS if key in names]


def note(locked: list[str]) -> str:
    return (f"[잠긴 칸] {', '.join(locked)} — 사람이 고친 칸이다. 이 칸은 쓰지 말고 「{LOCKED_MARK}」로 둔다. "
            "나머지 칸만 새 근거로 쓴다(잠긴 칸 내용과 어긋나지 않게).")
