"""v2 전체 팩 조립 — v1 전체(BASELINE · CURRENT · FOLLOWUP) 위에 v2 CURRENT(L006 CMP-PM3 한 run 수정 + sensorEnvelopes)를 얹고,
L011 · L012 추가 묶음(CURRENT 2 LOT · 6 wafer · 18 run)을 덧붙인다.

  python3 data/build_pack_v2.py          # data/fab-causal-pack-v2/ 를 다시 만들고 검사한다(같은 입력이면 같은 결과)

입력(읽기만 한다 — 원본은 고치지 않는다)
  V1_PACK = data/fab-causal-pack-v1/   BASELINE 900 · CURRENT 90 · FOLLOWUP 27 run
  V2_DATA = data/backend-handoff-v2/data/                                CURRENT 90 run만(BASELINE · FOLLOWUP은 전달에서 뺀 것)
  ADDON   = data/priority-addon-l011-l012-v1/   CURRENT-L011 · L012 18 run(append-only — 기존 행은 건드리지 않는다)
규칙
  - phase가 있는 파일(process-runs · site-metrology · run-sensor-features · sensor-traces)은 v1 순서를 지키고 CURRENT 행만 v2로 바꾼다
  - 두 곳에 같은 파일(quality-limits · feature-reference-bands · sensor-catalog · change-events)은 해시가 같아야 한다
  - sensor-envelopes.json은 v2에서, knowledge/는 v1 그대로
  - 추가 묶음은 v1 + v2로 만든 팩이 묶음의 기준 팩(pack-manifest.json `dependencies`)과 해시까지 같을 때만 붙인다.
    묶음 manifest의 `mergeKeys`로 겹치는 행이 없는지 보고, phase가 있는 파일은 마지막 CURRENT 행 뒤에, 나머지는 끝에 붙인다
  - current-wafer-summary.json은 합친 run에서 다시 계산한다(L006 옛 값 10200.4가 남지 않게). 나머지 29행은 v1과,
    추가 6행은 묶음의 요약과 같아야 한다
  - CSV 사본 · evaluation/ · generate.py · 검증 스크립트는 넣지 않는다(코드는 JSON만 읽고, 정답은 어디에도 넣지 않는다).
    추가 묶음의 편차 구간 · 사건 후보 · 우선순위 정책은 아직 읽는 코드가 없어 팩에 넣지 않는다(묶음 폴더에만 둔다)
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V1_PACK = ROOT / "data" / "fab-causal-pack-v1"
V2_DATA = ROOT / "data" / "backend-handoff-v2" / "data"
ADDON = ROOT / "data" / "priority-addon-l011-l012-v1"
OUT = ROOT / "data" / "fab-causal-pack-v2"
BASE_VERSION = "causal-pack-v2-scenarios"  # v1 + v2만 합친 팩 — 추가 묶음이 기준으로 삼은 버전
DATA_VERSION = "causal-pack-v2.1-scenarios"  # + L011 · L012
REVISED_RUN = "RUN-W-CURRENT-006-3-CMP"

PHASED = {  # 파일 → 행을 가리키는 키
    "process-runs.json": ("runId",),
    "site-metrology.json": ("runId", "siteId"),
    "run-sensor-features.json": ("runId", "sensorId"),
}
SAME = ["quality-limits.json", "feature-reference-bands.json", "sensor-catalog.json", "change-events.json"]
V1_ONLY = ["run-events.json", "wafer-lineage.json"]  # 수치 없음 — 시각 · 경로뿐(L006 run의 시각 · 경로는 그대로)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def dump(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def key(row: dict, fields) -> tuple:
    return tuple(row[field] for field in fields)


def merge_rows(v1: list[dict], v2: list[dict], fields) -> list[dict]:
    """v1 순서 그대로, CURRENT 행만 v2 행으로 바꾼다. v2는 CURRENT 행만, v1 CURRENT와 같은 키 집합이어야 한다"""
    assert all(row["phase"] == "CURRENT" for row in v2), "v2에 CURRENT 밖의 행이 있다"
    new = {key(row, fields): row for row in v2}
    old_current = [key(row, fields) for row in v1 if row["phase"] == "CURRENT"]
    assert sorted(old_current) == sorted(new), "v1 CURRENT와 v2 키 집합이 다르다"
    return [new[key(row, fields)] if row["phase"] == "CURRENT" else row for row in v1]


def read_traces(path: Path) -> list[str]:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        return [line.rstrip("\n") for line in handle if line.strip()]


def trace_key(line: str) -> tuple[str, str, str]:
    record = json.loads(line)
    return record["phase"], record["runId"], record["sensorId"]


def write_gzip_lines(path: Path, lines: list[str]) -> None:
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0, filename="") as gzip_file:  # mtime=0 → 다시 돌려도 같은 바이트
        for line in lines:
            gzip_file.write((line + "\n").encode("utf-8"))
    path.write_bytes(buffer.getvalue())


def wafer_summary(runs: list[dict]) -> list[dict]:
    """current-wafer-summary — 합친 run에서 웨이퍼 한 장의 세 공정 결과를 다시 계산한다(v1 파일과 같은 키 · 순서)"""
    by_wafer = {}
    for run in runs:
        if run["phase"] == "CURRENT":
            by_wafer.setdefault(run["waferId"], {})[run["process"]] = run
    summary = []
    for wafer_id, by_process in by_wafer.items():
        cvd, etch, cmp = by_process["CVD"], by_process["ETCH"], by_process["CMP"]
        summary.append({"lotId": cmp["lotId"], "waferId": wafer_id, "cvdPM": cvd["equipmentId"], "etchPM": etch["equipmentId"],
                        "cmpPM": cmp["equipmentId"], "cvdAfterA": cvd["outputMeanA"], "etchRemovalA": etch["addedOrRemovedA"],
                        "etchAfterA": etch["outputMeanA"], "cmpRemovalA": cmp["addedOrRemovedA"], "cmpAfterA": cmp["outputMeanA"],
                        "cvdOoc": cvd["oocR1"], "etchOoc": etch["oocR1"], "cmpOoc": cmp["oocR1"], "cmpOos": cmp["oos"]})
    return summary


def finish_data(out: Path, v1_data: Path, data_version: str) -> None:
    """합친 run에서 다시 계산하는 두 파일 — 웨이퍼 요약과 시계열 manifest(샘플 수 · 데이터 버전)"""
    dump(out / "current-wafer-summary.json", wafer_summary(load(out / "process-runs.json")))
    manifest = load(v1_data / "trace-manifest.json")
    manifest["sampleCount"] = sum(len(json.loads(line)["values"]) for line in read_traces(out / "sensor-traces.jsonl.gz"))
    manifest["dataVersion"] = data_version
    dump(out / "trace-manifest.json", manifest)


def insert_after_current(rows: list, added: list, is_current) -> list:
    """phase가 있는 파일 — 마지막 CURRENT 행 바로 뒤에 넣는다(FOLLOWUP 행은 뒤에 그대로)"""
    last = max(index for index, row in enumerate(rows) if is_current(row))
    return rows[:last + 1] + added + rows[last + 1:]


def append_addon(out: Path, merge_keys: dict) -> None:
    """추가 묶음 행 덧붙이기 — 겹치는 키가 있으면 멈춘다. 웨이퍼 요약은 finish_data가 다시 계산한다"""
    for name, fields in merge_keys.items():
        if name == "current-wafer-summary.json":
            continue
        if name == "sensor-traces.jsonl.gz":
            base, added = read_traces(out / name), read_traces(ADDON / "data" / name)
            assert not ({key(json.loads(line), fields) for line in base}
                        & {key(json.loads(line), fields) for line in added}), f"{name}: 겹치는 키"
            write_gzip_lines(out / name, insert_after_current(base, added, lambda line: trace_key(line)[0] == "CURRENT"))
            continue
        base, added = load(out / name), load(ADDON / "data" / name)
        assert not {key(row, fields) for row in base} & {key(row, fields) for row in added}, f"{name}: 겹치는 키"
        if "phase" in base[0]:
            dump(out / name, insert_after_current(base, added, lambda row: row["phase"] == "CURRENT"))
        else:
            dump(out / name, base + added)


def build() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "data").mkdir(parents=True)
    v1_data, out = V1_PACK / "data", OUT / "data"

    for file_name, fields in PHASED.items():
        dump(out / file_name, merge_rows(load(v1_data / file_name), load(V2_DATA / file_name), fields))
    v1_lines, v2_lines = read_traces(v1_data / "sensor-traces.jsonl.gz"), read_traces(V2_DATA / "sensor-traces.jsonl.gz")
    v2_by = {trace_key(line): line for line in v2_lines}
    assert sorted(v2_by) == sorted(trace_key(line) for line in v1_lines if trace_key(line)[0] == "CURRENT")
    write_gzip_lines(out / "sensor-traces.jsonl.gz",
                     [v2_by[trace_key(line)] if trace_key(line)[0] == "CURRENT" else line for line in v1_lines])
    for file_name in SAME:
        assert sha(v1_data / file_name) == sha(V2_DATA / file_name), f"{file_name}: v1과 v2가 다르다"
        shutil.copyfile(V2_DATA / file_name, out / file_name)
    shutil.copyfile(V2_DATA / "sensor-envelopes.json", out / "sensor-envelopes.json")
    for file_name in V1_ONLY:
        shutil.copyfile(v1_data / file_name, out / file_name)
    finish_data(out, v1_data, BASE_VERSION)
    shutil.copytree(V1_PACK / "knowledge", OUT / "knowledge")

    addon = load(ADDON / "pack-manifest.json")
    changed = [name for name, digest in addon["dependencies"].items()
               if name.startswith(("data/", "knowledge/")) and sha(OUT / name) != digest]
    assert not changed, f"추가 묶음의 기준 팩과 다르다: {changed}"
    append_addon(out, addon["mergeKeys"])
    finish_data(out, v1_data, DATA_VERSION)

    dump(OUT / "pack-manifest.json", {
        "dataVersion": DATA_VERSION, "revisionPackage": "fab-scenarios-v2", "basePackage": "fab-causal-pack-v1",
        "sources": {"v1": str(V1_PACK.relative_to(ROOT)), "v2": str(V2_DATA.relative_to(ROOT)),
                    "addon": str(ADDON.relative_to(ROOT))},
        "revisedRuns": [REVISED_RUN], "addedFiles": ["data/sensor-envelopes.json"],
        "appendedLots": addon["lots"], "appendedFrom": addon["dataVersion"],
        "recomputedFiles": ["data/current-wafer-summary.json", "data/trace-manifest.json"],
        "excluded": ["evaluation/*", "generate.py", "prepare_knowledge.py", "validate.py", "*.csv",
                     "addon: data/sensor-deviation-observations.json · event-candidates/ · policy/ · reference/ · evaluation/"],
        "allSynthetic": True})
    write_readme()
    sums = [f"{sha(path)}  {path.relative_to(OUT).as_posix()}" for path in sorted(OUT.rglob("*"))
            if path.is_file() and path.name != "SHA256SUMS.txt"]
    (OUT / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")


def write_readme() -> None:
    (OUT / "README.md").write_text(f"""# fab-causal-pack-v2 — dataVersion `{DATA_VERSION}`

