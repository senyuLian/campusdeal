/**
 * 后端接口冒烟 + 鉴权基线测试（方案 §4.2）
 * 用法: node tools/api-smoke.js
 * 覆盖 36 接口：公开=200/需登录=401(无token)/需登录=200(有token)
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
const results = [];
let pass = 0, fail = 0;

// 直连 Redis（RESP 协议），避免依赖 redis-cli 路径
function redisRaw(commands) {
    return new Promise((resolve, reject) => {
        const sock = net.connect(6379, '127.0.0.1', () => {
            let buf = '';
            for (const c of commands) {
                const parts = c.map(String);
                buf += '*' + parts.length + '\r\n';
                for (const p of parts) buf += '$' + Buffer.byteLength(p) + '\r\n' + p + '\r\n';
            }
            sock.write(buf);
        });
        let data = '';
        sock.on('data', d => { data += d.toString(); });
        sock.on('end', () => resolve(data));
        sock.on('error', reject);
        setTimeout(() => { sock.destroy(); resolve(data); }, 2000);
    });
}
function redis(cmd) {
    const args = cmd.trim().split(/\s+/);
    return redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], args]).then(parseBulk);
}
function parseBulk(data) {
    const re = /\$(\d+)\r\n([\s\S]*?)\r\n/;
    const m = data.match(re);
    return m ? m[2] : null;
}

function report(name, ok, detail = '') {
    results.push({ name, ok, detail });
    ok ? pass++ : fail++;
    console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : ''));
}

async function req(method, path, { token, body, query, headers = {} } = {}) {
    let url = BASE + path;
    if (query) {
        const qs = Object.entries(query).filter(([, v]) => v != null && v !== '')
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
        if (qs) url += '?' + qs;
    }
    const h = { ...headers };
    if (body) h['Content-Type'] = 'application/json';
    if (token) h['Authorization'] = token;
    const res = await fetch(url, {
        method,
        headers: h,
        body: body ? JSON.stringify(body) : undefined,
    });
    let json = null;
    try { json = await res.json(); } catch { /* 非 JSON（如 SSE） */ }
    return { status: res.status, json };
}

async function login() {
    await req('POST', '/user/code', { query: { phone: PHONE } });
    const code = await redis(`GET login:code:${PHONE}`);
    const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
    return r.json && r.json.data;
}

// 断言：无 token 应 401
async function expect401(name, method, path) {
    const r = await req(method, path, {});
    report(name + ' 无token=401', r.status === 401, 'got ' + r.status);
}

// 断言：公开接口应 success
async function expectPublic(name, method, path, opts = {}) {
    const r = await req(method, path, opts);
    report(name, r.json && r.json.success === true, r.status + ' / ' + JSON.stringify(r.json).slice(0, 80));
}

// 断言：需登录接口带 token 应 success（或不报 401）
async function expectAuthed(name, method, path, token, opts = {}) {
    const r = await req(method, path, { ...opts, token });
    const ok = r.status !== 401 && r.json && r.json.success === true;
    report(name, ok, r.status + ' / ' + JSON.stringify(r.json).slice(0, 80));
}

