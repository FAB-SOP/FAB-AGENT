"""Harness — 모델을 감싸는 코드. 모델은 다음 행동을 제안만 하고, 실행 · 한도 · 기록 · 복구는 여기서 한다.

8요소와 모듈:
  Instructions   instructions.py  (prompts/*.md 버전 관리)
  Tools          ../toolbox.py    (MCP 도구 변환 · 결과 길이 제한 · 오류를 ToolMessage로 · 변경 도구 숨김)
  Agent Loop     limits.py        (모델 판단 · 같은 도구 · 활성 시간 한도)
  Context        context.py       (Token 예산 · 긴 결과 요약)
  State·Memory   ../graph.py      (checkpointer, thread = 사건)
  Guardrails     guard.py         (승인 interrupt · 근거 검증 · 마스킹 · 신뢰할 수 없는 입력 표시)
  Recovery       retry.py         (일시 오류 재시도 · 대체 모델 · 재시도 예약/실패 분류)
  Observability  trace.py         (trace_id 하나로 node · tool · token · 지연 · 재시도)
"""
