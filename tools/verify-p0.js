/**
 * P0 修复验证脚本（阶段 B）
 * 用法: node tools/verify-p0.js
 * 断言：
 *   P0-4  POST/PUT /merchant 无 token = 401，有 token = 200
 *   P0-1  POST /coupon-order/seckill/10 不再阻塞 60s（< 5000ms 且 success）
 *   P0-3  秒杀后订单落库（由调用方另查 MySQL；本脚本只保证接口 success + 返回 orderId）
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
const DEAL_ID = 10;
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

// Redis RESP 直连
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
    return redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], args]);
}

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

async function login() {
    await req('POST', '/user/code', { query: { phone: PHONE } });
    // 从 Redis 读验证码
    const raw = await redis(`GET login:code:${PHONE}`);
    const code = /\$(\d+)\r\n([\s\S]*?)\r\n/.exec(raw);
    const v = code ? code[2] : null;
    const r = await req('POST', '/user/login', { body: { phone: PHONE, code: v } });
    return r.json && r.json.data;
}

async function main() {
    console.log('══ P0-4：/merchant 写接口鉴权 ══');
    const rPostAnon = await req('POST', '/merchant', { body: { name: '__p0_probe__', typeId: 1 } });
    report('POST /merchant 无 token = 401', rPostAnon.status === 401, 'got ' + rPostAnon.status);
    const rPutAnon = await req('PUT', '/merchant', { body: { id: 99999, name: '__p0_probe__' } });
    report('PUT /merchant 无 token = 401', rPutAnon.status === 401, 'got ' + rPutAnon.status);

    const token = await login();
    report('登录获取 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    const rPostAuth = await req('POST', '/merchant', { token, body: { name: '__p0_probe__', typeId: 1, images: '', address: '测试地址', x: 120.1, y: 30.2, sold: 0, comments: 0, score: 50 } });
    report('POST /merchant 有 token 放行', rPostAuth.status === 200 && rPostAuth.json && rPostAuth.json.success === true,
        rPostAuth.status + ' / ' + JSON.stringify(rPostAuth.json).slice(0, 80));

    console.log('\n══ P0-1/P0-3：秒杀链路 ══');
    // 重置库存 + 清除已购标记，保证本次可成功秒杀
    await redis(`SET flashdeal:stock:${DEAL_ID} 100`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);

    const t0 = Date.now();
    const rSeckill = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    const elapsed = Date.now() - t0;
    const ok = rSeckill.json && rSeckill.json.success === true;
    report('秒杀成功（不阻塞）', ok, rSeckill.status + ' / ' + JSON.stringify(rSeckill.json).slice(0, 100) + ` / ${elapsed}ms`);
    report('秒杀耗时 < 5000ms（P0-1 不再 60s）', elapsed < 5000, elapsed + 'ms');
    if (ok) {
        console.log('        orderId = ' + rSeckill.json.data);
    }

    console.log('\n════════ 汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
