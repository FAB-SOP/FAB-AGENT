#!/usr/bin/env python3
"""시연용 이상 이벤트 전송 — 이상 탐지 서비스 대신 레포의 이벤트 후보를 POST /api/events/anomalies로 보낸다.

새 사건이 생기면서 우선순위 평가 · 새 이상 알림 · AI 기본 분석이 실제 경로로 돈다(화면 알림 시연용).
이벤트 내용은 data/priority-addon-l011-l012-v1/event-candidates/anomaly-events.json 그대로 쓰고,
eventId 뒤에 전송 시각만 붙여 여러 번 보내도 새 사건이 생기게 한다.

  python3 ops/demo/send_anomaly.py          # L012 (회전계 동반 편차 → 우선순위 중)
  python3 ops/demo/send_anomaly.py L011     # L011 (단일 센서 짧은 편차 → 하)

환경 변수: FAB_BASE_URL (기본 http://127.0.0.1:8088), FAB_SERVICE_TOKEN (기본 dev-simulator-token)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CANDIDATES = Path(__file__).resolve().parents[2] / "data/priority-addon-l011-l012-v1/event-candidates/anomaly-events.json"


def main() -> int:
    lot = (sys.argv[1] if len(sys.argv) > 1 else "L012").upper()
    events = json.loads(CANDIDATES.read_text(encoding="utf-8"))
    event = next((e for e in events if e["lotId"].endswith(lot)), None)
    if event is None:
        print(f"이벤트 후보에 {lot}가 없습니다: {[e['lotId'] for e in events]}", file=sys.stderr)
        return 1
    event = {**event, "eventId": f"{event['eventId']}-{int(time.time())}"}
    request = urllib.request.Request(
        os.environ.get("FAB_BASE_URL", "http://127.0.0.1:8088") + "/api/events/anomalies",
        data=json.dumps(event).encode(),
        headers={"Content-Type": "application/json",
                 "X-Service-Token": os.environ.get("FAB_SERVICE_TOKEN", "dev-simulator-token")},
        method="POST")
    try:
        with urllib.request.urlopen(request) as response:
            accepted = json.load(response)["data"]
    except urllib.error.HTTPError as error:
        print(f"전송 실패 {error.code}: {error.read().decode()}", file=sys.stderr)
        return 1
    print(f"{accepted['displayId']} 접수 ({event['lotId']}) — 화면에 새 이상 알림이 뜨고 우선순위·기본 분석이 시작됩니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
