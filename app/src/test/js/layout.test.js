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
const lib = new Function(src.slice(a, b) + '; return { computeLayout, normalise, NEED_SIDE, NEED_BOTTOM, CTL_GAP, CTL_STACK_H, CTL_BUTTONS, CTL_ROW_H };')();
const { computeLayout, normalise, NEED_SIDE, NEED_BOTTOM, CTL_GAP, CTL_STACK_H, CTL_BUTTONS, CTL_ROW_H } = lib;
const NS = NEED_SIDE, NB = NEED_BOTTOM;
function lay(vw, vh, iw, ih, bar) { return computeLayout(vw, vh, iw, ih, bar, NS, NB); }

function overlaps(r1, r2) {
  return r1.x < r2.x + r2.w && r2.x < r1.x + r1.w && r1.y < r2.y + r2.h && r2.y < r1.y + r1.h;
}
function pic(L) { return { x: L.left, y: L.top, w: L.cw, h: L.ch }; }
function inside(r, vw, vh) { return r.x >= 0 && r.y >= 0 && r.x + r.w <= vw && r.y + r.h <= vh; }

let n = 0;
function check(name, fn) { fn(); n++; }

function centred(L, vw, vh) { return Math.abs(L.left - (vw - L.cw - L.left)) <= 1 && Math.abs(L.top - (vh - L.ch - L.top)) <= 1; }

check('portrait stream in a landscape window: same picture as without controls, right bar', function () {
  const L = lay(1280, 800, 576, 1280, true), M = lay(1280, 800, 576, 1280, false);
  assert.deepStrictEqual(pic(L), pic(M));
  assert.strictEqual(L.bar.side, 'right');
  assert(!overlaps(pic(L), L.bar));
  assert(L.bar.x - (L.left + L.cw) === CTL_GAP);
});
check('landscape stream with a big bottom bar -> bottom bar, picture untouched', function () {
  const L = lay(1000, 1000, 1280, 576, true), M = lay(1000, 1000, 1280, 576, false);
  assert.deepStrictEqual(pic(L), pic(M));
  assert.strictEqual(L.bar.side, 'bottom');
});
check('tight: picture fills the window, shrinks symmetrically by the smallest amount', function () {
  const L = lay(1000, 1000, 500, 500, true);
  assert(centred(L, 1000, 1000));
  assert(L.cw < 1000);
  assert(!overlaps(pic(L), L.bar));
  const room = L.bar.side === 'right' ? L.left : L.top;
  assert(room >= (L.bar.side === 'right' ? NS : NB));
  assert(room < (L.bar.side === 'right' ? NS : NB) + 2, 'shrunk more than needed');
});
check('without controls the picture fills the window as before', function () {
  const L = lay(1280, 800, 576, 1280, false);
  assert.strictEqual(L.bar, null);
  assert.strictEqual(L.ch, 800); assert.strictEqual(L.cw, 360); assert.strictEqual(L.left, 460);
});

// The right bar stacks Control, Sound and Keyboard (3 buttons) plus note, fingers and menu: it must fit the shortest window.
check('the right bar fits the full control stack in the shortest window', function () {
  assert.strictEqual(CTL_BUTTONS, 3);
  assert(CTL_STACK_H >= CTL_BUTTONS * CTL_ROW_H, 'stack smaller than its buttons');
  assert(CTL_STACK_H <= 300, 'stack taller than the shortest window: ' + CTL_STACK_H);
  const L = lay(1280, 300, 576, 1280, true);
  assert(L.bar.h >= CTL_STACK_H);
});
check('adding the Keyboard button leaves the picture untouched', function () {
  // the bar thickness (NEED_SIDE / NEED_BOTTOM) does not depend on the number of buttons, so the picture is the same
  const L = lay(1280, 800, 576, 1280, true), M = lay(1280, 800, 576, 1280, false);
  assert.deepStrictEqual(pic(L), pic(M));
  assert(L.bar.w >= NS - CTL_GAP);
});

// Random sweep.
let seed = 12345;
function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
let shrunk = 0, unshrunk = 0;
for (let i = 0; i < 30000; i++) {
  const vw = 300 + Math.floor(rnd() * 2200);
  const vh = 300 + Math.floor(rnd() * 1500);
  const iw = 16 + Math.floor(rnd() * 2400);
  const ih = 16 + Math.floor(rnd() * 2400);
  const key = JSON.stringify([vw, vh, iw, ih]);
  const L = lay(vw, vh, iw, ih, true);
  const M = lay(vw, vh, iw, ih, false);
  const p = pic(L);
  assert(inside(p, vw, vh), 'picture outside window ' + key);
  assert(inside(L.bar, vw, vh), 'bar outside window ' + key);
  assert(!overlaps(p, L.bar), 'overlap ' + key);
  assert(centred(L, vw, vh), 'not centred ' + key);
  assert(Math.abs(L.cw * ih - L.ch * iw) <= iw + ih + 1, 'aspect ' + key);
  assert.strictEqual(M.bar, null);
  const need = L.bar.side === 'right' ? NS : NB;
  const have = L.bar.side === 'right' ? L.left : L.top; // black area on one side of the picture
  assert(have >= need - 1, 'bar too thin ' + have + ' ' + key);
  const sc = Math.min(vw / iw, vh / ih);
  const fitsAlready = (vw - iw * sc) / 2 >= NS || (vh - ih * sc) / 2 >= NB;
  if (fitsAlready) {
    // bars big enough: the picture is exactly the one without controls (within 1 px)
    assert(Math.abs(L.cw - M.cw) <= 1 && Math.abs(L.ch - M.ch) <= 1 && Math.abs(L.left - M.left) <= 1 && Math.abs(L.top - M.top) <= 1, 'moved ' + key);
    unshrunk++;
  } else {
    assert(L.cw <= M.cw && L.ch <= M.ch, 'grew ' + key);
    // smallest amount: one more pixel of picture would leave the bar too thin
    const lim = Math.max(have - need, 0);
    assert(lim <= 2.5, 'shrunk more than needed ' + lim + ' ' + key);
    shrunk++;
  }
  n++;
}
assert(shrunk > 100 && unshrunk > 100, 'sweep covers both cases');

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
