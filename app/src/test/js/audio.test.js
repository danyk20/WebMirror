// Tests the pure audio scheduling math of the player. Run: node app/src/test/js/audio.test.js
// It extracts the text between AUDIO-MATH-BEGIN and AUDIO-MATH-END from player.js, so it tests the shipped code.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/web/player.js'), 'utf8');
const a = src.indexOf('// AUDIO-MATH-BEGIN');
const b = src.indexOf('// AUDIO-MATH-END');
assert(a > 0 && b > a, 'markers not found');
const lib = new Function(src.slice(a, b) +
  '; return { AUDIO_PROFILES, AUDIO_DRIFT_S, audioClockCreate, audioClockReset, audioClockSetProfile, audioSchedule, audioBufferedMs,' +
  ' audioVideoDueMs, audioVideoDue, audioPcmToFloat };')();
const L = lib;
const near = function (x, y, eps) { assert(Math.abs(x - y) <= (eps || 1e-9), x + ' vs ' + y); };

let n = 0;
function check(name, fn) { fn(); n++; }

// Plays `chunks` consecutive chunks of `dur` seconds, the context clock running at `rate` times the phone clock; returns the events.
function run(st, chunks, dur, startCtx, startPts, rate, jitter) {
  const events = [];
  let ctx = startCtx;
  for (let i = 0; i < chunks; i++) {
    const pts = startPts + i * dur;
    const r = L.audioSchedule(st, ctx, pts, dur);
    events.push(r.event);
    ctx += dur * rate;
    if (jitter) ctx += jitter(i);
  }
  return { events: events, ctx: ctx };
}

check('profiles: the two parameter sets', function () {
  const f = L.AUDIO_PROFILES.fast, q = L.AUDIO_PROFILES.quality;
  assert.deepStrictEqual([f.start, f.min, f.max, f.grow, f.quietS], [0.04, 0.03, 0.15, 0.01, 15]);
  assert.deepStrictEqual([q.start, q.min, q.max, q.grow, q.shrink, q.quietS], [0.10, 0.08, 0.40, 0.03, 0.01, 60]);
  assert.strictEqual(L.audioClockCreate('nonsense').profile, 'quality');
});

check('first chunk anchors: plays target seconds from now; later chunks are contiguous', function () {
  const st = L.audioClockCreate('quality');
  const r0 = L.audioSchedule(st, 5.0, 1000.0, 0.02);
  assert.strictEqual(r0.event, 'anchor');
  near(r0.when, 5.1);
  near(st.base, 5.0 + 0.10 - 1000.0);
  const r1 = L.audioSchedule(st, 5.02, 1000.02, 0.02);
  assert.strictEqual(r1.event, '');
  near(r1.when, 5.12);
  assert.strictEqual(r1.offset, 0);
  near(L.audioBufferedMs(st, 5.02), (5.14 - 5.02) * 1000, 1e-6);
});

check('underrun: grows by the profile step up to its max and re-anchors', function () {
  for (const name of ['fast', 'quality']) {
    const P = L.AUDIO_PROFILES[name];
    const st = L.audioClockCreate(name);
    L.audioSchedule(st, 10, 0, 0.02);
    // The next chunk arrives late: context time is already past its start.
    const r = L.audioSchedule(st, 10 + P.start + 0.05, 0.02, 0.02);
    assert.strictEqual(r.event, 'underrun');
    near(st.target, P.start + P.grow);
    assert.strictEqual(st.underruns, 1);
    near(r.when, 10 + P.start + 0.05 + st.target); // re-anchored: now + new target
    // Many underruns stay within max.
    let ctx = 20;
    for (let i = 0; i < 100; i++) { L.audioSchedule(st, ctx, i * 1.0, 0.02); ctx += 5; }
    near(st.target, P.max);
    assert(st.underruns > 5);
  }
});

