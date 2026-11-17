// Browser capability probe (M0.9). Plain script, no modules; no ?. , ??, or BigInt (old Chromium).
// Every check is wrapped so one failing API never stops the rest.
const results = {};
const statusEl = document.getElementById('status');
const tableEl = document.getElementById('results');
let ws = null;

function setStatus(text) {
  statusEl.textContent = text;
}

function fmt(v) {
  if (v === null) return 'null';
  if (v === undefined) return 'undefined';
  if (typeof v === 'object') {
    if (v.nodeType === undefined && !Array.isArray(v) && v['avc1.42E01F 1280x720'] !== undefined) {
      return Object.keys(v).map(function (k) { return k + ': ' + v[k]; }).join('\n');
    }
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }
  return String(v);
}

function render() {
  while (tableEl.firstChild) tableEl.removeChild(tableEl.firstChild);
  Object.keys(results).forEach(function (key) {
    const tr = document.createElement('tr');
    const th = document.createElement('th');
    const td = document.createElement('td');
    th.textContent = key;
    td.textContent = fmt(results[key]);
    td.style.whiteSpace = 'pre-wrap';
    if (results[key] === true) td.className = 'ok';
    if (results[key] === false) td.className = 'bad';
    tr.appendChild(th);
    tr.appendChild(td);
    tableEl.appendChild(tr);
  });
}

function set(key, value) {
  results[key] = value;
  render();
}

function check(key, fn) {
  try {
    set(key, fn());
  } catch (e) {
    set(key, 'ERROR: ' + e);
  }
}

async function checkAsync(key, fn) {
  try {
    set(key, await fn());
  } catch (e) {
    set(key, 'ERROR: ' + e);
  }
}

