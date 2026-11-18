// Tests the pure layout / coordinate math of the player. Run: node app/src/test/js/layout.test.js
// It extracts the text between LAYOUT-MATH-BEGIN and LAYOUT-MATH-END from player.js, so it tests the shipped code.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/web/player.js'), 'utf8');
const a = src.indexOf('// LAYOUT-MATH-BEGIN');
const b = src.indexOf('// LAYOUT-MATH-END');
assert(a > 0 && b > a, 'markers not found');
const lib = new Function(src.slice(a, b) + '; return { computeLayout, normalise, MIN_BAR };')();
const { computeLayout, normalise, MIN_BAR } = lib;

function overlaps(r1, r2) {
  return r1.x < r2.x + r2.w && r2.x < r1.x + r1.w && r1.y < r2.y + r2.h && r2.y < r1.y + r1.h;
}
function pic(L) { return { x: L.left, y: L.top, w: L.cw, h: L.ch }; }
function inside(r, vw, vh) { return r.x >= 0 && r.y >= 0 && r.x + r.w <= vw && r.y + r.h <= vh; }

let n = 0;
function check(name, fn) { fn(); n++; }

// Tesla-like window (1280x800 CSS px): portrait phone stream -> side bars, big enough already.
check('portrait stream in a landscape window', function () {
  const L = computeLayout(1280, 800, 576, 1280, true, MIN_BAR);
  assert.strictEqual(L.ch, 800);
  assert.strictEqual(L.bar.side, 'right');
  assert(L.bar.w >= MIN_BAR);
  assert(!overlaps(pic(L), L.bar));
});
check('landscape stream in a landscape window -> bottom bar', function () {
  const L = computeLayout(1280, 800, 1280, 576, true, MIN_BAR);
  assert.strictEqual(L.bar.side, 'bottom');
  assert(L.bar.h >= MIN_BAR);
  assert(!overlaps(pic(L), L.bar));
});
check('picture fits exactly: it shrinks so a 64 px bar exists', function () {
  const L = computeLayout(1000, 1000, 500, 500, true, MIN_BAR);
  assert(L.bar.w >= MIN_BAR, 'bar ' + L.bar.w);
  assert(!overlaps(pic(L), L.bar));
  assert(L.cw <= 1000 - 2 * MIN_BAR);
});
check('thin bar on the larger side shrinks the picture just enough', function () {
  // window 1000x800, picture 4:3-ish 1000x750 -> by = 25 (larger than bx = 0) -> bottom bar must grow to 64
  const L = computeLayout(1000, 800, 1000, 750, true, MIN_BAR);
  assert.strictEqual(L.bar.side, 'bottom');
  assert(L.bar.h >= MIN_BAR);
  assert(L.ch <= 800 - 2 * MIN_BAR);
  assert(!overlaps(pic(L), L.bar));
});
check('without a bar the picture fills the window as before', function () {
  const L = computeLayout(1280, 800, 576, 1280, false, MIN_BAR);
  assert.strictEqual(L.bar, null);
  assert.strictEqual(L.ch, 800);
  assert.strictEqual(L.cw, 360);
  assert.strictEqual(L.left, 460);
});
check('portrait window (rotated browser) with a portrait stream', function () {
  const L = computeLayout(800, 1280, 576, 1280, true, MIN_BAR);
  assert(L.bar.w >= MIN_BAR || L.bar.h >= MIN_BAR);
  assert(!overlaps(pic(L), L.bar));
  assert(inside(L.bar, 800, 1280));
});

// Random sweep: the bar never overlaps the picture, is at least MIN_BAR on its short side, stays in the window, and the
// picture keeps its aspect ratio (within rounding) and stays inside the window.
let seed = 12345;
function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
for (let i = 0; i < 20000; i++) {
  const vw = 300 + Math.floor(rnd() * 2200);
  const vh = 300 + Math.floor(rnd() * 1500);
  const iw = 16 + Math.floor(rnd() * 2400);
  const ih = 16 + Math.floor(rnd() * 2400);
  const L = computeLayout(vw, vh, iw, ih, true, MIN_BAR);
  const p = pic(L);
  assert(inside(p, vw, vh), 'picture outside window ' + [vw, vh, iw, ih]);
  assert(inside(L.bar, vw, vh), 'bar outside window');
  assert(!overlaps(p, L.bar), 'overlap ' + [vw, vh, iw, ih] + ' ' + JSON.stringify(L));
  const short = L.bar.side === 'right' ? L.bar.w : L.bar.h;
  assert(short >= MIN_BAR, 'bar too thin ' + short + ' for ' + [vw, vh, iw, ih]);
  assert(Math.abs(L.cw * ih - L.ch * iw) <= iw + ih + 1, 'aspect ' + [vw, vh, iw, ih] + ' ' + JSON.stringify(L));
  const M = computeLayout(vw, vh, iw, ih, false, MIN_BAR);
  assert.strictEqual(M.bar, null);
  n++;
}

// Normalisation
check('normalise maps and clamps', function () {
  const r = { left: 100, top: 50, width: 200, height: 400 };
  assert.deepStrictEqual(normalise(100, 50, r), { x: 0, y: 0 });
  assert.deepStrictEqual(normalise(300, 450, r), { x: 1, y: 1 });
  assert.deepStrictEqual(normalise(200, 250, r), { x: 0.5, y: 0.5 });
  assert.deepStrictEqual(normalise(-50, 9999, r), { x: 0, y: 1 });
  assert.deepStrictEqual(normalise(5, 5, { left: 0, top: 0, width: 0, height: 0 }), { x: 0, y: 0 });
});

console.log('layout tests passed: ' + n + ' checks');
