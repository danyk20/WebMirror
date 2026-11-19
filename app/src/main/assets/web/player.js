// WebMirror player. Plain script, no modules; no ?. , ??, or BigInt (old Chromium in the car).
// Protocol: docs/development/PLAN.md section 2.1. Decoders are chosen per mode (hello.mode): "jpeg", "h264", "hevc", "vp9" and "av1" (the video modes share packet type 1 and one decoder implementation).
(function () {
  'use strict';

  // ---- constants ----
  const DEFAULT_BUFFER_MS = 20;    // network jitter buffer (plan 2.4); the phone's hello.cfg.jitterBufferMs overrides it
  const BASE_WINDOW_MS = 2000;     // clock mapping = minimum over the last 2 s (re-adapts when the network speeds up)
  const MAX_QUEUE = 200;           // hard cap of waiting packets
  const DEFAULT_LATE_SKIP = 2;     // a frame later than this many frame intervals is skipped (hello.cfg.skipLateFrames; 0 = never)
  const VIEW_DEBOUNCE_MS = 300;    // window resizes are reported to the phone after this quiet time
  const SKIP_HOLDOFF_MS = 1000;    // after a skip, do not skip again for this long (no skip spiral)
  const KF_MIN_INTERVAL_MS = 500;  // at most one keyframe request per this time
  const DEAD_MS = 3000;            // nothing received (not even the server ping) for this long = dead link
  const WATCHDOG_MS = 500;
  const STATS_INTERVAL_MS = 2000;  // also the keep-alive (server drops viewers silent for 10 s)
  const RECONNECT_MS = 1000;
  const MAX_PUMP_MS = 50;          // longest wait between two pump runs
  const PAUSED_TEXT = 'Paused on the phone. Open WebMirror and tap Start.';
  const CONNECT_TIMEOUT_MS = 5000;
  const DELAY_KEY = 'webmirror.delayMs';
  const HEADER = 12;
  const TYPE_H264 = 1;
  const TYPE_JPEG = 2;
  const TYPE_AUDIO = 3;
  const FLAG_AUDIO_OPUS = 2;       // audio packet flag: set = Opus, clear = PCM s16le interleaved 48 kHz stereo
  const SOUND_KEY = 'webmirror.sound';          // '1' = the user turned sound on in this browser
  const MUTED_KEY = 'webmirror.soundMuted';     // '1' = muted
  const TRIM_KEY = 'webmirror.audioTrimMs';     // fine trim of the picture against the sound (browser audio), -300..300
  const TRIM_MAX = 300;
  const AUDIO_STALE_MS = 600;      // no audio packet for this long: the picture stops following the sound
  // Representative codec strings for the capability check (hello.codecs), checked at 1280x720.
  const PROBE_CODECS = {
    h264: 'avc1.42E01F',
    hevc: 'hev1.1.6.L93.B0',
    vp9: 'vp09.00.31.08',
    av1: 'av01.0.08M.08'
  };
  const RAF_STALL_MS = 100;        // vsync pacing: no animation frame for this long = rAF is throttled, draw at once
  const E2E_RAW_MAX = 150;         // most per-frame e2e values sent with one stats message (the phone's automatic test aggregates them)
  const E2E_KEEP = 600;            // most e2e values kept between two stats messages

  // ---- elements ----
  const canvas = document.getElementById('screen');
  const ctx = canvas.getContext('2d');
  const messageEl = document.getElementById('message');
  const overlayEl = document.getElementById('overlay');
  const statusEl = document.getElementById('status');
  const statsEl = document.getElementById('stats');
  const delayEl = document.getElementById('delay');
  const delayValueEl = document.getElementById('delayValue');
  const hintEl = document.getElementById('hint');
  const fullscreenBtn = document.getElementById('fullscreen');
  const noticeEl = document.getElementById('notice');
  const barEl = document.getElementById('bar');
  const ctlToggleEl = document.getElementById('ctlToggle');
  const ctlLabelEl = document.getElementById('ctlLabel');
  const ctlNoteEl = document.getElementById('ctlNote');
  const fingersEl = document.getElementById('fingers');
  const menuBtnEl = document.getElementById('menuBtn');
  const soundBtnEl = document.getElementById('soundBtn');
  const kbdBtnEl = document.getElementById('kbdBtn');
  const rotBtnEl = document.getElementById('rotBtn');
  const soundLabelEl = document.getElementById('soundLabel');
  const pairEl = document.getElementById('pair');
  const pairMsgEl = document.getElementById('pairMsg');
  const pairPinEl = document.getElementById('pairPin');
  const pairPadEl = document.getElementById('pairPad');
  const pairOkEl = document.getElementById('pairOk');

  // ---- state ----
  let ws = null;
  let connectTimer = null;
  let reconnectTimer = null;
  let hello = null;               // latest hello from the phone
  let decoder = null;             // decoder object for hello.mode
  let queue = [];                 // waiting packets: { type, key, pts (ms), data }
  let baseSamples = [];           // recent { t: arrival time, v: arrival - pts }, ms
  let baseMs = null;
  let bufferMs = DEFAULT_BUFFER_MS;   // active jitter buffer, from hello.cfg
  let lateSkip = DEFAULT_LATE_SKIP;   // active late-skip threshold in frame intervals, from hello.cfg
  let cfgId = '';                     // short id of the phone's active configuration (hello.cfg.id)
  let cfgSummary = '';
  let pacing = 'immediate';           // 'immediate' or 'vsync', from hello.cfg.pacing
  let held = null;                    // vsync pacing: newest decoded frame not drawn yet { draw, close }
  let lastRafAt = 0;                  // when the last animation-frame callback ran
  let serverTarget = '';              // from the server's ping: current adaptive bitrate / quality target
  let viewTimer = null;
  let offsetMs = 0;
  let dropped = 0;
  let kfwait = 0;                 // frames discarded because of a deliberate reset (waiting for a keyframe, leftovers of a mode change): not congestion
  let serverKfwait = 0;           // from the server's ping: frames it refused or dropped because of a reset
  let framesDrawn = 0;
  let framesAtLastStats = 0;
  let lastStatsAt = 0;
  let fps = 0;
  let pumpTimer = null;
  let codecSupport = null;        // { h264: {sw, hw}, hevc: ..., vp9: ..., av1: ... } from the capability check
  let codecCheck = null;          // promise of that check, run once
  let paused = false;             // latest hello said mode "paused"
  let lastRxAt = 0;               // when anything was last received on the socket
  let lastKfAt = -100000;         // when a keyframe was last requested
  let skipHoldoffUntil = 0;
  let serverInFlight = 0;         // from the server's ping: frames sent to this viewer, not yet acknowledged
  let serverPending = 0;          // from the server's ping: frames the server holds back for this viewer
  // measured since the last stats message
  let lateSum = 0;
  let lateMax = 0;
  let lateCount = 0;
  let netLateSum = 0;
  let netLateCount = 0;
  let skipped = 0;                // frames skipped because they were late (or replaced before vsync drawing)
  let replaced = 0;               // part of skipped: decoded frames replaced by a newer one before they were drawn
  let decoderResets = 0;          // decoder re-created after an error or an overload (cumulative)
  let reconnects = 0;             // connections lost after having been open (cumulative)
  // Absolute delay (plan 2.1 ping.ts / ping.off): page clock minus phone clock in ms, from the phone's best round trip.
  let phoneOffsetMs = null;
  let e2eList = [];               // per drawn frame since the last stats: ms from the phone's capture time to drawing
  let rttSum = 0;
  let rttCount = 0;
  let noticeTimer = null;
  // Pairing (plan 2.1.4): the PIN panel is shown while the phone waits for the PIN; no ping arrives meanwhile.
  const TOKEN_KEY = 'webmirror.pairToken';
  let pinLength = 6;              // digits of the PIN; the phone sends the real length in every `pair` message
  const PAIR_PROMPT = 'Enter the PIN shown in the WebMirror app on the phone';
  let pairing = false;
  let pairDigits = '';
  let pairNote = '';              // 'Wrong PIN' or '' (the prompt)
  let pairLockUntil = 0;          // performance.now() when the lockout ends, 0 = not locked
  let pairTimer = null;
  // Remote control (plan "Remote control"): allowed/ready come from hello.control; on = this page owns the control.
  const ctl = { allowed: false, ready: false, ownerPresent: false, on: false, pending: false, autoAsked: false };
  // Tesla keyboard state (plan 2.1.5, see the section further down); declared here because updateBar() reads it.
  const kbd = { tesla: false, show: false, type: 'text', action: 'enter', multiline: false, composing: false, field: null, spec: '', noticed: false };
  const CTL_OPTOUT_KEY = 'webmirror.controlOptOut'; // set when the user turned control off in this browser: no automatic request
  let ctlNoteTimer = null;

  // Browser audio (plan 2.4.1). want/muted = the user's choice (remembered); ctx = the AudioContext; fmt = hello.audio while the
  // phone offers browser sound and this page can play it; clock = the pure scheduling state (AUDIO-MATH section).
  const audioSupported = !!(window.AudioContext || window.webkitAudioContext);
  const snd = {
    want: false, muted: false, ctx: null, gain: null, fmt: null, available: false, clock: null, decoder: null, decoderErrors: 0,
    sentOn: false, lastPktAt: 0, packets: 0, clk: null, trimMs: 0, shown: false, resumeNote: ''
  };
  let audioCheck = null;          // promise of { opus } (null when the page cannot play audio)

  // ---- small helpers ----
  function setStatus(text) {
    statusEl.textContent = text;
  }

  function showMessage(text) {
    if (text) {
      messageEl.textContent = text;
      messageEl.hidden = false;
    } else {
      messageEl.hidden = true;
    }
  }

  function send(obj) {
    if (ws && ws.readyState === 1) {
      try { ws.send(JSON.stringify(obj)); } catch (e) { /* closing */ }
    }
  }

  // The server holds back video when too many frames are unacknowledged, so every frame the page is done
  // with (drawn or discarded) must be acknowledged; the ack is cumulative (all frames up to this pts).
  function ack(rawPts) {
    send({ t: 'ack', pts: rawPts });
  }

  function discard(p) {
    dropped++;
    ack(p.raw);
  }

  function askKeyframe() {
    const now = performance.now();
    if (now - lastKfAt < KF_MIN_INTERVAL_MS) return;
    lastKfAt = now;
    send({ t: 'kf' });
  }

  // Lateness of a frame that reaches the screen: how much later than its due time (best recent network
  // path + buffer + user offset) it is drawn. Measured from the phone's pts, so a backlog inside the
  // browser (decoder, throttled timers) shows up, not only network delay.
  // Every decoded picture goes through here. Immediate pacing draws it at once. Vsync pacing keeps only the
  // newest one and the animation-frame loop draws it once per screen refresh; a picture replaced before it
  // was drawn counts as skipped (the newer one's ack covers it). If rAF is throttled (hidden pane), draw at once.
  function present(draw, close) {
    if (pacing !== 'vsync' || performance.now() - lastRafAt > RAF_STALL_MS) {
      dropHeld();
      try { draw(); } finally { close(); }
      return;
    }
    if (held) {
      held.close();
      skipped++;
      replaced++; // counted in skipped too; the phone does not treat these as congestion
    }
    held = { draw: draw, close: close };
  }

  function drawHeld() {
    if (!held) return;
    const h = held;
    held = null;
    try { h.draw(); } finally { h.close(); }
  }

  function dropHeld() {
    if (!held) return;
    const h = held;
    held = null;
    h.close();
  }

  function noteDrawn(ptsMs) {
    if (phoneOffsetMs !== null) {
      const e2e = performance.now() - (ptsMs + phoneOffsetMs);
      e2eList.push(e2e);
      if (e2eList.length > E2E_KEEP) e2eList.shift();
    }
    if (baseMs === null) return;
    const late = performance.now() - dueTime({ pts: ptsMs });
    lateSum += late;
    lateCount++;
    if (late > lateMax) lateMax = late;
  }

  function loadDelay() {
    try {
      const v = parseInt(localStorage.getItem(DELAY_KEY), 10);
      if (v >= 0 && v <= 1500) return v;
    } catch (e) { /* storage blocked */ }
    return 0;
  }

  function saveDelay(v) {
    try { localStorage.setItem(DELAY_KEY, String(v)); } catch (e) { /* storage blocked */ }
  }

  function loadTrim() {
    try {
      const v = parseInt(localStorage.getItem(TRIM_KEY), 10);
      if (v >= -TRIM_MAX && v <= TRIM_MAX) return v;
    } catch (e) { /* storage blocked */ }
    return 0;
  }

  function saveTrim(v) {
    try { localStorage.setItem(TRIM_KEY, String(v)); } catch (e) { /* storage blocked */ }
  }

  // The slider is the Bluetooth delay (0..1500 ms) normally, and the fine trim (-300..300 ms) while the sound plays in this browser.
  function setDelay(v) {
    if (soundActive()) {
      snd.trimMs = Math.max(-TRIM_MAX, Math.min(TRIM_MAX, Math.round(v / 10) * 10));
      saveTrim(snd.trimMs);
    } else {
      offsetMs = Math.max(0, Math.min(1500, Math.round(v / 10) * 10));
      saveDelay(offsetMs);
    }
    syncDelayUi();
  }

  function syncDelayUi() {
    const a = soundActive();
    delayEl.min = a ? String(-TRIM_MAX) : '0';
    delayEl.max = a ? String(TRIM_MAX) : '1500';
    const v = a ? snd.trimMs : offsetMs;
    delayEl.value = String(v);
    delayValueEl.textContent = v + ' ms';
    updateHint();
  }

  // ---- layout: canvas fills the window keeping the aspect ratio ----
  // LAYOUT-MATH-BEGIN (pure; app/src/test/js/layout.test.js runs exactly this text under node)
  // Compact remote-control controls (CSS px, see style.css): the vertical stack (right bar) is CTL_W wide and CTL_STACK_H
  // tall, the horizontal row (bottom bar) is CTL_ROW_H tall. A bar must be thicker than the controls plus CTL_PAD.
  const CTL_W = 104;
  const CTL_ROW_H = 44;
  const CTL_PAD = 8;
  const CTL_GAP = 4;               // gap between the picture and the bar content
  // The right bar stacks, top to bottom: Control, Sound, Keyboard (3 buttons of CTL_ROW_H), a note (up to 3 lines), the finger
  // indicator and the 40 px menu button, 8 px apart. A window is never shorter than 300 px, so the stack always fits.
  const CTL_BUTTONS = 3;
  const CTL_STACK_H = CTL_BUTTONS * CTL_ROW_H + 40 + 40 + 14 + 5 * 8;
  const NEED_SIDE = CTL_W + CTL_PAD;
  const NEED_BOTTOM = CTL_ROW_H + CTL_PAD;
  // Picture rectangle (centred, aspect kept) inside a vw x vh window for an iw x ih picture. The picture is ALWAYS the
  // largest one that fits the window, centred, exactly as without remote control; the controls go into the black bar
  // (the right bar, or the bottom bar when that one is larger; a tie takes the right bar), centred in it, CTL_GAP away
  // from the picture. Only when neither bar is thick enough (needSide / needBottom) the picture shrinks, by the smallest
  // amount that makes one of them fit and symmetrically (so it stays centred and both bars grow equally).
  function computeLayout(vw, vh, iw, ih, withBar, needSide, needBottom) {
    let scale = Math.min(vw / iw, vh / ih);
    let side = '';
    if (withBar) {
      const bx = (vw - iw * scale) / 2;
      const by = (vh - ih * scale) / 2;
      const okSide = bx >= needSide;
      const okBottom = by >= needBottom;
      if (okSide && okBottom) side = bx >= by ? 'right' : 'bottom';
      else if (okSide) side = 'right';
      else if (okBottom) side = 'bottom';
      else {
        const sSide = Math.max((vw - 2 * needSide) / iw, 0.02);
        const sBottom = Math.max((vh - 2 * needBottom) / ih, 0.02);
        if (sSide >= sBottom) { side = 'right'; scale = Math.min(scale, sSide); }
        else { side = 'bottom'; scale = Math.min(scale, sBottom); }
      }
    }
    const cw = Math.max(1, Math.floor(iw * scale));
    const ch = Math.max(1, Math.floor(ih * scale));
    const left = Math.floor((vw - cw) / 2);
    const top = Math.floor((vh - ch) / 2);
    let bar = null;
    if (side === 'right') bar = { side: 'right', x: left + cw + CTL_GAP, y: 0, w: vw - (left + cw + CTL_GAP), h: vh };
    else if (side === 'bottom') bar = { side: 'bottom', x: 0, y: top + ch + CTL_GAP, w: vw, h: vh - (top + ch + CTL_GAP) };
    return { cw: cw, ch: ch, left: left, top: top, bar: bar };
  }

  // Position inside the picture rectangle r (left, top, width, height) as 0..1, clamped.
  function normalise(clientX, clientY, r) {
    const x = r.width > 0 ? (clientX - r.left) / r.width : 0;
    const y = r.height > 0 ? (clientY - r.top) / r.height : 0;
    return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
  }
  // LAYOUT-MATH-END

  function layout() {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const L = computeLayout(vw, vh, canvas.width, canvas.height, ctl.allowed || snd.available, NEED_SIDE, NEED_BOTTOM);
    canvas.style.width = L.cw + 'px';
    canvas.style.height = L.ch + 'px';
    canvas.style.left = L.left + 'px';
    canvas.style.top = L.top + 'px';
    if (L.bar) {
      barEl.style.left = L.bar.x + 'px';
      barEl.style.top = L.bar.y + 'px';
      barEl.style.width = L.bar.w + 'px';
      barEl.style.height = L.bar.h + 'px';
      barEl.className = L.bar.side === 'right' ? 'bar-v' : 'bar-h';
      barEl.hidden = false;
    } else {
      barEl.hidden = true;
    }
  }

  function setCanvasSize(w, h) {
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
      layout();
    }
  }

  // ---- jitter buffer (plan 2.4) ----
  function noteArrival(ptsMs) {
    const now = performance.now();
    const v = now - ptsMs;
    baseSamples.push({ t: now, v: v });
    while (baseSamples.length > 1 && now - baseSamples[0].t > BASE_WINDOW_MS) baseSamples.shift();
    let m = baseSamples[0].v;
    for (let i = 1; i < baseSamples.length; i++) {
      if (baseSamples[i].v < m) m = baseSamples[i].v;
    }
    baseMs = m;
    netLateSum += v - m; // network lateness of this packet against the best path of the last 2 s
    netLateCount++;
  }

  // While the sound plays in this browser the picture follows the sound (audio is the master clock, plan 2.4.1); otherwise the
  // jitter-buffer time above is used, unchanged.
  function dueTime(p) {
    if (audioMaster()) {
      const d = audioVideoDue(snd.clock, p.pts, snd.trimMs, audioClk(), p.pts + baseMs + bufferMs + offsetMs);
      return d;
    }
    return p.pts + baseMs + bufferMs + offsetMs;
  }

  function flushQueue() {
    dropHeld();
    if (queue.length > 0) ack(queue[queue.length - 1].raw);
    queue = [];
    baseSamples = [];
    baseMs = null;
  }


  // ---- browser audio: pure scheduling math ----
  // AUDIO-MATH-BEGIN (pure; app/src/test/js/audio.test.js runs exactly this text under node)
  // The two audio profiles of the phone's Settings > Audio (hello.audio.profile), page side: the playback buffer (seconds) and how it adapts.
  // start/min/max = target buffer; grow = added on an underrun; shrink = removed after quietS seconds without one. Keep in step with
  // AudioProfile.kt on the phone (the phone has the frame sizes and bit rates).
  const AUDIO_PROFILES = {
    fast:    { start: 0.04, min: 0.03, max: 0.15, grow: 0.01, shrink: 0.01, quietS: 15, drift: 0.05 },
    quality: { start: 0.10, min: 0.08, max: 0.40, grow: 0.03, shrink: 0.01, quietS: 60, drift: 0.08 }
  };
  const AUDIO_DRIFT_S = 0.1;       // default: scheduled time further ahead of currentTime than target + this (profile `drift`): re-anchor
  const AUDIO_MAX_VIDEO_AHEAD_MS = 1500; // a picture due later than this from audio timing is not trusted (normal timing is used)

  function audioProfileParams(name) {
    return AUDIO_PROFILES[name] || AUDIO_PROFILES.quality;
  }

  function audioClockCreate(profileName) {
    const p = audioProfileParams(profileName);
    return { profile: AUDIO_PROFILES[profileName] ? profileName : 'quality', p: p, target: p.start, base: null, lastEnd: 0,
             quietSince: 0, underruns: 0, reanchors: 0 };
  }

  // Forget the pts -> context time mapping (new stream, reconnect, tab shown again); the adapted target stays.
  function audioClockReset(st) {
    st.base = null;
    st.lastEnd = 0;
  }

  // Another profile: its start target, and a new anchor at the next chunk.
  function audioClockSetProfile(st, profileName) {
    const name = AUDIO_PROFILES[profileName] ? profileName : 'quality';
    if (name === st.profile) return;
    st.profile = name;
    st.p = AUDIO_PROFILES[name];
    st.target = st.p.start;
    audioClockReset(st);
  }

  // audioBase = ctx.currentTime + target - pts: the chunk with this pts plays target seconds from now.
  function audioAnchor(st, ctxNow, ptsSec) {
    st.base = ctxNow + st.target - ptsSec;
    st.reanchors++;
  }

  // Where to play the chunk with phone time ptsSec and length durSec, given the context time ctxNow.
  // Returns { when, offset, skip, event }: start the source at `when` (context seconds) at `offset` seconds into the chunk; skip = do not
  // play it (already covered). event = '' | 'anchor' | 'underrun' | 'drift' | 'shrink'.
  function audioSchedule(st, ctxNow, ptsSec, durSec) {
    let event = '';
    if (st.base === null) {
      audioAnchor(st, ctxNow, ptsSec);
      st.quietSince = ctxNow;
      event = 'anchor';
    } else {
      const w = ptsSec + st.base;
      if (w < ctxNow) {
        // Underrun: the chunk's start time is already past. A bigger buffer next time.
        st.underruns++;
        st.target = Math.min(st.p.max, st.target + st.p.grow);
        st.quietSince = ctxNow;
        audioAnchor(st, ctxNow, ptsSec);
        event = 'underrun';
      } else if (w - ctxNow > st.target + (st.p.drift || AUDIO_DRIFT_S)) {
        audioAnchor(st, ctxNow, ptsSec);
        event = 'drift';
      } else if (st.target > st.p.min && ctxNow - st.quietSince >= st.p.quietS) {
        st.target = Math.max(st.p.min, st.target - st.p.shrink);
        st.quietSince = ctxNow;
        audioAnchor(st, ctxNow, ptsSec);
        event = 'shrink';
      }
    }
    let when = ptsSec + st.base;
    let offset = 0;
    if (when < st.lastEnd) {          // overlaps what is already scheduled (after a re-anchor): play only the new part
      offset = st.lastEnd - when;
      when = st.lastEnd;
      if (offset < 0.0002) offset = 0;
    }
    const skip = offset >= durSec - 0.0005;
    if (!skip) st.lastEnd = when + (durSec - offset);
    return { when: when, offset: offset, skip: skip, event: event };
  }

  function audioBufferedMs(st, ctxNow) {
    return Math.max(0, (st.lastEnd - ctxNow) * 1000);
  }

  // Due time, in performance.now() milliseconds, of the picture with phone pts ptsMs while the sound is the master clock.
  // clk = { perfMs, ctxSec, latencySec }: the audio context time ctxSec that is being HEARD at perfMs (from getOutputTimestamp, latencySec 0),
  // or the render time at perfMs with the output latency to subtract. A chunk scheduled at context time T is heard at T + latency,
  // so the picture is due when the heard time reaches ptsMs/1000 + base (+ the user's trim).
  function audioVideoDueMs(st, ptsMs, trimMs, clk) {
    return clk.perfMs + ((ptsMs / 1000 + st.base + trimMs / 1000) - clk.ctxSec + clk.latencySec) * 1000;
  }

  // As above, but fallbackMs (the normal due time) when the sound timing is not usable (no anchor yet, or absurdly far ahead).
  function audioVideoDue(st, ptsMs, trimMs, clk, fallbackMs) {
    if (!st || st.base === null || !clk) return fallbackMs;
    const d = audioVideoDueMs(st, ptsMs, trimMs, clk);
    if (!(d - clk.perfMs < AUDIO_MAX_VIDEO_AHEAD_MS)) return fallbackMs;
    return d;
  }

  // 16-bit little-endian interleaved stereo (DataView view, byte offset off) -> two Float32Arrays of `frames` samples.
  function audioPcmToFloat(view, off, frames, left, right) {
    for (let i = 0; i < frames; i++) {
      left[i] = view.getInt16(off + i * 4, true) / 32768;
      right[i] = view.getInt16(off + i * 4 + 2, true) / 32768;
    }
  }
  // AUDIO-MATH-END

  // ---- browser audio: the player ----
  function helloAudio(h) {
    if (!h || h.audio === undefined || h.audio === null) return { mode: 'off' };
    return typeof h.audio === 'string' ? { mode: h.audio } : h.audio;
  }

  function loadSoundPrefs() {
    try {
      snd.want = localStorage.getItem(SOUND_KEY) === '1';
      snd.muted = localStorage.getItem(MUTED_KEY) === '1';
    } catch (e) { /* storage blocked */ }
    snd.trimMs = loadTrim();
  }

  function saveSoundPrefs() {
    try {
      localStorage.setItem(SOUND_KEY, snd.want ? '1' : '0');
      localStorage.setItem(MUTED_KEY, snd.muted ? '1' : '0');
    } catch (e) { /* storage blocked */ }
  }

  // Sound is playing in this browser (or about to): the user wants it, it is not muted, and the context runs.
  function soundActive() {
    return !!(snd.available && snd.want && !snd.muted && snd.ctx && snd.ctx.state === 'running');
  }

  // The picture follows the sound only while audio packets keep arriving.
  function audioMaster() {
    return !!(snd.clock && snd.clock.base !== null && soundActive() && performance.now() - snd.lastPktAt < AUDIO_STALE_MS);
  }

  function audioClk() {
    const c = snd.ctx;
    const pm = performance.now();
    if (snd.clk && pm - snd.clk.at < 50) return snd.clk;
    let clk = null;
    if (typeof c.getOutputTimestamp === 'function') {
      try {
        const t = c.getOutputTimestamp();
        if (t && t.performanceTime > 0 && t.contextTime > 0 && Math.abs(pm - t.performanceTime) < 2000) {
          clk = { perfMs: t.performanceTime, ctxSec: t.contextTime, latencySec: 0, at: pm };
        }
      } catch (e) { /* not available */ }
    }
    if (!clk) {
      const lat = (typeof c.outputLatency === 'number' && c.outputLatency > 0) ? c.outputLatency : (c.baseLatency || 0);
      clk = { perfMs: pm, ctxSec: c.currentTime, latencySec: lat, at: pm };
    }
    snd.clk = clk;
    return clk;
  }

  function ensureAudioContext() {
    if (snd.ctx) return snd.ctx;
    const Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return null;
    let c = null;
    try { c = new Ctor({ sampleRate: 48000, latencyHint: 'interactive' }); } catch (e) {
      try { c = new Ctor(); } catch (e2) { c = null; }
    }
    if (!c) return null;
    snd.ctx = c;
    snd.gain = c.createGain();
    snd.gain.gain.value = snd.muted ? 0 : 1;
    snd.gain.connect(c.destination);
    c.onstatechange = function () { snd.clk = null; onSoundStateChange(); };
    return c;
  }

  function resumeAudioContext() {
    const c = ensureAudioContext();
    if (!c) return;
    if (c.state !== 'running') {
      try {
        const r = c.resume();
        if (r && r.then) r.then(onSoundStateChange, function () { /* needs a user gesture */ });
      } catch (e) { /* needs a user gesture */ }
    }
    onSoundStateChange();
  }

  // Tell the phone whether this page wants sound (it captures only while somebody does), and refresh the button.
  function onSoundStateChange() {
    const on = soundActive();
    if (!on) { if (snd.clock) audioClockReset(snd.clock); flushAudioDecoder(); }
    if (on !== snd.sentOn && ws && ws.readyState === 1 && hello) {
      snd.sentOn = on;
      send({ t: 'audio', on: on });
    }
    updateSoundUi();
    schedulePump(0);
  }

  function updateSoundUi() {
    const running = !!(snd.ctx && snd.ctx.state === 'running');
    soundBtnEl.hidden = !snd.available;
    let label = 'Tap for sound';
    let cls = 'off';
    if (snd.available && snd.want && running) { label = snd.muted ? 'Muted' : 'Sound on'; cls = snd.muted ? 'muted' : 'on'; }
    soundLabelEl.textContent = label;
    soundBtnEl.className = cls;
    if (snd.shown !== snd.available) { snd.shown = snd.available; layout(); } // the bar appears or goes away
    syncDelayUi();
  }

  function enableSound() {
    snd.want = true;
    snd.muted = false;
    saveSoundPrefs();
    if (snd.gain) snd.gain.gain.value = 1;
    resumeAudioContext();
  }

  function toggleMute() {
    snd.muted = !snd.muted;
    saveSoundPrefs();
    if (snd.gain) snd.gain.gain.value = snd.muted ? 0 : 1;
    onSoundStateChange();
  }

  soundBtnEl.addEventListener('click', function () {
    const running = !!(snd.ctx && snd.ctx.state === 'running');
    if (snd.want && running) toggleMute();
    else enableSound();
  });

  // The remembered choice cannot start sound without a gesture: the next touch anywhere resumes it.
  function onGesture() {
    if (snd.available && snd.want && !snd.muted && !(snd.ctx && snd.ctx.state === 'running')) resumeAudioContext();
  }
  ['pointerdown', 'touchend', 'mousedown', 'keydown'].forEach(function (n) { document.addEventListener(n, onGesture, true); });

  // hello.audio: { mode, codec, sampleRate, channels, frameMs, profile }
  function applyAudioHello(m) {
    const a = helloAudio(m);
    const avail = !!(audioSupported && a.mode === 'browser' && a.codec);
    const prev = snd.fmt;
    snd.fmt = avail ? a : null;
    snd.available = avail;
    if (avail) {
      if (!snd.clock) snd.clock = audioClockCreate(a.profile);
      else audioClockSetProfile(snd.clock, a.profile);
      if (!prev || prev.codec !== a.codec || prev.frameMs !== a.frameMs) { flushAudioDecoder(); audioClockReset(snd.clock); }
    } else if (snd.clock) {
      audioClockReset(snd.clock);
      flushAudioDecoder();
    }
    onSoundStateChange();
  }

  function flushAudioDecoder() {
    const d = snd.decoder;
    snd.decoder = null;
    if (d) { try { d.close(); } catch (e) { /* already closed */ } }
  }

  function scheduleAudio(ptsSec, left, right, frames) {
    const c = snd.ctx;
    if (!c || !snd.clock) return;
    const r = audioSchedule(snd.clock, c.currentTime, ptsSec, frames / 48000);
    snd.lastPktAt = performance.now();
    if (r.event) schedulePump(0);       // the mapping changed: due times of waiting pictures changed
    if (r.skip) return;
    const buf = c.createBuffer(2, frames, 48000);
    buf.getChannelData(0).set(left);
    buf.getChannelData(1).set(right);
    const src = c.createBufferSource();
    src.buffer = buf;
    src.connect(snd.gain);
    try { src.start(r.when, r.offset); } catch (e) { /* out of range: drop this chunk */ }
  }

  function onAudioData(ad) {
    try {
      if (!soundActive()) return;
      const n = ad.numberOfFrames;
      const left = new Float32Array(n);
      let right = left;
      try {
        ad.copyTo(left, { planeIndex: 0, format: 'f32-planar' });
        if (ad.numberOfChannels > 1) { right = new Float32Array(n); ad.copyTo(right, { planeIndex: 1, format: 'f32-planar' }); }
      } catch (e) {
        // No format conversion: take the data as it is (interleaved f32).
        const all = new Float32Array(n * ad.numberOfChannels);
        ad.copyTo(all, { planeIndex: 0 });
        const ch = ad.numberOfChannels;
        right = ch > 1 ? new Float32Array(n) : left;
        for (let i = 0; i < n; i++) { left[i] = all[i * ch]; if (ch > 1) right[i] = all[i * ch + 1]; }
      }
      scheduleAudio(ad.timestamp / 1e6, left, right, n);
    } catch (e) {
      snd.decoderErrors++;
    } finally {
      try { ad.close(); } catch (e2) { /* ignore */ }
    }
  }

  function opusDecoder() {
    if (snd.decoder) return snd.decoder;
    const d = new window.AudioDecoder({
      output: onAudioData,
      error: function (e) {
        if (snd.decoder !== d) return;
        snd.decoderErrors++;
        if (window.console) console.log('audio decoder error', e);
        flushAudioDecoder();          // recreated by the next packet
      }
    });
    d.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 });
    snd.decoder = d;
    return d;
  }

  function onAudioPacket(buf, flags, ptsUs) {
    if (!soundActive() || !snd.clock) return;
    if (!snd.fmt) return;
    snd.packets++;
    if (phoneOffsetMs !== null) {
      const age = performance.now() - (ptsUs / 1000 + phoneOffsetMs);
      snd.ageSum = (snd.ageSum || 0) + age;
      snd.ageN = (snd.ageN || 0) + 1;
      snd.ageMin = Math.min(snd.ageMin === undefined ? 1e9 : snd.ageMin, age);
    }
    try {
      if ((flags & FLAG_AUDIO_OPUS) !== 0) {
        if (!window.AudioDecoder || !window.EncodedAudioChunk) return;
        const d = opusDecoder();
        if (d.decodeQueueSize > 20) return;   // the decoder is behind: drop, the clock re-anchors
        d.decode(new window.EncodedAudioChunk({ type: 'key', timestamp: ptsUs, duration: (snd.fmt.frameMs || 20) * 1000, data: new Uint8Array(buf, HEADER) }));
      } else {
        const frames = Math.floor((buf.byteLength - HEADER) / 4);
        if (frames <= 0) return;
        const left = new Float32Array(frames);
        const right = new Float32Array(frames);
        audioPcmToFloat(new DataView(buf), HEADER, frames, left, right);
        scheduleAudio(ptsUs / 1e6, left, right, frames);
      }
    } catch (e) {
      snd.decoderErrors++;
      flushAudioDecoder();
    }
  }

  // Page capability for the phone's format choice: Opus only if WebCodecs can decode it (secure pages only).
  function checkAudio() {
    if (audioCheck) return audioCheck;
    audioCheck = new Promise(function (resolve) {
      if (!audioSupported) { resolve(null); return; }
      try {
        if (typeof window.AudioDecoder === 'function' && typeof window.EncodedAudioChunk === 'function' &&
            typeof window.AudioData === 'function' && window.AudioDecoder.isConfigSupported) {
          window.AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 }).then(
            function (r) { resolve({ opus: !!(r && r.supported) }); },
            function () { resolve({ opus: false }); }
          );
          return;
        }
      } catch (e) { /* not supported */ }
      resolve({ opus: false });
    });
    return audioCheck;
  }

  // ---- decoders, one per mode ----
  const decoders = {};

  // JPEG: createImageBitmap(blob) -> drawImage. One decode at a time; only the newest pending packet is kept.
  decoders.jpeg = (function () {
    let busy = false;
    let pending = null;
    const haveBitmap = typeof window.createImageBitmap === 'function';

    function drawn(p, w, h, source) {
      setCanvasSize(w, h);
      ctx.drawImage(source, 0, 0);
      framesDrawn++;
      noteDrawn(p.pts);
      ack(p.raw);
      showMessage('');
    }

    function finish() {
      busy = false;
      if (pending) {
        const next = pending;
        pending = null;
        decode(next);
      }
    }

    function decode(p) {
      busy = true;
      const blob = new Blob([p.data], { type: 'image/jpeg' });
      if (haveBitmap) {
        window.createImageBitmap(blob).then(function (bmp) {
          present(function () {
            drawn(p, bmp.width, bmp.height, bmp);
          }, function () {
            if (bmp.close) bmp.close();
          });
          finish();
        }, function () {
          ack(p.raw);
          finish();
        });
      } else {
        const url = URL.createObjectURL(blob);
        const img = new Image();
        img.onload = function () {
          present(function () {
            drawn(p, img.naturalWidth, img.naturalHeight, img);
          }, function () {
            URL.revokeObjectURL(url);
          });
          finish();
        };
        img.onerror = function () {
          URL.revokeObjectURL(url);
          ack(p.raw);
          finish();
        };
        img.src = url;
      }
    }

    return {
      type: TYPE_JPEG,
      start: function () { pending = null; },
      reset: function () { pending = null; },
      droppable: true,
      isWaiting: function () { return false; },
      decodeQueue: function () { return pending ? 1 : 0; },
      inDecoder: function () { return (busy ? 1 : 0) + (pending ? 1 : 0); },
      // Called with one due packet at a time. While a decode runs only the newest waiting one is kept.
      submit: function (p) {
        if (busy) {
          if (pending) discard(pending);
          pending = p;
        } else {
          decode(p);
        }
      },
      resync: function () {}
    };
  })();

  // H.264, HEVC, VP9 and AV1: WebCodecs VideoDecoder -> canvas. No `description`: H.264/HEVC are Annex-B with the
  // parameter sets (SPS/PPS, HEVC VPS/SPS/PPS) inside keyframes; VP9 chunks are single frames; AV1 chunks are
  // temporal units with the sequence header OBU inside keyframes. The codec string comes from hello.codec.
  function makeVideoDecoder() {
    let dec = null;
    let codec = '';
    let size = '';
    let waitingForKey = true;
    let submitted = 0;
    let output = 0;

    function drawFrame(frame) {
      setCanvasSize(frame.displayWidth, frame.displayHeight);
      ctx.drawImage(frame, 0, 0);
      framesDrawn++;
      noteDrawn(frame.timestamp / 1000);
      ack(frame.timestamp);
      showMessage('');
    }

    function onFrame(frame) {
      output++;
      // present() always closes the frame, drawn or replaced; forgetting that leaks GPU memory and freezes the page.
      present(function () { drawFrame(frame); }, function () { frame.close(); });
    }

    function closeDecoder() {
      const old = dec;
      dec = null;
      if (old) {
        try { old.close(); } catch (e) { /* already closed */ }
      }
    }

    function create() {
      closeDecoder();
      waitingForKey = true;
      submitted = 0;
      output = 0;
      const mine = new VideoDecoder({
        output: onFrame,
        error: function (e) {
          if (dec !== mine) return;
          if (window.console) console.log('decoder error', e);
          recover();
        }
      });
      dec = mine;
      const cfg = { codec: codec, optimizeForLatency: true };
      // Ask for hardware only where the capability check found it (an unsupported preference could fail configure()).
      const fam = codecFamily(codec);
      if (fam && codecSupport && codecSupport[fam] && codecSupport[fam].hw) cfg.hardwareAcceleration = 'prefer-hardware';
      mine.configure(cfg);
    }

    function recover() {
      decoderResets++;
      try {
        create();
      } catch (e) {
        dec = null;
      }
      askKeyframe();
    }

    return {
      type: TYPE_H264,
      droppable: false,
      isWaiting: function () { return waitingForKey; },
      decodeQueue: function () { return dec ? dec.decodeQueueSize : 0; },
      inDecoder: function () { return Math.max(0, submitted - output); },
      start: function (h, resync) {
        const changed = !dec || h.codec !== codec || size !== h.width + 'x' + h.height;
        codec = h.codec;
        size = h.width + 'x' + h.height;
        if (changed) {
          try { create(); } catch (e) { dec = null; setStatus('Decoder failed'); }
        } else if (resync) {
          waitingForKey = true;
        }
      },
      reset: function () { closeDecoder(); },
      resync: function () { waitingForKey = true; },
      submit: function (p) {
        if (!dec) return;
        if (waitingForKey && !p.key) {
          kfwait++;
          ack(p.raw);
          return;
        }
        if (dec.decodeQueueSize > decodeQueueLimit(hello ? hello.fps : 0)) {
          // Overloaded (slow or throttled browser): throw away what the decoder still holds, so the
          // backlog is gone at once, and continue at the next keyframe.
          discard(p);
          skipped++;
          decoderResets++;
          try { create(); } catch (e) { dec = null; return; }
          askKeyframe();
          return;
        }
        if (p.key) waitingForKey = false;
        submitted++;
        try {
          dec.decode(new EncodedVideoChunk({
            type: p.key ? 'key' : 'delta',
            timestamp: Math.round(p.pts * 1000),
            data: p.data
          }));
        } catch (e) {
          if (window.console) console.log('decode failed', e);
          recover();
        }
      }
    };
  }
  decoders.h264 = makeVideoDecoder();
  decoders.hevc = makeVideoDecoder();
  decoders.vp9 = makeVideoDecoder();
  decoders.av1 = makeVideoDecoder();

  function codecFamily(codec) {
    if (codec.indexOf('avc1') === 0) return 'h264';
    if (codec.indexOf('hev1') === 0 || codec.indexOf('hvc1') === 0) return 'hevc';
    if (codec.indexOf('vp09') === 0) return 'vp9';
    if (codec.indexOf('av01') === 0) return 'av1';
    return '';
  }

  function chooseDecoder(h) {
    if (h.mode === 'paused') {
      // Capture ended on the phone: keep the decoder and the last picture, tell the viewer why.
      paused = true;
      setStatus('Paused');
      showMessage(PAUSED_TEXT);
      updateHint();
      updateStats();
      return;
    }
    const resumed = paused;
    paused = false;
    const next = decoders[h.mode];
    if (decoder && decoder !== next) decoder.reset();
    decoder = next || null;
    if (decoder) {
      decoder.start(h, resumed);
      setStatus('Connected');
      showMessage(framesDrawn > 0 ? '' : 'Waiting for the picture…');
    } else {
      setStatus('Unsupported mode: ' + h.mode);
      showMessage('This page cannot show mode "' + h.mode + '".');
    }
    updateHint();
    updateStats();
  }

  // A short message from the phone (e.g. the automatic test's progress and result), shown at the top for ttl seconds.
  function showNotice(text, ttlS) {
    if (noticeTimer !== null) { clearTimeout(noticeTimer); noticeTimer = null; }
    if (!text) { noticeEl.hidden = true; return; }
    noticeEl.textContent = text;
    noticeEl.hidden = false;
    noticeTimer = setTimeout(function () { noticeTimer = null; noticeEl.hidden = true; }, Math.max(1, ttlS) * 1000);
  }

  function updateHint() {
    const mode = helloAudio(hello).mode;
    if (mode === 'bluetooth') {
      hintEl.textContent = 'Adjust delay until lips match sound.';
      hintEl.hidden = false;
    } else if (soundActive()) {
      hintEl.textContent = 'Sound plays here, in sync. Use the slider only to fine-tune.';
      hintEl.hidden = false;
    } else {
      hintEl.hidden = true;
    }
  }

  // ---- pump: hand due packets to the decoder ----
  // Driven by setTimeout to the next packet's due time (0..MAX_PUMP_MS), because some browsers throttle
  // requestAnimationFrame to about 1 call/s when the page is not visible. rAF is only an extra trigger.
  function schedulePump(delay) {
    if (pumpTimer !== null) clearTimeout(pumpTimer);
    pumpTimer = setTimeout(pump, Math.max(0, Math.min(MAX_PUMP_MS, delay)));
  }

  function frameIntervalMs() {
    return hello && hello.fps > 0 ? 1000 / hello.fps : 33;
  }

  // FPS-MATH-BEGIN (pure, tested by app/src/test/js/fps.test.js)
  const MAX_DECODE_QUEUE = 3;      // VideoDecoder backlog that counts as overload at up to 30 fps (more at higher rates: see decodeQueueLimit)
  const LATE_SKIP_MIN_MS = 60;     // the late-skip limit (skipLateFrames intervals) is never shorter than this, so 60 fps does not skip on 33 ms of jitter

  // Late-skip limit in ms: skipFrames intervals, but at 60 fps two intervals are only 33 ms, less than normal Wi-Fi jitter.
  function lateLimitMs(interval, skipFrames) {
    return skipFrames > 0 ? Math.max(interval * skipFrames, LATE_SKIP_MIN_MS) : Infinity; // Infinity: never skip
  }

  // Decoder backlog that counts as overload: 3 chunks up to 30 fps, one more per 10 fps above (about 100 ms of video at 60 fps: 6).
  function decodeQueueLimit(fps) {
    return Math.max(MAX_DECODE_QUEUE, Math.ceil((fps > 0 ? fps : 30) / 10));
  }

  // " @60 fps" or " @30 fps (fallback)": the rate the stream really has (the phone lowers it when the viewer cannot keep up).
  function streamFpsText(h) {
    return h && h.fps > 0 ? ' @' + h.fps + ' fps' + (h.fpsLimit === 'hot' ? ' (phone hot)' : h.fpsFallback ? ' (fallback)' : '') : '';
  }
  // FPS-MATH-END

  function pump() {
    if (pumpTimer !== null) {
      clearTimeout(pumpTimer);
      pumpTimer = null;
    }
    if (held && performance.now() - lastRafAt > RAF_STALL_MS) drawHeld(); // rAF throttled: do not hold the last picture
    if (decoder && baseMs !== null) {
      const now = performance.now();
      const interval = frameIntervalMs();
      const lateLimit = lateLimitMs(interval, lateSkip);
      while (queue.length > 0) {
        const p = queue[0];
        if (p.type !== decoder.type) {
          // Leftover of a mode change.
          kfwait++;
          ack(queue.shift().raw);
          continue;
        }
        const due = dueTime(p);
        if (due > now) break;
        if (decoder.droppable) {
          queue.shift();
          // JPEG: skip a frame that is more than lateSkip intervals late when a newer one is due as well.
          if (now - due > lateLimit && queue.length > 0 &&
              queue[0].type === p.type && dueTime(queue[0]) <= now) {
            skipped++;
            discard(p);
            continue;
          }
          decoder.submit(p);
          continue;
        }
        // H.264: deltas cannot be skipped one by one, only a whole GOP: jump to the newest keyframe
        // waiting (or, if none is waiting, drop everything and ask for one).
        if (now - due > lateLimit && now >= skipHoldoffUntil && !decoder.isWaiting()) {
          skipHoldoffUntil = now + SKIP_HOLDOFF_MS;
          skipped++;
          let k = -1;
          for (let i = queue.length - 1; i >= 0; i--) {
            if (queue[i].type === p.type && queue[i].key) { k = i; break; }
          }
          if (k < 0) {
            dropped += queue.length;
            ack(queue[queue.length - 1].raw);
            queue = [];
            decoder.resync();
            askKeyframe();
            break;
          }
          for (let i = 0; i < k; i++) discard(queue.shift());
          continue; // the keyframe is now the head; the hold-off lets it through
        }
        queue.shift();
        decoder.submit(p);
      }
    }
    const next = queue.length > 0 && baseMs !== null ? dueTime(queue[0]) - performance.now() : MAX_PUMP_MS;
    schedulePump(next);
  }

  function rafTick() {
    lastRafAt = performance.now();
    flushInput();
    drawHeld();
    pump();
    window.requestAnimationFrame(rafTick);
  }

  // ---- configuration from the phone (hello.cfg) ----
  function applyCfg(c) {
    if (!c || typeof c !== 'object') return;
    const j = Number(c.jitterBufferMs);
    if (j >= 0 && j <= 5000) bufferMs = j;
    const k = Number(c.skipLateFrames);
    if (k >= 0 && k <= 100) lateSkip = k;
    pacing = c.pacing === 'vsync' ? 'vsync' : 'immediate';
    if (pacing !== 'vsync') drawHeld();
    cfgId = typeof c.id === 'string' ? c.id : '';
    cfgSummary = typeof c.summary === 'string' ? c.summary : '';
  }

  // The window size, so the phone can pick a stream size (config fitToViewer). CSS pixels + pixel ratio.
  function sendView() {
    send({ t: 'view', w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio || 1 });
  }

  function scheduleView() {
    if (viewTimer !== null) clearTimeout(viewTimer);
    viewTimer = setTimeout(function () { viewTimer = null; sendView(); }, VIEW_DEBOUNCE_MS);
  }

  // ---- messages ----
  function onBinary(buf) {
    if (buf.byteLength < HEADER) return;
    const view = new DataView(buf);
    const type = view.getUint8(0);
    const hi = view.getUint32(4, false);
    const lo = view.getUint32(8, false);
    if (type === TYPE_AUDIO) { onAudioPacket(buf, view.getUint8(1), hi * 4294967296 + lo); return; }
    if (type !== TYPE_JPEG && type !== TYPE_H264) return; // 0 = probe dummy
    if (!hello) return;                                    // wait for the stream description
    const ptsMs = (hi * 4294967296 + lo) / 1000;
    noteArrival(ptsMs);
    queue.push({
      type: type,
      key: (view.getUint8(1) & 1) !== 0,
      pts: ptsMs,
      raw: hi * 4294967296 + lo,
      data: new Uint8Array(buf, HEADER)
    });
    if (queue.length > MAX_QUEUE) {
      // Hard limit. H.264 cannot continue after a gap: drop all and resume at a keyframe.
      ack(queue[queue.length - 1].raw);
      dropped += queue.length;
      queue = [];
      if (decoder) decoder.resync();
      askKeyframe();
    }
    if (queue.length === 1) schedulePump(dueTime(queue[0]) - performance.now());
  }

  // ---- pairing ----
  function loadToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }

  function saveToken(t) {
    try { localStorage.setItem(TOKEN_KEY, t); } catch (e) { /* storage blocked: the PIN is asked again next time */ }
  }

  function clearToken() {
    try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* storage blocked */ }
  }

  function renderPair() {
    const left = pairLockUntil > 0 ? Math.ceil((pairLockUntil - performance.now()) / 1000) : 0;
    if (pairLockUntil > 0 && left <= 0) { pairLockUntil = 0; pairNote = ''; }
    let dots = '';
    for (let i = 0; i < pinLength; i++) dots += (i < pairDigits.length ? '\u25CF' : '\u25CB');
    pairPinEl.textContent = dots;
    if (pairLockUntil > 0) {
      pairMsgEl.textContent = 'Too many attempts, try again in ' + left + ' s';
      pairMsgEl.className = 'err';
    } else {
      pairMsgEl.textContent = pairNote || PAIR_PROMPT;
      pairMsgEl.className = pairNote === 'Wrong PIN' ? 'err' : '';
    }
    pairOkEl.disabled = pairLockUntil > 0 || pairDigits.length === 0;
    if (pairLockUntil > 0 && pairTimer === null) pairTimer = setInterval(renderPair, 500);
    if (pairLockUntil === 0 && pairTimer !== null) { clearInterval(pairTimer); pairTimer = null; }
  }

  function showPair() {
    pairing = true;
    pairEl.hidden = false;
    showMessage('');
    setStatus('Waiting for the PIN');
    renderPair();
  }

  function hidePair() {
    pairing = false;
    pairDigits = '';
    pairNote = '';
    pairLockUntil = 0;
    if (pairTimer !== null) { clearInterval(pairTimer); pairTimer = null; }
    pairEl.hidden = true;
  }

  function onPair(m) {
    const sock = ws;
    if (m.reason === 'required' && sock && sock.wmTokenSent) {
      clearToken();            // the phone does not know this token any more (forgotten): ask for the PIN
      sock.wmTokenSent = false;
    }
    pairDigits = '';
    const len = Number(m.pinLength);
    if (len >= 1 && len <= 12) pinLength = Math.floor(len);
    if (m.reason === 'wrong') pairNote = 'Wrong PIN';
    else if (m.reason === 'locked') pairLockUntil = performance.now() + (Number(m.retryInMs) || 0);
    else if (!pairing) pairNote = '';
    showPair();
  }

  function onPaired(m) {
    if (typeof m.token === 'string' && m.token) saveToken(m.token);
    hidePair();
    setStatus('Waiting for the stream…');
    showMessage('Waiting for the picture…');
  }

  function pairKey(k) {
    if (!pairing || pairLockUntil > 0) return;
    if (k === 'del') {
      pairDigits = pairDigits.slice(0, -1);
    } else if (k === 'ok') {
      if (pairDigits.length === 0) return;
      send({ t: 'pair', pin: pairDigits });
      pairNote = 'Checking…';
      pairDigits = '';
    } else if (k.length === 1 && k >= '0' && k <= '9' && pairDigits.length < pinLength) {
      pairDigits += k;
      pairNote = '';
    }
    renderPair();
  }

  pairPadEl.addEventListener('click', function (ev) {
    const t = ev.target;
    const k = t && t.getAttribute ? t.getAttribute('data-k') : null;
    if (k) pairKey(k);
  });
  document.addEventListener('keydown', function (ev) {
    if (!pairing) return;
    if (ev.key === 'Backspace') pairKey('del');
    else if (ev.key === 'Enter') pairKey('ok');
    else if (ev.key && ev.key.length === 1) pairKey(ev.key);
  });

  function onText(text) {
    let m;
    try { m = JSON.parse(text); } catch (e) { return; }
    if (m.t === 'pair') {
      onPair(m);
    } else if (m.t === 'paired') {
      onPaired(m);
    } else if (m.t === 'hello') {
      if (pairing) hidePair();
      hello = m;
      applyCfg(m.cfg);
      applyControlHello(m.control);
      applyAudioHello(m);
      chooseDecoder(m);
    } else if (m.t === 'ping') {
      // Answer at once with the page clock: the phone measures the round trip and the clock offset from it.
      if (typeof m.ts === 'number') send({ t: 'pong', ts: m.ts, pageTs: performance.now() });
      if (typeof m.off === 'number') phoneOffsetMs = m.off / 1000;
      if (typeof m.rtt === 'number') { rttSum += m.rtt; rttCount++; }
      serverInFlight = m.inFlight | 0;
      serverPending = m.pending | 0;
      serverKfwait = m.kfw | 0;
      serverTarget = typeof m.target === 'string' ? m.target : '';
    } else if (m.t === 'notice') {
      showNotice(typeof m.text === 'string' ? m.text : '', Number(m.ttlS) || 10);
    } else if (m.t === 'control' || m.t === 'controlDenied') {
      onControlMessage(m);
    } else if (m.t === 'kbd') {
      onKbdMessage(m);
    } else if (m.t === 'rotate') {
      if (m.ok === false && m.reason === 'permission') showCtlNote('Allow rotation on the phone: WebMirror > Settings > Remote control > Rotate from the car.');
    } else if (m.t === 'denied') {
      setStatus('Denied: ' + m.reason);
      showMessage('Access denied (' + m.reason + ')');
    }
  }

  // ---- capability check: per codec, decodable at all (sw) and decodable in hardware (hw), at 1280x720 ----
  function checkOne(codec, preferHardware) {
    return new Promise(function (resolve) {
      try {
        const cfg = { codec: codec, codedWidth: 1280, codedHeight: 720, optimizeForLatency: true };
        if (preferHardware) cfg.hardwareAcceleration = 'prefer-hardware';
        window.VideoDecoder.isConfigSupported(cfg).then(
          function (r) { resolve(!!(r && r.supported)); },
          function () { resolve(false); }
        );
      } catch (e) {
        resolve(false);
      }
    });
  }

  function checkCodecs() {
    if (codecCheck) return codecCheck;
    codecCheck = new Promise(function (resolve) {
      const result = {};
      const names = Object.keys(PROBE_CODECS);
      names.forEach(function (n) { result[n] = { sw: false, hw: false }; });
      if (!('VideoDecoder' in window) || typeof window.EncodedVideoChunk !== 'function') {
        codecSupport = result;
        resolve(result);
        return;
      }
      const jobs = [];
      names.forEach(function (n) {
        jobs.push(checkOne(PROBE_CODECS[n], false).then(function (ok) { result[n].sw = ok; }));
        jobs.push(checkOne(PROBE_CODECS[n], true).then(function (ok) { result[n].hw = ok; }));
      });
      Promise.all(jobs).then(function () {
        codecSupport = result;
        resolve(result);
      });
    });
    return codecCheck;
  }

  // ---- connection ----
  function connect() {
    reconnectTimer = null;
    let socket;
    try {
      socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    } catch (e) {
      scheduleReconnect();
      return;
    }
    ws = socket;
    socket.binaryType = 'arraybuffer';
    setStatus('Connecting…');
    // A connect attempt that hangs is abandoned so the retry loop keeps going.
    connectTimer = setTimeout(function () {
      if (ws === socket && socket.readyState !== 1) {
        try { socket.close(); } catch (e) { /* ignore */ }
        onClosed(socket);
      }
    }, CONNECT_TIMEOUT_MS);
    socket.onopen = function () {
      if (ws !== socket) return;
      lastRxAt = performance.now();
      socket.wmOpened = true;
      clearTimeout(connectTimer);
      setStatus('Waiting for the stream…');
      Promise.all([checkCodecs(), checkAudio()]).then(function (res) {
        const c = res[0];
        if (ws === socket) {
          // h264 / hevc booleans: kept for older phone apps (h264 = decodable at all, hevc = in hardware).
          const hi = { t: 'hello', v: 1, h264: c.h264.sw || c.h264.hw, hevc: c.hevc.hw, codecs: c, pin: '' };
          if (res[1]) hi.audio = res[1];   // only pages that can play audio at all
          const tok = loadToken();
          if (tok) { hi.token = tok; socket.wmTokenSent = true; }
          send(hi);
          sendView();
        }
      });
    };
    socket.onmessage = function (ev) {
      if (ws !== socket) return;
      lastRxAt = performance.now();
      if (typeof ev.data === 'string') onText(ev.data);
      else onBinary(ev.data);
    };
    socket.onclose = function () { onClosed(socket); };
    socket.onerror = function () { /* onclose follows */ };
  }

  function onClosed(socket) {
    if (ws !== socket) return;
    ws = null;
    if (socket.wmOpened) reconnects++;
    clearTimeout(connectTimer);
    hello = null;
    if (pairing) hidePair(); // the next connection asks again if needed
    if (ctl.on || ctl.pending || ctl.allowed) {
      // The phone releases the control when the socket closes; the next hello says what is allowed now.
      setControlOn(false, false);
      ctl.allowed = false;
      ctl.autoAsked = false;
      updateBar();
      layout();
    }
    snd.sentOn = false;   // the next hello of the phone re-sends the sound state
    if (snd.clock) audioClockReset(snd.clock);
    flushAudioDecoder();
    phoneOffsetMs = null; // the next connection measures its own offset
    paused = false;
    flushQueue();
    if (decoder) decoder.reset();
    setStatus('Reconnecting…');
    showMessage('Reconnecting…');
    scheduleReconnect();
  }

  // Dead-connection watchdog: the server pings every second, so 3 s of silence means the link is gone
  // (e.g. the car dropped Wi-Fi). The old socket may never report it, so it is abandoned right here.
  function watchdog() {
    if (pairing) return; // the phone sends nothing to an unpaired page
    if (ws && ws.readyState === 1 && performance.now() - lastRxAt > DEAD_MS) {
      const dead = ws;
      try { dead.close(); } catch (e) { /* ignore */ }
      onClosed(dead);
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer === null) reconnectTimer = setTimeout(connect, RECONNECT_MS);
  }

  // ---- stats ----
  function decodeQueueSize() {
    return decoder && decoder.decodeQueue ? decoder.decodeQueue() : 0;
  }

  function inDecoderCount() {
    return decoder && decoder.inDecoder ? decoder.inDecoder() : 0;
  }

  let lastLateAvg = 0;
  let lastLateMax = 0;
  let lastNetLate = 0;
  let lastE2eAvg = 0;
  let lastE2eP95 = 0;
  let lastE2eMax = 0;
  let lastRtt = 0;

  // The e2e values collected since the last stats message: average, 95th percentile (nearest rank), maximum,
  // and at most E2E_RAW_MAX of the values themselves (evenly thinned) for the phone's aggregation.
  function e2eSummary() {
    const n = e2eList.length;
    if (n === 0) return null;
    const sorted = e2eList.slice().sort(function (a, b) { return a - b; });
    let sum = 0;
    for (let i = 0; i < n; i++) sum += sorted[i];
    const raw = [];
    const step = Math.max(1, Math.ceil(n / E2E_RAW_MAX));
    for (let i = 0; i < n; i += step) raw.push(Math.round(e2eList[i]));
    return {
      n: n,
      avg: sum / n,
      p95: sorted[Math.min(n - 1, Math.ceil(0.95 * n) - 1)],
      max: sorted[n - 1],
      raw: raw
    };
  }

  function updateStats() {
    const lines = [];
    lines.push('fps ' + fps.toFixed(1) + '   dropped ' + dropped + (kfwait + serverKfwait > 0 ? '   kfwait ' + (kfwait + serverKfwait) : '') + '   skipped ' + skipped);
    lines.push('late ' + Math.round(lastLateAvg) + ' ms (max ' + Math.round(lastLateMax) + ')   net ' + Math.round(lastNetLate) + ' ms');
    lines.push(phoneOffsetMs === null ? 'e2e: measuring the clock offset…' :
      'e2e avg ' + Math.round(lastE2eAvg) + ' ms   p95 ' + Math.round(lastE2eP95) + '   max ' + Math.round(lastE2eMax) + '   rtt ' + lastRtt.toFixed(1) + ' ms');
    lines.push('queue ' + queue.length + '   decoder ' + inDecoderCount() + ' (queue ' + decodeQueueSize() + ')   in flight ' + serverInFlight + ' (held ' + serverPending + ')');
    if (serverTarget) lines.push('target ' + serverTarget);
    if (ctl.allowed) lines.push('control ' + (ctl.on ? 'ON' : 'off') + '   service ' + (ctl.ready ? 'ready' : 'not ready') + '   fingers ' + fingersNow + ' (max ' + fingersMax + ')');
    if (cfgId) lines.push('cfg ' + cfgId + '   ' + cfgSummary);
    if (hello) {
      lines.push(hello.width + 'x' + hello.height + streamFpsText(hello) + '   mode ' + hello.mode + '   audio ' + helloAudio(hello).mode);
      if (snd.fmt && snd.clock) {
        const c = snd.ctx;
        lines.push('audio ' + snd.fmt.codec + ' ' + Math.round((snd.fmt.sampleRate || 48000) / 1000) + 'k ' + (snd.fmt.frameMs || 20) + ' ms ' + snd.clock.profile +
          '   ' + (soundActive() ? (audioMaster() ? 'following' : 'on') : snd.muted ? 'muted' : 'off') +
          (c ? '   buf ' + Math.round(audioBufferedMs(snd.clock, c.currentTime)) + ' ms (target ' + Math.round(snd.clock.target * 1000) + ')' : '') +
          '   underruns ' + snd.clock.underruns + '   decoder errors ' + snd.decoderErrors);
        // Where the sound delay goes: age of a packet when it arrives (phone capture + send + network), and the browser's output delay.
        if (c && snd.ageN > 0) {
          const outLat = (typeof c.outputLatency === 'number' && c.outputLatency > 0) ? c.outputLatency : (c.baseLatency || 0);
          lines.push('audio age ' + Math.round(snd.ageSum / snd.ageN) + ' ms (min ' + Math.round(snd.ageMin) + ')   output ' + Math.round(outLat * 1000) +
            ' ms (base ' + Math.round((c.baseLatency || 0) * 1000) + ')');
          snd.ageSum = 0; snd.ageN = 0; snd.ageMin = 1e9;
        }
      }
      if (hello.codec) lines.push('codec ' + hello.codec + (hello.encoder ? '   encoder ' + hello.encoder : ''));
      if (hello.why) lines.push(hello.why);
    }
    statsEl.textContent = lines.join('\n');
  }

  function sendStats() {
    const now = performance.now();
    if (lastStatsAt > 0) {
      fps = (framesDrawn - framesAtLastStats) * 1000 / (now - lastStatsAt);
    }
    lastStatsAt = now;
    framesAtLastStats = framesDrawn;
    if (lateCount > 0) {
      lastLateAvg = lateSum / lateCount;
      lastLateMax = lateMax;
    }
    if (netLateCount > 0) lastNetLate = netLateSum / netLateCount;
    lateSum = 0; lateMax = 0; lateCount = 0; netLateSum = 0; netLateCount = 0;
    const e2e = e2eSummary();
    e2eList = [];
    if (e2e) {
      lastE2eAvg = e2e.avg;
      lastE2eP95 = e2e.p95;
      lastE2eMax = e2e.max;
    }
    if (rttCount > 0) lastRtt = rttSum / rttCount;
    const rttMs = rttCount > 0 ? Math.round(lastRtt * 10) / 10 : 0;
    rttSum = 0; rttCount = 0;
    updateStats();
    send({
      t: 'stats',
      fps: Math.round(fps * 10) / 10,
      queue: queue.length,
      dropped: dropped,
      kfwait: kfwait,
      skipped: skipped,
      replaced: replaced,
      decoderResets: decoderResets,
      reconnects: reconnects,
      e2eAvg: e2e ? Math.round(e2e.avg) : null,
      e2eP95: e2e ? Math.round(e2e.p95) : null,
      e2eMax: e2e ? Math.round(e2e.max) : null,
      e2eN: e2e ? e2e.n : 0,
      e2eRaw: e2e ? e2e.raw : [],
      rttMs: rttMs,
      bufferMs: bufferMs + offsetMs,
      cfgId: cfgId,
      lateMs: Math.round(lastLateAvg),
      lateMaxMs: Math.round(lastLateMax),
      netLateMs: Math.round(lastNetLate),
      decodeQueue: decodeQueueSize(),
      inDecoder: inDecoderCount(),
      srvInFlight: serverInFlight,
      audioBufMs: snd.clock && snd.ctx ? Math.round(audioBufferedMs(snd.clock, snd.ctx.currentTime)) : 0,
      audioUnderruns: snd.clock ? snd.clock.underruns : 0,
      ua: navigator.userAgent
    });
  }

  // TYPING-MATH-BEGIN (pure; app/src/test/js/typing.test.js runs exactly this text under node)
  // Tesla keyboard (plan 2.1.5): the page keeps a hidden text field focused so the car browser opens its own keyboard,
  // and turns what is typed into `type` / `key` messages. The field is kept EMPTY: committed text is sent and cleared.
  const KBD_MAX_CHUNK = 200;       // the phone accepts at most 256 characters per `type` message
  const KBD_ACTIONS = { done: 1, search: 1, go: 1, send: 1, next: 1, enter: 1 };

  // Attributes of the hidden field for the kind of field the phone reports. Autocapitalize stays off: the field is empty
  // before every word, so the car keyboard would capitalise each one.
  function kbdFieldSpec(type, action, multiline) {
    const t = String(type || 'text');
    const spec = {
      tag: multiline && t !== 'password' ? 'textarea' : 'input',
      type: 'text', inputmode: 'text',
      enterkeyhint: KBD_ACTIONS.hasOwnProperty(action) ? action : 'enter',
      autocapitalize: 'off', autocomplete: 'off', autocorrect: 'off', spellcheck: false
    };
    if (t === 'number') spec.inputmode = 'numeric';
    else if (t === 'phone') { spec.type = 'tel'; spec.inputmode = 'tel'; }
    else if (t === 'email') { spec.type = 'email'; spec.inputmode = 'email'; }
    else if (t === 'url') { spec.type = 'url'; spec.inputmode = 'url'; }
    else if (t === 'password') spec.type = 'password';
    if (spec.tag === 'textarea') spec.type = null;
    return spec;
  }

  // Committed text -> messages: at most maxChunk characters per `type` (never splitting a surrogate pair), a new line
  // becomes the Enter key and a tab the Tab key, other control characters are dropped.
  function kbdSplitText(text, maxChunk) {
    const parts = String(text === null || text === undefined ? '' : text).replace(/\r\n?/g, '\n').split(/([\n\t])/);
    const out = [];
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      if (part === '\n') { out.push({ t: 'key', key: 'Enter' }); continue; }
      if (part === '\t') { out.push({ t: 'key', key: 'Tab' }); continue; }
      const clean = part.replace(/[\u0000-\u001f\u007f]/g, '');
      let pos = 0;
      while (pos < clean.length) {
        let end = Math.min(pos + maxChunk, clean.length);
        if (end < clean.length) {
          const c = clean.charCodeAt(end - 1);
          if (c >= 0xd800 && c <= 0xdbff) end--; // do not cut between the halves of a surrogate pair
        }
        if (end <= pos) end = Math.min(pos + 2, clean.length);
        out.push({ t: 'type', text: clean.slice(pos, end) });
        pos = end;
      }
    }
    return out;
  }

  // Which key a keydown stands for (null = let the field handle it). While an IME composes (or keyCode 229) nothing is
  // a key. Backspace and the arrows count only on an empty field: otherwise they edit the text being composed.
  function kbdKeyFromKeydown(key, valueEmpty, composing) {
    if (composing) return null;
    if (key === 'Enter' || key === 'Tab') return key;
    if (key === 'Backspace' || key === 'ArrowLeft' || key === 'ArrowRight') return valueEmpty ? key : null;
    return null;
  }

  // Same for beforeinput (soft keyboards that do not send a usable keydown): Backspace on the empty field, line breaks.
  function kbdKeyFromBeforeInput(inputType, valueEmpty, composing) {
    if (composing) return null;
    if (inputType === 'deleteContentBackward' && valueEmpty) return 'Backspace';
    if (inputType === 'insertLineBreak' || inputType === 'insertParagraph') return 'Enter';
    return null;
  }

  // The Keyboard button shows while the phone has a text field focused (Tesla keyboard on, this page controls).
  function kbdButtonVisible(tesla, show, controlOn) {
    return !!(tesla && show && controlOn);
  }
  // TYPING-MATH-END

  // ---- remote control: touches on the picture go to the phone while ctl.on ----
  const INPUT_ID_MAX = 1000000;
  const slots = {};                // browser pointer/touch id -> { id, x, y, ts, dirty }
  let nextInputId = 0;
  const seenPointers = {};         // every pointer the browser reports (picture or not): the finger indicator
  let fingersNow = 0;
  let fingersMax = 0;
  const havePointer = typeof window.PointerEvent === 'function';

  function round5(v) { return Math.round(v * 100000) / 100000; }

  function sendInput(e, id, x, y, ts) {
    const m = { t: 'input', e: e, id: id, ts: Math.round(ts * 10) / 10, w: hello ? hello.width | 0 : 0, h: hello ? hello.height | 0 : 0 };
    if (x !== null) { m.x = round5(x); m.y = round5(y); }
    send(m);
  }

  function pictureRect() { return canvas.getBoundingClientRect(); }

  function ctlDown(key, cx, cy) {
    if (slots[key]) return;
    const n = normalise(cx, cy, pictureRect());
    const now = performance.now();
    const id = nextInputId;
    nextInputId = (nextInputId + 1) % INPUT_ID_MAX;
    slots[key] = { id: id, x: n.x, y: n.y, ts: now, dirty: false };
    sendInput('down', id, n.x, n.y, now);
  }

  function ctlMove(key, cx, cy) {
    const s = slots[key];
    if (!s) return;
    const n = normalise(cx, cy, pictureRect());
    s.x = n.x; s.y = n.y; s.ts = performance.now(); s.dirty = true; // sent from the next animation frame (at most one move per frame and pointer)
  }

  function ctlEnd(key, cx, cy, cancel) {
    const s = slots[key];
    if (!s) return;
    delete slots[key];
    if (cx !== null && !cancel) {
      const n = normalise(cx, cy, pictureRect());
      s.x = n.x; s.y = n.y;
    }
    sendInput(cancel ? 'cancel' : 'up', s.id, s.x, s.y, performance.now());
  }

  function flushInput() {
    for (const key in slots) {
      const s = slots[key];
      if (s.dirty) {
        s.dirty = false;
        sendInput('move', s.id, s.x, s.y, s.ts);
      }
    }
  }

  function cancelAllPointers() {
    for (const key in slots) ctlEnd(key, null, null, true);
  }

  function showCtlNote(text) {
    if (ctlNoteTimer !== null) { clearTimeout(ctlNoteTimer); ctlNoteTimer = null; }
    if (!text) { ctlNoteEl.hidden = true; return; }
    ctlNoteEl.textContent = text;
    ctlNoteEl.hidden = false;
    ctlNoteTimer = setTimeout(function () { ctlNoteTimer = null; ctlNoteEl.hidden = true; }, 6000);
  }

  function updateBar() {
    ctlToggleEl.className = ctl.pending ? 'pending' : ctl.on ? 'on' : 'off';
    ctlLabelEl.textContent = ctl.pending ? 'Control ...' : ctl.on ? 'Control on' : 'Control off';
    canvas.className = ctl.on ? 'controlling' : '';
    fingersEl.hidden = !ctl.on;
    fingersEl.textContent = 'Fingers ' + fingersNow + '/' + fingersMax;
    ctlToggleEl.hidden = !ctl.allowed;
    kbdBtnEl.hidden = !kbdButtonVisible(kbd.tesla, kbd.show, ctl.on);
    rotBtnEl.hidden = !ctl.on;
  }

  // Control is on (owner) or off. notifyPhone: tell the phone (not needed when the phone said it).
  function setControlOn(on, notifyPhone) {
    if (!on) cancelAllPointers();
    const changed = ctl.on !== on || ctl.pending;
    ctl.on = on;
    ctl.pending = false;
    if (on) overlayEl.hidden = true;
    if (!on && notifyPhone && changed) send({ t: 'control', on: false });
    updateBar();
    syncKbd();
  }

  function ctlOptedOut() {
    try { return localStorage.getItem(CTL_OPTOUT_KEY) === '1'; } catch (e) { return false; }
  }

  function ctlSetOptOut(v) {
    try { if (v) localStorage.setItem(CTL_OPTOUT_KEY, '1'); else localStorage.removeItem(CTL_OPTOUT_KEY); } catch (e) { /* storage blocked */ }
  }

  // Asks the phone for the control; the answer comes as `control` or `controlDenied`.
  function requestControl() {
    ctl.pending = true;
    updateBar();
    send({ t: 'control', on: true });
    // No answer (old phone app, link problem): back to OFF.
    setTimeout(function () { if (ctl.pending) { ctl.pending = false; updateBar(); showCtlNote('No answer from the phone'); } }, 4000);
  }

  function applyControlHello(c) {
    const allowed = !!(c && c.allowed);
    ctl.ready = !!(c && c.ready);
    ctl.ownerPresent = !!(c && c.ownerPresent);
    const changed = allowed !== ctl.allowed;
    ctl.allowed = allowed;
    applyKbdHello(c);
    if (!allowed && (ctl.on || ctl.pending)) setControlOn(false, false);
    // Control starts ON: ask automatically once per "nobody owns it" period, unless the user turned it off in this
    // browser. If another screen owns it, say so and ask again once its owner is gone (the hello tells).
    if (!allowed || !ctl.ready || ctl.ownerPresent) ctl.autoAsked = false;
    if (allowed && ctl.ready && !ctl.on && !ctl.pending) {
      if (ctl.ownerPresent) {
        showCtlNote(CTL_REASONS.busy);
      } else if (!ctl.autoAsked && !ctlOptedOut()) {
        ctl.autoAsked = true;
        requestControl();
      }
    }
    updateBar();
    if (changed) layout(); // the bar appears or goes away, which may shrink or grow the picture
  }

  const CTL_REASONS = {
    notAllowed: 'Not allowed on the phone',
    notReady: 'Phone: turn on the accessibility service',
    busy: 'Another screen is controlling'
  };

  function onControlMessage(m) {
    if (m.t === 'controlDenied') {
      ctl.pending = false;
      setControlOn(false, false);
      showCtlNote(CTL_REASONS[m.reason] || ('Denied: ' + m.reason));
    } else if (m.on && m.owner) {
      setControlOn(true, false);
      showCtlNote('');
    } else {
      const wasOn = ctl.on || ctl.pending;
      setControlOn(false, false);
      if (wasOn && m.reason) showCtlNote(CTL_REASONS[m.reason] || m.reason);
    }
  }

  // ---- Tesla keyboard (plan 2.1.5): a hidden field opens the car browser's keyboard; what is typed goes to the phone ----
  // Nothing typed is logged or stored: the field is emptied after every send.

  function applyKbdHello(c) {
    const t = !!(c && c.kbd === 'tesla');
    if (t !== kbd.tesla) { kbd.tesla = t; if (!t) kbd.show = false; syncKbd(); }
    if (c && c.kbdUnsupported && c.allowed && !kbd.noticed) {
      kbd.noticed = true;
      showNotice('Tesla keyboard needs Android 13 or newer; using the phone keyboard', 8);
    }
  }

  // The phone says a text field has / lost input focus. Remembered even before this page owns the control (the message
  // can overtake the control answer); acted on only while it does.
  function onKbdMessage(m) {
    if (!kbd.tesla) return;
    kbd.show = m.show === true;
    if (kbd.show) {
      kbd.type = typeof m.type === 'string' ? m.type : 'text';
      kbd.action = typeof m.action === 'string' ? m.action : 'enter';
      kbd.multiline = m.multiline === true;
    }
    updateBar();
    syncKbd();
  }

  function removeKbdField() {
    const f = kbd.field;
    kbd.field = null;
    kbd.spec = '';
    kbd.composing = false;
    if (f) {
      try { f.blur(); } catch (e) { /* gone */ }
      if (f.parentNode) f.parentNode.removeChild(f);
    }
  }

  // Sends and clears what the field holds (committed text only).
  function kbdFlush() {
    const f = kbd.field;
    if (!f || !f.value) return;
    const v = f.value;
    f.value = '';
    const msgs = kbdSplitText(v, KBD_MAX_CHUNK);
    for (let i = 0; i < msgs.length; i++) send(msgs[i]);
  }

  function kbdSendKey(key) {
    kbdFlush();
    send({ t: 'key', key: key });
  }

  function buildKbdField() {
    const spec = kbdFieldSpec(kbd.type, kbd.action, kbd.multiline);
    const sig = JSON.stringify(spec);
    if (kbd.field && kbd.spec === sig) return kbd.field;
    removeKbdField();
    const f = document.createElement(spec.tag);
    f.id = 'kbdField';
    if (spec.type) f.setAttribute('type', spec.type);
    f.setAttribute('inputmode', spec.inputmode);
    f.setAttribute('enterkeyhint', spec.enterkeyhint);
    f.setAttribute('autocapitalize', spec.autocapitalize);
    f.setAttribute('autocomplete', spec.autocomplete);
    f.setAttribute('autocorrect', spec.autocorrect);
    f.setAttribute('spellcheck', 'false');
    f.setAttribute('aria-hidden', 'true');
    f.setAttribute('tabindex', '-1');
    if (spec.tag === 'textarea') f.setAttribute('rows', '1');
    f.addEventListener('compositionstart', function () { kbd.composing = true; });
    f.addEventListener('compositionend', function () { kbd.composing = false; kbdFlush(); });
    f.addEventListener('input', function (ev) {
      if (ev.isComposing || kbd.composing) return; // only committed text is sent
      kbdFlush();
    });
    f.addEventListener('beforeinput', function (ev) {
      const key = kbdKeyFromBeforeInput(ev.inputType, f.value === '', !!ev.isComposing || kbd.composing);
      if (key) { ev.preventDefault(); kbdSendKey(key); }
    });
    f.addEventListener('keydown', function (ev) {
      const key = kbdKeyFromKeydown(ev.key, f.value === '', !!ev.isComposing || kbd.composing || ev.keyCode === 229);
      if (key) { ev.preventDefault(); kbdSendKey(key); }
    });
    document.body.appendChild(f);
    kbd.field = f;
    kbd.spec = sig;
    return f;
  }

  // Focus the field (opens the car browser's keyboard where the browser allows it; many only do so from a tap, hence the
  // Keyboard button and the refocus after a tap on the picture).
  function focusKbdField() {
    if (!(kbd.tesla && kbd.show && ctl.on)) return;
    const f = buildKbdField();
    try { f.focus({ preventScroll: true }); } catch (e) { try { f.focus(); } catch (e2) { /* not focusable */ } }
  }

  function syncKbd() {
    if (kbd.tesla && kbd.show && ctl.on) focusKbdField();
    else removeKbdField();
    updateBar();
  }

  kbdBtnEl.addEventListener('click', function () { focusKbdField(); });
  rotBtnEl.addEventListener('click', function () { send({ t: 'rotate' }); });
  // A tap on the picture ends with a user gesture: use it to bring the keyboard back if the user dismissed it.
  function refocusKbd() {
    if (kbd.tesla && kbd.show && ctl.on && document.activeElement !== kbd.field) focusKbdField();
  }
  canvas.addEventListener('pointerup', refocusKbd);
  canvas.addEventListener('touchend', refocusKbd);

  ctlToggleEl.addEventListener('click', function () {
    if (ctl.on || ctl.pending) {
      ctlSetOptOut(true);
      setControlOn(false, true);
    } else {
      ctlSetOptOut(false);
      requestControl();
    }
  });
  menuBtnEl.addEventListener('click', toggleOverlay);

  // Touches on the picture: Pointer Events, or Touch Events where those are missing.
  if (havePointer) {
    canvas.addEventListener('pointerdown', function (ev) {
      if (!ctl.on || (ev.pointerType === 'mouse' && ev.button !== 0)) return;
      ev.preventDefault();
      try { canvas.setPointerCapture(ev.pointerId); } catch (e) { /* not capturable */ }
      ctlDown('p' + ev.pointerId, ev.clientX, ev.clientY);
    });
    canvas.addEventListener('pointermove', function (ev) {
      if (!ctl.on) return;
      ev.preventDefault();
      ctlMove('p' + ev.pointerId, ev.clientX, ev.clientY);
    });
    canvas.addEventListener('pointerup', function (ev) { ev.preventDefault(); ctlEnd('p' + ev.pointerId, ev.clientX, ev.clientY, false); });
    canvas.addEventListener('pointercancel', function (ev) { ctlEnd('p' + ev.pointerId, null, null, true); });
  } else {
    const touchOpts = { passive: false };
    canvas.addEventListener('touchstart', function (ev) {
      if (!ctl.on) return;
      ev.preventDefault();
      for (let i = 0; i < ev.changedTouches.length; i++) {
        const t = ev.changedTouches[i];
        ctlDown('t' + t.identifier, t.clientX, t.clientY);
      }
    }, touchOpts);
    canvas.addEventListener('touchmove', function (ev) {
      if (!ctl.on) return;
      ev.preventDefault();
      for (let i = 0; i < ev.changedTouches.length; i++) {
        const t = ev.changedTouches[i];
        ctlMove('t' + t.identifier, t.clientX, t.clientY);
      }
    }, touchOpts);
    canvas.addEventListener('touchend', function (ev) {
      ev.preventDefault();
      for (let i = 0; i < ev.changedTouches.length; i++) {
        const t = ev.changedTouches[i];
        ctlEnd('t' + t.identifier, t.clientX, t.clientY, false);
      }
    }, touchOpts);
    canvas.addEventListener('touchcancel', function (ev) {
      for (let i = 0; i < ev.changedTouches.length; i++) ctlEnd('t' + ev.changedTouches[i].identifier, null, null, true);
    }, touchOpts);
  }

  // Finger indicator: how many touches the browser reports right now (anywhere on the page); shows whether the car's
  // screen delivers multi-touch at all.
  function countFingers(n) {
    fingersNow = n;
    if (n > fingersMax) fingersMax = n;
    if (ctl.on) updateBar();
  }
  if (havePointer) {
    window.addEventListener('pointerdown', function (ev) { seenPointers[ev.pointerId] = true; countFingers(Object.keys(seenPointers).length); }, true);
    const gone = function (ev) { delete seenPointers[ev.pointerId]; countFingers(Object.keys(seenPointers).length); };
    window.addEventListener('pointerup', gone, true);
    window.addEventListener('pointercancel', gone, true);
  } else {
    const touches = function (ev) { countFingers(ev.touches ? ev.touches.length : 0); };
    window.addEventListener('touchstart', touches, true);
    window.addEventListener('touchend', touches, true);
    window.addEventListener('touchcancel', touches, true);
  }
  window.addEventListener('blur', cancelAllPointers);

  // ---- UI ----
  function toggleOverlay() {
    overlayEl.hidden = !overlayEl.hidden;
  }

  document.body.addEventListener('click', function (ev) {
    let el = ev.target;
    while (el) {
      if (el === overlayEl || el === barEl) return; // taps inside the panel or the control bar do not toggle it
      el = el.parentNode;
    }
    if (ctl.on && ev.target === canvas) return; // while controlling, the picture belongs to the phone
    toggleOverlay();
  });
  document.getElementById('close').addEventListener('click', function () { overlayEl.hidden = true; });
  document.getElementById('delayMinus').addEventListener('click', function () { setDelay((soundActive() ? snd.trimMs : offsetMs) - 10); });
  document.getElementById('delayPlus').addEventListener('click', function () { setDelay((soundActive() ? snd.trimMs : offsetMs) + 10); });
  delayEl.addEventListener('input', function () { setDelay(parseInt(delayEl.value, 10) || 0); });

  const docEl = document.documentElement;
  const canFullscreen = !!(docEl.requestFullscreen || docEl.webkitRequestFullscreen);
  if (!canFullscreen) {
    fullscreenBtn.hidden = true;
  }
  fullscreenBtn.addEventListener('click', function () {
    try {
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        if (document.exitFullscreen) document.exitFullscreen();
        else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
      } else if (docEl.requestFullscreen) {
        docEl.requestFullscreen();
      } else if (docEl.webkitRequestFullscreen) {
        docEl.webkitRequestFullscreen();
      }
    } catch (e) { /* not allowed here */ }
  });

  window.addEventListener('resize', function () { layout(); scheduleView(); });
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) cancelAllPointers();
    if (!document.hidden) {
      if (snd.ctx && snd.available && snd.want && !snd.muted) resumeAudioContext();
      if (snd.clock) audioClockReset(snd.clock);   // re-anchor: the context may have stood still while hidden
      flushQueue(); // the queue may hold stale packets after a long hidden period
      if (decoder) decoder.resync();
      askKeyframe();
    }
  });

  // ---- go ----
  loadSoundPrefs();
  offsetMs = loadDelay();
  syncDelayUi();
  updateBar();
  layout();
  setInterval(sendStats, STATS_INTERVAL_MS);
  setInterval(watchdog, WATCHDOG_MS);
  schedulePump(0);
  window.requestAnimationFrame(rafTick);
  connect();
})();
