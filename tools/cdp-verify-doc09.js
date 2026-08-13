/**
 * CampusDeal Doc09 前后端联调验证脚本
 * 启动 headless Chrome → CDP WebSocket → 断言首页搜索区/金刚区/宫格 + 4 个子页面
 * 用法: node verify-frontend.js
 */
const { spawn } = require('child_process');
const fs = require('fs');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const DEBUG_PORT = 9223;
const BASE = 'http://localhost:8080';
const PROFILE = 'C:/tmp/chrome-cdp-verify-' + Date.now();

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
let JS_ERRORS = 0;
let consoleErrors = [];

function report(name, pass, detail) {
    results.push({ name, pass, detail });
    console.log((pass ? '  ✅ ' : '  ❌ ') + name + (detail ? ' — ' + detail : ''));
}

async function getJson(url) {
    const res = await fetch(url);
    return res.json();
}

async function connect(wsUrl) {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    const pending = new Map();
    const ready = new Promise((resolve, reject) => {
        ws.onopen = resolve;
        ws.onerror = reject;
    });
    await ready;
    const send = (method, params = {}) => new Promise((resolve, reject) => {
        const mid = ++id;
        pending.set(mid, { resolve, reject });
        ws.send(JSON.stringify({ id: mid, method, params }));
    });
    // 监听异常
    ws.onmessage = (e) => {
        const msg = JSON.parse(e.data);
        if (msg.id && pending.has(msg.id)) {
            const p = pending.get(msg.id);
            pending.delete(msg.id);
            if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
            else p.resolve(msg.result);
        } else if (msg.method === 'Runtime.exceptionThrown') {
            JS_ERRORS++;
            const d = msg.params.exceptionDetails;
            consoleErrors.push('exception: ' + (d.text || '') + ' ' + (d.exception ? d.exception.description : ''));
        } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
            const args = (msg.params.args || []).map(a => a.value || a.description || '').join(' ');
            if (!/favicon|404/i.test(args)) { JS_ERRORS++; consoleErrors.push('console.error: ' + args); }
        }
    };
    return { ws, send };
}

