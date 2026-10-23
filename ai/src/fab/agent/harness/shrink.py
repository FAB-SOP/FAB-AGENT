"""도구 결과 줄이기 — 글자 수 한도를 넘는 JSON을 항목 단위로 줄여 정상 JSON으로 만든다.

예전에는 JSON 문자열을 6,000자에서 그대로 잘랐다. 그러면 모델이 받는 결과가 깨진 JSON이 되고, 뒤쪽 항목(나중 요청 · 회신 · 조치)이
통째로 사라졌다 — 보고서 입력(get_report_context)은 요청 하나 · 회신 하나만으로 5,000자를 넘었다(10-23 측정).
줄이는 순서: ① 빈 칸(null · 빈 목록) 빼기 → ② 긴 글을 앞부분만(보존 칸은 덜 줄인다) → ③ 목록 뒤쪽 항목 빼기(정한 순서대로,
보존 목록은 빼지 않는다). 무엇을 줄였는지는 결과의 `_omitted`에 남긴다 — 모델이 「기록이 더 있다」는 것을 안다.
"""

from __future__ import annotations

import json
from typing import Any

Path = tuple[str, ...]  # 칸 경로 — 목록 항목은 "*"
_FLOORS = ((120, 300), (60, 150))  # 줄인 글의 최소 길이(일반 · 보존 칸) — 목록 항목을 빼기 전에 한 번 더 짧게


def size(obj: Any) -> int:
    return len(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))


def _matches(path: Path, pattern: Path) -> bool:
    return len(path) >= len(pattern) and all(p == "*" or p == q for p, q in zip(pattern, path))


def _protected(path: Path, protected: tuple[Path, ...]) -> bool:
    return any(_matches(path, pattern) for pattern in protected)


def _without_empty(obj: Any) -> Any:
    if isinstance(obj, dict):
        out = {k: _without_empty(v) for k, v in obj.items()}
        return {k: v for k, v in out.items() if v is not None and v != [] and v != {} and v != ""}
    if isinstance(obj, list):
        return [_without_empty(item) for item in obj]
    return obj


def _strings(obj: Any, path: Path = ()):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _strings(v, (*path, k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from _strings(v, (*path, "*"))
    elif isinstance(obj, str):
        yield path, obj


def _shorten_longest(obj: Any, protected: tuple[Path, ...], floors: tuple[int, int]) -> bool:
    """가장 긴 글 하나를 절반으로(최소 길이까지) — 줄일 글이 없으면 False"""
    best: tuple[int, Path, str, int] | None = None
    for path, text in _strings(obj):
        floor = floors[1] if _protected(path, protected) else floors[0]
        if len(text) > floor + 20 and (best is None or len(text) > best[0]):
            best = (len(text), path, text, floor)
    if best is None:
        return False
    length, path, text, floor = best
    keep = max(floor, length // 2)
    _replace(obj, path, text, text[:keep] + f"…({length - keep}자 생략)")
    return True


def _replace(obj: Any, path: Path, old: str, new: str) -> bool:
    """경로(* 포함)를 따라 처음 만나는 같은 글을 바꾼다"""
    if not path:
        return False
    head, rest = path[0], path[1:]
    children = list(enumerate(obj)) if head == "*" and isinstance(obj, list) else (
        [(head, obj[head])] if isinstance(obj, dict) and head in obj else [])
    for key, child in children:
        if not rest and child == old:
            obj[key] = new
            return True
        if rest and _replace(child, rest, old, new):
            return True
    return False


def _lists(obj: Any, pattern: Path, path: Path = ()):
    """패턴에 맞는 목록들 — (실제 경로, 목록)"""
    if _matches(path, pattern) and len(path) == len(pattern) and isinstance(obj, list):
        yield path, obj
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            if len(path) < len(pattern) and pattern[len(path)] in (k, "*"):
                yield from _lists(v, pattern, (*path, k))
    elif isinstance(obj, list) and len(path) < len(pattern) and pattern[len(path)] == "*":
        for v in obj:
            yield from _lists(v, pattern, (*path, "*"))


def shrink(obj: Any, limit: int, *, drop_order: tuple[Path, ...] = (), protected: tuple[Path, ...] = ()) -> Any:
    """limit 글자 안의 정상 JSON(dict)으로 줄인다. drop_order 목록의 뒤쪽 항목(또는 칸)부터 빼고, 그래도 넘치면 보존하지 않는
    가장 긴 목록에서 뺀다. 보존 칸(protected)의 목록 항목은 빼지 않고 글도 덜 줄인다. `_omitted` 표시까지 한도 안에 든다"""
    budget = limit
    for _ in range(4):
        out = _shrink(obj, budget, drop_order, protected)
        if size(out) <= limit:
            break
        budget -= size(out) - limit + 50  # 생략 표시가 차지한 만큼 더 줄인다
    return out


def _shrink(obj: Any, budget: int, drop_order: tuple[Path, ...], protected: tuple[Path, ...]) -> Any:
    out = _without_empty(json.loads(json.dumps(obj, ensure_ascii=False)))
    omitted: dict[str, int] = {}
    while size(out) > budget and _shorten_longest(out, protected, _FLOORS[0]):
        pass
    for pattern in drop_order:  # 목록이면 뒤쪽 항목부터, 칸이면 그 칸을 뺀다
        for path, items in list(_lists(out, pattern)):
            while items and size(out) > budget:
                items.pop()
                omitted[".".join(path)] = omitted.get(".".join(path), 0) + 1
        for parent in list(_parents(out, pattern[:-1])):
            if size(out) <= budget:
                break
            if isinstance(parent, dict) and pattern[-1] in parent and not isinstance(parent[pattern[-1]], list):
                del parent[pattern[-1]]
                omitted[".".join(pattern)] = omitted.get(".".join(pattern), 0) + 1
    while size(out) > budget and _shorten_longest(out, protected, _FLOORS[1]):  # 항목을 빼기 전에 글을 더 짧게
        pass
    while size(out) > budget:  # 정한 순서로도 모자라면 — 보존하지 않는 가장 긴 목록에서
        candidates = [(size(items), path, items) for path, items in _all_lists(out)
                      if items and not _protected((*path, "*"), protected)]
        if not candidates:
            break
        _, path, items = max(candidates, key=lambda c: c[0])
        items.pop()
        omitted[".".join(path)] = omitted.get(".".join(path), 0) + 1
    if omitted and isinstance(out, dict):
        out["_omitted"] = {"items": omitted, "note": "한도 때문에 뺀 항목 수입니다. 기록이 더 있습니다 — 없다고 쓰지 않습니다."}
    return out


def _parents(obj: Any, pattern: Path):
    """패턴 경로에 있는 값들(* 는 목록 항목 전부)"""
    if not pattern:
        yield obj
        return
    head, rest = pattern[0], pattern[1:]
    if head == "*" and isinstance(obj, list):
        for item in obj:
            yield from _parents(item, rest)
    elif isinstance(obj, dict) and head in obj:
        yield from _parents(obj[head], rest)


def _all_lists(obj: Any, path: Path = ()):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _all_lists(v, (*path, k))
    elif isinstance(obj, list):
        yield path, obj
        for v in obj:
            yield from _all_lists(v, (*path, "*"))
