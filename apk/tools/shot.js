// Mobile-width screenshot via CDP (headless Chrome clamps windows to 500px).
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const url = process.argv[2];
const out = process.argv[3];
const wait = Number(process.argv[4] || 2600);
const evalScript = process.argv[5] || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function captureOnce() {
  const port = 9300 + Math.floor(Math.random() * 600);
  const profile = path.join(os.tmpdir(), 'xstream-remote-chrome-shot-' + Date.now() + '-' + port);
  const child = spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--remote-allow-origins=*',
    '--no-sandbox',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-component-extensions-with-background-pages',
    '--disable-background-networking',
    '--disable-sync',
    '--mute-audio',
    '--remote-debugging-port=' + port,
    '--user-data-dir=' + profile,
    '--window-size=500,900',
    'about:blank'
  ], { stdio: 'ignore' });

  let ws = null;
  try {
    let targets = null;
    for (let i = 0; i < 60; i++) {
      await sleep(200);
      try {
        targets = await new Promise((resolve, reject) => {
          const req = http.get('http://127.0.0.1:' + port + '/json', (res) => {
            let data = '';
            res.on('data', (chunk) => (data += chunk));
            res.on('end', () => resolve(JSON.parse(data)));
          });
          req.on('error', reject);
          req.setTimeout(1500, () => req.destroy(new Error('json timeout')));
        });
        if (targets && targets.length) break;
      } catch (e) { /* not up yet */ }
    }
    if (!targets) throw new Error('Chrome DevTools did not start');
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('Chrome has no page target');

    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('DevTools WebSocket timeout')), 10000);
      ws.onopen = () => { clearTimeout(timer); resolve(); };
      ws.onerror = () => { clearTimeout(timer); reject(new Error('DevTools WebSocket failed')); };
      ws.onclose = () => { clearTimeout(timer); reject(new Error('DevTools WebSocket closed')); };
    });

    let id = 0;
    const pending = new Map();
    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && pending.has(msg.id)) {
        pending.get(msg.id)(msg);
        pending.delete(msg.id);
      }
    };
    const send = (method, params) => new Promise((resolve, reject) => {
      const myId = ++id;
      const timer = setTimeout(() => {
        pending.delete(myId);
        reject(new Error('CDP timeout: ' + method));
      }, 20000);
      pending.set(myId, (msg) => { clearTimeout(timer); resolve(msg); });
      ws.send(JSON.stringify({ id: myId, method, params }));
    });

    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', {
      width: 390, height: 844, deviceScaleFactor: 2, mobile: true,
      screenWidth: 390, screenHeight: 844
    });
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Page.navigate', { url });
    await sleep(wait);

    if (evalScript) {
      const evaluated = await send('Runtime.evaluate', {
        expression: evalScript, awaitPromise: true, returnByValue: true, userGesture: true
      });
      if (evaluated.result && evaluated.result.exceptionDetails) {
        console.error('page eval failed:', evaluated.result.exceptionDetails.text || 'error');
      }
      await sleep(450);
    }

    const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    if (!shot.result || !shot.result.data) throw new Error('No screenshot data');
    fs.writeFileSync(out, Buffer.from(shot.result.data, 'base64'));
  } finally {
    try { if (ws) ws.close(); } catch (e) {}
    try { child.kill(); } catch (e) {}
  }
}

(async function () {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await captureOnce();
      console.log('wrote ' + out);
      setTimeout(() => process.exit(0), 150);
      return;
    } catch (e) {
      lastError = e;
      if (attempt < 2) console.error('screenshot attempt ' + (attempt + 1) + ' failed, retrying');
      await sleep(300);
    }
  }
  console.error(lastError && lastError.message || lastError);
  process.exit(1);
})();
