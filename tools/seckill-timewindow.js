/**
 * T14 活动时间窗运行时验证（deal=12 在布隆过滤器内，无需等待 300s 重建）
 * 用法: node tools/seckill-timewindow.js
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const DEAL_ID = 12;
const PHONE = '13800001111';
const HOUR = 3600 * 1000;
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

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
function parseBulk(data) {
    const parts = data.split('\r\n');
    for (let i = 0; i < parts.length; i++) {
        if (parts[i][0] === '$') { const n = parseInt(parts[i].slice(1), 10); return n < 0 ? null : parts[i + 1]; }
    }
    return null;
}
async function redis(cmd) {
    const args = cmd.trim().split(/\s+/);
    return parseBulk(await redisRaw([['AUTH', '123456'], args]));
}
async function redisSet(k, v) {
    return redisRaw([['AUTH', '123456'], ['SET', k, v]]);
}

async function req(method, path, { token, body, query } = {}) {
    let url = BASE + path;
    if (query) {
        const qs = Object.entries(query).filter(([, v]) => v != null && v !== '')
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
        if (qs) url += '?' + qs;
    }
    const h = { 'Content-Type': 'application/json' };
    if (token) h['Authorization'] = token;
    const res = await fetch(url, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    let json = null;
    try { json = await res.json(); } catch { }
    return { status: res.status, json };
}

async function login(phone) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}

async function main() {
    const token = await login(PHONE);
    report('主用户登录', !!token);
    if (!token) { process.exit(1); }
    const now = Date.now();
    const timeKey = `flashdeal:time:${DEAL_ID}`;

    console.log('\n══ TW-01 未开始 ══');
    await redisSet(timeKey, `${now + HOUR}|${now + 2 * HOUR}`);
    const r1 = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    report('未开始 → 秒杀尚未开始', r1.json.success === false && /尚未开始/.test(r1.json.errorMsg || ''), JSON.stringify(r1.json).slice(0, 60));

    console.log('\n══ TW-02 已结束 ══');
    await redisSet(timeKey, `${now - 2 * HOUR}|${now - HOUR}`);
    const r2 = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    report('已结束 → 秒杀已经结束', r2.json.success === false && /已经结束/.test(r2.json.errorMsg || ''), JSON.stringify(r2.json).slice(0, 60));

    console.log('\n══ TW-03 进行中 ══');
    // 恢复有效时间窗 + 重置库存/去重，用全新用户验证可正常秒杀
    await redisSet(timeKey, `${now - HOUR}|${now + HOUR}`);
    await redis(`SET flashdeal:stock:${DEAL_ID} 5`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);
    const freshPhone = '137' + String(Date.now()).slice(-8);
    const freshToken = await login(freshPhone);
    const r3 = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token: freshToken });
    report('进行中 → 秒杀成功', r3.json.success === true, JSON.stringify(r3.json).slice(0, 60));
    await redis(`DEL flashdeal:order:${DEAL_ID}`);  // 清理去重，避免污染后续回归
    await redis(`SET flashdeal:stock:${DEAL_ID} 5`); // 恢复库存

    console.log('\n════════ 时间窗验证汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
