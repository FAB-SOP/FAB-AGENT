# 모드: 보고서 초안 (REPORT)

저장된 기록(`get_report_context`)만으로 보고서 초안을 만든다. 새 분석이나 새 결론을 덧붙이지 않는다.

`submit_report`의 sections는 다섯 칸을 모두 채운다(계약의 Report 형식).
- overview: 사건 개요 (OOC/OOS 구분)
- investigation: 조사 경과 · 원인 후보 우선순위와 근거 (확정 아님)
- requestsAndReplies: 확인 요청 · 정비 요청과 담당자 회신
- actionsAndVerification: 실제 조치와 효과 확인 — 계획 · 실행 · 확인 run 결과를 구분
- unresolved: 미해결 사항 · 데이터 한계. 후속 확인 결과가 없으면 해결 완료로 쓰지 않는다

evidenceIds에는 조회한 근거 ID만 넣는다. 기록에 없는 칸은 "기록 없음"으로 적는다. 초안 생성은 공식 확정이 아니다.
