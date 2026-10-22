"""조사 Agent 그래프 (LangGraph) — state(상태 · 실행 자원) · nodes(노드 하나에 파일 하나, 노드 이름 = 파일 이름) ·
routes(갈림길) · build(조립).

  start ─▶ required_evidence ─▶ agent ◀──────────────┐
                                  │ (제안 도구를 고르면)   │
                                  ├──▶ request_brief ─┐ │
                                  ▼                   ▼ │
                                tools ── 읽기 · 검색 ──┘
                                  │
            ┌─────────────────────┼──────────────────────┐
            ▼                     ▼                      ▼
     approval_wait           finalize                 (한도 초과 → finalize)
   (interrupt: 사람 승인)
     ├ 거절 → agent
     └ 승인 → 승인 ID로 변경 도구 실행 → reply_wait (interrupt: 담당자 회신) → agent

모델은 다음 행동을 '제안'만 한다. 도구 실행 · 한도 · 승인 · 기록은 Harness 코드가 한다.
request_brief는 제안 저장 '전'에 받는 사람이 볼 요청 브리프(편차 수치 · 원인 후보 · 관련 기록)를 도구 결과에서 만든다 —
브리프는 제안 payload에 들어가 승인 해시로 묶이므로, 승인자가 본 내용과 받는 사람이 보는 내용이 같다.
"""

from fab.agent.graph.build import build_graph
from fab.agent.graph.state import AgentState, Runtime, current_job

__all__ = ["AgentState", "Runtime", "build_graph", "current_job"]
