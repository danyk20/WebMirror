// WebMirror player. Plain script, no modules; no ?. , ??, or BigInt (old Chromium in the car).
// Protocol: docs/PLAN.md section 2.1. Decoders are chosen per mode (hello.mode): "jpeg", "h264" and "hevc" (the two video modes share packet type 1 and one decoder implementation).
(function () {
  'use strict';

  // ---- constants ----
  const DEFAULT_BUFFER_MS = 40;    // network jitter buffer (plan 2.4); the phone's hello.cfg.jitterBufferMs overrides it
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
  const H264_PROBE_CODEC = 'avc1.42E01F';
  const HEVC_PROBE_CODEC = 'hev1.1.6.L93.B0';   // HEVC Main 3.1, the family the phone's encoder produces
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
  let h264Supported = false;      // VideoDecoder exists and isConfigSupported said yes
  let h264Check = null;           // promise of that check, run once
  let hevcSupported = false;      // isConfigSupported said yes for HEVC with prefer-hardware
  let hevcCheck = null;
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
  function layout() {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const iw = canvas.width;
    const ih = canvas.height;
    const scale = Math.min(vw / iw, vh / ih);
    const cw = Math.floor(iw * scale);
    const ch = Math.floor(ih * scale);
    canvas.style.width = cw + 'px';
    canvas.style.height = ch + 'px';
    canvas.style.left = Math.floor((vw - cw) / 2) + 'px';
    canvas.style.top = Math.floor((vh - ch) / 2) + 'px';
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

  // H.264 and HEVC: WebCodecs VideoDecoder -> canvas. Annex-B without `description`: parameter sets
  // (SPS/PPS, for HEVC VPS/SPS/PPS) come inside keyframes. The codec string comes from hello.codec.
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
      if (codec.indexOf('hev1') === 0 || codec.indexOf('hvc1') === 0) cfg.hardwareAcceleration = 'prefer-hardware';
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
    } else if (m.t === 'denied') {
      setStatus('Denied: ' + m.reason);
      showMessage('Access denied (' + m.reason + ')');
    }
  }

  // ---- capability check: H.264 only if WebCodecs can decode Baseline 3.1 ----
  function checkH264() {
    if (h264Check) return h264Check;
    h264Check = new Promise(function (resolve) {
      if (!('VideoDecoder' in window) || typeof window.EncodedVideoChunk !== 'function') {
        resolve(false);
        return;
      }
      try {
        window.VideoDecoder.isConfigSupported({ codec: H264_PROBE_CODEC, optimizeForLatency: true }).then(
          function (r) {
            h264Supported = !!(r && r.supported);
            resolve(h264Supported);
          },
          function () { resolve(false); }
        );
      } catch (e) {
        resolve(false);
      }
    });
    return h264Check;
  }

  // HEVC only if the browser says it can decode it in hardware (software HEVC would add delay).
  function checkHevc() {
    if (hevcCheck) return hevcCheck;
    hevcCheck = new Promise(function (resolve) {
      if (!('VideoDecoder' in window) || typeof window.EncodedVideoChunk !== 'function') {
        resolve(false);
        return;
      }
      try {
        window.VideoDecoder.isConfigSupported({
          codec: HEVC_PROBE_CODEC, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware'
        }).then(
          function (r) {
            hevcSupported = !!(r && r.supported);
            resolve(hevcSupported);
          },
          function () { resolve(false); }
        );
      } catch (e) {
        resolve(false);
      }
    });
    return hevcCheck;
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
      Promise.all([checkH264(), checkHevc()]).then(function (r) {
        if (ws === socket) {
          send({ t: 'hello', v: 1, h264: r[0], hevc: r[1], pin: '' });
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
    if (cfgId) lines.push('cfg ' + cfgId + '   ' + cfgSummary);
    if (hello) {
      lines.push(hello.width + 'x' + hello.height + '   mode ' + hello.mode + '   audio ' + hello.audio);
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

  // ---- UI ----
  function toggleOverlay() {
    overlayEl.hidden = !overlayEl.hidden;
  }

  document.body.addEventListener('click', function (ev) {
    let el = ev.target;
    while (el) {
      if (el === overlayEl) return; // taps inside the panel do not toggle it
      el = el.parentNode;
    }
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
    if (!document.hidden) {
      flushQueue(); // the queue may hold stale packets after a long hidden period
      if (decoder) decoder.resync();
      askKeyframe();
    }
  });

  // ---- go ----
  setDelay(loadDelay());
  layout();
  setInterval(sendStats, STATS_INTERVAL_MS);
  setInterval(watchdog, WATCHDOG_MS);
  schedulePump(0);
  window.requestAnimationFrame(rafTick);
  connect();
})();
