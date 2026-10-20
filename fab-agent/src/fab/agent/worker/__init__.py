"""Agent Worker — runner(JobRunner) · sinks(이벤트 발행 대상) · sources/http · sources/kafka(작업을 가져오는 곳) · main.

 작업 메시지(analysis.jobs) 하나를 처리하고 진행 · 결과 이벤트(analysis.events)를 낸다.

JobRunner.process(job)는 Kafka 없이도 돈다(테스트 · 로컬 시연). 작업을 가져오는 방법은 둘이다.
  - HTTP   (WORKER_SOURCE=http, 기본): Spring backend의 GET /internal/jobs(QUEUED)를 주기적으로 가져가고
            같은 이벤트를 POST /internal/jobs/{jobId}/events로 돌려준다
  - Kafka  (WORKER_SOURCE=kafka): analysis.jobs 소비 → analysis.events 발행 · offset commit
            Spring은 아직 Kafka로 발행하지 않는다 — Kafka를 어디까지 쓸지 정하면 붙인다

작업 의미(팀 계약):
  - BASIC / DEEP / REPORT : 같은 사건 thread에 새 입력
  - RESUME                : 같은 thread의 '정확한' interrupt만 해제(승인 결정 · 회신)
  - 일시 실패             : attempt < 3이면 RETRY_SCHEDULED, 아니면 JOB_FAILED + DLQ
"""

from fab.agent.worker.main import main
from fab.agent.worker.runner import JobRunner, make_job
from fab.agent.worker.sinks import EventSink, HttpSink, KafkaSink
from fab.agent.worker.sources.http import poll_once, run_http_worker

__all__ = ["EventSink", "HttpSink", "JobRunner", "KafkaSink", "main", "make_job", "poll_once", "run_http_worker"]