check('fast grows by 0.01, quality by 0.03 per underrun', function () {
  const f = L.audioClockCreate('fast'), q = L.audioClockCreate('quality');
  [f, q].forEach(function (st) { L.audioSchedule(st, 1, 0, 0.01); L.audioSchedule(st, 3, 0.01, 0.01); });
  near(f.target, 0.05);
  near(q.target, 0.13);
});

check('shrink: after quietS without underrun by the profile step, never below min; quality needs 60 s, fast 15 s', function () {
  const q = L.audioClockCreate('quality'), f = L.audioClockCreate('fast');
  // Chunks of 20 ms for q; ctx keeps pace exactly.
  function drive(st, secs, dur) {
    let ctx = 100, pts = 0, shrinks = 0, firstShrinkAt = null;
    for (let t = 0; t < secs; t += dur) {
      const r = L.audioSchedule(st, ctx, pts, dur);
      if (r.event === 'shrink') { shrinks++; if (firstShrinkAt === null) firstShrinkAt = ctx - 100; }
      ctx += dur; pts += dur;
    }
    return { shrinks: shrinks, first: firstShrinkAt };
  }
  const rq = drive(q, 59, 0.02);
  assert.strictEqual(rq.shrinks, 0, 'quality must not shrink before 60 s');
  const rq2 = drive(L.audioClockCreate('quality'), 61, 0.02);
  assert.strictEqual(rq2.shrinks, 1);
  assert(rq2.first >= 60 - 1e-6 && rq2.first < 60.1);
  const rf = drive(f, 16, 0.01);
  assert.strictEqual(rf.shrinks, 1);
  assert(rf.first >= 15 - 1e-6 && rf.first < 15.1);
  // Long run: target settles at min.
  const q2 = L.audioClockCreate('quality'); drive(q2, 600, 0.02);
  near(q2.target, 0.08);
  const f2 = L.audioClockCreate('fast'); drive(f2, 200, 0.01);
  near(f2.target, 0.03);
});

check('an underrun restarts the quiet period', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 0, 0, 0.02);
  L.audioSchedule(st, 50, 1, 0.02);        // underrun at ctx 50 (chunk start long past)
  assert.strictEqual(st.underruns, 1);
  const r = L.audioSchedule(st, 50 + 59, 59 + 1.02, 0.02); // 59 s later, still in the quiet window
  assert.notStrictEqual(r.event, 'shrink');
});

check('drift: scheduled time running away from currentTime beyond target + 0.1 re-anchors', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 0, 0, 0.02);
  // The phone clock runs 1 ms/chunk faster than the context: the schedule creeps ahead.
  const res = run(st, 400, 0.02, 0.02, 0.02, 0.98, null);
  assert(res.events.indexOf('drift') >= 0, 'drift must be detected');
  assert.strictEqual(res.events.indexOf('underrun'), -1);
});

check('slow clock difference the other way ends in an underrun (not a crash), target grows', function () {
  const st = L.audioClockCreate('fast');
  L.audioSchedule(st, 0, 0, 0.01);
  const res = run(st, 600, 0.01, 0.01, 0.01, 1.01, null);
  assert(res.events.indexOf('underrun') >= 0);
  assert(st.target > 0.04);
});

check('pts jump forward (phone paused, then resumed) re-anchors without an underrun', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 1, 10, 0.02);
  const r = L.audioSchedule(st, 1.02, 50, 0.02);
  assert.strictEqual(r.event, 'drift');
  near(r.when, 1.02 + 0.10);
  assert.strictEqual(st.underruns, 0);
});

check('reset forgets the mapping but keeps the adapted target', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 0, 0, 0.02); L.audioSchedule(st, 9, 1, 0.02);
  const t = st.target;
  L.audioClockReset(st);
  assert.strictEqual(st.base, null);
  assert.strictEqual(st.target, t);
  assert.strictEqual(L.audioSchedule(st, 20, 500, 0.02).event, 'anchor');
});

