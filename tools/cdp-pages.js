/**
 * 前端页面矩阵基线测试（方案 §4.3）—— 13 路由 CDP 无头 Chrome
 * 用法: node tools/cdp-pages.js
 * 断言：路由渲染 + 关键元素存在 + JS 无异常 + 需登录页匿名访问跳转
 */
const { spawn } = require('child_process');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT = 9230;
const BASE = 'http://localhost:8080';
const PROFILE = 'C:/tmp/chrome-cdp-pages-' + Date.now();
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let JS_ERRORS = 0;
const results = [];
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { results.push({ name, ok }); ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

const PAGES = [
    { route: '/', name: 'home', sel: '.search-bar', auth: false },
    { route: '/shops/1?name=' + encodeURIComponent('美食'), name: 'shop-list', sel: '.shop-list-page', auth: false },
    { route: '/shop/1', name: 'shop-detail', sel: '.shop-detail__info', auth: false },
    { route: '/search?kw=' + encodeURIComponent('美发'), name: 'search', sel: '.search-result__head', auth: false },
    { route: '/flash', name: 'flash', sel: '.flash-page__title', auth: false },
    { route: '/shops/nearby', name: 'nearby', sel: '.nearby-page', auth: false },
    { route: '/coupons', name: 'coupons', sel: '.coupons-page__tabs', auth: false },
    { route: '/post/7', name: 'post-detail', sel: '.post-detail__author', auth: false },
    { route: '/user/1', name: 'user-profile', sel: '.profile__header', auth: false },
    { route: '/login', name: 'login', sel: '.login__input', auth: false },
    // 需登录页（匿名访问应跳转 /login，出现登录输入框即通过）
    { route: '/post-edit', name: 'post-edit', sel: '.login__input', auth: true },
    { route: '/profile', name: 'profile', sel: '.login__input', auth: true },
    { route: '/chat', name: 'chat', sel: '.login__input', auth: true },
];

async function main() {
    const proc = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--remote-debugging-port=' + PORT, '--user-data-dir=' + PROFILE, '--window-size=390,844', BASE + '/#/'],
        { stdio: 'ignore' });

    let tabs;
    for (let i = 0; i < 40; i++) { try { tabs = await (await fetch('http://localhost:' + PORT + '/json')).json(); if (tabs.length) break; } catch {} await sleep(400); }
    const page = tabs.find(t => t.type === 'page');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    let id = 0; const pending = new Map();
    ws.onmessage = e => {
        const m = JSON.parse(e.data);
        if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); }
        else if (m.method === 'Runtime.exceptionThrown') { JS_ERRORS++; }
        else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            const t = (m.params.args || []).map(a => a.value || a.description || '').join(' ');
            if (!/favicon|404/i.test(t)) JS_ERRORS++;
        }
    };
    const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
    const ev = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); return r.result.value; };
    const wait = async (expr, t = 6000) => { const t0 = Date.now(); while (Date.now() - t0 < t) { if (await ev(expr)) return true; await sleep(200); } return false; };

    await send('Runtime.enable');
    await wait(`!!document.querySelector('.search-bar')`, 8000);

    for (const p of PAGES) {
        const before = JS_ERRORS;
        await ev(`location.hash = '#${p.route}'`);
        const rendered = await wait(`!!document.querySelector('${p.sel}')`, 6000);
        const pageErr = JS_ERRORS > before;
        const hashOk = await ev(`location.hash.startsWith('#${p.route.split('?')[0]}') || location.hash.includes('/login')`);
        let detail = '';
        if (!rendered) detail = '关键元素未出现';
        if (pageErr) detail += (detail ? '；' : '') + 'JS错误';
        report(`[${p.name}] ${p.route}`, rendered && !pageErr, detail || (p.auth ? '匿名→login' : 'ok'));
    }

    // 需登录页回跳校验
    await ev(`location.hash = '#/profile'`);
    await sleep(800);
    const redirected = await ev(`location.hash.includes('/login')`);
    report('需登录页回跳 /login（/profile）', redirected, await ev(`location.hash`));

    console.log('\n════════ 汇总 ════════');
    console.log(`通过 ${pass} / ${results.length}`);
    console.log('JS_ERRORS 总计 =', JS_ERRORS);
    ws.close(); proc.kill();
    try { require('fs').rmSync(PROFILE, { recursive: true, force: true }); } catch {}
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
