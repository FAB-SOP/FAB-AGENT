"""공개 API 대조 — 실행 중인 서버의 /v3/api-docs와 backend-handoff-v2/combined.openapi.json(프론트 계약)을 비교한다.

  python3 tools/openapi_diff.py http://localhost:8096          # 표(마크다운)로 차이를 출력
operation(메서드 · 경로 · operationId)과, 각 operation의 요청 · 응답 스키마를 $ref까지 펼쳐 필드 · 필수 · enum을 비교한다.
"""

import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(base):
    with urllib.request.urlopen(base.rstrip("/") + "/v3/api-docs") as r:
        return json.load(r)


def resolve(doc, s, seen=()):
    if not isinstance(s, dict):
        return s
    if "$ref" in s:
        name = s["$ref"].split("/")[-1]
        if name in seen:
            return {"$ref": name}
        return resolve(doc, doc["components"]["schemas"][name], seen + (name,))
    out = {}
    for k, v in s.items():
        if k == "properties":
            out[k] = {p: resolve(doc, x, seen) for p, x in v.items()}
        elif k in ("items", "additionalProperties"):
            out[k] = resolve(doc, v, seen)
        elif k in ("allOf", "oneOf", "anyOf"):
            out[k] = [resolve(doc, x, seen) for x in v]
        else:
            out[k] = v
    return out


def flat(s, path=""):
    """스키마를 '경로 → (타입, 필수 여부, enum)'으로 펼친다"""
    out = {}
    if not isinstance(s, dict):
        return out
    for sub in s.get("allOf", []) + s.get("oneOf", []) + s.get("anyOf", []):
        out.update(flat(sub, path))
    req = set(s.get("required", []))
    for p, x in (s.get("properties") or {}).items():
        key = f"{path}.{p}" if path else p
        t = x.get("type") if isinstance(x, dict) else None
        out[key] = {"type": json.dumps(t), "required": p in req, "enum": tuple(sorted(map(str, x.get("enum", []))))
                    if isinstance(x, dict) else ()}
        out.update(flat(x, key))
    if isinstance(s.get("items"), dict):
        out.update(flat(s["items"], path + "[]"))
    return out


def ops(doc):
    m = {}
    for p, items in doc["paths"].items():
        for meth, o in items.items():
            if meth not in ("get", "post", "patch", "put", "delete"):
                continue
            body = (((o.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
            resp = {}
            for code, r in (o.get("responses") or {}).items():
                c = (r.get("content") or {})
                sch = next(iter(c.values()), {}).get("schema") if c else None
                if sch and code.startswith("2"):
                    resp[code] = flat(resolve(doc, sch))
            params = {x["name"]: (x.get("in"), x.get("required", False), tuple(sorted(map(str, (x.get("schema") or {}).get("enum", [])))))
                      for x in o.get("parameters", [])}
            m[(meth.upper(), p)] = {"op": o.get("operationId"), "params": params,
                                    "body": flat(resolve(doc, body)) if body else {}, "resp": resp}
    return m


def main():
    server, front = ops(load(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8096")), ops(
        json.loads((ROOT / "backend-handoff-v2/combined.openapi.json").read_text(encoding="utf-8")))
    rows = []
    only_front, only_server = sorted(set(front) - set(server)), sorted(set(server) - set(front))
    for k in only_front:
        rows.append((f"{k[0]} {k[1]}", "operation", "프론트에만", ""))
    for k in only_server:
        rows.append((f"{k[0]} {k[1]}", "operation", "서버에만", ""))
    for k in sorted(set(front) & set(server)):
        f, s = front[k], server[k]
        name = f"{k[0]} {k[1]}"
        if f["op"] != s["op"]:
            rows.append((name, "operationId", f["op"], s["op"]))
        for p in sorted(set(f["params"]) | set(s["params"])):
            if f["params"].get(p) != s["params"].get(p):
                rows.append((name, f"param {p}", str(f["params"].get(p)), str(s["params"].get(p))))
        for part in ("body",):
            for fld in sorted(set(f[part]) | set(s[part])):
                if f[part].get(fld) != s[part].get(fld):
                    rows.append((name, f"요청 {fld}", str(f[part].get(fld)), str(s[part].get(fld))))
        for code in sorted(set(f["resp"]) | set(s["resp"])):
            fr, sr = f["resp"].get(code, {}), s["resp"].get(code, {})
            for fld in sorted(set(fr) | set(sr)):
                a, b = fr.get(fld), sr.get(fld)
                if a is None and b is not None:
                    continue  # 서버가 더 주는 필드(프론트는 무시) — 아래에서 따로 센다
                if a != b:
                    rows.append((name, f"응답 {code} {fld}", str(a), str(b)))
    print(f"operation: 프론트 {len(front)} · 서버 {len(server)} · 공통 {len(set(front) & set(server))}")
    print("| operation | 항목 | 프론트 계약 | 서버 |\n|---|---|---|---|")
    for r in rows:
        print("| " + " | ".join(x.replace("|", "\\|") for x in r) + " |")
    extra = sum(1 for k in set(front) & set(server) for code in server[k]["resp"]
                for fld in server[k]["resp"][code] if fld not in front[k]["resp"].get(code, {}))
    print(f"\n(서버가 더 내려주는 응답 필드 {extra}개는 표에서 뺐다 — 프론트는 모르는 필드를 무시한다)")


if __name__ == "__main__":
    main()
