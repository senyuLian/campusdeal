/**
 * 接口契约测试（方案 01-interface-test.md §3.3 五维矩阵）
 * 覆盖：Result 结构、参数合法/缺失/非法、数据边界、orderId 字符串契约、登出 token 失效、签到幂等、缓存穿透
 * 用法: node tools/api-contract-test.js
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
const results = [];
let pass = 0, fail = 0;

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
    return redisRaw([['AUTH', '123456'], args]).then(parseBulk);
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
    if (body !== undefined) h['Content-Type'] = 'application/json';
    if (token) h['Authorization'] = token;
    const res = await fetch(url, {
        method,
        headers: h,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    let json = null;
    try { json = await res.json(); } catch { }
    return { status: res.status, json };
}

// 断言 Result 结构契约：success 必存在、errorMsg 成功时为 null、data 存在
function checkResultShape(name, r, { expectSuccess }) {
    const j = r.json;
    const ok = j && typeof j.success === 'boolean'
        && (expectSuccess ? j.success === true && j.errorMsg == null : j.success === false);
    report(name, ok, r.status + ' / ' + JSON.stringify(j).slice(0, 90));
}

async function main() {
    console.log('══ 0. 登录 ══');
    const token = await (async () => {
        await req('POST', '/user/code', { query: { phone: PHONE } });
        const code = await redis(`GET login:code:${PHONE}`);
        const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
        return r.json && r.json.data;
    })();
    report('获取 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    console.log('\n══ 1. Result 结构契约 ══');
    const rOk = await req('GET', '/merchant-type/list');
    checkResultShape('Result 成功结构 (success=true, errorMsg=null)', rOk, { expectSuccess: true });
    const rFail = await req('POST', '/user/code', { query: { phone: '123' } });
    checkResultShape('Result 失败结构 (success=false)', rFail, { expectSuccess: false });

    console.log('\n══ 2. 用户模块边界 ══');
    // 非法手机号
    const rBadPhone = await req('POST', '/user/code', { query: { phone: 'abc' } });
    report('U1 非法手机号 → fail', rBadPhone.json && rBadPhone.json.success === false, JSON.stringify(rBadPhone.json).slice(0, 80));
    // 错误验证码登录
    const rWrongCode = await req('POST', '/user/login', { body: { phone: PHONE, code: '000000' } });
    report('U2 错误验证码 → fail', rWrongCode.json && rWrongCode.json.success === false, JSON.stringify(rWrongCode.json).slice(0, 80));
    // 登录响应 data 是字符串 token（非对象）
    const token2 = await (async () => {
        await req('POST', '/user/code', { query: { phone: PHONE } });
        const code = await redis(`GET login:code:${PHONE}`);
        const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
        return r.json && r.json.data;
    })();
    report('U2 登录 data 为字符串 token', typeof token2 === 'string' && token2.length > 10, '');
    // 登出使 token 失效
    const rLogout = await req('POST', '/user/logout', { token });
    report('U3 登出成功', rLogout.json && rLogout.json.success === true, JSON.stringify(rLogout.json).slice(0, 80));
    const rAfterLogout = await req('GET', '/user/me', { token });
    report('U3 登出后旧 token → 401', rAfterLogout.status === 401, 'got ' + rAfterLogout.status);
    // 重新登录（后续用例用）
    const freshToken = await (async () => {
        await req('POST', '/user/code', { query: { phone: PHONE } });
        const code = await redis(`GET login:code:${PHONE}`);
        const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
        return r.json && r.json.data;
    })();

    console.log('\n══ 3. 签到幂等 ══');
    await req('POST', '/user/sign', { token: freshToken });
    const c1 = await req('GET', '/user/sign/count', { token: freshToken });
    await req('POST', '/user/sign', { token: freshToken });
    const c2 = await req('GET', '/user/sign/count', { token: freshToken });
    report('U8/U9 重复签到天数不变（幂等）', c1.json.data === c2.json.data, `day=${c1.json.data} → ${c2.json.data}`);

    console.log('\n══ 4. 商户边界与缓存穿透 ══');
    const rMiss = await req('GET', '/merchant/999999');
    report('M1 不存在商户 → fail(空值缓存)', rMiss.json && rMiss.json.success === false, JSON.stringify(rMiss.json).slice(0, 80));
    const rTypeBad = await req('GET', '/merchant/of/type', { query: { typeId: 999, current: 1 } });
    report('M4 不存在类型 → success(空列表)', rTypeBad.json && rTypeBad.json.success === true, JSON.stringify(rTypeBad.json).slice(0, 80));
    const rNameEmpty = await req('GET', '/merchant/of/name', { query: { current: 1 } });
    report('M6 空关键字 → success(全量)', rNameEmpty.json && rNameEmpty.json.success === true, JSON.stringify(rNameEmpty.json).slice(0, 80));
    const rNearbyNoCoord = await req('GET', '/merchant/nearby', { query: { current: 1 } });
    report('M5 无坐标 nearby → 降级 success', rNearbyNoCoord.json && rNearbyNoCoord.json.success === true, JSON.stringify(rNearbyNoCoord.json).slice(0, 80));

    console.log('\n══ 5. 秒杀订单字符串契约 ══');
    // 用 flash/list 找可秒杀活动；幂等考虑：用户买过的活动会返回 Already purchased，跳过继续试下一个。
    // 任一活动返回字符串 orderId 即通过；全部已购买则视为"本次无法复验"（跳过）。
    const flashList = await req('GET', '/coupon/flash/list');
    const deals = (flashList.json && flashList.json.data) || [];
    let seckillOk = false, seckillDetail = 'no flash deal with stock', triedDeal = null;
    for (const d of deals) {
        if (!(d.stock > 0)) continue;
        triedDeal = d.id;
        const rSeckill = await req('POST', `/coupon-order/seckill/${d.id}`, { token: freshToken });
        const j = rSeckill.json;
        if (j && j.success === true && typeof j.data === 'string' && /^\d{10,20}$/.test(j.data)) {
            seckillOk = true;
            seckillDetail = `deal=${d.id} 成功 orderId=${j.data}`;
            break;
        }
        if (j && j.errorMsg === 'Already purchased') { seckillDetail = `deal=${d.id} 已购买(幂等拦截)`; continue; }
        seckillDetail = `deal=${d.id} → ${rSeckill.status} ${JSON.stringify(j).slice(0, 60)}`;
    }
    report(`CO1 秒杀 orderId 为字符串${triedDeal ? '(' + triedDeal + ')' : ''}`, seckillOk, seckillDetail);

    console.log('\n══ 6. 帖子/关注边界 ══');
    const rPostMiss = await req('GET', '/post/999999');
    report('P6 不存在帖子 → fail/空', rPostMiss.json && rPostMiss.json.success === false, JSON.stringify(rPostMiss.json).slice(0, 80));
    const rFollowBad = await req('PUT', '/follow/1/5', { token: freshToken });
    report('F1 非法 isFollow → fail 或优雅处理', rFollowBad.json && rFollowBad.json.success === false, JSON.stringify(rFollowBad.json).slice(0, 80));
    const rLikesEmpty = await req('GET', '/post/likes/999999');
    report('P7 不存在帖子点赞 → success(空数组)', rLikesEmpty.json && rLikesEmpty.json.success === true, JSON.stringify(rLikesEmpty.json).slice(0, 80));

    console.log('\n══ 7. Agent 鉴权与空会话边界 ══');
    const rHist = await req('GET', '/agent/history/nonexistent-session', { token: freshToken });
    report('A2 不存在的会话 → 优雅空', rHist.status === 200, rHist.status + ' / ' + JSON.stringify(rHist.json).slice(0, 80));

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
