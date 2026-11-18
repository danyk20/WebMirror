// Tests the pure frame-rate math of the player (60 fps handling). Run: node app/src/test/js/fps.test.js
// It extracts the text between FPS-MATH-BEGIN and FPS-MATH-END from player.js, so it tests the shipped code.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/web/player.js'), 'utf8');
const a = src.indexOf('// FPS-MATH-BEGIN');
const b = src.indexOf('// FPS-MATH-END');
assert(a > 0 && b > a, 'markers not found');
const L = new Function(src.slice(a, b) + '; return { lateLimitMs, decodeQueueLimit, streamFpsText };')();

let n = 0;
function check(name, fn) { fn(); n++; }

check('late-skip limit keeps the 30 fps value and is not shorter at 60 fps', function () {
  assert.strictEqual(L.lateLimitMs(1000 / 15, 2), 2000 / 15);
  assert(Math.abs(L.lateLimitMs(1000 / 30, 2) - 66.667) < 0.01);
  assert.strictEqual(L.lateLimitMs(1000 / 60, 2), 60); // 33 ms would skip on normal jitter
  assert.strictEqual(L.lateLimitMs(1000 / 60, 0), Infinity);
});
check('decode queue limit: 3 up to 30 fps, 6 at 60 fps, 3 when the rate is unknown', function () {
  assert.strictEqual(L.decodeQueueLimit(15), 3);
  assert.strictEqual(L.decodeQueueLimit(30), 3);
  assert.strictEqual(L.decodeQueueLimit(60), 6);
  assert.strictEqual(L.decodeQueueLimit(0), 3);
  assert.strictEqual(L.decodeQueueLimit(undefined), 3);
});
check('overlay text shows the effective rate and the fallback', function () {
  assert.strictEqual(L.streamFpsText({ fps: 60 }), ' @60 fps');
  assert.strictEqual(L.streamFpsText({ fps: 30, fpsFallback: true }), ' @30 fps (fallback)');
  assert.strictEqual(L.streamFpsText({ fps: 30, fpsLimit: 'hot' }), ' @30 fps (phone hot)');
  assert.strictEqual(L.streamFpsText({ fps: 0 }), '');
  assert.strictEqual(L.streamFpsText(null), '');
});

console.log('fps.test.js: ' + n + ' checks passed');
