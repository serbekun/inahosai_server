(() => {
  const canvas = document.getElementById('graph');
  if (!canvas || !canvas.getContext) return;

  const ctx = canvas.getContext('2d');
  const FONT = '600 %dpx "Hiragino Mincho ProN", "Yu Mincho", "YuMincho", "Noto Serif JP", "Source Han Serif", serif';
  const still = matchMedia('(prefers-reduced-motion: reduce)');

  const CENTER = { text: 'つなぐ', at: [0.46, 0.48] };

  const BRANCHES = [
    { label: '時間をつなぐ', at: [0.27, 0.20],
      leaves: ['過去', '現在', '未来'],
      leafAt: [[0.10, 0.31], [0.20, 0.09], [0.38, 0.07]] },

    { label: '学びをつなぐ', at: [0.71, 0.17],
      leaves: ['書道', '美術', '英語'],
      leafAt: [[0.60, 0.06], [0.80, 0.06], [0.90, 0.26]] },

    { label: '場所をつなぐ', at: [0.28, 0.79],
      leaves: ['教室', '校庭', '地域'],
      leafAt: [[0.13, 0.92], [0.33, 0.93], [0.10, 0.68]] },

    { label: '世界をつなぐ', at: [0.72, 0.75],
      leaves: ['言葉', '文化', '友情'],
      leafAt: [[0.88, 0.62], [0.87, 0.90], [0.66, 0.93]] },
  ];

  const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

  function layout(wide) {
    const VW = wide ? 1240 : 620;
    const VH = wide ? 980 : 1560;

    const fs = wide ? { center: 68, branch: 44, leaf: 30 }
                    : { center: 74, branch: 50, leaf: 38 };

    const cx = wide ? CENTER.at[0] * VW : VW / 2;
    const cy = wide ? CENTER.at[1] * VH : 120;

    const nodes = [{ text: CENTER.text, x: cx, y: cy, fs: fs.center, kind: 'center', branch: -1 }];
    const edges = [];
    const rail  = wide ? null : 58;

    BRANCHES.forEach((b, i) => {
      let bx, by;

      if (wide) {
        bx = b.at[0] * VW;
        by = b.at[1] * VH;
      } else {
        bx = VW / 2 + (i % 2 ? 34 : -26);
        by = 320 + i * 300;
      }

      nodes.push({ text: b.label, x: bx, y: by, fs: fs.branch, kind: 'branch', branch: i });
      const bIdx = nodes.length - 1;
      edges.push({ a: 0, b: bIdx, elbow: !wide, delay: i * 0.06 });

      b.leaves.forEach((leaf, j) => {
        let lx, ly;

        if (wide) {
          const hw = leaf.length * fs.leaf * 0.5 + 12;
          const hh = fs.leaf * 0.8 + 10;
          lx = clamp(b.leafAt[j][0] * VW, hw, VW - hw);
          ly = clamp(b.leafAt[j][1] * VH, hh, VH - hh);
        } else {
          lx = VW / 2 + (j - 1) * 176;
          ly = by + 140;
        }

        nodes.push({ text: leaf, x: lx, y: ly, fs: fs.leaf, kind: 'leaf', branch: i });
        edges.push({ a: bIdx, b: nodes.length - 1, elbow: false, delay: 0.2 + i * 0.06 + j * 0.03 });
      });
    });

    return { VW, VH, nodes, edges, rail };
  }

  let dots = null;
  let edgePaths = [];
  let scale = 1, cssW = 0, cssH = 0;

  let layoutNodes = [];
  let branchNodes = [];
  let hovered = -1;

  const HTML_API = '/static/v0/html/';
  const BRANCH_LINKS = [
    HTML_API + 'jikan.html',
    HTML_API + 'manabi.html',
    HTML_API + 'basho.html',
    HTML_API + 'sekai.html',
  ];
  const HOVER = 1.28;

  const COLORS = { center: '#9a7415', branch: '#171d27', leaf: '#5c6472', edge: 'rgba(23,29,39,.24)' };

  function readColors() {
    const s = getComputedStyle(document.documentElement);
    const get = (n, fb) => (s.getPropertyValue(n) || '').trim() || fb;
    COLORS.center = get('--dot-center', COLORS.center);
    COLORS.branch = get('--dot-branch', COLORS.branch);
    COLORS.leaf   = get('--dot-leaf',   COLORS.leaf);
    COLORS.edge   = get('--edge',       COLORS.edge);
  }

  function wordDots(text, fsPx, cx, cy, out, kind, delay, br) {
    const off  = document.createElement('canvas');
    const octx = off.getContext('2d', { willReadFrequently: true });
    const font = FONT.replace('%d', Math.round(fsPx));

    octx.font = font;
    const tracking = fsPx * 0.1;
    const chars  = Array.from(text);
    const widths = chars.map((c) => octx.measureText(c).width);
    const textW  = widths.reduce((a, b) => a + b, 0) + tracking * (chars.length - 1);

    const tw = Math.ceil(textW) + 6;
    const th = Math.ceil(fsPx * 1.3);
    off.width = tw; off.height = th;

    octx.font = font;
    octx.textAlign = 'left';
    octx.textBaseline = 'middle';
    octx.fillStyle = '#fff';
    let px = (tw - textW) / 2;
    chars.forEach((c, i) => { octx.fillText(c, px, th / 2); px += widths[i] + tracking; });

    const data = octx.getImageData(0, 0, tw, th).data;
    const gap  = Math.max(1.7, fsPx / 19);
    const ox   = cx - tw / 2;
    const oy   = cy - th / 2;

    const at = (x, y) => (x < 0 || y < 0 || x >= tw || y >= th) ? 0 : data[((y | 0) * tw + (x | 0)) * 4 + 3];

    for (let y = 0; y < th; y += gap) {
      for (let x = 0; x < tw; x += gap) {
        if (at(x, y) < 100) continue;

        const edge = at(x - gap, y) < 100 || at(x + gap, y) < 100 ||
                     at(x, y - gap) < 100 || at(x, y + gap) < 100;
        if (!edge && Math.random() > 0.66) continue;

        const ang = Math.random() * Math.PI * 2;
        const r   = 40 + Math.random() * 70;

        out.push({
          hx: ox + x, hy: oy + y,
          sx: ox + x + Math.cos(ang) * r,
          sy: oy + y + Math.sin(ang) * r,
          s: gap * (Math.random() < 0.2 ? 0.95 : 0.68),
          a: edge ? 1 : 0.52 + Math.random() * 0.42,
          kind,
          branch: br,
          d: delay + Math.random() * 0.12,
          fl: Math.random() < 0.38 ? 0.6 + Math.random() * 1.3 : 0,
          ph: Math.random() * Math.PI * 2,
        });
      }
    }
  }

  function build() {
    readColors();

    const wide = canvas.parentElement.clientWidth > 760;
    const { VW, VH, nodes, edges, rail } = layout(wide);

    cssW  = canvas.parentElement.clientWidth;
    scale = cssW / VW;
    cssH  = VH * scale;

    const titleEl = document.querySelector('.hero__title');
    const disp = titleEl ? parseFloat(getComputedStyle(titleEl).fontSize) : 0;
    if (disp > 0) {
      nodes.forEach((nd) => { if (nd.kind === 'branch') nd.fs = disp / scale; });
    }

    layoutNodes = nodes;
    branchNodes = nodes.filter((n) => n.kind === 'branch')
      .map((n) => ({ n, sx: n.x * scale, sy: n.y * scale, baseRendered: n.fs * scale }));

    const dpr = Math.min(devicePixelRatio || 1, 2);
    canvas.style.height = cssH + 'px';
    canvas.width  = Math.round(cssW * dpr);
    canvas.height = Math.round(cssH * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const list = [];
    nodes.forEach((n, k) => {
      const delay = n.kind === 'center' ? 0 : n.kind === 'branch' ? 0.12 + n.branch * 0.05 : 0.3 + n.branch * 0.05;
      wordDots(n.text, n.fs * scale, n.x * scale, n.y * scale, list, n.kind, delay, n.branch);
      void k;
    });
    dots = list;

    if (hovered >= 0) rebuildBranch(hovered, HOVER);

    edgePaths = edges.map((e) => {
      const a = nodes[e.a], b = nodes[e.b];
      const p = new Path2D();
      let len;

      if (e.elbow) {
        const y0 = (a.y + a.fs * 0.66 + 18) * scale;
        const bx = (b.x - b.text.length * b.fs * 0.5 - 14) * scale;
        const rx = rail * scale, by = b.y * scale, ax = a.x * scale;
        p.moveTo(ax, y0); p.lineTo(ax, y0 + 24 * scale);
        p.lineTo(rx, y0 + 24 * scale); p.lineTo(rx, by); p.lineTo(bx, by);
        len = Math.abs(24 * scale) + Math.abs(ax - rx) + Math.abs(by - y0 - 24 * scale) + Math.abs(bx - rx);
      } else {
        const dx = b.x - a.x, dy = b.y - a.y;
        const L  = Math.hypot(dx, dy) || 1;
        const ux = dx / L, uy = dy / L;
        const cut = (n, pad) => {
          const hw = n.text.length * n.fs * 0.5, hh = n.fs * 0.66;
          const tx = Math.abs(ux) < 1e-4 ? Infinity : hw / Math.abs(ux);
          const ty = Math.abs(uy) < 1e-4 ? Infinity : hh / Math.abs(uy);
          return Math.min(tx, ty) + pad;
        };
        const ka = cut(a, 16), kb = cut(b, 14);
        p.moveTo((a.x + ux * ka) * scale, (a.y + uy * ka) * scale);
        p.lineTo((b.x - ux * kb) * scale, (b.y - uy * kb) * scale);
        len = Math.max(1, (L - ka - kb) * scale);
      }

      return { p, len, delay: e.delay };
    });
  }

  let rp = 0;
  let running = false;
  let t0 = 0;

  const easeOut = (x) => 1 - Math.pow(1 - x, 3);
  const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);

  function draw(now) {
    const t = (now - t0) / 1000;
    ctx.clearRect(0, 0, cssW, cssH);

    ctx.strokeStyle = COLORS.edge;
    ctx.lineWidth = 1;
    edgePaths.forEach((e) => {
      const p = clamp01((rp - e.delay) / 0.5);
      if (p <= 0) return;
      ctx.setLineDash([e.len * easeOut(p), 1e6]);
      ctx.stroke(e.p);
    });
    ctx.setLineDash([]);

    const BUCKETS = 6;
    for (const kind of ['leaf', 'branch', 'center']) {
      ctx.fillStyle = COLORS[kind];
      const paths = Array.from({ length: BUCKETS }, () => new Path2D());

      for (let i = 0; i < dots.length; i++) {
        const d = dots[i];
        if (d.kind !== kind) continue;

        const e = easeOut(clamp01((rp - d.d) / (1 - d.d)));
        if (e <= 0) continue;

        let a = d.a * e;
        if (d.fl && rp >= 1) {
          const v = 0.5 + 0.5 * Math.sin(t * d.fl + d.ph);
          a *= 0.34 + 0.66 * v * v;
        }
        if (a < 0.04) continue;

        const x = d.hx + (d.sx - d.hx) * (1 - e);
        const y = d.hy + (d.sy - d.hy) * (1 - e);
        const b = Math.min(BUCKETS - 1, (a * BUCKETS) | 0);
        paths[b].rect(x - d.s / 2, y - d.s / 2, d.s, d.s);
      }

      paths.forEach((p, b) => {
        ctx.globalAlpha = (b + 1) / BUCKETS;
        ctx.fill(p);
      });
    }
    ctx.globalAlpha = 1;

    if (rp < 1) rp = Math.min(1, rp + 0.011);
    if (running) requestAnimationFrame(draw);
  }

  function start() {
    if (running) return;
    running = true;
    rp = still.matches ? 1 : 0;
    t0 = performance.now();
    requestAnimationFrame(draw);
  }

  const measurer = document.createElement('canvas').getContext('2d');

  function measureW(text, fs) {
    measurer.font = FONT.replace('%d', Math.round(fs));
    const tracking = fs * 0.1;
    let w = 0;
    for (const c of text) w += measurer.measureText(c).width + tracking;
    return w;
  }

  function branchBox(i) {
    const b = branchNodes[i];
    const fs = b.baseRendered * (i === hovered ? HOVER : 1);
    const w = measureW(b.n.text, fs);
    const h = fs * 1.3;
    return {
      x0: b.sx - w / 2 - 10, x1: b.sx + w / 2 + 10,
      y0: b.sy - h / 2 - 10, y1: b.sy + h / 2 + 10,
    };
  }

  function branchAt(mx, my) {
    for (let i = 0; i < branchNodes.length; i++) {
      const b = branchBox(i);
      if (mx >= b.x0 && mx <= b.x1 && my >= b.y0 && my <= b.y1) return i;
    }
    return -1;
  }

  function rebuildBranch(b, factor) {
    const bn = branchNodes[b];
    if (!bn) return;

    const others = dots.filter((d) => d.branch !== b);
    const added  = [];
    const leaves = layoutNodes.filter((n) => n.kind === 'leaf' && n.branch === b);

    wordDots(bn.n.text, bn.baseRendered * factor, bn.sx, bn.sy, added, 'branch', 0, b);

    leaves.forEach((l) => {
      const fs = l.fs * scale * factor;
      const hw = l.text.length * fs * 0.55;
      const hh = fs * 0.75;
      const cx = clamp(l.x * scale, hw, cssW - hw);
      const cy = clamp(l.y * scale, hh, cssH - hh);
      wordDots(l.text, fs, cx, cy, added, 'leaf', 0, b);
    });

    dots = others.concat(added);
  }

  function setHover(b) {
    if (b === hovered) return;
    if (hovered >= 0) rebuildBranch(hovered, 1);
    hovered = b;
    if (b >= 0) rebuildBranch(b, HOVER);
    canvas.style.cursor = b >= 0 ? 'pointer' : '';
  }

  canvas.addEventListener('pointermove', (e) => {
    const r = canvas.getBoundingClientRect();
    setHover(branchAt(e.clientX - r.left, e.clientY - r.top));
  });
  canvas.addEventListener('pointerleave', () => setHover(-1));
  canvas.addEventListener('click', (e) => {
    const r = canvas.getBoundingClientRect();
    const b = branchAt(e.clientX - r.left, e.clientY - r.top);
    if (b >= 0) location.href = BRANCH_LINKS[b];
  });

  build();
  ctx.clearRect(0, 0, cssW, cssH);
  if (document.fonts) document.fonts.ready.then(() => { if (!running) build(); });

  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => { if (e.isIntersecting) { start(); io.disconnect(); } });
    }, { threshold: 0.15 });
    io.observe(canvas);
  } else {
    start();
  }

  let tm = 0;
  addEventListener('resize', () => {
    clearTimeout(tm);
    tm = setTimeout(() => { const was = rp; build(); rp = was; }, 180);
  });
})();
