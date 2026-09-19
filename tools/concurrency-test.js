/**
 * 秒杀并发无超卖测试（方案 §4.4 S2 并发压测）
 * 用法: node tools/concurrency-test.js
 *
 * 逻辑：库存设为 S=10，用 N=30 个互不相同的用户并发秒杀同一券，
 * 断言：恰好 S 个成功、N-S 个 Out of stock、0 个异常、DB 订单增量 == S（无超卖）。
 * 每次运行用时间戳手机号，保证用户全新、幂等键不残留，可重复执行。
 */
const { execFileSync } = require('child_process');
const net = require('net');

const BASE = 'http://localhost:8081';
const DEAL_ID = 12;
const STOCK = 10;
const N = 30;
const MYSQL_EXE = process.env.CAMPUSDEAL_MYSQL_EXE || 'mysql';
const runId = Date.now().toString().slice(-8);   // 每次运行唯一，避免手机号复用

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
    return parseBulk(await redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], args]));
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

function mysql(q) {
    try {
        return execFileSync(MYSQL_EXE, ['-uroot', '-p' + (process.env.CAMPUSDEAL_DB_PASSWORD || ''), '-N', '-e', q], { encoding: 'utf8' }).trim();
    } catch (e) {
        return 'ERR:' + ((e.stderr && e.stderr.toString()) || e.message || '');
    }
}

async function login(phone) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}

async function main() {
    console.log(`并发测试：deal=${DEAL_ID} stock=${STOCK} 并发用户=${N} runId=${runId}\n`);

    // 准备：库存 + 清 Lua 去重集合
    await redis(`SET flashdeal:stock:${DEAL_ID} ${STOCK}`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);
    const before = parseInt(mysql(`SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${DEAL_ID};`), 10);

    // 批量登录 N 个全新用户
    console.log('登录 N 个用户…');
    const tokens = [];
    for (let i = 1; i <= N; i++) {
        const phone = '139' + runId.slice(0, 6) + String(i).padStart(2, '0');  // 139 + 6 + 2 = 11 位
        const t = await login(phone);
        if (t) tokens.push(t);
    }
    report(`成功登录 ${N} 个用户`, tokens.length === N, 'got ' + tokens.length);
    if (tokens.length < N) { console.log('用户登录不足，终止'); process.exit(1); }

    // 并发秒杀
    console.log('并发秒杀…');
    const t0 = Date.now();
    const results = await Promise.all(tokens.map(t => req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token: t })));
    const elapsed = Date.now() - t0;

    let success = 0, outOfStock = 0, duplicate = 0, other = 0;
    for (const r of results) {
        const j = r.json || {};
        if (j.success === true) success++;
        else if (/Out of stock/i.test(j.errorMsg || '')) outOfStock++;
        else if (/Already|purchased/i.test(j.errorMsg || '')) duplicate++;
        else other++;
    }

    const after = parseInt(mysql(`SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${DEAL_ID};`), 10);
    const stockNow = await redis(`GET flashdeal:stock:${DEAL_ID}`);

    console.log('\n结果统计：');
    console.log(`  成功=${success} OutOfStock=${outOfStock} duplicate=${duplicate} 其他=${other}  耗时=${elapsed}ms`);
    console.log(`  库存 flashdeal:stock:${DEAL_ID}=${stockNow}  订单增量=${after - before}\n`);

    report('并发成功数 == 库存 S', success === STOCK, `success=${success}`);
    report('并发 Out of stock == N - S', outOfStock === N - STOCK, `oos=${outOfStock}`);
    report('无重复/异常响应', duplicate === 0 && other === 0, `dup=${duplicate} other=${other}`);
    report('Redis 库存扣到 0', stockNow === '0', 'got ' + stockNow);
    report('DB 订单增量 == S（无超卖）', after - before === STOCK, `before=${before} after=${after} delta=${after - before}`);
    report('压测耗时 < 5000ms', elapsed < 5000, elapsed + 'ms');

    console.log('\n════════ 并发压测汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