async function main() {
    console.log('══ 0. 登录 ══');
    const token = await login();
    report('获取登录 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    console.log('\n══ 1. User 模块 ══');
    await expectPublic('POST /user/code', 'POST', '/user/code', { query: { phone: PHONE } });
    // login 已在上面测过
    await expect401('POST /user/logout', 'POST', '/user/logout');
    await expect401('GET /user/me', 'GET', '/user/me');
    await expectAuthed('GET /user/me (auth)', 'GET', '/user/me', token);
    await expectAuthed('GET /user/info/{id} (auth)', 'GET', '/user/info/1', token);
    await expect401('GET /user/{id}', 'GET', '/user/1');
    await expectPublic('GET /user/public/{id}', 'GET', '/user/public/1');
    await expect401('POST /user/sign', 'POST', '/user/sign');
    await expectAuthed('POST /user/sign (auth)', 'POST', '/user/sign', token);
    await expect401('GET /user/sign/count', 'GET', '/user/sign/count');
    await expectAuthed('GET /user/sign/count (auth)', 'GET', '/user/sign/count', token);

    console.log('\n══ 2. Merchant 模块 ══');
    await expectPublic('GET /merchant/{id}', 'GET', '/merchant/1');
    // P0-4 回归：POST/PUT /merchant 应需登录（公开路径，Controller 内自校验）。
    // 注意必须携带合法 JSON body：空 body 会先触发 @RequestBody 解析异常（被全局兜底成 200），
    // 走不到 Controller 内的登录校验，导致误报"公开=缺陷"。
    const rPostMerchant = await req('POST', '/merchant', { body: { name: 'SmokeTestShop', typeId: 1 } });
    report('POST /merchant 应需登录(P0-4)', rPostMerchant.status === 401, '当前=' + rPostMerchant.status + '（有body无token，期望401）');
    const rPutMerchant = await req('PUT', '/merchant', { body: { name: 'SmokeTestShop', id: 1 } });
    report('PUT /merchant 应需登录(P0-4)', rPutMerchant.status === 401, '当前=' + rPutMerchant.status + '（有body无token，期望401）');
    await expectPublic('GET /merchant/of/type', 'GET', '/merchant/of/type', { query: { typeId: 1, current: 1 } });
    await expectPublic('GET /merchant/nearby', 'GET', '/merchant/nearby', { query: { x: 120.149993, y: 30.334229, current: 1 } });
    await expectPublic('GET /merchant/of/name', 'GET', '/merchant/of/name', { query: { name: '奶茶', current: 1 } });
    await expectPublic('GET /merchant-type/list', 'GET', '/merchant-type/list');

    console.log('\n══ 3. Coupon / 秒杀 ══');
    await expectPublic('GET /coupon/list/{shopId}', 'GET', '/coupon/list/1');
    await expectPublic('GET /coupon/flash/list', 'GET', '/coupon/flash/list');
    await expectPublic('GET /coupon/list/all', 'GET', '/coupon/list/all');
    await expect401('POST /coupon-order/seckill/{id}', 'POST', '/coupon-order/seckill/10');
    // 秒杀 happy path（P0-1 缺陷：Kafka 未运行 → 预期 500/服务器异常）。
    // 幂等考虑：重复运行（同用户同 deal）会命中 Lua Set → "Already purchased"，
    // 这也证明鉴权+下单链路可用；成功 或 重复购买 均视为通过。
    const t0 = Date.now();
    const rSeckill = await req('POST', '/coupon-order/seckill/10', { token });
    const seckillMs = Date.now() - t0;
    const seckillOk = (rSeckill.json && rSeckill.json.success === true)
        || (rSeckill.json && rSeckill.json.errorMsg === 'Already purchased');
    report('POST /coupon-order/seckill (auth)', seckillOk, rSeckill.status + ' / ' + JSON.stringify(rSeckill.json).slice(0, 80) + ` / ${seckillMs}ms`);
    if (!seckillOk) console.log('        ⚠️ 秒杀失败原因（P0-1 预期：Kafka 阻塞5s→500）耗时=' + seckillMs + 'ms');

    console.log('\n══ 4. Follow 模块 ══');
    await expect401('PUT /follow/{id}/{isFollow}', 'PUT', '/follow/1/1');
    await expect401('GET /follow/or/not/{id}', 'GET', '/follow/or/not/1');
    await expect401('GET /follow/common/{id}', 'GET', '/follow/common/1');
    await expectAuthed('GET /follow/or/not/{id} (auth)', 'GET', '/follow/or/not/1', token);

    console.log('\n══ 5. Post 模块 ══');
    await expectPublic('GET /post/hot', 'GET', '/post/hot', { query: { current: 1 } });
    await expectPublic('GET /post/{id}', 'GET', '/post/7');
    await expectPublic('GET /post/likes/{id}', 'GET', '/post/likes/7');
    await expectPublic('GET /post/of/user', 'GET', '/post/of/user', { query: { id: 1, current: 1 } });
    await expect401('GET /post/of/me', 'GET', '/post/of/me');
    await expectAuthed('GET /post/of/me (auth)', 'GET', '/post/of/me', token);
    await expect401('GET /post/of/follow', 'GET', '/post/of/follow');
    await expect401('POST /post', 'POST', '/post');
    await expect401('PUT /post/like/{id}', 'PUT', '/post/like/1');

    console.log('\n══ 6. Upload / Agent ══');
    // 上传需 multipart，仅测鉴权（公开；无文件应报错但非401）
    const rUpload = await req('POST', '/upload/post', {});
    report('POST /upload/post (公开，无文件应报错非401)', rUpload.status !== 401, 'got ' + rUpload.status);
    await expect401('GET /agent/history/{sessionId}', 'GET', '/agent/history/test-session');
    await expect401('POST /agent/confirm', 'POST', '/agent/confirm');
    // SSE 专项在阶段D 全链路测

    console.log('\n════════ 汇总 ════════');
    console.log(`通过 ${pass} / ${results.length}`);
    const failed = results.filter(r => !r.ok);
    if (failed.length) {
        console.log('\n失败项：');
        failed.forEach(f => console.log('  ❌ ' + f.name + ' — ' + f.detail));
    }
    process.exit(fail === 0 ? 0 : 2);
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