**전부 시연용 합성 데이터다.** `data/build_pack_v2.py`가 세 원본을 합쳐 만든다(원본은 고치지 않는다). 다시 돌리면 같은 바이트가 나오고, 조립이 끝나면 원본들과 행 · 개수를 대조하는 검사를 함께 돌린다(`python3 data/build_pack_v2.py`).

| 출처 | 쓴 것 |
|---|---|
| `{V1_PACK.relative_to(ROOT)}` (v1) | BASELINE 900 · FOLLOWUP 27 run의 행 전부, FOLLOWUP 원시 시계열 180, `run-events` · `wafer-lineage`, `knowledge/` |
| `{V2_DATA.relative_to(ROOT)}` (v2, `fab-scenarios-v2`) | CURRENT 90 run · 계측 450 · 특징 600 · 원시 시계열 600, `sensor-envelopes.json`(새 파일) |
| `{ADDON.relative_to(ROOT)}` (추가 묶음) | CURRENT-L011 · L012의 18 run · 계측 90 · 특징 120 · 원시 시계열 120 · run 이벤트 90 · 경로 12 · 웨이퍼 요약 6 — 덧붙이기만 했다 |

추가 묶음은 v1 + v2 팩(`{BASE_VERSION}`)을 기준으로 만들어졌다. 조립은 그 기준과 해시가 같을 때만 묶음을 붙이고, 겹치는 키가 있으면 멈춘다.
L011 · L012는 정상 L010의 운전 · 계측 · 시계열을 본떠 ID · 시각만 바꾼 뒤, 3번 wafer의 CMP-PM3 run(처리 120초 무렵)에 3초 미만의 짧은 센서 편차를 넣은 LOT이다 —
L011은 `head_pressure` 하나, L012는 `platen_speed` · `head_speed` 둘이 함께 떨어졌다가 회복한다. 두께는 모든 공정에서 관리한계 안이라 시드가 사건으로 올리지 않는다.
묶음의 편차 구간(`sensor-deviation-observations.json`) · 사건 후보 · 우선순위 정책은 아직 읽는 코드가 없어 이 팩에 넣지 않았다.