check('switching profile live: start target of the new profile, new anchor', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 0, 0, 0.02);
  L.audioClockSetProfile(st, 'fast');
  assert.strictEqual(st.profile, 'fast');
  near(st.target, 0.04);
  assert.strictEqual(st.base, null);
  const r = L.audioSchedule(st, 3, 7, 0.01);
  near(r.when, 3.04);
  L.audioClockSetProfile(st, 'fast'); // same profile: nothing changes
  assert(st.base !== null);
});

check('a re-anchor that moves earlier plays only the new part (no overlap), and drops a chunk that is fully covered', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 0, 0, 0.02);       // when 0.10, lastEnd 0.12
  L.audioSchedule(st, 0.02, 0.02, 0.02); // when 0.12, lastEnd 0.14
  // Force a shrink-like re-anchor by hand: base 0.015 earlier.
  st.base -= 0.015;
  const r = L.audioSchedule(st, 0.04, 0.04, 0.02); // nominal when = 0.145 - ... 
  assert(r.when >= 0.14 - 1e-9);
  const st2 = L.audioClockCreate('quality');
  L.audioSchedule(st2, 0, 0, 0.02);
  st2.base -= 0.05;                       // everything shifts 50 ms earlier than what is scheduled
  const r2 = L.audioSchedule(st2, 0.0, 0.02, 0.02);
  assert.strictEqual(r2.skip, true);
});

check('video due time follows the audio base, trim and latency', function () {
  const st = L.audioClockCreate('quality');
  L.audioSchedule(st, 5.0, 1000.0, 0.02);      // base = 5.1 - 1000
  // getOutputTimestamp style: context time 5.0 is being heard at perf 2000 ms; latency 0.
  let clk = { perfMs: 2000, ctxSec: 5.0, latencySec: 0 };
  // frame with the first chunk's pts: heard at ctx 5.1 -> 100 ms after perf 2000
  near(L.audioVideoDueMs(st, 1000000.0, 0, clk), 2100, 1e-6);
  // later pts: +40 ms
  near(L.audioVideoDueMs(st, 1000040.0, 0, clk), 2140, 1e-6);
  // trim +50 ms delays the picture, -50 shows it earlier
  near(L.audioVideoDueMs(st, 1000000.0, 50, clk), 2150, 1e-6);
  near(L.audioVideoDueMs(st, 1000000.0, -50, clk), 2050, 1e-6);
  // render-time style with an output latency: the sound scheduled for ctx 5.1 is heard at 5.1 + latency
  clk = { perfMs: 2000, ctxSec: 5.0, latencySec: 0.03 };
  near(L.audioVideoDueMs(st, 1000000.0, 0, clk), 2130, 1e-6);
});

check('audioVideoDue falls back to the normal due time without an anchor or when absurdly far ahead', function () {
  const st = L.audioClockCreate('quality');
  const clk = { perfMs: 1000, ctxSec: 5, latencySec: 0 };
  assert.strictEqual(L.audioVideoDue(st, 123, 0, clk, 777), 777);       // no base yet
  assert.strictEqual(L.audioVideoDue(null, 123, 0, clk, 777), 777);
  L.audioSchedule(st, 5, 1000, 0.02);
  assert.strictEqual(L.audioVideoDue(st, 1000000 + 60000, 0, clk, 777), 777); // 60 s ahead
  near(L.audioVideoDue(st, 1000000, 0, clk, 777), 1100, 1e-6);
});

check('pcm s16le -> float', function () {
  const bytes = new Uint8Array([0x00, 0x80, 0xff, 0x7f, 0x00, 0x00, 0x00, 0x40]); // L=-32768 R=32767 | L=0 R=16384
  const view = new DataView(bytes.buffer);
  const l = new Float32Array(2), r = new Float32Array(2);
  L.audioPcmToFloat(view, 0, 2, l, r);
  near(l[0], -1); near(r[0], 32767 / 32768); near(l[1], 0); near(r[1], 0.5);
});

console.log('audio.test.js: ' + n + ' checks passed');
