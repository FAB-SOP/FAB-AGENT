"""v2 전체 팩 조립 — v1 전체(BASELINE · CURRENT · FOLLOWUP) 위에 v2 CURRENT(L006 CMP-PM3 한 run 수정 + sensorEnvelopes)를 얹는다.

  python3 data/build_pack_v2.py          # data/fab-causal-pack-v2/ 를 다시 만들고 검사한다(같은 입력이면 같은 결과)

입력(읽기만 한다 — 원본은 고치지 않는다)
  V1 = FAB-실제문서-선별RAG-센서데이터-통합/fab-causal-pack-v1/   BASELINE 900 · CURRENT 90 · FOLLOWUP 27 run
  V2 = backend-handoff-v2/data/                                CURRENT 90 run만(BASELINE · FOLLOWUP은 전달에서 뺀 것)
규칙
  - phase가 있는 파일(process-runs · site-metrology · run-sensor-features · sensor-traces)은 v1 순서를 지키고 CURRENT 행만 v2로 바꾼다
  - 두 곳에 같은 파일(quality-limits · feature-reference-bands · sensor-catalog · change-events)은 해시가 같아야 한다
  - sensor-envelopes.json은 v2에서, knowledge/는 v1 그대로
  - current-wafer-summary.json은 합친 run에서 다시 계산한다(L006 옛 값 10200.4가 남지 않게). 나머지 29행은 v1과 같아야 한다
  - CSV 사본 · evaluation/ · generate.py · 검증 스크립트는 넣지 않는다(코드는 JSON만 읽고, 정답은 어디에도 넣지 않는다)
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
V1 = ROOT / "FAB-실제문서-선별RAG-센서데이터-통합" / "fab-causal-pack-v1"
V2 = ROOT / "backend-handoff-v2" / "data"
OUT = ROOT / "data" / "fab-causal-pack-v2"
DATA_VERSION = "causal-pack-v2-scenarios"
REVISED_RUN = "RUN-W-CURRENT-006-3-CMP"

PHASED = {  # 파일 → 행을 가리키는 키
    "process-runs.json": ("runId",),
    "site-metrology.json": ("runId", "siteId"),
    "run-sensor-features.json": ("runId", "sensorId"),
}
SAME = ["quality-limits.json", "feature-reference-bands.json", "sensor-catalog.json", "change-events.json"]
V1_ONLY = ["run-events.json", "wafer-lineage.json"]  # 수치 없음 — 시각 · 경로뿐(L006 run의 시각 · 경로는 그대로)


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def load(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def dump(p: Path, obj) -> None:
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def key(row: dict, fields) -> tuple:
    return tuple(row[f] for f in fields)


def merge_rows(v1: list[dict], v2: list[dict], fields) -> list[dict]:
    """v1 순서 그대로, CURRENT 행만 v2 행으로 바꾼다. v2는 CURRENT 행만, v1 CURRENT와 같은 키 집합이어야 한다"""
    assert all(r["phase"] == "CURRENT" for r in v2), "v2에 CURRENT 밖의 행이 있다"
    new = {key(r, fields): r for r in v2}
    old_current = [key(r, fields) for r in v1 if r["phase"] == "CURRENT"]
    assert sorted(old_current) == sorted(new), "v1 CURRENT와 v2 키 집합이 다르다"
    return [new[key(r, fields)] if r["phase"] == "CURRENT" else r for r in v1]


def read_traces(p: Path) -> list[str]:
    with gzip.open(p, "rt", encoding="utf-8") as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def trace_key(line: str) -> tuple[str, str, str]:
    r = json.loads(line)
    return r["phase"], r["runId"], r["sensorId"]


def write_gzip_lines(p: Path, lines: list[str]) -> None:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, filename="") as gz:  # mtime=0 → 다시 돌려도 같은 바이트
        for line in lines:
            gz.write((line + "\n").encode("utf-8"))
    p.write_bytes(buf.getvalue())


def wafer_summary(runs: list[dict]) -> list[dict]:
    """current-wafer-summary — 합친 run에서 웨이퍼 한 장의 세 공정 결과를 다시 계산한다(v1 파일과 같은 키 · 순서)"""
    by = {}
    for r in runs:
        if r["phase"] == "CURRENT":
            by.setdefault(r["waferId"], {})[r["process"]] = r
    out = []
    for w, p in by.items():
        cvd, etch, cmp = p["CVD"], p["ETCH"], p["CMP"]
        out.append({"lotId": cmp["lotId"], "waferId": w, "cvdPM": cvd["equipmentId"], "etchPM": etch["equipmentId"],
                    "cmpPM": cmp["equipmentId"], "cvdAfterA": cvd["outputMeanA"], "etchRemovalA": etch["addedOrRemovedA"],
                    "etchAfterA": etch["outputMeanA"], "cmpRemovalA": cmp["addedOrRemovedA"], "cmpAfterA": cmp["outputMeanA"],
                    "cvdOoc": cvd["oocR1"], "etchOoc": etch["oocR1"], "cmpOoc": cmp["oocR1"], "cmpOos": cmp["oos"]})
    return out


def build() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "data").mkdir(parents=True)
    d1, out = V1 / "data", OUT / "data"

    for f, fields in PHASED.items():
        dump(out / f, merge_rows(load(d1 / f), load(V2 / f), fields))
    v1_lines, v2_lines = read_traces(d1 / "sensor-traces.jsonl.gz"), read_traces(V2 / "sensor-traces.jsonl.gz")
    v2_by = {trace_key(x): x for x in v2_lines}
    assert sorted(v2_by) == sorted(trace_key(x) for x in v1_lines if trace_key(x)[0] == "CURRENT")
    write_gzip_lines(out / "sensor-traces.jsonl.gz",
                     [v2_by[trace_key(x)] if trace_key(x)[0] == "CURRENT" else x for x in v1_lines])
    for f in SAME:
        assert sha(d1 / f) == sha(V2 / f), f"{f}: v1과 v2가 다르다"
        shutil.copyfile(V2 / f, out / f)
    shutil.copyfile(V2 / "sensor-envelopes.json", out / "sensor-envelopes.json")
    for f in V1_ONLY:
        shutil.copyfile(d1 / f, out / f)
    dump(out / "current-wafer-summary.json", wafer_summary(load(out / "process-runs.json")))
    manifest = load(d1 / "trace-manifest.json")
    manifest["sampleCount"] = sum(len(json.loads(x)["values"]) for x in read_traces(out / "sensor-traces.jsonl.gz"))
    manifest["dataVersion"] = DATA_VERSION
    dump(out / "trace-manifest.json", manifest)
    shutil.copytree(V1 / "knowledge", OUT / "knowledge")
    dump(OUT / "pack-manifest.json", {
        "dataVersion": DATA_VERSION, "revisionPackage": "fab-scenarios-v2", "basePackage": "fab-causal-pack-v1",
        "sources": {"v1": str(V1.relative_to(ROOT)), "v2": str(V2.relative_to(ROOT))},
        "revisedRuns": [REVISED_RUN], "addedFiles": ["data/sensor-envelopes.json"],
        "recomputedFiles": ["data/current-wafer-summary.json", "data/trace-manifest.json"],
        "excluded": ["evaluation/*", "generate.py", "prepare_knowledge.py", "validate.py", "*.csv"], "allSynthetic": True})
    write_readme()
    sums = [f"{sha(p)}  {p.relative_to(OUT).as_posix()}" for p in sorted(OUT.rglob("*"))
            if p.is_file() and p.name != "SHA256SUMS.txt"]
    (OUT / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")


def write_readme() -> None:
    (OUT / "README.md").write_text(f"""# fab-causal-pack-v2 — dataVersion `{DATA_VERSION}`

