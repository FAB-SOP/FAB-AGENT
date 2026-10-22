"""Spring 패키지 의존 표와 순환 — import와 본문 안의 완전한 이름(com.fabcontext.api.x.Y)을 센다.

  python3 ops/tools/java_pkgdeps.py backend/src/main/java

순환(강연결 요소)이 「없음」이어야 한다. 서로 의존하는 쌍이 있으면 어느 클래스가 잇는지 보여 준다.
"""
import collections
import itertools
import os
import re
import sys

BASE = "com.fabcontext.api."


def read_sources(root):
    """(파일 경로, 패키지, 본문) 목록"""
    sources = []
    for directory, _, file_names in os.walk(root):
        for file_name in file_names:
            if file_name.endswith(".java"):
                path = os.path.join(directory, file_name)
                text = open(path, encoding="utf-8").read()
                package = re.search(r"^package ([\w.]+);", text, re.M).group(1)
                sources.append((path, package, text))
    return sources


def short(package):
    return package[len(BASE):] if package.startswith(BASE) else "(root)" if package == BASE[:-1] else package


def referenced_names(text):
    """import와 본문 안의 완전한 이름"""
    names = set(re.findall(r"^import (?:static )?(com\.fabcontext\.api\.[\w.]+?)(?:\.\*)?;", text, re.M))
    body = re.sub(r"^(import|package) .*$", "", text, flags=re.M)
    names |= set(re.findall(r"(com\.fabcontext\.api(?:\.[a-z]\w*)+\.[A-Z]\w*)", body))
    return names


def dependency_edges(sources):
    """패키지 → 의존 패키지 → 잇는 「클래스→대상 클래스」 집합"""
    edges = collections.defaultdict(lambda: collections.defaultdict(set))
    for path, package, text in sources:
        for name in referenced_names(text):
            parts = name.split(".")
            # 클래스 이름이 시작되는 곳(첫 대문자 조각)
            class_index = next((k for k, part in enumerate(parts) if part[:1].isupper()), len(parts))
            target = ".".join(parts[:class_index])
            if target and target != package:
                source_class = os.path.basename(path)[:-5]
                link = source_class + "→" + parts[class_index] if class_index < len(parts) else os.path.basename(path)
                edges[short(package)][short(target)].add(link)
    return edges


def strongly_connected(edges, packages):
    """Tarjan 강연결 요소 중 둘 이상인 것"""
    index, low, stack, on_stack, components, counter = {}, {}, [], set(), [], [0]

    def visit(node):
        index[node] = low[node] = counter[0]
        counter[0] += 1
        stack.append(node)
        on_stack.add(node)
        for neighbor in edges[node]:
            if neighbor not in index:
                visit(neighbor)
                low[node] = min(low[node], low[neighbor])
            elif neighbor in on_stack:
                low[node] = min(low[node], index[neighbor])
        if low[node] == index[node]:
            component = []
            while True:
                member = stack.pop()
                on_stack.discard(member)
                component.append(member)
                if member == node:
                    break
            if len(component) > 1:
                components.append(sorted(component))

    sys.setrecursionlimit(10000)
    for node in packages:
        if node not in index:
            visit(node)
    return components


def main(root):
    sources = read_sources(root)
    edges = dependency_edges(sources)
    sizes = collections.Counter()
    for _, package, text in sources:
        sizes[short(package)] += text.count("\n")
    packages = sorted(set(edges) | {target for targets in edges.values() for target in targets} | set(sizes))
    print("| 패키지 | 줄 | 의존하는 패키지 |\n|---|---|---|")
    for package in packages:
        print(f"| {package} | {sizes[package]} | {', '.join(sorted(edges[package]))} |")
    print("\n순환(강연결 요소):", strongly_connected(edges, packages) or "없음")
    print("서로 의존하는 쌍:")
    for first, second in itertools.combinations(packages, 2):
        if second in edges[first] and first in edges[second]:
            print(f"  {first} ↔ {second}:  {sorted(edges[first][second])[:6]}  ||  {sorted(edges[second][first])[:6]}")


if __name__ == "__main__":
    main(sys.argv[1])
