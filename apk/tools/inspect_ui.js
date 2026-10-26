// Layout probe: launches headless Chrome with CDP, dumps element geometry.
const { spawn } = require('child_process');
const http = require('http');
const os = require('os');
const path = require('path');

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const url = process.argv[2];
const port = 9333;

const child = spawn(CHROME, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--remote-debugging-port=' + port,
  '--user-data-dir=' + path.join(os.tmpdir(), 'xstream-remote-chrome-ui-' + process.pid),
  '--window-size=390,844',
  url
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  let targets = null;
  for (let i = 0; i < 40; i++) {
    await sleep(250);
    try {
      targets = await new Promise((resolve, reject) => {
        http.get('http://127.0.0.1:' + port + '/json', (res) => {
          let data = '';
          res.on('data', (chunk) => (data += chunk));
          res.on('end', () => resolve(JSON.parse(data)));
        }).on('error', reject);
      });
      if (targets && targets.length) break;
    } catch (e) { /* not up yet */ }
  }
  const page = targets.find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve) => (ws.onopen = resolve));

  let id = 0;
  const pending = new Map();
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  };
  const send = (method, params) => new Promise((resolve) => {
    const myId = ++id;
    pending.set(myId, resolve);
    ws.send(JSON.stringify({ id: myId, method, params }));
  });

  await sleep(2500);

  const expression = `(() => {
    const pill = document.querySelector('#statusPill');
    const matched = [];
    for (const sheet of document.styleSheets) {
      let rules;
      try { rules = sheet.cssRules; } catch (e) { continue; }
      for (const rule of rules) {
        if (rule.selectorText && pill.matches(rule.selectorText)) {
          matched.push(rule.selectorText + ' { ' + rule.style.cssText + ' }');
        }
        if (rule.cssRules) {
          for (const inner of rule.cssRules) {
            if (inner.selectorText && pill.matches(inner.selectorText)) {
              matched.push('@media ' + rule.conditionText + ' :: ' + inner.selectorText + ' { ' + inner.style.cssText + ' }');
            }
          }
        }
      }
    }
    return JSON.stringify({
      innerWidth, innerHeight,
      pillHTML: pill.outerHTML,
      parent: pill.parentElement.tagName + '.' + pill.parentElement.className,
      grandparent: pill.parentElement.parentElement.tagName + '.' + pill.parentElement.parentElement.className,
      matched,
      styleAttr: pill.getAttribute('style')
    }, null, 1);
  })()`;

  const res = await send('Runtime.evaluate', { expression, returnByValue: true });
  console.log(res.result.result.value || JSON.stringify(res));
  ws.close();
  child.kill();
}

main().catch((e) => { console.error(e); child.kill(); process.exit(1); });