async function main() {
    const proc = spawn(CHROME, [
        '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--remote-debugging-port=' + DEBUG_PORT, '--user-data-dir=' + PROFILE,
        '--window-size=390,844',
        BASE + '/',
    ], { stdio: 'ignore' });

    // 等待调试端口
    let tabs;
    for (let i = 0; i < 40; i++) {
        try {
            tabs = await getJson('http://localhost:' + DEBUG_PORT + '/json');
            if (tabs.length) break;
        } catch {}
        await sleep(500);
    }
    if (!tabs || !tabs.length) {
        console.error('Chrome 调试端口未就绪');
        process.exit(1);
    }
    const page = tabs.find(t => t.type === 'page');
    const { ws, send } = await connect(page.webSocketDebuggerUrl);

    await send('Runtime.enable');
    await send('Page.enable');

    const evaluate = async (expr) => {
        const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
        if (r.exceptionDetails) {
            return { error: (r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text };
        }
        return r.result.value;
    };
    const waitFor = async (expr, timeout = 8000) => {
        const t0 = Date.now();
        while (Date.now() - t0 < timeout) {
            const v = await evaluate(expr);
            if (v === true) return true;
            await sleep(250);
        }
        return false;
    };

    console.log('\n══ 1. 首页渲染 ══\n');
    const appReady = await waitFor(`document.querySelector('.app-loading') === null && document.querySelector('#app-inner') !== null`);
    report('SPA 挂载完成', appReady);
    // 等待首页内容
    await waitFor(`!!document.querySelector('.search-bar')`);

    // 第一排
    report('搜索栏存在', await evaluate(`!!document.querySelector('.search-bar')`));
    const cityName = await evaluate(`(document.querySelector('.search-bar__city')||{}).textContent||''`);
    report('校区选择器显示校名', /校本部/.test(cityName), cityName.trim());
    // 搜索输入框 enabled（可聚焦/可输入）
    const inputEnabled = await evaluate(`(() => {
        const el = document.querySelector('.search-bar input');
        if (!el) return false;
        return !el.disabled && !el.readOnly;
    })()`);
    report('搜索输入框可用', inputEnabled);
    // 第一排右侧仅个人中心
    const profileBtn = await evaluate(`!!document.querySelector('.search-bar a[href="#/profile"], .search-bar [data-role="profile"]')`);
    report('第一排右侧含个人中心入口', profileBtn);

    // 第二排金刚区
    const quickCount = await evaluate(`document.querySelectorAll('.quick-nav__item').length`);
    report('金刚区 5 个入口', quickCount === 5, 'count=' + quickCount);
    const quickLabels = await evaluate(`[...document.querySelectorAll('.quick-nav__label')].map(e => e.textContent.trim()).join(',')`);
    report('金刚区标签', /抢购/.test(quickLabels) && /附近/.test(quickLabels) && /卡券/.test(quickLabels) && /动态/.test(quickLabels) && /签到/.test(quickLabels), quickLabels);

    // 抢购入口脉冲点（常驻）
    const pulse = await evaluate(`!!document.querySelector('.quick-nav__pulse')`);
    report('抢购入口脉冲点', pulse);

    // 第三排分类宫格
    const gridCount = await evaluate(`document.querySelectorAll('.campus-grid__item').length`);
    report('分类宫格 ≥5+更多', gridCount >= 6, 'count=' + gridCount);

    // 搜索面板：真实点击路径（包裹层 click → ref.focus → @focus 打开）
    const panelOpened = await evaluate(`(async () => {
        const wrap = document.querySelector('.search-bar__input');
        if (!wrap) return false;
        wrap.click();
        await new Promise(r => setTimeout(r, 400));
        return !!document.querySelector('.search-panel');
    })()`);
    report('搜索面板弹出', panelOpened);
    const hotTag = await evaluate(`document.querySelector('.search-panel__tag') ? document.querySelector('.search-panel__tag').textContent.trim() : ''`);
    report('搜索面板含热词', hotTag !== '', 'first=' + hotTag);

    console.log('\n══ 2. 子页面导航 ══\n');

    // /flash
    await evaluate(`location.hash = '#/flash'`);
    const flashReady = await waitFor(`!!document.querySelector('.flash-page__title')`);
    report('/flash 渲染', flashReady);
    const dealCount = await evaluate(`document.querySelectorAll('.flash-page__item').length`);
    report('/flash 加载闪购券', dealCount > 0, 'deals=' + dealCount);

    // /shops/nearby
    await evaluate(`location.hash = '#/shops/nearby'`);
    const nearbyReady = await waitFor(`!!document.querySelector('.nearby-page')`);
    report('/shops/nearby 渲染', nearbyReady);
    await waitFor(`document.querySelectorAll('.nearby-page .merchant-card, .nearby-page [class*=merchant-card]').length > 0`, 6000);
    const nearbyCards = await evaluate(`document.querySelectorAll('.nearby-page .merchant-card, .nearby-page [class*=merchant-card]').length`);
    report('/shops/nearby 加载商户', nearbyCards > 0, 'cards=' + nearbyCards);

    // /coupons
    await evaluate(`location.hash = '#/coupons'`);
    const couponsReady = await waitFor(`!!document.querySelector('.coupons-page')`);
    report('/coupons 渲染', couponsReady);
    await waitFor(`document.querySelectorAll('.coupons-page__item').length > 0`, 6000);
    const couponCount = await evaluate(`document.querySelectorAll('.coupons-page__item').length`);
    report('/coupons 加载券', couponCount > 0, 'items=' + couponCount);
    // 闪购 tab
    await evaluate(`document.querySelectorAll('.coupons-page__tab')[2].click()`);
    await sleep(400);
    const flashOnly = await evaluate(`[...document.querySelectorAll('.coupons-page__item')].length`);
    report('/coupons 闪购tab过滤', flashOnly > 0, 'flashItems=' + flashOnly);

    // /search — 分类直达（「美发」→「丽人·美发」）
    await evaluate(`location.hash = '#/search?kw=' + encodeURIComponent('美发')`);
    const searchReady = await waitFor(`!!document.querySelector('.search-result__head')`);
    report('/search 渲染', searchReady);
    await waitFor(`!!document.querySelector('.search-result__type')`, 5000);
    const typeMatchName = await evaluate(`document.querySelector('.search-result__type-name') ? document.querySelector('.search-result__type-name').textContent.trim() : ''`);
    report('/search 分类直达', typeMatchName === '丽人·美发', 'type=' + typeMatchName);

    // 无结果兜底 → 智能助手按钮（「奶茶」无商户/分类命中）
    await evaluate(`location.hash = '#/search?kw=' + encodeURIComponent('奶茶')`);
    await waitFor(`!!document.querySelector('.search-result__empty-actions')`, 6000);
    const askBtn = await evaluate(`document.querySelector('.search-result__empty-actions button') ? document.querySelector('.search-result__empty-actions button').textContent.trim() : ''`);
    report('/search 空态含智能助手入口', /智能助手/.test(askBtn), askBtn);

    console.log('\n══ 3. JS 错误统计 ══\n');
    report('JS 无异常', JS_ERRORS === 0, JS_ERRORS === 0 ? '' : (consoleErrors.slice(0, 5).join(' | ') || 'unknown'));

    // 汇总
    console.log('\n════════ 汇总 ════════');
    const passed = results.filter(r => r.pass).length;
    console.log(`通过 ${passed}/${results.length}`);
    if (JS_ERRORS) console.log(`⚠️ JS 错误 ${JS_ERRORS} 条`);
    ws.close();
    proc.kill();
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
    process.exit(JS_ERRORS ? 1 : (passed === results.length ? 0 : 2));
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