**전부 시연용 합성 데이터다.** `data/build_pack_v2.py`가 두 원본을 합쳐 만든다(원본은 고치지 않는다). 다시 돌리면 같은 바이트가 나온다.

| 출처 | 쓴 것 |
|---|---|
| `{V1.relative_to(ROOT)}` (v1) | BASELINE 900 · FOLLOWUP 27 run의 행 전부, FOLLOWUP 원시 시계열 180, `run-events` · `wafer-lineage`, `knowledge/` |
| `{V2.relative_to(ROOT)}` (v2, `fab-scenarios-v2`) | CURRENT 90 run · 계측 450 · 특징 600 · 원시 시계열 600, `sensor-envelopes.json`(새 파일) |

v2가 v1에서 바꾼 것은 **`{REVISED_RUN}` 한 run**이다 — 제거량 4829.951877 → 4749.009449 Å, 출고 평균 10200.449787 → 10281.392216 Å,
5위치 계측 5행, `head_pressure` 특징(mean 2.890141 → 2.841574 등) · 원시 시계열(약 3 psi → 처리 100–210초 약 2.55 psi → 회복).
나머지 89 run, L007 · L009, 정상 LOT, 관리한계는 그대로다. **v1 센서와 v2 계측을 섞지 않는다** — 네 가지(run · 계측 · 특징 · 시계열)를 모두 v2로 바꿨다.

