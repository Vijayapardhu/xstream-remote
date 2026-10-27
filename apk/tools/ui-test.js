// Headless UI flow test for the Xstream remote preview.
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const url = process.argv[2] || 'file:///C:/Users/PARDHU/airtel/apk/assets/ui/index.html?preview=1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function run() {
  const port = 9900 + Math.floor(Math.random() * 90);
  const profile = path.join(os.tmpdir(), 'xstream-ui-test-' + Date.now());
  const child = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars',
    '--remote-allow-origins=*', '--no-sandbox', '--no-first-run',
    '--no-default-browser-check', '--disable-extensions',
    '--disable-component-extensions-with-background-pages',
    '--disable-background-networking', '--disable-sync', '--mute-audio',
    '--remote-debugging-port=' + port, '--user-data-dir=' + profile,
    '--window-size=500,900', 'about:blank'
  ], { stdio: 'ignore' });
  let ws;
  try {
    let targets = null;
    for (let i = 0; i < 60; i++) {
      await sleep(200);
      try {
        targets = await new Promise((resolve, reject) => {
          const req = http.get('http://127.0.0.1:' + port + '/json', (res) => {
            let data = '';
            res.on('data', (c) => (data += c));
            res.on('end', () => resolve(JSON.parse(data)));
          });
          req.on('error', reject);
          req.setTimeout(1500, () => req.destroy(new Error('json timeout')));
        });
        if (targets && targets.length) break;
      } catch (e) {}
    }
    if (!targets) throw new Error('Chrome did not start');
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('No page target');

    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('ws timeout')), 10000);
      ws.onopen = () => { clearTimeout(timer); resolve(); };
      ws.onerror = () => { clearTimeout(timer); reject(new Error('ws failed')); };
      ws.onclose = () => { clearTimeout(timer); reject(new Error('ws closed')); };
    });

    let id = 0;
    const pending = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
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
    await sleep(3000);

    const suite = async function () {
      const results = [];
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const q = (sel) => document.querySelector(sel);
      const qa = (sel) => Array.from(document.querySelectorAll(sel));
      const click = (sel) => {
        const el = typeof sel === 'string' ? q(sel) : sel;
        if (!el) throw new Error('Missing element: ' + sel);
        el.click();
        return el;
      };
      const check = (name, condition, detail) => {
        results.push({ name: name, pass: !!condition, detail: condition ? '' : String(detail || '') });
      };
      const saved = () => {
        try { return JSON.parse(localStorage.getItem('xstream.config.v2')); }
        catch (e) { return null; }
      };
      const setSelect = (sel, value) => {
        const el = q(sel);
        if (!el) throw new Error('Missing select: ' + sel);
        el.value = value;
        el.dispatchEvent(new Event('change', { bubbles: true }));
      };
      const openCustomize = () => {
        click('#btnMore');
        click('[data-open-panel="customize"]');
      };
      const bridge = () => window.__bridgeCalls || {};
      const clearCalls = () => {
        const b = bridge();
        Object.keys(b).forEach((k) => { if (Array.isArray(b[k])) b[k].length = 0; });
      };
      const callsOf = (name) => (bridge()[name] || []).slice();
      const keyCalls = () => callsOf('key');
      const hapticCalls = () => callsOf('haptic');
      const pointOf = (el) => {
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      };
      const tapEl = (sel) => {
        const el = typeof sel === 'string' ? q(sel) : sel;
        if (!el) throw new Error('Missing tap target: ' + sel);
        const p = pointOf(el);
        const opts = { bubbles: true, cancelable: true, pointerId: 41, pointerType: 'touch', isPrimary: true, clientX: p.x, clientY: p.y };
        el.dispatchEvent(new PointerEvent('pointerdown', opts));
        el.dispatchEvent(new PointerEvent('pointerup', opts));
        return el;
      };
      const goRemote = () => {
        const back = qa('[data-panel-back]').find((el) => el.offsetParent !== null);
        if (back) { back.click(); return; }
        const done = q('#btnCustomDone');
        if (done && done.offsetParent !== null) { done.click(); return; }
      };
      const openPanel = (name) => {
        if (!q('#moreSheet') || q('#moreSheet').hidden) click('#btnMore');
        click('[data-open-panel="' + name + '"]');
      };
      const setApp = (name) => {
        window.__tv({ type: 'app', app: name });
      };
      const padPoint = (dx, dy) => {
        const r = q('#pad').getBoundingClientRect();
        return { x: r.left + r.width / 2 + (dx || 0), y: r.top + r.height / 2 + (dy || 0) };
      };
      const padEvent = (type, p, id) => q('#pad').dispatchEvent(new PointerEvent(type, {
        bubbles: true, cancelable: true, pointerId: id || 51, pointerType: 'touch', isPrimary: true,
        clientX: p.x, clientY: p.y
      }));
      window.confirm = function () { return true; };

      try {
        await sleep(1200);
        check('preview connects', q('#screenMain') && !q('#screenMain').hidden && q('#statusText').textContent === 'Connected');
        check('header and pad are present', !!q('.hdr .hdr-gear') && !!q('#pad') && qa('.seg').length === 4);
        check('default rail volumes render', q('#volNum').textContent !== '—');

        click('#btnMore');
        check('more sheet opens', !q('#moreSheet').hidden);
        check('sheet hint reflects gesture config', /Long-press: Menu/.test(q('#sheetHint').textContent));
        click('[data-open-panel="keys"]');
        check('keys panel opens', !q('#tab-keys').hidden && q('#moreSheet').hidden);
        click('#tab-keys [data-panel-back]');
        check('keys back returns to remote', !q('#tab-remote').hidden && !q('#screenMain').hidden);

        click('#btnMore');
        click('[data-open-panel="keyboard"]');
        check('keyboard panel opens', !q('#tab-keyboard').hidden);
        click('#tab-keyboard [data-panel-back]');

        click('#btnSettings');
        check('settings panel opens', !q('#tab-settings').hidden && !q('#screenMain').hidden);
        click('#tab-settings [data-panel-back]');
        check('settings back returns to remote', !q('#tab-remote').hidden);

        openCustomize();
        check('customize panel opens', !q('#tab-customize').hidden);
        click('[data-cust="accent"][data-accent="#3B82F6"]');
        check('accent swatch updates css', getComputedStyle(document.documentElement).getPropertyValue('--accent').trim().toUpperCase() === '#3B82F6');
        check('accent persists', saved().theme.accent === '#3B82F6');

        click('[data-cust="toggle-labels"]');
        check('labels can hide', document.body.classList.contains('hide-labels'));
        check('labels persist', saved().theme.labels === false);
        click('[data-cust="toggle-labels"]');
        check('labels can show', !document.body.classList.contains('hide-labels'));

        click('[data-cust="pad"][data-value="small"]');
        check('pad size persists', saved().layout.pad === 'small' && q('#tab-remote').style.getPropertyValue('--pad-max') === '230px');
        click('[data-cust="pad"][data-value="large"]');

        click('[data-cust="toggle-channel"]');
        check('channel rail can hide', q('#tab-remote').classList.contains('no-channel'));
        click('[data-cust="toggle-channel"]');

        click('[data-cust="tile-add"]');
        check('tile modal opens', !q('#tileModal').hidden);
        q('#tileLabel').value = 'YouTube';
        q('#tileIcon').value = 'circle-play';
        q('#tileIcon').dispatchEvent(new Event('change', { bubbles: true }));
        setSelect('#tileType', 'launch');
        check('tile type fields switch', !q('#tileUriWrap').hidden && q('#tileKeyWrap').hidden);
        q('#tileUri').value = 'https://www.youtube.com';
        q('#tileAccent').checked = true;
        click('#tileSave');
        await sleep(80);
        let cfg = saved();
        check('custom app tile saves', cfg && cfg.tiles.length === 4 && cfg.tiles[3].label === 'YouTube' && cfg.tiles[3].type === 'launch');
        check('custom app tile renders', qa('#tileRow .action-tile').some((el) => el.textContent.indexOf('YouTube') >= 0));

        click('[data-cust="macro-add"]');
        check('macro modal opens', !q('#macroModal').hidden);
        q('#macroName').value = 'Test macro';
        click('#macroAddKey');
        click('#macroAddWait');
        click('#macroAddApp');
        click('#macroSave');
        await sleep(80);
        cfg = saved();
        check('macro saves', cfg && cfg.macros.length === 1 && cfg.macros[0].name === 'Test macro' && cfg.macros[0].steps.length === 3);
        click('[data-cust="macro-test"][data-index="0"]');
        await sleep(30);
        check('macro test runs', /Testing Test macro|Running Test macro/.test(q('#toast').textContent));

        const macroId = cfg.macros[0].id;
        click('[data-cust="tile-add"]');
        q('#tileLabel').value = 'Movie night';
        setSelect('#tileType', 'macro');
        q('#tileMacro').value = macroId;
        click('#tileSave');
        await sleep(80);
        cfg = saved();
        check('macro tile saves', cfg && cfg.tiles.some((t) => t.type === 'macro' && t.value === macroId));
        const macroTile = qa('#tileRow .action-tile').find((el) => el.textContent.indexOf('Movie night') >= 0);
        check('macro tile renders', !!macroTile);
        if (macroTile) {
          macroTile.click();
          await sleep(30);
          check('macro tile runs', /Running Test macro/.test(q('#toast').textContent));
        }

        const appTile = qa('#tileRow .action-tile').find((el) => el.textContent.indexOf('YouTube') >= 0);
        check('app tile is present', !!appTile);
        if (appTile) appTile.click();
        await sleep(80);
        check('app-link tile launches', /Opening on the TV|Opening https:\/\/www\.youtube\.com|Launching https:\/\/www\.youtube\.com/.test(q('#toast').textContent), q('#toast').textContent);

        openCustomize();
        setSelect('[data-gesture="longPress"]', 'key:PLAY_PAUSE');
        check('gesture select persists', saved().gestures.longPress.value === 'PLAY_PAUSE');
        setSelect('[data-gesture="longPress"]', 'key:MENU');
        click('[data-cust="toggle-two-volume"]');
        check('two-finger volume can toggle off', saved().gestures.twoFingerVolume === false);
        click('[data-cust="toggle-two-volume"]');

        const exported = q('#custJson').value;
        check('config exports json', /"macros"/.test(exported) && JSON.parse(exported).version === 3);
        const imported = JSON.parse(exported);
        imported.theme.accent = '#2ED573';
        imported.tiles = imported.tiles.slice(0, 2);
        q('#custJson').value = JSON.stringify(imported);
        click('[data-cust="import"]');
        await sleep(60);
        check('config imports', getComputedStyle(document.documentElement).getPropertyValue('--accent').trim().toUpperCase() === '#2ED573' && saved().tiles.length === 2);
        click('[data-cust="reset"]');
        await sleep(60);
        cfg = saved();
        check('reset restores default', cfg && cfg.tiles.length === 3 && cfg.macros.length === 0 && cfg.theme.accent === '#FF3B47');

        click('[data-cust="pad"][data-value="large"]');
        click('#btnCustomDone');
        check('customize done returns home', !q('#tab-remote').hidden);

        cfg = saved();
        check('config is v3', cfg && cfg.version === 3);
        check('Power default is restored', cfg && cfg.tiles[0] && cfg.tiles[0].value === 'POWER' && cfg.tiles[0].icon === 'power');
        check('every-key grid has 22 keys', qa('#keyGrid .kt').length === 22);
        check('volume up can repeat', q('#tab-remote [data-key="VOLUME_UP"]').dataset.repeat === '1');

        window.__tv({ type: 'volume', level: 18, max: 25, muted: false });
        check('unmuted icon is correct', q('#muteIcon use').getAttribute('href') === '#i-volume-2');
        clearCalls();
        tapEl('#btnMute');
        await sleep(20);
        check('mute sends MUTE', keyCalls().join(',') === 'MUTE');
        check('mute gives one haptic', hapticCalls().length === 1 && hapticCalls()[0] === 'tap');
        check('mute icon changes', q('#muteIcon use').getAttribute('href') === '#i-volume-x' && q('#muteLabel').textContent === 'Unmute');
        clearCalls();
        tapEl('#btnMute');
        await sleep(20);
        check('unmute restores icon', q('#muteIcon use').getAttribute('href') === '#i-volume-2' && q('#muteLabel').textContent === 'Mute');

        setApp('com.airtel.tv');
        await sleep(30);
        check('live context actions', qa('#ctxKeys .ctxkey').map((el) => el.dataset.ctxKey).join(',') === 'GUIDE,MENU,CAPTIONS');
        clearCalls();
        qa('#ctxKeys .ctxkey').forEach((el) => el.click());
        await sleep(30);
        check('live context sends keys', keyCalls().join(',') === 'GUIDE,MENU,CAPTIONS');
        check('context keys give one haptic each', hapticCalls().length === 3);

        setApp('com.netflix.ninja');
        await sleep(30);
        check('media layout turns on', q('#tab-remote').classList.contains('media-app'));
        check('media context actions', qa('#ctxKeys .ctxkey').map((el) => el.dataset.ctxKey).join(',') === 'SEARCH,CAPTIONS,MENU');
        check('media rail is rewind/play/forward', qa('.media-rail [data-key]').map((el) => el.dataset.key).join(',') === 'REWIND,PLAY_PAUSE,FAST_FORWARD');
        check('channel rail hides for media', getComputedStyle(q('.ch-rail')).display === 'none');
        clearCalls();
        qa('#ctxKeys .ctxkey').forEach((el) => el.click());
        await sleep(30);
        check('media context sends keys', keyCalls().join(',') === 'SEARCH,CAPTIONS,MENU');
        check('media context haptics are not doubled', hapticCalls().length === 3);
        clearCalls();
        qa('.media-rail [data-key]').forEach((el) => tapEl(el));
        await sleep(30);
        check('media rail sends keys', keyCalls().join(',') === 'REWIND,PLAY_PAUSE,FAST_FORWARD');

        setApp('com.internet.tvbrowser');
        await sleep(30);
        check('other-app context actions', qa('#ctxKeys .ctxkey').map((el) => el.dataset.ctxKey).join(',') === 'APP_SWITCH,SEARCH,HOME');
        clearCalls();
        qa('#ctxKeys .ctxkey').forEach((el) => el.click());
        await sleep(30);
        check('other context sends keys', keyCalls().join(',') === 'APP_SWITCH,SEARCH,HOME');

        setApp('com.google.android.katniss');
        await sleep(30);
        check('Google app is named', q('#ctxAppName').textContent === 'Google');
        setApp('com.airtel.tv');
        await sleep(30);

        clearCalls();
        qa('#tileRow .action-tile').forEach((el) => el.click());
        await sleep(30);
        check('shortcut tiles send their keys', keyCalls().join(',') === 'POWER,TV,GUIDE');

        const padCenter = padPoint(0, 0);
        clearCalls();
        padEvent('pointerdown', padCenter);
        padEvent('pointerup', padCenter);
        await sleep(30);
        check('pad center sends CENTER', keyCalls().join(',') === 'CENTER' && hapticCalls().join(',') === 'ok');

        const padLeft = padPoint(-70, 0);
        clearCalls();
        padEvent('pointerdown', padLeft);
        padEvent('pointerup', padLeft);
        await sleep(30);
        check('pad left sends LEFT', keyCalls().join(',') === 'LEFT' && hapticCalls().join(',') === 'key');

        clearCalls();
        padEvent('pointerdown', padCenter);
        padEvent('pointermove', padPoint(70, 0));
        padEvent('pointerup', padPoint(70, 0));
        await sleep(30);
        check('pad swipe sends one direction', keyCalls().join(',') === 'RIGHT');

        clearCalls();
        padEvent('pointerdown', padCenter, 52);
        await sleep(620);
        padEvent('pointerup', padCenter, 52);
        await sleep(30);
        check('pad long press runs smart action', keyCalls().join(',') === 'MENU');
        check('pad long press gives long haptic', hapticCalls().indexOf('long') >= 0);

        click('#ctxNow');
        await sleep(30);
        check('now-on-TV opens apps', !q('#tab-apps').hidden);
        clearCalls();
        qa('.appcard').forEach((el) => el.click());
        await sleep(30);
        check('app cards launch working links', callsOf('launch').join('|') === [
          'https://www.youtube.com/tv',
          'https://www.netflix.com/browse',
          'https://www.hotstar.com',
          'https://app.primevideo.com/',
          'market://search?q=spotify',
          'market://details?id=com.android.vending'
        ].join('|'), callsOf('launch').join('|'));
        goRemote();

        openPanel('numbers');
        await sleep(30);
        check('numbers panel opens', !q('#tab-numbers').hidden);
        clearCalls();
        [q('#numPad [data-key="NUM_1"]'), q('#numPad [data-key="NUM_2"]'), q('#numPad [data-key="CENTER"]')].forEach(tapEl);
        await sleep(30);
        check('numbers send key codes', keyCalls().join(',') === 'NUM_1,NUM_2,CENTER');
        check('numbers have haptics', hapticCalls().length === 3);
        goRemote();

        openPanel('keyboard');
        await sleep(30);
        q('#txtInput').value = 'hello';
        clearCalls();
        click('#btnSendText');
        await sleep(30);
        check('keyboard sends text', callsOf('text').join('|') === 'hello');
        check('keyboard send has haptic', hapticCalls().indexOf('ok') >= 0);
        goRemote();

        click('#btnSettings');
        await sleep(30);
        check('settings panel opens with comfort', !q('#tab-settings').hidden && !!q('#setEasy') && !!q('#setHaptics') && !!q('#setMotion'));
        const easyBefore = saved().access.easy;
        click('#setEasy');
        check('comfort easy toggles', saved().access.easy === !easyBefore);
        click('#setEasy');
        const hapticsBefore = saved().access.haptics;
        click('#setHaptics');
        check('comfort haptics toggles', saved().access.haptics === !hapticsBefore);
        click('#setHaptics');
        const motionBefore = saved().access.motion;
        click('#setMotion');
        check('comfort motion toggles', saved().access.motion === !motionBefore);
        click('#setMotion');
        check('comfort settings restored', saved().access.easy === easyBefore && saved().access.haptics === hapticsBefore && saved().access.motion === motionBefore);
        goRemote();

        openPanel('keys');
        await sleep(30);
        clearCalls();
        qa('#keyGrid .kt').forEach((el) => tapEl(el));
        await sleep(30);
        check('every key in the grid is wired', keyCalls().join(',') === [
          'POWER', 'MUTE', 'GUIDE', 'INFO', 'TV', 'TV_INPUT', 'CHANNEL_UP', 'CHANNEL_DOWN',
          'PLAY_PAUSE', 'REWIND', 'FAST_FORWARD', 'STOP', 'PREVIOUS', 'NEXT', 'CAPTIONS', 'SEARCH',
          'MENU', 'ASSIST', 'SETTINGS', 'APP_SWITCH', 'SLEEP', 'WAKEUP'
        ].join(','), keyCalls().join(','));
        check('every key has a haptic', hapticCalls().length === 22, hapticCalls().length);
        goRemote();

        window.__tv({ type: 'volume', level: 18, max: 25, muted: false });
        const before = q('#volNum').textContent;
        const upBtn = q('#tab-remote [data-key="VOLUME_UP"]');
        upBtn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, pointerId: 12, pointerType: 'touch' }));
        upBtn.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true, pointerId: 12, pointerType: 'touch' }));
        await sleep(30);
        check('volume rail updates', q('#volNum').textContent !== before);

        const pad = q('#pad');
        const rect = pad.getBoundingClientRect();
        const pointerOpts = {
          bubbles: true, cancelable: true, pointerId: 11, pointerType: 'touch', isPrimary: true,
          clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2
        };
        pad.dispatchEvent(new PointerEvent('pointerdown', pointerOpts));
        await sleep(30);
        check('pad center press lights', q('#okBtn').classList.contains('on'));
        pad.dispatchEvent(new PointerEvent('pointerup', pointerOpts));
        await sleep(180);
        check('pad center press clears', !q('#okBtn').classList.contains('on'));

        click('#btnSettings');
        click('#btnDisconnect');
        await sleep(60);
        check('disconnect returns to connect screen', !q('#screenConnect').hidden && q('#screenMain').hidden);
        click('#btnSettings');
        check('settings can open from connect screen', !q('#tab-settings').hidden && !q('#screenMain').hidden);
        click('#tab-settings [data-panel-back]');
        check('back returns to connect when idle', !q('#screenConnect').hidden && q('#screenMain').hidden);
        click('#btnManual');
        document.querySelector('#manualIp').value = '192.168.1.10';
        document.querySelector('#manualForm').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        await sleep(1300);
        check('reconnect restores remote', !q('#screenMain').hidden && q('#statusText').textContent === 'Connected');

        return results;
      } catch (e) {
        results.push({ name: 'suite aborted', pass: false, detail: e && e.stack || String(e) });
        return results;
      }
    };

    const evaluated = await send('Runtime.evaluate', {
      expression: '(' + suite.toString() + ')()',
      awaitPromise: true, returnByValue: true, userGesture: true
    });
    if (evaluated.result && evaluated.result.exceptionDetails) {
      console.error(JSON.stringify(evaluated.result.exceptionDetails, null, 2));
      process.exitCode = 1;
      return;
    }
    const results = evaluated.result.result.value || [];
    let failed = 0;
    for (const r of results) {
      console.log((r.pass ? 'PASS ' : 'FAIL ') + r.name + (r.detail ? ' — ' + r.detail : ''));
      if (!r.pass) failed++;
    }
    console.log('\n' + (results.length - failed) + ' passed, ' + failed + ' failed');
    if (failed) process.exitCode = 1;
  } finally {
    try { if (ws) ws.close(); } catch (e) {}
    try { child.kill(); } catch (e) {}
  }
}

run().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
