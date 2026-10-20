"""승인 대기 중 Worker 재시작 → 같은 thread · 같은 interrupt로 재개되는지 본다 (체크포인트는 Postgres agent 스키마).

  1단계: docker compose --profile tools run --rm --no-deps smoke python evaluation/restart_smoke.py before
  (사이에) docker compose restart agent
  2단계: docker compose --profile tools run --rm --no-deps smoke python evaluation/restart_smoke.py after

1단계는 EV-1009에 질문해 승인 대기에서 멈춘다. 2단계는 재시작된 Worker 위에서 승인 → 회신 → 보고서까지 간다.
"""

import asyncio
import sys

import compose_smoke as cs
from fab.devtools.spring import User


async def before() -> int:
    cmp = await User("cmp.park").login()
    await cs.ask_until_approval(cmp)
    await cmp.close()
    print("승인 대기에서 멈췄습니다 — 이제 docker compose restart agent")
    return 1 if cs.FAILS else 0


async def after() -> int:
    cmp = await User("cmp.park").login()
    i = await cmp.incident(cs.INCIDENT)
    _, page = await cmp.get(f"/api/approvals?status=PENDING&incidentId={i['incidentId']}")
    _, a = await cmp.get(f"/api/approvals/{page['data']['items'][0]['approvalId']}")
    await cs.approve_to_end(cmp, i, a["data"])
    await cmp.close()
    print(f"재시작 후 재개: 실패 {len(cs.FAILS)}건")
    return 1 if cs.FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(before() if sys.argv[1] == "before" else after()))
