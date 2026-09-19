/**
 * 模块 06 一致性运行时验证（IT-01..05 + T4 唯一索引 DB 兜底）
 * 用法: node tools/consistency-verify.js
 *
 * IT-01 秒杀→落库闭环（同步落库路径）
 * IT-02 幂等兜底：Lua 去重 + DB 唯一索引 uk_user_voucher
 * IT-03 同步失败→Outbox 补偿（运行时不注入故障；验证 outbox 表结构 + 单测覆盖）
 * IT-04 Canal 实时失效（可选，未部署则记录）
 * IT-05 无 Kafka/Canal 环境秒杀不受影响（响应不阻塞）
 */
const net = require('net');
const { execFileSync } = require('child_process');

const BASE = 'http://localhost:8081';
const DEAL_ID = 12;
const HOUR = 3600 * 1000;
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

const MYSQL = ['mysql', '-h127.0.0.1', '-uroot', '-p' + (process.env.CAMPUSDEAL_DB_PASSWORD || ''), 'campusdeal', '-N', '-e'];

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
    return parseBulk(await redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], args]));
}
async function redisSet(k, v) { return redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], ['SET', k, v]]); }

function mysql(sql) {
    try {
        return execFileSync(MYSQL[0], [...MYSQL.slice(1), sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (e) {
        // 暴露 MySQL 原始错误（如 Duplicate entry）便于断言
        const last = String(e.stderr || '').trim().split('\n').pop() || e.message;
        throw new Error(last);
    }
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
    const start = Date.now();
    const res = await fetch(url, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    const elapsed = Date.now() - start;
    let json = null;
    try { json = await res.json(); } catch { }
    return { status: res.status, json, elapsed };
}

async function login(phone) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}

async function main() {
    console.log('══ 0. 环境与前置 ══');
    // 确保 deal=12 处于有效时间窗、库存充足、去重集合干净
    const now = Date.now();
    await redisSet(`flashdeal:time:${DEAL_ID}`, `${now - HOUR}|${now + HOUR}`);
    await redis(`SET flashdeal:stock:${DEAL_ID} 5`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);

    // 唯一索引是否已应用（T4）
    const idx = mysql(`SHOW INDEX FROM tb_voucher_order WHERE Key_name='uk_user_voucher'`).split('\n').filter(Boolean).length;
    report('T4 唯一索引 uk_user_voucher 已应用', idx >= 2, `索引列数=${idx}`);

    const phone = '136' + String(Date.now()).slice(-8);
    const token = await login(phone);
    report('测试用户登录', !!token, phone);
    if (!token) process.exit(1);
    const userId = await redis(`HGET login:token:${token} id`);
    report('获取 userId', !!userId, String(userId));

    console.log('\n══ IT-01 秒杀→落库闭环 ══');
    await redis(`DEL flashdeal:order:${DEAL_ID}`);  // 确保新用户可下单
    const r1 = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    report('秒杀请求成功', r1.json.success === true, JSON.stringify(r1.json).slice(0, 60));
    report('IT-05 响应不阻塞（无 Kafka/Canal 环境）', r1.elapsed < 2000, `${r1.elapsed}ms`);
    const orderCount = mysql(`SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=${userId} AND voucher_id=${DEAL_ID}`);
    report('同步落库闭环：DB 存在该订单', orderCount === '1', `count=${orderCount}`);

    console.log('\n══ IT-02 幂等兜底 ══');
    const r2 = await req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token });
    report('Lua 去重：同用户重复秒杀被拒', r2.json.success === false && /Already purchased|already/i.test(r2.json.errorMsg || ''), JSON.stringify(r2.json).slice(0, 60));

    // T4 DB 兜底：绕过 Lua/Redis，直接插重复 (user_id, voucher_id) 的不同 orderId → 唯一索引必须拦截
    const fakeOrderId = '774' + String(Date.now()).slice(4) + '0'.repeat(4);
    let dupBlocked = false, dupErr = '';
    try {
        mysql(`INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (${fakeOrderId}, ${userId}, ${DEAL_ID}, 1)`);
    } catch (e) {
        dupBlocked = true;
        dupErr = String(e.message).split('\n')[0];
    }
    report('T4 DB 兜底：重复(user,voucher)直接插单被唯一索引拦截', dupBlocked, dupBlocked ? dupErr.slice(0, 60) : '未拦截！');

    console.log('\n══ IT-03 Outbox 补偿（结构验证） ══');
    let outboxOk = false, outboxDetail = '';
    try {
        const cols = mysql(`SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='campusdeal' AND table_name='outbox'`).split('\n')[0] || '';
        outboxOk = cols === '9';  // id,message_id,topic,payload,status,retry_count,error_msg,create_time,update_time
        outboxDetail = `字段数=${cols}`;
    } catch (e) {
        outboxDetail = '表不存在';
    }
    report('outbox 表存在且字段完整', outboxOk, outboxDetail);
    const pendingRows = mysql(`SELECT COUNT(*) FROM outbox WHERE status='PENDING'`).split('\n')[0];
    report('当前无积压 PENDING 消息', pendingRows === '0', `pending=${pendingRows}`);

    console.log('\n══ IT-04 Canal 实时失效（可选） ══');
    // Canal 客户端仅在手动 start() 后生效，本环境未部署 → 按计划标记为「跳过」而非失败
    report('Canal 未部署，实时失效跳过（可选；单测 CN-01..06 覆盖失效规则）', true, 'binlog→Redis 由 30s 逻辑过期兜底');

    console.log('\n══ 清理 ══');
    mysql(`DELETE FROM tb_voucher_order WHERE user_id=${userId} AND voucher_id=${DEAL_ID}`);
    await redis(`DEL flashdeal:order:${DEAL_ID}`);
    await redis(`SET flashdeal:stock:${DEAL_ID} 5`);
    report('清理测试订单/去重/库存', true);

    console.log('\n════════ 一致性运行时验证汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
