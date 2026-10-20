"""원인 매트릭스 ID — 데이터 팩 knowledge/cause-matrix.json의 15개 (tests가 팩 원본과 같은지 확인한다).

Agent 컨테이너에는 팩을 붙이지 않으므로 ID와 공정만 여기 둔다. 결과 · 제안의 causeIds는 이 목록 밖의 값을 쓸 수 없다.
공정이 ALL인 METROLOGY는 어느 공정의 계측에도 붙는다.
"""

CAUSES: dict[str, tuple[str, str]] = {
    "CMP_PRESSURE": ("CMP", "CMP 헤드 압력 부족·제어 불안정"),
    "CMP_SPEED": ("CMP", "CMP 플래튼·헤드 속도 이탈"),
    "CMP_SLURRY": ("CMP", "CMP 슬러리 공급 부족·불안정"),
    "CMP_PAD": ("CMP", "CMP 패드·컨디셔닝·슬러리 화학 상태 변화"),
    "CMP_TIME": ("CMP", "CMP 실제 연마 시간 부족·잘못된 레시피"),
    "ETCH_RF": ("ETCH", "ETCH 플라스마 전력 전달·매칭 상태 변화"),
    "ETCH_GAS": ("ETCH", "ETCH 가스 공급·혼합비 변화"),
    "ETCH_PRESSURE": ("ETCH", "ETCH 압력 제어·배기 상태 변화"),
    "ETCH_THERMAL": ("ETCH", "ETCH 척 열 제어·백사이드 냉각 상태 변화"),
    "ETCH_WALL": ("ETCH", "ETCH 챔버 표면·세정 이후 상태 변화"),
    "CVD_GAS": ("CVD", "CVD 전구체·반응 가스 공급 변화"),
    "CVD_PRESSURE_RF": ("CVD", "CVD 증착 압력·플라스마 조건 변화"),
    "CVD_THERMAL": ("CVD", "CVD 히터·열 상태 변화"),
    "CVD_FILM": ("CVD", "CVD 막질·형상 차이로 후속 반응 차이"),
    "METROLOGY": ("ALL", "계측 오프셋·측정 위치·모델 오류"),
}
CAUSE_IDS = list(CAUSES)
PROCESS_ORDER = ["CVD", "ETCH", "CMP"]

# 원인마다 확인할 증거 · 한계 (원인 매트릭스 evidenceToCheck · limits 그대로) — 요청 브리프의 원인 후보 · 요청 항목에 쓴다
CAUSE_DETAILS: dict[str, dict[str, str]] = {
    "CMP_PRESSURE": {"evidenceToCheck": "같은 입고 두께 대비 제거량 감소; 설정값 대비 실제 압력 저하",
                       "limits": "압력 상승을 임의 처방하지 않음; 압력 센서 교정 오류도 대안"},
    "CMP_SPEED": {"evidenceToCheck": "레시피 단계별 속도 편차와 제거량 변화를 함께 확인",
                       "limits": "속도 변화 효과는 재료·공정 범위에 따라 다름"},
    "CMP_SLURRY": {"evidenceToCheck": "유량 저하 구간과 제거량 감소가 동반되는지 확인",
                       "limits": "총유량만으로 국소 분포 불균일이나 막힘 위치를 확정 못 함"},
    "CMP_PAD": {"evidenceToCheck": "투입 두께·압력·속도 정상인데 제거량 변화; 소모품 이력 연관",
                       "limits": "모터 전류는 비특이적 보조 신호; 마모·화학 상태를 직접 측정하지 않음"},
    "CMP_TIME": {"evidenceToCheck": "PROCESS 실제 지속시간, 중단·종료 이벤트 확인",
                       "limits": "기본 시연은 고정 시간이며 엔드포인트 센서를 가정하지 않음"},
    "ETCH_RF": {"evidenceToCheck": "Etch 제거량·잔막 분포와 RF 편차를 함께 확인",
                       "limits": "정상 readback이 실제 플라스마 상태 정상의 증명은 아님"},
    "ETCH_GAS": {"evidenceToCheck": "라인별 실제값/설정값, 식각 제거량 확인",
                       "limits": "한 가스 증가가 식각률을 항상 같은 방향으로 바꾸지는 않음"},
    "ETCH_PRESSURE": {"evidenceToCheck": "압력 편차·불안정과 제거량·위치 분포 확인",
                       "limits": "센서만으로 누설/밸브 고장을 구분할 수 없음"},
    "ETCH_THERMAL": {"evidenceToCheck": "온도·헬륨 압력·알람·위치별 결과를 함께 비교",
                       "limits": "척 온도는 웨이퍼 표면 온도가 아님; He 압력 정상도 열접촉을 보증하지 않음"},
    "ETCH_WALL": {"evidenceToCheck": "직접 태그가 정상이어도 제거량 추세가 바뀌는지와 세정 이력 확인",
                       "limits": "제거량 변화만으로 챔버 벽을 확정하지 않음"},
    "CVD_GAS": {"evidenceToCheck": "CVD 직후 두께·분포 변화가 해당 웨이퍼를 따라 이어지는지 확인",
                       "limits": "MFC readback은 실제 반응 조성의 독립 측정이 아님"},
    "CVD_PRESSURE_RF": {"evidenceToCheck": "증착 후 두께와 단계별 신호 편차 비교",
                       "limits": "효과 방향·감도는 레시피별 검증 필요"},
    "CVD_THERMAL": {"evidenceToCheck": "같은 레시피의 온도 안정화·처리 시간·증착 결과 비교",
                       "limits": "히터 온도와 웨이퍼 온도를 동일시하지 않음"},
    "CVD_FILM": {"evidenceToCheck": "두께만으로 설명되지 않을 때 막질/형상 검사 추가",
                       "limits": "현 MVP에는 막질 계측이 없어 미확인 가설로만 표시"},
    "METROLOGY": {"evidenceToCheck": "센서/제거량 설명과 불일치, 재측정·기준 시편 확인",
                       "limits": "모든 품질 차이를 공정 고장으로 가정하지 않음"},
}
