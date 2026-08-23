(() => {
  const canvas = document.getElementById('dots');
  if (!canvas || !canvas.getContext) return;

  const ctx  = canvas.getContext('2d', { alpha: true });
  const TEXT = canvas.dataset.text || '茎崎';
  const FONT = '600 %dpx "Hiragino Mincho ProN", "Yu Mincho", "YuMincho", "Noto Serif JP", "Source Han Serif", serif';

  const still = matchMedia('(prefers-reduced-motion: reduce)');
  const fine  = matchMedia('(pointer: fine)');

  let dpr = 1, W = 0, H = 0;
  let px, py, hx, hy, vx, vy, sz, bucket;   
  let flk, phs;                             
  let n = 0;
  const BUCKETS = [0.38, 0.62, 0.82, 1];

  const pointer = { x: -1e4, y: -1e4, on: false };

  let intro = 0;
  let raf = 0;
  let fade = 0;
  let clock = 0;
  let lastT = 0;

  function build() {
    dpr = Math.min(devicePixelRatio || 1, 2);
    W = canvas.clientWidth;
    H = canvas.clientHeight;
    canvas.width  = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const portrait = H > W;

    const measure = (fs) => {
      const o = document.createElement('canvas').getContext('2d');
      o.font = FONT.replace('%d', Math.round(fs));
      const tracking = fs * 0.12;
      let w = 0;
      for (const c of TEXT) w += o.measureText(c).width + tracking;
      return w;
    };

    let fontSize = portrait ? Math.min(W * 0.40, H * 0.26)
                            : Math.min(W * 0.27, H * 0.38);
    const measuredW = measure(fontSize);
    if (measuredW > W * 0.92) fontSize *= (W * 0.92) / measuredW;
    const gap      = Math.max(2.6, fontSize / 68);

    const off  = document.createElement('canvas');
    const octx = off.getContext('2d', { willReadFrequently: true });

    const font = FONT.replace('%d', Math.round(fontSize));
    octx.font = font;

    const chars    = Array.from(TEXT);
    const tracking = fontSize * 0.12;
    const widths   = chars.map((c) => octx.measureText(c).width);
    const textW    = widths.reduce((a, b) => a + b, 0) + tracking * (chars.length - 1);

    const tw = Math.ceil(textW) + 8;
    const th = Math.ceil(fontSize * 1.24);

    off.width = tw; off.height = th;
    octx.font = font;
    octx.textAlign = 'left';
    octx.textBaseline = 'middle';
    octx.fillStyle = '#fff';

    let cx = (tw - textW) / 2;
    chars.forEach((c, i) => {
      octx.fillText(c, cx, th / 2);
      cx += widths[i] + tracking;
    });

    const data = octx.getImageData(0, 0, tw, th).data;

    const ox = (W - tw) / 2;
    const oy = (H - th) / 2 - H * 0.03;

    const HX = [], HY = [], SZ = [], BK = [], FL = [], PH = [];

    const alphaAt = (x, y) =>
      (x < 0 || y < 0 || x >= tw || y >= th) ? 0 : data[((y | 0) * tw + (x | 0)) * 4 + 3];

    const push = (x, y, size, b, fl, ph) => {
      HX.push(x); HY.push(y); SZ.push(size); BK.push(b); FL.push(fl); PH.push(ph);
    };

    for (let y = 0; y < th; y += gap) {
      for (let x = 0; x < tw; x += gap) {
        const a = alphaAt(x, y);
        if (a < 90) continue;

        const edge = alphaAt(x - gap, y) < 90 || alphaAt(x + gap, y) < 90 ||
                     alphaAt(x, y - gap) < 90 || alphaAt(x, y + gap) < 90;
  
        if (!edge && Math.random() > 0.52) continue;

        const size = gap * (Math.random() < (edge ? 0.3 : 0.14) ? 0.8 : 0.52);
        const b = edge ? 2 + (Math.random() * 2 | 0)
                       : (Math.random() * 3 | 0);
        const fl = Math.random() < 0.38 ? 0.6 + Math.random() * 1.3 : 0;
        const ph = Math.random() * Math.PI * 2;
        push(ox + x, oy + y, size, b, fl, ph);

        if (edge && Math.random() < 0.16) {
          const ang = Math.random() * Math.PI * 2;
          const r   = gap * (1.6 + Math.random() * 3.4);
          push(ox + x + Math.cos(ang) * r, oy + y + Math.sin(ang) * r, gap * 0.44, 0, 0, 0);
        }
      }
    }

    n  = HX.length;
    hx = Float32Array.from(HX);
    hy = Float32Array.from(HY);
    sz = Float32Array.from(SZ);
    bucket = Uint8Array.from(BK);
    flk = Float32Array.from(FL);
    phs = Float32Array.from(PH);
    px = new Float32Array(n);
    py = new Float32Array(n);
    vx = new Float32Array(n);
    vy = new Float32Array(n);

    for (let i = 0; i < n; i++) {
      const ang = Math.random() * Math.PI * 2;
      const r   = Math.max(W, H) * (0.35 + Math.random() * 0.5);
      px[i] = W / 2 + Math.cos(ang) * r;
      py[i] = H / 2 + Math.sin(ang) * r * 0.6;
    }
    intro = still.matches ? 1 : 0;
  }

  const R      = 130;
  const FORCE  = 46;
  const SPRING = 0.055;
  const DAMP   = 0.9;

  function frame(now) {
    ctx.clearRect(0, 0, W, H);

    const heroH = canvas.offsetHeight || window.innerHeight;
    fade = Math.max(0, Math.min(1, window.scrollY / (heroH * 0.55)));

    clock += (now - (lastT || now)) / 1000;
    lastT = now;

    if (intro < 1) intro = Math.min(1, intro + 0.012);
    const ease = 1 - Math.pow(1 - intro, 3);
    const show = 1 - fade;

    const mx = pointer.x, my = pointer.y, act = pointer.on && !still.matches;
    const R2 = R * R;

    const AB = 8;
    const paths = Array.from({ length: AB }, () => new Path2D());

    for (let i = 0; i < n; i++) {
      let x = px[i], y = py[i];

      if (intro < 1) {
        x = px[i] += (hx[i] - x) * (0.02 + 0.06 * ease);
        y = py[i] += (hy[i] - y) * (0.02 + 0.06 * ease);
      } else {
        if (act) {
          const dx = x - mx, dy = y - my;
          const d2 = dx * dx + dy * dy;
          if (d2 < R2 && d2 > 0.01) {
            const d = Math.sqrt(d2);
            const f = (1 - d / R) * FORCE / d;
            vx[i] += dx * f * 0.02;
            vy[i] += dy * f * 0.02;
          }
        }
        vx[i] = (vx[i] + (hx[i] - x) * SPRING) * DAMP;
        vy[i] = (vy[i] + (hy[i] - y) * SPRING) * DAMP;
        x = px[i] += vx[i];
        y = py[i] += vy[i];
      }

      let a = BUCKETS[bucket[i]] * show;
      const fl = flk[i];
      if (fl && intro >= 1) {
        const v = 0.5 + 0.5 * Math.sin(clock * fl + phs[i]);
        a *= 0.34 + 0.66 * v * v;
      }
      if (a < 0.04) continue;

      const bi = Math.min(AB - 1, (a * AB) | 0);
      const s = sz[i];
      paths[bi].rect(x - s / 2, y - s / 2, s, s);
    }

    ctx.fillStyle = '#eef3f8';
    paths.forEach((p, b) => {
      ctx.globalAlpha = (b + 1) / AB;
      ctx.fill(p);
    });
    ctx.globalAlpha = 1;
    raf = requestAnimationFrame(frame);
  }

  function onMove(e) {
    if (!fine.matches && e.pointerType === 'mouse') return;
    const r = canvas.getBoundingClientRect();
    pointer.x = e.clientX - r.left;
    pointer.y = e.clientY - r.top;
    pointer.on = true;
  }

  canvas.addEventListener('pointermove', onMove);
  canvas.addEventListener('pointerdown', onMove);
  canvas.addEventListener('pointerleave', () => { pointer.on = false; });

  let t = 0;
  addEventListener('resize', () => {
    clearTimeout(t);
    t = setTimeout(build, 180);
  });

  (document.fonts ? document.fonts.ready : Promise.resolve()).then(() => {
    build();
    if (!raf) raf = requestAnimationFrame(frame);
  });
})();