- `current-wafer-summary.json`은 합친 run에서 다시 계산했다(v1에는 L006 옛 값 10200.4가 있다). 다른 29행은 v1과 같다
- CSV 사본은 넣지 않았다 — 코드는 JSON만 읽고, CSV를 옮기면 L006 옛 값이 남는다
- `evaluation/`(정답) · 생성기 · 검증 스크립트는 넣지 않았다. Agent · RAG · 컨테이너 어디에도 붙이지 않는다
- `sensor-envelopes.json`의 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니다(`calibratedForRealTool=false`)
""", encoding="utf-8")


def check() -> list[str]:
    """조립 결과 검사 — 실패 항목 목록(비면 통과)"""
    bad: list[str] = []
    d1, out = V1 / "data", OUT / "data"

    def eq(label, a, b):
        if a != b:
            bad.append(label)

    for f, fields in PHASED.items():
        merged, v1, v2 = load(out / f), load(d1 / f), load(V2 / f)
        cur = [r for r in merged if r["phase"] == "CURRENT"]
        eq(f"{f}: CURRENT 행 = v2", sorted(json.dumps(r, sort_keys=True) for r in cur),
           sorted(json.dumps(r, sort_keys=True) for r in v2))
        eq(f"{f}: BASELINE · FOLLOWUP 행 = v1", [r for r in merged if r["phase"] != "CURRENT"],
           [r for r in v1 if r["phase"] != "CURRENT"])
        eq(f"{f}: 전체 행 수 = v1", len(merged), len(v1))
    runs = load(out / "process-runs.json")
    eq("CURRENT run 90", sum(r["phase"] == "CURRENT" for r in runs), 90)
    eq("CURRENT 계측 450", sum(r["phase"] == "CURRENT" for r in load(out / "site-metrology.json")), 450)
    eq("CURRENT 특징 600", sum(r["phase"] == "CURRENT" for r in load(out / "run-sensor-features.json")), 600)
    lines = read_traces(out / "sensor-traces.jsonl.gz")
    cur = sorted(x for x in lines if trace_key(x)[0] == "CURRENT")
    eq("CURRENT 시계열 600 = v2(줄 단위)", cur, sorted(read_traces(V2 / "sensor-traces.jsonl.gz")))
    eq("FOLLOWUP 시계열 = v1(줄 단위)", [x for x in lines if trace_key(x)[0] != "CURRENT"],
       [x for x in read_traces(d1 / "sensor-traces.jsonl.gz") if trace_key(x)[0] != "CURRENT"])
    eq("시계열 780", len(lines), 780)
    for f in SAME + ["sensor-envelopes.json"]:
        eq(f"{f}: 해시 = v2", sha(out / f), sha(V2 / f))

    # L006 네 가지가 모두 v2이고 v1과 다르다
    r = next(x for x in runs if x["runId"] == REVISED_RUN)
    eq("L006 run 출고 평균 10281.392216", (r["outputMeanA"], r["addedOrRemovedA"]), (10281.392216, 4749.009449))
    v1run = next(x for x in load(d1 / "process-runs.json") if x["runId"] == REVISED_RUN)
    eq("L006 run은 v1과 다르다", r["outputMeanA"] != v1run["outputMeanA"], True)
    sites = [x for x in load(out / "site-metrology.json") if x["runId"] == REVISED_RUN]
    v2sites = [x for x in load(V2 / "site-metrology.json") if x["runId"] == REVISED_RUN]
    v1sites = [x for x in load(d1 / "site-metrology.json") if x["runId"] == REVISED_RUN]
    eq("L006 계측 5행 = v2 ≠ v1", (len(sites), sites == v2sites, sites != v1sites), (5, True, True))
    hp = next(x for x in load(out / "run-sensor-features.json") if x["runId"] == REVISED_RUN and x["sensorId"] == "head_pressure")
    eq("L006 head_pressure 특징 mean 2.841574", hp["mean"], 2.841574)
    tr = next(json.loads(x) for x in lines if trace_key(x)[1:] == (REVISED_RUN, "head_pressure"))
    tr1 = next(json.loads(x) for x in read_traces(d1 / "sensor-traces.jsonl.gz")
               if trace_key(x)[1:] == (REVISED_RUN, "head_pressure"))
    tr2 = next(json.loads(x) for x in read_traces(V2 / "sensor-traces.jsonl.gz")
               if trace_key(x)[1:] == (REVISED_RUN, "head_pressure"))
    eq("L006 head_pressure 시계열 = v2 ≠ v1", (tr == tr2, tr["values"] != tr1["values"]), (True, True))
    eq("L006 시계열이 처리 중 2.6 psi 아래로 떨어진다", min(tr["values"][1100:2000]) < 2.6, True)

    # 요약은 합친 run과 맞고, L006 말고는 v1과 같다
    summary, v1sum = load(out / "current-wafer-summary.json"), load(d1 / "current-wafer-summary.json")
    eq("요약 30행", len(summary), 30)
    eq("요약 L006 말고 29행 = v1", [s for s in summary if s["waferId"] != "W-CURRENT-006-3"],
       [s for s in v1sum if s["waferId"] != "W-CURRENT-006-3"])
    l6 = next(s for s in summary if s["waferId"] == "W-CURRENT-006-3")
    eq("요약 L006 = v2 값", (l6["cmpAfterA"], l6["cmpRemovalA"]), (10281.392216, 4749.009449))
    eq("팩에 evaluation 없음", any(p.name == "evaluation" for p in OUT.rglob("*")), False)
    eq("팩에 CSV 없음", any(p.suffix == ".csv" for p in OUT.rglob("*")), False)
    eq("knowledge = v1", sorted(sha(p) for p in (OUT / "knowledge").iterdir()),
       sorted(sha(p) for p in (V1 / "knowledge").iterdir()))
    return bad


if __name__ == "__main__":
    build()
    failed = check()
    for name in failed:
        print("✘", name)
    print(f"검사 {'통과' if not failed else '실패 ' + str(len(failed)) + '건'} — {OUT.relative_to(ROOT)}")
    sys.exit(1 if failed else 0)
