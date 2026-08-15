/**
 * 模块 04 缓存运行时验证：布隆 L0、逻辑过期 L2、缓存一致性 CC
 * 用法: node tools/cache-verify.js
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
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

async function login(phone = PHONE) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}

async function main() {
    console.log('══ 0. 登录 ══');
    const token = await login();
    report('获取登录 token', !!token, token ? token.slice(0, 8) + '…' : '');
    if (!token) { console.log('终止'); process.exit(1); }

    // ============ BF-01/02 布隆过滤器（L0） ============
    console.log('\n══ 1. 布隆过滤器 L0 ══');
    const t0 = Date.now();
    const bogus = await req('POST', '/coupon-order/seckill/999999', { token });
    const bogusMs = Date.now() - t0;
    report('BF 拦截不存在 dealId=999999（Deal not found）', bogus.json && bogus.json.success === false && bogus.json.errorMsg === 'Deal not found',
        bogusMs + 'ms ' + (bogus.json && bogus.json.errorMsg));
    const real = await req('POST', '/coupon-order/seckill/11', { token });
    report('BF 放行真实 dealId=11（非 Deal not found）', real.json && real.json.errorMsg !== 'Deal not found',
        JSON.stringify(real.json).slice(0, 50));

    // ============ LE-01/02 逻辑过期缓存（L2） ============
    console.log('\n══ 2. 逻辑过期缓存 L2 ══');
    const m1 = await req('GET', '/merchant/1');
    const cached = await redis(`GET cache:merchant:1`);
    report('LE 查询后写入逻辑过期包装（含 expireTime）', m1.json.success === true && cached !== null && cached.includes('expireTime'),
        cached ? cached.slice(0, 70) : 'null');
    const m1again = await req('GET', '/merchant/1');
    report('LE 二次查询命中缓存一致', m1again.json.success === true && m1again.json.data.id === m1.json.data.id && m1again.json.data.name === m1.json.data.name,
        m1again.json.data.name);

    // ============ CC-01 缓存一致性 ============
    console.log('\n══ 3. 缓存一致性 CC ══');
    const put = await req('PUT', '/merchant', { token, body: m1.json.data });
    const afterPut = await redis(`GET cache:merchant:1`);
    report('CC-01 更新商户后缓存被删除', put.json.success === true && afterPut === null,
        put.json.success ? 'cache:merchant:1=' + String(afterPut) : JSON.stringify(put.json).slice(0, 60));
    const m1post = await req('GET', '/merchant/1');
    report('CC-02 更新后查询重建缓存', m1post.json.success === true && (await redis(`GET cache:merchant:1`)) !== null,
        'rebuilt=' + String(await redis(`GET cache:merchant:1`) !== null));

    // ============ 缓存穿透（空值缓存）单元已验证；运行时 merchant 走逻辑过期 ============
    console.log('\n══ 4. 备注 ══');
    console.log('  merchant 详情走 queryWithLogicalExpire（击穿防御）；空值缓存 queryWithPassThrough 由 CacheClientTest PT-01..04 单元覆盖（当前未接线到 merchant）。');

    console.log('\n════════ 缓存运行时验证汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