function sleep(ms) {
  return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function basicChecks() {
  check('userAgent', function () { return navigator.userAgent; });
  check('location.href', function () { return location.href; });
  check('isSecureContext', function () { return window.isSecureContext; });
  check('screen', function () { return screen.width + 'x' + screen.height; });
  check('window.inner', function () { return window.innerWidth + 'x' + window.innerHeight; });
  check('devicePixelRatio', function () { return window.devicePixelRatio; });
  check('has.WebSocket', function () { return 'WebSocket' in window; });
  check('has.VideoDecoder', function () { return 'VideoDecoder' in window; });
  check('has.AudioContext', function () { return 'AudioContext' in window || 'webkitAudioContext' in window; });
  check('has.AudioWorklet', function () { return 'AudioWorklet' in window; });
  check('has.createImageBitmap', function () { return 'createImageBitmap' in window; });
  check('has.OffscreenCanvas', function () { return 'OffscreenCanvas' in window; });
  check('has.MediaSource', function () { return 'MediaSource' in window; });
  check('has.RTCPeerConnection', function () { return 'RTCPeerConnection' in window; });
  check('has.localStorage', function () {
    localStorage.setItem('webmirrorProbe', '1');
    const ok = localStorage.getItem('webmirrorProbe') === '1';
    localStorage.removeItem('webmirrorProbe');
    return ok;
  });
  check('video.canPlayType(avc1.42E01F)', function () {
    const v = document.createElement('video');
    return v.canPlayType('video/mp4; codecs="avc1.42E01F"');
  });
}

async function codecChecks() {
  if (!('VideoDecoder' in window)) {
    set('VideoDecoder.isConfigSupported', 'n/a (no VideoDecoder)');
    return;
  }
  const codecs = [
    'avc1.42E01F', 'avc1.4D401F', 'avc1.640028',
    'hev1.1.6.L93.B0', 'hvc1.1.6.L93.B0', 'hev1.1.6.L120.B0',
    'vp09.00.31.08', 'av01.0.08M.08'
  ];
  const sizes = [[1280, 720], [1920, 1080]];
  const accels = [null, 'prefer-hardware'];
  const out = {};
  for (let i = 0; i < codecs.length; i++) {
    for (let j = 0; j < sizes.length; j++) {
      for (let k = 0; k < accels.length; k++) {
        // No-acceleration keys keep the old "codec WxH" form; hardware ones add " hw".
        const key = codecs[i] + ' ' + sizes[j][0] + 'x' + sizes[j][1] + (accels[k] ? ' hw' : '');
        try {
          const cfg = { codec: codecs[i], codedWidth: sizes[j][0], codedHeight: sizes[j][1] };
          if (accels[k]) cfg.hardwareAcceleration = accels[k];
          const r = await VideoDecoder.isConfigSupported(cfg);
          out[key] = r.supported;
        } catch (e) {
          out[key] = 'ERROR: ' + e;
        }
        set('VideoDecoder.isConfigSupported', out);
      }
    }
  }
}

async function canvasSpeed() {
  if (!window.requestAnimationFrame) {
    set('canvas.fps', 'n/a (no requestAnimationFrame)');
    return;
  }
  const canvas = document.getElementById('speed');
  const ctx = canvas.getContext('2d');
  const src = document.createElement('canvas');
  src.width = 1280;
  src.height = 720;
  const sctx = src.getContext('2d');
  const grad = sctx.createLinearGradient(0, 0, 1280, 720);
  grad.addColorStop(0, '#ff5722');
  grad.addColorStop(1, '#2196f3');
  sctx.fillStyle = grad;
  sctx.fillRect(0, 0, 1280, 720);
  let image = src;
  let usedBitmap = false;
  if ('createImageBitmap' in window) {
    try {
      image = await createImageBitmap(src);
      usedBitmap = true;
    } catch (e) {
      image = src;
    }
  }
  const result = await new Promise(function (resolve) {
    let frames = 0;
    const start = performance.now();
    function tick() {
      ctx.drawImage(image, 0, 0);
      frames++;
      const elapsed = performance.now() - start;
      if (elapsed >= 3000) {
        resolve({ fps: Math.round(frames * 10000 / elapsed) / 10, frames: frames, ms: Math.round(elapsed) });
      } else {
        window.requestAnimationFrame(tick);
      }
    }
    window.requestAnimationFrame(tick);
  });
  if (usedBitmap && image.close) image.close();
  result.source = usedBitmap ? 'ImageBitmap 1280x720' : 'canvas 1280x720';
  set('canvas.fps', result);
}

function connect() {
  return new Promise(function (resolve) {
    if (!('WebSocket' in window)) { resolve(null); return; }
    let done = false;
    let sock = null;
    function finish(v) {
      if (!done) { done = true; resolve(v); }
    }
    try {
      sock = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
      sock.binaryType = 'arraybuffer';
      sock.onopen = function () { finish(sock); };
      sock.onerror = function () { finish(null); };
      sock.onclose = function () { finish(null); };
    } catch (e) {
      finish(null);
    }
    setTimeout(function () { finish(null); }, 5000);
  });
}

let blastBytes = 0;
let blastDoneCb = null;

function onSocketMessage(ev) {
  if (typeof ev.data === 'string') {
    try {
      const m = JSON.parse(ev.data);
      if (m.t === 'blastDone' && blastDoneCb) blastDoneCb('complete');
      if (m.t === 'hello') set('phone.hello', m);
    } catch (e) { /* ignore */ }
  } else if (ev.data && ev.data.byteLength) {
    blastBytes += ev.data.byteLength;
  }
}

function blast(sock) {
  return new Promise(function (resolve) {
    let done = false;
    blastBytes = 0;
    const start = performance.now();
    function finish(note) {
      if (done) return;
      done = true;
      const ms = performance.now() - start;
      resolve({
        bytes: blastBytes,
        ms: Math.round(ms),
        mbit_per_s: Math.round(blastBytes * 8 / (ms / 1000) / 1000) / 1000,
        note: note
      });
    }
    blastDoneCb = finish;
    sock.send(JSON.stringify({ t: 'blast' }));
    setTimeout(function () { finish('timeout'); }, 20000);
  });
}

async function wsChecks() {
  const sock = await connect();
  if (!sock) {
    set('websocket', 'FAILED to connect to /ws');
    return null;
  }
  set('websocket', 'connected');
  ws = sock;
  sock.onmessage = onSocketMessage;
  sock.send(JSON.stringify({ t: 'hello', v: 1, h264: 'VideoDecoder' in window, pin: '' }));
  await sleep(300);
  try {
    set('websocket.throughput', await blast(sock));
  } catch (e) {
    set('websocket.throughput', 'ERROR: ' + e);
  }
  return sock;
}

function sendProbe() {
  if (!ws || ws.readyState !== 1) {
    setStatus('Done, but the WebSocket is not connected: results were NOT sent to the phone.');
    return;
  }
  try {
    ws.send(JSON.stringify({ t: 'probe', results: results }));
    setStatus('Done. Results shown below and sent to the phone.');
  } catch (e) {
    setStatus('Done, but sending failed: ' + e);
  }
}

function keepAlive() {
  setInterval(function () {
    try {
      if (ws && ws.readyState === 1) {
        ws.send(JSON.stringify({ t: 'stats', fps: 0, queue: 0, dropped: 0, bufferMs: 0, ua: navigator.userAgent }));
      }
    } catch (e) { /* ignore */ }
  }, 2000);
}

async function main() {
  setStatus('Running checks...');
  basicChecks();
  await checkAsync('codecs', async function () { await codecChecks(); return 'done'; });
  setStatus('Measuring canvas speed (3 s)...');
  await checkAsync('canvas', async function () { await canvasSpeed(); return 'done'; });
  setStatus('Connecting WebSocket and measuring throughput...');
  await checkAsync('ws', async function () { await wsChecks(); return 'done'; });
  const wrappers = ['codecs', 'canvas', 'ws'];
  for (let i = 0; i < wrappers.length; i++) {
    if (results[wrappers[i]] === 'done') delete results[wrappers[i]];
  }
  results.finishedAt = new Date().toISOString();
  render();
  sendProbe();
  keepAlive();
}

main();
