// 상자는 내용 크기에 맞춰 렌더링되고, 이 스크립트가 행 배치 · 연결선 · 띠 · 캔버스 높이를 계산한다.
// 노드: <div class="node" data-row="0" data-x="800" data-anchor="c|l|r" data-dy="0" data-below="id" data-gap="40">
const C = document.getElementById('c');
function R(id) {
  const e = document.getElementById(id), c = C.getBoundingClientRect(), r = e.getBoundingClientRect();
  return {l: r.left - c.left, r: r.right - c.left, t: r.top - c.top, b: r.bottom - c.top,
          cx: (r.left + r.right) / 2 - c.left, cy: (r.top + r.bottom) / 2 - c.top};
}
function layout(cfg) {
  const nodes = [...document.querySelectorAll('.node')];
  for (const n of nodes) {
    n.style.left = n.dataset.x + 'px';
    if (n.dataset.anchor === 'r') n.style.transform = 'translateX(-100%)';
    else if (n.dataset.anchor !== 'l') n.style.transform = 'translateX(-50%)';
  }
  const rowBox = []; let top = cfg.top;
  const ids = [...new Set(nodes.map(n => +n.dataset.row))].sort((a, b) => a - b);
  for (const row of ids) {
    const rn = nodes.filter(n => +n.dataset.row === row);
    for (const n of rn.filter(n => !n.dataset.below)) n.style.top = (top + +(n.dataset.dy || 0)) + 'px';
    for (const n of rn.filter(n => n.dataset.below)) {
      const above = document.getElementById(n.dataset.below).closest('.node');
      n.style.top = (above.offsetTop + above.offsetHeight + +(n.dataset.gap || 30)) + 'px';
    }
    const bottom = Math.max(...rn.map(n => n.offsetTop + n.offsetHeight));
    rowBox.push({row, top, bottom});
    top = bottom + (cfg.gaps[row] ?? 80);
  }
  return rowBox;
}
function draw(edges, rowBox) {
  const svg = document.querySelector('svg.edges');
  let html = '';
  for (const e of edges) {
    const p = e.pts(R, rowBox);
    html += `<path class="l" d="M${p.map(q => q.join(',')).join(' L')}" stroke="${e.c}" ${e.dash ? 'stroke-dasharray="6 5"' : ''} marker-end="url(#m${e.c.slice(1)})"/>`;
    for (const [x, y, s, a] of e.label ? e.label(p, R, rowBox) : [])
      html += `<text class="t" x="${x}" y="${y}" text-anchor="${a || 'start'}">${s}</text>`;
  }
  const colors = [...new Set(edges.map(e => e.c))];
  svg.innerHTML = '<defs>' + colors.map(c => `<marker id="m${c.slice(1)}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0L10,5L0,10z" fill="${c}"/></marker>`).join('') + '</defs>' + html;
}
function bands(rowBox, defs) {
  for (const d of defs) {
    const r = rowBox.find(x => x.row === d.row), top = r.top - 16, h = r.bottom - r.top + 32;
    C.insertAdjacentHTML('afterbegin', `<div class="band" style="top:${top}px;height:${h}px"></div><div class="bandname" style="top:${top}px;height:${h}px">${d.name}</div>`);
  }
}
function finish(rowBox, extra) {
  const all = [...document.querySelectorAll('.node, .legend')];
  const h = Math.max(...all.map(n => n.offsetTop + n.offsetHeight), ...rowBox.map(r => r.bottom)) + (extra || 40);
  C.style.height = h + 'px';
  document.querySelector('svg.edges').setAttribute('height', h);
  document.body.setAttribute('data-h', Math.ceil(h));
}
