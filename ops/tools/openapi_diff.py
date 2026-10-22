"""공개 API 대조 — 실행 중인 서버의 /v3/api-docs와 data/backend-handoff-v2/combined.openapi.json(프론트 계약)을 비교한다.

  python3 ops/tools/openapi_diff.py http://localhost:8096          # 표(마크다운)로 차이를 출력
operation(메서드 · 경로 · operationId)과, 각 operation의 요청 · 응답 스키마를 $ref까지 펼쳐 필드 · 필수 · enum을 비교한다.
"""

import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(base):
    with urllib.request.urlopen(base.rstrip("/") + "/v3/api-docs") as response:
        return json.load(response)


def resolve(doc, schema, seen=()):
    if not isinstance(schema, dict):
        return schema
    if "$ref" in schema:
        name = schema["$ref"].split("/")[-1]
        if name in seen:
            return {"$ref": name}
        return resolve(doc, doc["components"]["schemas"][name], seen + (name,))
    out = {}
    for key, value in schema.items():
        if key == "properties":
            out[key] = {prop_name: resolve(doc, item, seen) for prop_name, item in value.items()}
        elif key in ("items", "additionalProperties"):
            out[key] = resolve(doc, value, seen)
        elif key in ("allOf", "oneOf", "anyOf"):
            out[key] = [resolve(doc, item, seen) for item in value]
        else:
            out[key] = value
    return out


def flat(schema, path=""):
    """스키마를 '경로 → (타입, 필수 여부, enum)'으로 펼친다"""
    out = {}
    if not isinstance(schema, dict):
        return out
    for sub in schema.get("allOf", []) + schema.get("oneOf", []) + schema.get("anyOf", []):
        out.update(flat(sub, path))
    required = set(schema.get("required", []))
    for prop_name, prop_schema in (schema.get("properties") or {}).items():
        key = f"{path}.{prop_name}" if path else prop_name
        prop_type = prop_schema.get("type") if isinstance(prop_schema, dict) else None
        out[key] = {"type": json.dumps(prop_type), "required": prop_name in required, "enum": tuple(sorted(map(str, prop_schema.get("enum", []))))
                    if isinstance(prop_schema, dict) else ()}
        out.update(flat(prop_schema, key))
    if isinstance(schema.get("items"), dict):
        out.update(flat(schema["items"], path + "[]"))
    return out


def ops(doc):
    operations = {}
    for path, items in doc["paths"].items():
        for method, operation in items.items():
            if method not in ("get", "post", "patch", "put", "delete"):
                continue
            body = (((operation.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
            resp = {}
            for code, response in (operation.get("responses") or {}).items():
                content = (response.get("content") or {})
                schema = next(iter(content.values()), {}).get("schema") if content else None
                if schema and code.startswith("2"):
                    resp[code] = flat(resolve(doc, schema))
            params = {param["name"]: (param.get("in"), param.get("required", False), tuple(sorted(map(str, (param.get("schema") or {}).get("enum", [])))))
                      for param in operation.get("parameters", [])}
            operations[(method.upper(), path)] = {"op": operation.get("operationId"), "params": params,
                                    "body": flat(resolve(doc, body)) if body else {}, "resp": resp}
    return operations


def operation_rows(name, front_op, server_op):
    """공통 operation 하나의 차이 — operationId · 파라미터 · 요청 필드 · 응답 필드(서버에만 있는 응답 필드는 뺀다)"""
    rows = []
    if front_op["op"] != server_op["op"]:
        rows.append((name, "operationId", front_op["op"], server_op["op"]))
    for param in sorted(set(front_op["params"]) | set(server_op["params"])):
        if front_op["params"].get(param) != server_op["params"].get(param):
            rows.append((name, f"param {param}", str(front_op["params"].get(param)), str(server_op["params"].get(param))))
    for field in sorted(set(front_op["body"]) | set(server_op["body"])):
        if front_op["body"].get(field) != server_op["body"].get(field):
            rows.append((name, f"요청 {field}", str(front_op["body"].get(field)), str(server_op["body"].get(field))))
    for code in sorted(set(front_op["resp"]) | set(server_op["resp"])):
        front_fields, server_fields = front_op["resp"].get(code, {}), server_op["resp"].get(code, {})
        for field in sorted(set(front_fields) | set(server_fields)):
            front_value, server_value = front_fields.get(field), server_fields.get(field)
            if front_value is None and server_value is not None:
                continue  # 서버가 더 주는 필드(프론트는 무시) — 아래에서 따로 센다
            if front_value != server_value:
                rows.append((name, f"응답 {code} {field}", str(front_value), str(server_value)))
    return rows


def main():
    server, front = ops(load(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8096")), ops(
        json.loads((ROOT / "data/backend-handoff-v2/combined.openapi.json").read_text(encoding="utf-8")))
    rows = []
    for key in sorted(set(front) - set(server)):
        rows.append((f"{key[0]} {key[1]}", "operation", "프론트에만", ""))
    for key in sorted(set(server) - set(front)):
        rows.append((f"{key[0]} {key[1]}", "operation", "서버에만", ""))
    for key in sorted(set(front) & set(server)):
        rows += operation_rows(f"{key[0]} {key[1]}", front[key], server[key])
    print(f"operation: 프론트 {len(front)} · 서버 {len(server)} · 공통 {len(set(front) & set(server))}")
    print("| operation | 항목 | 프론트 계약 | 서버 |\n|---|---|---|---|")
    for row in rows:
        print("| " + " | ".join(cell.replace("|", "\\|") for cell in row) + " |")
    extra = sum(1 for key in set(front) & set(server) for code in server[key]["resp"]
                for field in server[key]["resp"][code] if field not in front[key]["resp"].get(code, {}))
    print(f"\n(서버가 더 내려주는 응답 필드 {extra}개는 표에서 뺐다 — 프론트는 모르는 필드를 무시한다)")


if __name__ == "__main__":
    main()
