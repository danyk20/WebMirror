// WebMirror player. Plain script, no modules; no ?. , ??, or BigInt (old Chromium in the car).
// Protocol: docs/PLAN.md section 2.1. Decoders are chosen per mode (hello.mode): "jpeg", "h264", "hevc", "vp9" and "av1" (the video modes share packet type 1 and one decoder implementation).
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
  // Representative codec strings for the capability check (hello.codecs), checked at 1280x720.
  const PROBE_CODECS = {
    h264: 'avc1.42E01F',
    hevc: 'hev1.1.6.L93.B0',
    vp9: 'vp09.00.31.08',
    av1: 'av01.0.08M.08'
  };
  const MAX_DECODE_QUEUE = 3;      // VideoDecoder backlog that counts as overload
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
  // Remote control (plan "Remote control"): allowed/ready come from hello.control; on = this page owns the control.
  const ctl = { allowed: false, ready: false, ownerPresent: false, on: false, pending: false, autoAsked: false };
  const CTL_OPTOUT_KEY = 'webmirror.controlOptOut'; // set when the user turned control off in this browser: no automatic request
  let ctlNoteTimer = null;

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
    const late = performance.now() - ptsMs - baseMs - bufferMs - offsetMs;
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

  function setDelay(v) {
    v = Math.max(0, Math.min(1500, Math.round(v / 10) * 10));
    offsetMs = v;
    delayEl.value = String(v);
    delayValueEl.textContent = v + ' ms';
    saveDelay(v);
  }

  // ---- layout: canvas fills the window keeping the aspect ratio ----
  // LAYOUT-MATH-BEGIN (pure; app/src/test/js/layout.test.js runs exactly this text under node)
  // Compact remote-control controls (CSS px, see style.css): the vertical stack (right bar) is CTL_W wide and CTL_STACK_H
  // tall, the horizontal row (bottom bar) is CTL_ROW_H tall. A bar must be thicker than the controls plus CTL_PAD.
  const CTL_W = 104;
  const CTL_ROW_H = 44;
  const CTL_PAD = 8;
  const CTL_GAP = 4;               // gap between the picture and the bar content
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
    const L = computeLayout(vw, vh, canvas.width, canvas.height, ctl.allowed, NEED_SIDE, NEED_BOTTOM);
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

  function dueTime(p) {
    return p.pts + baseMs + bufferMs + offsetMs;
  }

  function flushQueue() {
    dropHeld();
    if (queue.length > 0) ack(queue[queue.length - 1].raw);
    queue = [];
    baseSamples = [];
    baseMs = null;
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
        if (dec.decodeQueueSize > MAX_DECODE_QUEUE) {
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
    hintEl.hidden = !(hello && hello.audio === 'bluetooth');
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

  function pump() {
    if (pumpTimer !== null) {
      clearTimeout(pumpTimer);
      pumpTimer = null;
    }
    if (held && performance.now() - lastRafAt > RAF_STALL_MS) drawHeld(); // rAF throttled: do not hold the last picture
    if (decoder && baseMs !== null) {
      const now = performance.now();
      const interval = frameIntervalMs();
      const lateLimit = lateSkip > 0 ? interval * lateSkip : Infinity; // Infinity: never skip
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
    if (type !== TYPE_JPEG && type !== TYPE_H264) return; // 0 = probe dummy, 3 = audio (later)
    if (!hello) return;                                    // wait for the stream description
    const hi = view.getUint32(4, false);
    const lo = view.getUint32(8, false);
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

  function onText(text) {
    let m;
    try { m = JSON.parse(text); } catch (e) { return; }
    if (m.t === 'hello') {
      hello = m;
      applyCfg(m.cfg);
      applyControlHello(m.control);
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
      checkCodecs().then(function (c) {
        if (ws === socket) {
          // h264 / hevc booleans: kept for older phone apps (h264 = decodable at all, hevc = in hardware).
          send({ t: 'hello', v: 1, h264: c.h264.sw || c.h264.hw, hevc: c.hevc.hw, codecs: c, pin: '' });
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
    if (ctl.on || ctl.pending || ctl.allowed) {
      // The phone releases the control when the socket closes; the next hello says what is allowed now.
      setControlOn(false, false);
      ctl.allowed = false;
      ctl.autoAsked = false;
      updateBar();
      layout();
    }
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
      lines.push(hello.width + 'x' + hello.height + '   mode ' + hello.mode + '   audio ' + hello.audio);
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
      ua: navigator.userAgent
    });
  }

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
  document.getElementById('delayMinus').addEventListener('click', function () { setDelay(offsetMs - 10); });
  document.getElementById('delayPlus').addEventListener('click', function () { setDelay(offsetMs + 10); });
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
      flushQueue(); // the queue may hold stale packets after a long hidden period
      if (decoder) decoder.resync();
      askKeyframe();
    }
  });

  // ---- go ----
  setDelay(loadDelay());
  updateBar();
  layout();
  setInterval(sendStats, STATS_INTERVAL_MS);
  setInterval(watchdog, WATCHDOG_MS);
  schedulePump(0);
  window.requestAnimationFrame(rafTick);
  connect();
})();
