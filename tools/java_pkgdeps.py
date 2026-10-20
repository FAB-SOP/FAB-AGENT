"""Spring 패키지 의존 표와 순환 — import와 본문 안의 완전한 이름(com.fabcontext.api.x.Y)을 센다.

  python3 tools/java_pkgdeps.py fab-context-api/src/main/java

순환(강연결 요소)이 「없음」이어야 한다. 서로 의존하는 쌍이 있으면 어느 클래스가 잇는지 보여 준다.
"""
import re, sys, os, collections, itertools
root = sys.argv[1]; base = "com.fabcontext.api."
classes = {}  # FQN class -> package
files = []
for d, _, fs in os.walk(root):
    for f in fs:
        if f.endswith(".java"):
            p = os.path.join(d, f); s = open(p, encoding="utf-8").read()
            pkg = re.search(r"^package ([\w.]+);", s, re.M).group(1)
            classes[pkg + "." + f[:-5]] = pkg; files.append((p, pkg, s))
def short(pkg): return pkg[len(base):] if pkg.startswith(base) else "(root)" if pkg == base[:-1] else pkg
edges = collections.defaultdict(lambda: collections.defaultdict(set))
for p, pkg, s in files:
    refs = set(re.findall(r"^import (?:static )?(com\.fabcontext\.api\.[\w.]+?)(?:\.\*)?;", s, re.M))
    body = re.sub(r"^(import|package) .*$", "", s, flags=re.M)
    refs |= set(re.findall(r"(com\.fabcontext\.api(?:\.[a-z]\w*)+\.[A-Z]\w*)", body))
    for r in refs:
        parts = r.split(".")
        # find the class prefix (first capitalized segment)
        i = next((k for k, x in enumerate(parts) if x[:1].isupper()), len(parts))
        target = ".".join(parts[:i])
        if target and target != pkg:
            edges[short(pkg)][short(target)].add(os.path.basename(p)[:-5] + "→" + parts[i] if i < len(parts) else os.path.basename(p))
sizes = collections.Counter()
for p, pkg, s in files: sizes[short(pkg)] += s.count("\n")
pk = sorted(set(edges) | {t for v in edges.values() for t in v} | set(sizes))
print("| 패키지 | 줄 | 의존하는 패키지 |\n|---|---|---|")
for a in pk: print(f"| {a} | {sizes[a]} | {', '.join(sorted(edges[a]))} |")
# cycles via Tarjan SCC
idx = {}; low = {}; st = []; on = set(); sccs = []; c = [0]
def sc(v):
    idx[v] = low[v] = c[0]; c[0] += 1; st.append(v); on.add(v)
    for w in edges[v]:
        if w not in idx: sc(w); low[v] = min(low[v], low[w])
        elif w in on: low[v] = min(low[v], idx[w])
    if low[v] == idx[v]:
        comp = []
        while True:
            w = st.pop(); on.discard(w); comp.append(w)
            if w == v: break
        if len(comp) > 1: sccs.append(sorted(comp))
sys.setrecursionlimit(10000)
for v in pk:
    if v not in idx: sc(v)
print("\n순환(강연결 요소):", sccs or "없음")
print("서로 의존하는 쌍:")
for a, b in itertools.combinations(pk, 2):
    if b in edges[a] and a in edges[b]:
        print(f"  {a} ↔ {b}:  {sorted(edges[a][b])[:6]}  ||  {sorted(edges[b][a])[:6]}")
