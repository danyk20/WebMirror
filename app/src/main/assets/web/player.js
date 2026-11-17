// WebMirror player. Plain script, no modules; no ?. , ??, or BigInt (old Chromium in the car).
// Protocol: docs/PLAN.md section 2.1. Decoders are chosen per mode (hello.mode); today only "jpeg" exists.
// Adding H.264 means adding decoders.h264 and a capability check; nothing else needs to change.
(function () {
  'use strict';

  // ---- constants ----
  const BUFFER_MS = 80;            // network jitter buffer (plan 2.4)
  const BASE_WINDOW = 200;         // packets used for the clock mapping
  const MAX_QUEUE = 200;           // hard cap of waiting packets
  const STATS_INTERVAL_MS = 2000;  // also the keep-alive (server drops viewers silent for 10 s)
  const RECONNECT_MS = 1000;
  const MAX_PUMP_MS = 50;          // longest wait between two pump runs
  const PAUSED_TEXT = 'Paused on the phone. Open WebMirror and tap Start.';
  const CONNECT_TIMEOUT_MS = 5000;
  const DELAY_KEY = 'webmirror.delayMs';
  const HEADER = 12;
  const TYPE_H264 = 1;
  const TYPE_JPEG = 2;

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

  // ---- state ----
  let ws = null;
  let connectTimer = null;
  let reconnectTimer = null;
  let hello = null;               // latest hello from the phone
  let decoder = null;             // decoder object for hello.mode
  let queue = [];                 // waiting packets: { type, key, pts (ms), data }
  let baseSamples = [];           // recent (arrival - pts) values, ms
  let baseMs = null;
  let offsetMs = 0;
  let dropped = 0;
  let framesDrawn = 0;
  let framesAtLastStats = 0;
  let lastStatsAt = 0;
  let fps = 0;
  let pumpTimer = null;
  let paused = false;             // latest hello said mode "paused"

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
    baseSamples.push(performance.now() - ptsMs);
    if (baseSamples.length > BASE_WINDOW) baseSamples.shift();
    let m = baseSamples[0];
    for (let i = 1; i < baseSamples.length; i++) {
      if (baseSamples[i] < m) m = baseSamples[i];
    }
    baseMs = m;
  }

  function dueTime(p) {
    return p.pts + baseMs + BUFFER_MS + offsetMs;
  }

  function flushQueue() {
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

    function drawn(w, h, source) {
      setCanvasSize(w, h);
      ctx.drawImage(source, 0, 0);
      framesDrawn++;
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
          drawn(bmp.width, bmp.height, bmp);
          if (bmp.close) bmp.close();
          finish();
        }, function () {
          finish();
        });
      } else {
        const url = URL.createObjectURL(blob);
        const img = new Image();
        img.onload = function () {
          drawn(img.naturalWidth, img.naturalHeight, img);
          URL.revokeObjectURL(url);
          finish();
        };
        img.onerror = function () {
          URL.revokeObjectURL(url);
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
      // Called with one due packet at a time. While a decode runs only the newest waiting one is kept.
      submit: function (p) {
        if (busy) {
          if (pending) dropped++;
          pending = p;
        } else {
          decode(p);
        }
      },
      resync: function () {}
    };
  })();

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
    paused = false;
    const next = decoders[h.mode];
    if (decoder && decoder !== next) decoder.reset();
    decoder = next || null;
    if (decoder) {
      decoder.start(h);
      setStatus('Connected');
      showMessage(framesDrawn > 0 ? '' : 'Waiting for the picture…');
    } else {
      setStatus('Unsupported mode: ' + h.mode);
      showMessage('This page cannot show mode "' + h.mode + '".');
    }
    updateHint();
    updateStats();
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
    if (decoder && baseMs !== null) {
      const now = performance.now();
      const interval = frameIntervalMs();
      while (queue.length > 0) {
        const p = queue[0];
        if (p.type !== decoder.type) {
          // Leftover of a mode change.
          queue.shift();
          dropped++;
          continue;
        }
        const due = dueTime(p);
        if (due > now) break;
        queue.shift();
        // Drop only when genuinely late: more than one frame interval past due and a newer one is due too.
        if (decoder.droppable && now - due > interval && queue.length > 0 &&
            queue[0].type === p.type && dueTime(queue[0]) <= now) {
          dropped++;
          continue;
        }
        decoder.submit(p);
      }
    }
    const next = queue.length > 0 && baseMs !== null ? dueTime(queue[0]) - performance.now() : MAX_PUMP_MS;
    schedulePump(next);
  }

  function rafTick() {
    pump();
    window.requestAnimationFrame(rafTick);
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
      data: new Uint8Array(buf, HEADER)
    });
    while (queue.length > MAX_QUEUE) {
      queue.shift();
      dropped++;
    }
    if (queue.length === 1) schedulePump(dueTime(queue[0]) - performance.now());
  }

  function onText(text) {
    let m;
    try { m = JSON.parse(text); } catch (e) { return; }
    if (m.t === 'hello') {
      hello = m;
      chooseDecoder(m);
    } else if (m.t === 'denied') {
      setStatus('Denied: ' + m.reason);
      showMessage('Access denied (' + m.reason + ')');
    }
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
      clearTimeout(connectTimer);
      setStatus('Waiting for the stream…');
      send({ t: 'hello', v: 1, h264: false, pin: '' });
    };
    socket.onmessage = function (ev) {
      if (ws !== socket) return;
      if (typeof ev.data === 'string') onText(ev.data);
      else onBinary(ev.data);
    };
    socket.onclose = function () { onClosed(socket); };
    socket.onerror = function () { /* onclose follows */ };
  }

  function onClosed(socket) {
    if (ws !== socket) return;
    ws = null;
    clearTimeout(connectTimer);
    hello = null;
    paused = false;
    flushQueue();
    if (decoder) decoder.reset();
    setStatus('Reconnecting…');
    showMessage('Reconnecting…');
    scheduleReconnect();
  }

  function scheduleReconnect() {
    if (reconnectTimer === null) reconnectTimer = setTimeout(connect, RECONNECT_MS);
  }

  // ---- stats ----
  function updateStats() {
    const lines = [];
    lines.push('fps ' + fps.toFixed(1) + '   dropped ' + dropped + '   queue ' + queue.length);
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
    updateStats();
    send({
      t: 'stats',
      fps: Math.round(fps * 10) / 10,
      queue: queue.length,
      dropped: dropped,
      bufferMs: BUFFER_MS + offsetMs,
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

  window.addEventListener('resize', layout);
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      flushQueue();
      if (decoder) decoder.resync();
      send({ t: 'kf' });
    }
  });

  // ---- go ----
  setDelay(loadDelay());
  layout();
  setInterval(sendStats, STATS_INTERVAL_MS);
  schedulePump(0);
  window.requestAnimationFrame(rafTick);
  connect();
})();
