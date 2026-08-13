/**
 * 阶段 D 全量回归（方案 §4.2 接口矩阵 + E2E S1/S2/S3/S5）
 * 用法: node tools/regression-phase-d.js
 *
 * 覆盖：
 *  1) 36 接口矩阵：公开=200、需登录无token=401、需登录有token=200
 *  2) S1 登录→签到→角标（幂等）
 *  3) S2 秒杀→订单落库→重复下单幂等→库存扣减
 *  4) S3 缓存一致性（更新商户 → 缓存失效）
 *  5) S5 鉴权/越权（P0-4 已修复）
 * 并发无超卖见 tools/concurrency-test.js；Agent SSE 见 phase-d 联调（SSE 帧单独验证）。
 */
const { execFileSync } = require('child_process');
const net = require('net');
const MYSQL_EXE = 'D:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
const DEAL_ID = 11;           // S2 秒杀券（独立于 10/12，避免相互干扰）
const CACHE_KEY = 'cache:merchant:1';
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

// ---------- Redis RESP 直连 ----------
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
        setTimeout(() => { sock.destroy(); resolve(data); }, 3000);
    });
}
// 返回最后一条 bulk 字符串（够用：GET/SET/DEL）
function parseBulk(data) {
    const parts = data.split('\r\n');
    for (let i = 0; i < parts.length; i++) {
        if (parts[i][0] === '$') {
            const n = parseInt(parts[i].slice(1), 10);
            if (n < 0) return null;
            return parts[i + 1];
        }
    }
    return null;
}
async function redis(cmd) {
    const args = cmd.trim().split(/\s+/);
    const raw = await redisRaw([['AUTH', '123456'], args]);
    return parseBulk(raw);
}

// ---------- HTTP ----------
async function req(method, path, { token, body, query } = {}) {
    let url = BASE + path;
    if (query) {
        const qs = Object.entries(query).filter(([, v]) => v != null && v !== '')
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
        if (qs) url += '?' + qs;
    }
    const h = {};
    if (body) h['Content-Type'] = 'application/json';
    if (token) h['Authorization'] = token;
    const res = await fetch(url, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
    let json = null;
    try { json = await res.json(); } catch {}
    return { status: res.status, json };
}

async function login(phone = PHONE) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}

// ---------- MySQL ----------
function mysql(q) {
    try {
        return execFileSync(MYSQL_EXE, ['-uroot', '-p123456', '-N', '-e', q], { encoding: 'utf8' }).trim();
    } catch (e) {
        return 'ERR:' + ((e.stderr && e.stderr.toString()) || e.message || '');
    }
}

async function expect401(name, method, path) {
    const r = await req(method, path, {});
    report(name + ' 无token=401', r.status === 401, 'got ' + r.status);
}
async function expectPublic(name, method, path, opts = {}) {
    const r = await req(method, path, opts);
    report(name, r.json && r.json.success === true, r.status + ' / ' + JSON.stringify(r.json).slice(0, 80));
}
async function expectAuthed(name, method, path, token, opts = {}) {
    const r = await req(method, path, { ...opts, token });
    report(name, r.status !== 401 && r.json && r.json.success === true, r.status + ' / ' + JSON.stringify(r.json).slice(0, 80));
}

