"""가독성 지표 — 리팩터링 전후를 같은 규칙으로 잰다(research R4). 표준 라이브러리만 쓴다.

사용: python3 specs/002-refactor-readability/tools/metrics.py   (저장소 루트에서)
"""
import ast
import re
import subprocess
from pathlib import Path

LONG_FUNCTION_LINES = 40
LONG_LINE_CHARS = 160
ALLOWED_SHORT_NAMES = {"i", "j", "k", "v", "e", "_"}
PYTHON_ROOTS = ["fab-agent/src", "fab-agent/evaluation", "data", "tools"]
JAVA_ROOT = "fab-context-api/src/main/java"
WEB_ROOT = "fab-context-web/src"
JAVA_PACKAGE_PREFIX = "com/fabcontext/api/"


def tracked_files(root, suffixes):
    listing = subprocess.run(["git", "ls-files", root], capture_output=True, text=True, check=True).stdout
    return [path for path in listing.split() if path.endswith(suffixes)]


def web_line_report():
    rows = []
    for path in tracked_files(WEB_ROOT, (".ts", ".tsx")):
        lengths = [len(line) for line in Path(path).read_text(encoding="utf-8").split("\n")]
        over_limit = sum(1 for length in lengths if length > LONG_LINE_CHARS)
        rows.append((max(lengths), over_limit, path))
    return sorted(rows, key=lambda row: (-row[0], row[2]))


def python_reports():
    long_functions, short_names = [], {}
    for root in PYTHON_ROOTS:
        for path in tracked_files(root, (".py",)):
            tree = ast.parse(Path(path).read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    length = node.end_lineno - node.lineno + 1
                    if length > LONG_FUNCTION_LINES:
                        long_functions.append((length, path, node.name))
                is_short_assignment = (isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store)
                                       and len(node.id) <= 2 and node.id not in ALLOWED_SHORT_NAMES)
                if is_short_assignment:
                    short_names[path] = short_names.get(path, 0) + 1
    return sorted(long_functions, key=lambda row: (-row[0], row[1], row[2])), short_names


def is_method_declaration(line):
    return (re.match(r"\s*(public|private|protected|static)", line) and line.rstrip().endswith("{")
            and "(" in line and not re.search(r"\b(class|record|interface|enum)\b", line))


def java_long_methods():
    rows = []
    for path in tracked_files(JAVA_ROOT, (".java",)):
        lines = Path(path).read_text(encoding="utf-8").split("\n")
        index = 0
        while index < len(lines):
            if is_method_declaration(lines[index]):
                depth, end = 0, index
                for end in range(index, len(lines)):
                    depth += lines[end].count("{") - lines[end].count("}")
                    if depth == 0:
                        break
                length = end - index + 1
                if length > LONG_FUNCTION_LINES:
                    method_name = re.search(r"(\w+)\s*\(", lines[index]).group(1)
                    rows.append((length, path.split(JAVA_PACKAGE_PREFIX)[1], method_name))
                index = end
            index += 1
    return sorted(rows, key=lambda row: (-row[0], row[1], row[2]))


def main():
    web_rows = web_line_report()
    print("== 화면: 파일별 가장 긴 줄 · 160자 초과 줄 수")
    for longest, over_limit, path in web_rows:
        print(f"{longest:5d} {over_limit:3d} {path}")
    print(f"요약: 가장 긴 줄 {web_rows[0][0]}자 · 160자 초과 줄이 있는 파일 {sum(1 for row in web_rows if row[1])}개")

    long_functions, short_names = python_reports()
    print(f"\n== Python: 본문 {LONG_FUNCTION_LINES}줄 초과 함수 {len(long_functions)}개")
    for length, path, name in long_functions:
        print(f"{length:5d} {path} {name}")

    java_rows = java_long_methods()
    print(f"\n== Java: 본문 {LONG_FUNCTION_LINES}줄 초과 메서드 {len(java_rows)}개")
    for length, path, name in java_rows:
        print(f"{length:5d} {path} {name}")

    print(f"\n== Python: 한두 글자 이름 {sum(short_names.values())}곳 (허용 {' '.join(sorted(ALLOWED_SHORT_NAMES))})")
    for path, count in sorted(short_names.items(), key=lambda row: (-row[1], row[0])):
        print(f"{count:5d} {path}")


if __name__ == "__main__":
    main()