v2가 v1에서 바꾼 것은 **`{REVISED_RUN}` 한 run**이다 — 제거량 4829.951877 → 4749.009449 Å, 출고 평균 10200.449787 → 10281.392216 Å,
5위치 계측 5행, `head_pressure` 특징(mean 2.890141 → 2.841574 등) · 원시 시계열(약 3 psi → 처리 100–210초 약 2.55 psi → 회복).
나머지 89 run, L007 · L009, 정상 LOT, 관리한계는 그대로다. **v1 센서와 v2 계측을 섞지 않는다** — 네 가지(run · 계측 · 특징 · 시계열)를 모두 v2로 바꿨다.

- `current-wafer-summary.json`은 합친 run에서 다시 계산했다(v1에는 L006 옛 값 10200.4가 있다). 다른 29행은 v1과, 추가 6행은 묶음과 같다
- CSV 사본은 넣지 않았다 — 코드는 JSON만 읽고, CSV를 옮기면 L006 옛 값이 남는다
- `evaluation/`(정답) · 생성기 · 검증 스크립트는 넣지 않았다. Agent · RAG · 컨테이너 어디에도 붙이지 않는다
- `sensor-envelopes.json`의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니다(`calibratedForRealTool=false`)
""", encoding="utf-8")


class _Checks:
    """검사 결과 모으기 — 다르면 라벨을 실패 목록에 넣는다(라벨 순서가 곧 출력 순서)"""

    def __init__(self):
        self.failed: list[str] = []

    def eq(self, label, actual, expected):
        if actual != expected:
            self.failed.append(label)


def _addon_runs() -> set[str]:
    return {row["runId"] for row in load(ADDON / "data" / "process-runs.json")}


def _check_phased_files(checks: _Checks, v1_data: Path, out: Path) -> None:
    """phase가 있는 파일 — CURRENT 행은 v2 + 추가 묶음, 나머지는 v1, 행 수는 v1 + 추가 묶음"""
    addon_runs = _addon_runs()
    for name, fields in PHASED.items():
        merged, v1_rows, v2_rows = load(out / name), load(v1_data / name), load(V2_DATA / name)
        addon_rows = load(ADDON / "data" / name)
        current = [row for row in merged if row["phase"] == "CURRENT" and row["runId"] not in addon_runs]
        checks.eq(f"{name}: CURRENT 행 = v2", sorted(json.dumps(row, sort_keys=True) for row in current),
                  sorted(json.dumps(row, sort_keys=True) for row in v2_rows))
        checks.eq(f"{name}: 추가 행 = 묶음", [row for row in merged if row["runId"] in addon_runs], addon_rows)
        checks.eq(f"{name}: BASELINE · FOLLOWUP 행 = v1", [row for row in merged if row["phase"] != "CURRENT"],
                  [row for row in v1_rows if row["phase"] != "CURRENT"])
        checks.eq(f"{name}: 전체 행 수 = v1 + 묶음", len(merged), len(v1_rows) + len(addon_rows))


def _check_counts_and_traces(checks: _Checks, v1_data: Path, out: Path, runs: list[dict], lines: list[str]) -> None:
    addon_runs = _addon_runs()
    checks.eq("CURRENT run 108", sum(row["phase"] == "CURRENT" for row in runs), 108)
    checks.eq("CURRENT 계측 540", sum(row["phase"] == "CURRENT" for row in load(out / "site-metrology.json")), 540)
    checks.eq("CURRENT 특징 720", sum(row["phase"] == "CURRENT" for row in load(out / "run-sensor-features.json")), 720)
    current = sorted(line for line in lines if trace_key(line)[0] == "CURRENT" and trace_key(line)[1] not in addon_runs)
    checks.eq("CURRENT 시계열 600 = v2(줄 단위)", current, sorted(read_traces(V2_DATA / "sensor-traces.jsonl.gz")))
    checks.eq("추가 시계열 120 = 묶음(줄 단위)", [line for line in lines if trace_key(line)[1] in addon_runs],
              read_traces(ADDON / "data" / "sensor-traces.jsonl.gz"))
    checks.eq("FOLLOWUP 시계열 = v1(줄 단위)", [line for line in lines if trace_key(line)[0] != "CURRENT"],
              [line for line in read_traces(v1_data / "sensor-traces.jsonl.gz") if trace_key(line)[0] != "CURRENT"])
    checks.eq("시계열 900", len(lines), 900)
    for name in SAME + ["sensor-envelopes.json"]:
        checks.eq(f"{name}: 해시 = v2", sha(out / name), sha(V2_DATA / name))
    for path in sorted((ADDON / "reference").iterdir()):  # 묶음의 참고 사본은 팩과 같은 파일이어야 한다
        checks.eq(f"묶음 reference/{path.name} = 팩", sha(path), sha(out / path.name))
    for name in V1_ONLY:  # 수치 없는 두 파일 — v1 뒤에 묶음 행만
        checks.eq(f"{name} = v1 + 묶음", load(out / name), load(v1_data / name) + load(ADDON / "data" / name))


def _revised_trace(lines: list[str]) -> dict:
    return next(json.loads(line) for line in lines if trace_key(line)[1:] == (REVISED_RUN, "head_pressure"))


def _check_revised_run(checks: _Checks, v1_data: Path, out: Path, runs: list[dict], lines: list[str]) -> None:
    """L006 네 가지(run · 계측 · 특징 · 시계열)가 모두 v2이고 v1과 다르다"""
    run = next(row for row in runs if row["runId"] == REVISED_RUN)
    checks.eq("L006 run 출고 평균 10281.392216", (run["outputMeanA"], run["addedOrRemovedA"]), (10281.392216, 4749.009449))
    v1_run = next(row for row in load(v1_data / "process-runs.json") if row["runId"] == REVISED_RUN)
    checks.eq("L006 run은 v1과 다르다", run["outputMeanA"] != v1_run["outputMeanA"], True)
    sites = [row for row in load(out / "site-metrology.json") if row["runId"] == REVISED_RUN]
    v2_sites = [row for row in load(V2_DATA / "site-metrology.json") if row["runId"] == REVISED_RUN]
    v1_sites = [row for row in load(v1_data / "site-metrology.json") if row["runId"] == REVISED_RUN]
    checks.eq("L006 계측 5행 = v2 ≠ v1", (len(sites), sites == v2_sites, sites != v1_sites), (5, True, True))
    head_pressure = next(row for row in load(out / "run-sensor-features.json")
                         if row["runId"] == REVISED_RUN and row["sensorId"] == "head_pressure")
    checks.eq("L006 head_pressure 특징 mean 2.841574", head_pressure["mean"], 2.841574)
    trace = _revised_trace(lines)
    v1_trace = _revised_trace(read_traces(v1_data / "sensor-traces.jsonl.gz"))
    v2_trace = _revised_trace(read_traces(V2_DATA / "sensor-traces.jsonl.gz"))
    checks.eq("L006 head_pressure 시계열 = v2 ≠ v1", (trace == v2_trace, trace["values"] != v1_trace["values"]), (True, True))
    checks.eq("L006 시계열이 처리 중 2.6 psi 아래로 떨어진다", min(trace["values"][1100:2000]) < 2.6, True)


def _check_summary_and_contents(checks: _Checks, v1_data: Path, out: Path) -> None:
    """요약은 합친 run과 맞고 L006 말고는 v1, 추가 6행은 묶음과 같다. 정답 · CSV는 없고 knowledge는 v1 그대로"""
    summary, v1_summary = load(out / "current-wafer-summary.json"), load(v1_data / "current-wafer-summary.json")
    addon_summary = load(ADDON / "data" / "current-wafer-summary.json")
    addon_wafers = {row["waferId"] for row in addon_summary}
    checks.eq("요약 36행", len(summary), 36)
    checks.eq("요약 L006 말고 29행 = v1",
              [row for row in summary if row["waferId"] != "W-CURRENT-006-3" and row["waferId"] not in addon_wafers],
              [row for row in v1_summary if row["waferId"] != "W-CURRENT-006-3"])
    checks.eq("요약 추가 6행 = 묶음", [row for row in summary if row["waferId"] in addon_wafers], addon_summary)
    l006 = next(row for row in summary if row["waferId"] == "W-CURRENT-006-3")
    checks.eq("요약 L006 = v2 값", (l006["cmpAfterA"], l006["cmpRemovalA"]), (10281.392216, 4749.009449))
    checks.eq("팩에 evaluation 없음", any(path.name == "evaluation" for path in OUT.rglob("*")), False)
    checks.eq("팩에 CSV 없음", any(path.suffix == ".csv" for path in OUT.rglob("*")), False)
    checks.eq("팩에 묶음의 편차 구간 · 정책 없음",
              any(path.name in ("sensor-deviation-observations.json", "priority-rules.json") for path in OUT.rglob("*")), False)
    checks.eq("knowledge = v1", sorted(sha(path) for path in (OUT / "knowledge").iterdir()),
              sorted(sha(path) for path in (V1_PACK / "knowledge").iterdir()))


def check() -> list[str]:
    """조립 결과 검사 — 실패 항목 목록(비면 통과)"""
    checks = _Checks()
    v1_data, out = V1_PACK / "data", OUT / "data"
    _check_phased_files(checks, v1_data, out)
    runs = load(out / "process-runs.json")
    lines = read_traces(out / "sensor-traces.jsonl.gz")
    _check_counts_and_traces(checks, v1_data, out, runs, lines)
    _check_revised_run(checks, v1_data, out, runs, lines)
    _check_summary_and_contents(checks, v1_data, out)
    return checks.failed


if __name__ == "__main__":
    build()
    failed = check()
    for name in failed:
        print("✘", name)
    print(f"검사 {'통과' if not failed else '실패 ' + str(len(failed)) + '건'} — {OUT.relative_to(ROOT)}")
    sys.exit(1 if failed else 0)