async function main() {
    console.log('══ 0. 登录 ══');
    const token = await login();
    report('登录获取 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    console.log('\n══ 1. User 模块 ══');
    await expectPublic('POST /user/code', 'POST', '/user/code', { query: { phone: PHONE } });
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
    // P0-4 已修复：写接口需登录（发合法 body 以通过 @RequestBody 解析）
    const rPostAnon = await req('POST', '/merchant', { body: { name: '__d_probe__', typeId: 1 } });
    report('POST /merchant 无token=401 (P0-4)', rPostAnon.status === 401, 'got ' + rPostAnon.status);
    const rPutAnon = await req('PUT', '/merchant', { body: { id: 99999, name: '__d_probe__' } });
    report('PUT /merchant 无token=401 (P0-4)', rPutAnon.status === 401, 'got ' + rPutAnon.status);
    await expectPublic('GET /merchant/of/type', 'GET', '/merchant/of/type', { query: { typeId: 1, current: 1 } });
    await expectPublic('GET /merchant/nearby', 'GET', '/merchant/nearby', { query: { x: 120.149993, y: 30.334229, current: 1 } });
    await expectPublic('GET /merchant/of/name', 'GET', '/merchant/of/name', { query: { name: '奶茶', current: 1 } });
    await expectPublic('GET /merchant-type/list', 'GET', '/merchant-type/list');

    console.log('\n══ 3. Coupon / 秒杀 ══');
    await expectPublic('GET /coupon/list/{shopId}', 'GET', '/coupon/list/1');
    await expectPublic('GET /coupon/flash/list', 'GET', '/coupon/flash/list');
    await expectPublic('GET /coupon/list/all', 'GET', '/coupon/list/all');
    await expect401('POST /coupon-order/seckill/{id}', 'POST', '/coupon-order/seckill/10');

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

    console.log('\n══ 6. Upload / Agent 鉴权 ══');
    const rUpload = await req('POST', '/upload/post', {});
    report('POST /upload/post (公开，无文件应报错非401)', rUpload.status !== 401, 'got ' + rUpload.status);
    const rDel = await req('POST', '/upload/delete', { query: { name: '__nonexist__.png' } });
    report('POST /upload/delete (P1-6)', rDel.json && rDel.json.success === true, rDel.status + ' / ' + JSON.stringify(rDel.json).slice(0, 60));
    await expect401('GET /agent/history/{sessionId}', 'GET', '/agent/history/test-session');
    await expect401('POST /agent/confirm', 'POST', '/agent/confirm');

    // ================= S1 签到 =================
    console.log('\n══ S1：登录→签到→角标 ══');
    const beforeSign = await req('GET', '/user/sign/count', { token });
    const sign1 = await req('POST', '/user/sign', { token });
    const afterSign = await req('GET', '/user/sign/count', { token });
    report('S1 签到成功', sign1.json && sign1.json.success === true, JSON.stringify(sign1.json).slice(0, 60));
    report('S1 签到后连续天数 >= 1', afterSign.json && afterSign.json.success === true && afterSign.json.data >= 1,
        'before=' + (beforeSign.json && beforeSign.json.data) + ' after=' + (afterSign.json && afterSign.json.data));
    const sign2 = await req('POST', '/user/sign', { token });
    const afterSign2 = await req('GET', '/user/sign/count', { token });
    report('S1 重复签到幂等（天数不变）', sign2.json && sign2.json.success === true && afterSign2.json.data === afterSign.json.data,
        'count=' + (afterSign2.json && afterSign2.json.data));

    // ================= S2 秒杀 =================
    console.log('\n══ S2：秒杀→落库→幂等 ══');
    const before = parseInt(mysql(`SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${DEAL_ID};`), 10);
    await redis(`SET flashdeal:stock:${DEAL_ID} 10`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);
    // 清理本用户消费者层去重键（P1-8 两层去重）
    const meId = (await req('GET', '/user/me', { token })).json.data.id;
    await redis(`DEL order:dedup:${meId}:${DEAL_ID}`);

    const t0 = Date.now();
    const rSeckill = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    const elapsed = Date.now() - t0;
    report('S2 秒杀成功', rSeckill.json && rSeckill.json.success === true, rSeckill.status + ' / ' + JSON.stringify(rSeckill.json).slice(0, 100) + ` / ${elapsed}ms`);
    report('S2 秒杀耗时 < 3000ms', elapsed < 3000, elapsed + 'ms');
    const stockAfter = await redis(`GET flashdeal:stock:${DEAL_ID}`);
    report('S2 库存扣减 10→9', stockAfter === '9', 'got ' + stockAfter);

    const after = parseInt(mysql(`SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${DEAL_ID};`), 10);
    report('S2 订单落库 +1', after === before + 1, `before=${before} after=${after}`);

    const rDup = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    report('S2 重复下单=Already purchased', rDup.json && rDup.json.success === false && /Already|purchased|已/.test(rDup.json.errorMsg || ''),
        JSON.stringify(rDup.json).slice(0, 80));
    const afterDup = parseInt(mysql(`SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${DEAL_ID};`), 10);
    report('S2 重复下单不新增订单', afterDup === after, `after=${afterDup}`);

    // ================= S3 缓存一致性 =================
    console.log('\n══ S3：缓存一致性 ══');
    const m1 = await req('GET', '/merchant/1');
    const m1Name = m1.json && m1.json.data && m1.json.data.name;
    const cacheBefore = await redis(`GET ${CACHE_KEY}`);
    report('S3 查询后缓存已写入', !!cacheBefore, cacheBefore ? cacheBefore.slice(0, 40) + '…' : 'null');
    // 无副作用更新：name 回写原值（updateById 需至少一个非空字段，否则空 SET 报 SQL 语法错）
    const rPut = await req('PUT', '/merchant', { token, body: { id: 1, name: m1Name } });
    report('S3 PUT /merchant 成功', rPut.json && rPut.json.success === true, JSON.stringify(rPut.json).slice(0, 60));
    const cacheAfter = await redis(`GET ${CACHE_KEY}`);
    report('S3 更新后缓存已失效', cacheAfter === null, cacheAfter ? '仍存在: ' + cacheAfter.slice(0, 40) : '已删除');

    // ================= S5 鉴权/越权（收尾断言） =================
    console.log('\n══ S5：鉴权/越权 ══');
    const rAnonSeckill = await req('POST', '/coupon-order/seckill/10', {});
    report('S5 未登录秒杀=401', rAnonSeckill.status === 401, 'got ' + rAnonSeckill.status);

    console.log('\n════════ 阶段D 回归汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    if (fail > 0) {
        console.log('存在失败项，请检查上方 ❌ 输出');
        process.exit(2);
    }
    console.log('✅ 全量回归通过');
    process.exit(0);
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
