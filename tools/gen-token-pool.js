/**
 * 批量生成登录 token 池（供吞吐量压测使用）
 * 用法:
 *   node tools/gen-token-pool.js --count 1000 --out tools/.tokens.json
 *   node tools/gen-token-pool.js --count 1000 --concurrency 20
 * 参数:
 *   --count N        生成 token 数量（默认 300）
 *   --concurrency N  登录并发度（默认 10，避免打爆后端）
 *   --out path       输出 JSON 文件（默认 tools/.tokens.json）
 *
 * 说明:
 *   秒杀接口每用户仅可成功下单一次（幂等），持续压测需要大量不重复用户，
 *   否则会被「Already purchased」拒绝、测不到真实 TPS。
 *   输出文件含有效登录凭证，属敏感产物，勿提交 git（已加入 .gitignore）。
 */
const fs = require('fs');
const path = require('path');
const net = require('net');

const BASE = 'http://localhost:8081';
const REDIS_AUTH = process.env.CAMPUSDEAL_REDIS_PASSWORD || '';

// ---------- 参数解析 ----------
function parseArgs(argv) {
    const a = {};
    for (let i = 0; i < argv.length; i++) {
        const k = argv[i];
        if (k.startsWith('--')) {
            const key = k.slice(2);
            const next = argv[i + 1];
            if (next !== undefined && !next.startsWith('--')) { a[key] = next; i++; }
            else a[key] = true;
        }
    }
    return a;
}

// ---------- Redis 裸协议（与现有脚本一致） ----------
function redisRaw(commands, timeoutMs = 1000) {
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
        let settle = null;
        sock.on('data', d => {
            data += d.toString();
            // 收到数据后短延判稳：本地 Redis 应答通常 <1ms，20ms 内未再收到数据即视为完整。
            // 旧实现依赖 socket 'end'（Redis 长连接不主动关闭）导致每次固定等满 2s 超时。
            clearTimeout(settle);
            settle = setTimeout(() => { sock.destroy(); resolve(data); }, 20);
        });
        sock.on('end', () => { clearTimeout(settle); resolve(data); });
        sock.on('error', reject);
        setTimeout(() => { sock.destroy(); resolve(data); }, timeoutMs);
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
    return parseBulk(await redisRaw([['AUTH', REDIS_AUTH], args]));
}

async function req(method, pathname, { token, body, query } = {}) {
    let url = BASE + pathname;
    if (query) {
        const qs = Object.entries(query).filter(([, v]) => v != null && v !== '')
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
        if (qs) url += '?' + qs;
    }
    const h = {};
    if (body) h['Content-Type'] = 'application/json';
    if (token) h['Authorization'] = token;
    const res = await fetch(url, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    let json = null;
    try { json = await res.json(); } catch {}
    return { status: res.status, json };
}

async function login(phone) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redis(`GET login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return (r.json && r.json.data) || null;
}

// 生成 11 位唯一手机号（'139' + 批次(3位) + 序号(5位零填充)）
// 修复：旧实现 '139'+runId 后再 slice 截断会因 seq 长度变化产生前缀重叠碰撞
// （如 seq='1' 时 '...5'+'1' 与 seq='51' 时 '...'+'51' 得到同一号码），导致 token 池出现重复用户。
function phoneOf(i, runId) {
    const batch = runId.slice(0, 3);        // 批次隔离：跨次生成基本不冲突
    const seq = String(i).padStart(5, '0'); // 批内唯一：5 位序号，支持 1..99999
    return '139' + batch + seq;
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    const count = parseInt(args.count || '300', 10);
    const conc = parseInt(args.concurrency || '10', 10);
    const out = args.out || 'tools/.tokens.json';
    const runId = Date.now().toString().slice(-8);

    console.log(`生成 token 池：count=${count} 并发=${conc} → ${out}\n`);

    const phones = [];
    for (let i = 1; i <= count; i++) phones.push(phoneOf(i, runId));

    const tokens = new Array(count);
    let next = 0, done = 0, failed = 0;

    async function worker() {
        while (true) {
            const i = next++;
            if (i >= count) break;
            const t = await login(phones[i]);
            if (t) tokens[i] = t; else failed++;
            done++;
            if (done % 50 === 0 || done === count) process.stdout.write(`\r  登录进度 ${done}/${count}（失败 ${failed}）`);
        }
    }

    const t0 = Date.now();
    await Promise.all(Array.from({ length: Math.min(conc, count) }, () => worker()));
    const elapsed = Date.now() - t0;

    const ok = tokens.filter(Boolean);
    fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
    fs.writeFileSync(out, JSON.stringify({ count: ok.length, createdAt: new Date().toISOString(), phones, tokens: ok }, null, 2));

    console.log(`\n完成：${ok.length}/${count} 个有效 token，耗时 ${elapsed}ms，已写入 ${out}`);
    if (ok.length < count) {
        console.error(`失败 ${failed} 个（可能后端未启动或验证码未命中），请检查环境后重试。`);
        process.exit(1);
    }
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
