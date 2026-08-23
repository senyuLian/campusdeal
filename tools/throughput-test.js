/**
 * 吞吐量压测（QPS / TPS）
 * 补充模块 10 只测延迟、未测吞吐的缺口。用法:
 *
 *   # TP-01 商户详情读 QPS（缓存命中，公开接口）
 *   node tools/throughput-test.js --scene merchant --concurrency 100 --duration 30
 *
 *   # TP-03 秒杀下单 TPS/QPS（库存充足，需 token 池）
 *   node tools/throughput-test.js --scene seckill --deal 12 --stock 10000 \
 *     --tokens tools/.tokens.json --concurrency 100 --duration 30
 *
 *   # TP-04 秒杀耗尽后拦截极限 QPS（库存=0，L1 负缓存快速失败）
 *   node tools/throughput-test.js --scene seckill --deal 12 --stock 0 \
 *     --tokens tools/.tokens.json --concurrency 100 --duration 30
 *
 *   # TP-05 帖子热点读 QPS
 *   node tools/throughput-test.js --scene post-hot --concurrency 100 --duration 30
 *
 *   # 阶梯加压（每档 15s，找吞吐拐点）
 *   node tools/throughput-test.js --scene merchant --staircase 50,100,200,400,800 --duration 15
 *
 * 参数:
 *   --scene        merchant | seckill | post-hot
 *   --concurrency  并发数（默认 100）
 *   --duration     每档时长秒（默认 30）
 *   --staircase    阶梯并发列表，如 "50,100,200"（提供则忽略 --concurrency）
 *   --deal         秒杀 dealId（默认 12）
 *   --merchant     商户 id（默认 1）
 *   --stock        秒杀前置库存（默认 10000；设 0 测耗尽拦截）
 *   --tokens       token 池文件（默认 tools/.tokens.json）
 *   --no-prepare   跳过秒杀前置（库存/时间窗/去重 设置）
 *   --mysql-exe    mysql 客户端路径（可选，秒杀场景用于落库核对）
 *
 * 指标:
 *   QPS = 每秒总请求数；TPS = 每秒成功下单数（仅秒杀有意义）。
 *   同时输出 P50/P95/P99 与每秒序列，便于观察吞吐是否平稳、定位拐点。
 */
const fs = require('fs');
const { execFileSync } = require('child_process');
const net = require('net');

const BASE = 'http://localhost:8081';
const REDIS_AUTH = '123456';
const DEFAULT_MYSQL_EXE = 'D:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';

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
            // 收到数据后短延判稳，避免依赖 socket 'end'（Redis 长连接不主动关闭）导致固定等满超时
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
async function redisRawOnly(cmdArr) {
    return redisRaw([['AUTH', REDIS_AUTH], cmdArr]);
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

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

const percentiles = (arr) => {
    if (!arr.length) return { p50: 0, p95: 0, p99: 0, min: 0, max: 0, n: 0 };
    const sorted = [...arr].sort((a, b) => a - b);
    const f = p => sorted[Math.max(0, Math.min(Math.ceil((p / 100) * sorted.length) - 1, sorted.length - 1))];
    return { p50: f(50), p95: f(95), p99: f(99), min: sorted[0], max: sorted[sorted.length - 1], n: sorted.length };
};

function loadTokens(p) {
    if (!fs.existsSync(p)) {
        console.error(`token 池不存在: ${p}\n请先运行: node tools/gen-token-pool.js --count N --out ${p}`);
        process.exit(1);
    }
    const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
    const arr = Array.isArray(raw) ? raw : raw.tokens;
    if (!arr || !arr.length) { console.error('token 池为空'); process.exit(1); }
    return arr;
}

function mysqlCount(voucherId, mysqlExe) {
    if (!mysqlExe || !fs.existsSync(mysqlExe)) return null;
    try {
        const out = execFileSync(mysqlExe, ['-uroot', '-p123456', '-N', '-e',
            `SELECT COUNT(*) FROM campusdeal.tb_voucher_order WHERE voucher_id=${voucherId};`], { encoding: 'utf8' });
        return parseInt(out.trim(), 10);
    } catch (e) {
        return null;
    }
}

// ---------- 场景 worker 工厂 ----------
function makeWorker(scene, ctx) {
    if (scene === 'merchant') {
        return async () => {
            const r = await req('GET', `/merchant/${ctx.merchantId}`);
            const ok = r.status === 200 && r.json && r.json.success === true;
            return { kind: ok ? 'success' : 'httpError', status: r.status };
        };
    }
    if (scene === 'post-hot') {
        return async () => {
            const r = await req('GET', '/post/hot');
            const ok = r.status === 200 && r.json && r.json.success === true;
            return { kind: ok ? 'success' : 'httpError', status: r.status };
        };
    }
    if (scene === 'seckill') {
        let idx = 0;
        return async () => {
            const token = ctx.tokens[idx++ % ctx.tokens.length];
            const r = await req('POST', `/coupon-order/seckill/${ctx.dealId}`, { token });
            const j = r.json || {};
            if (j.success === true) return { kind: 'success' };
            const msg = j.errorMsg || '';
            if (r.status >= 500) return { kind: 'httpError', status: r.status };
            if (/out of stock/i.test(msg)) return { kind: 'outOfStock' };
            if (/already|purchased|重复|已购买/i.test(msg)) return { kind: 'duplicate' };
            return { kind: 'businessFail', msg };
        };
    }
    throw new Error('未知场景: ' + scene);
}

// ---------- 单档压测引擎 ----------
async function runOnce(scene, concurrency, durationSec, workerFn) {
    const start = Date.now();
    const deadline = start + durationSec * 1000;
    const lat = [];
    const cnt = { total: 0, success: 0, outOfStock: 0, duplicate: 0, businessFail: 0, httpError: 0, networkError: 0 };
    const perSec = []; // perSec[sec] = { total, success }

    async function worker() {
        while (Date.now() < deadline) {
            const t0 = Date.now();
            let r;
            try { r = await workerFn(); r.latency = Date.now() - t0; }
            catch (e) { r = { kind: 'networkError', latency: Date.now() - t0, msg: e.message }; }
            lat.push(r.latency);
            cnt.total++;
            cnt[r.kind] = (cnt[r.kind] || 0) + 1;
            const b = Math.floor((Date.now() - start) / 1000);
            if (!perSec[b]) perSec[b] = { total: 0, success: 0 };
            perSec[b].total++;
            if (r.kind === 'success') perSec[b].success++;
        }
    }

    await Promise.all(Array.from({ length: concurrency }, () => worker()));

    const elapsedMs = Date.now() - start;
    const elapsedSec = elapsedMs / 1000;
    const p = percentiles(lat);
    return {
        concurrency, elapsedMs,
        qps: cnt.total / elapsedSec,
        tps: cnt.success / elapsedSec,
        cnt, p, perSec,
    };
}

// ---------- 输出 ----------
function printSec(perSec, scene) {
    const secs = perSec.filter(Boolean);
    if (secs.length === 0) { console.log('  （无每秒数据）'); return; }
    const hasTps = scene === 'seckill';
    const head = hasTps ? '  t(s)   QPS      TPS' : '  t(s)   QPS';
    console.log(head);
    for (let i = 0; i < secs.length; i++) {
        const s = secs[i];
        if (hasTps) console.log(`  ${String(i).padStart(4)}  ${String(s.total).padStart(6)}  ${String(s.success).padStart(6)}`);
        else console.log(`  ${String(i).padStart(4)}  ${String(s.total).padStart(6)}`);
    }
}

function printResult(label, r, scene) {
    console.log(`\n── ${label} ──`);
    console.log(`  并发=${r.concurrency}  实测时长=${(r.elapsedMs / 1000).toFixed(1)}s  总请求=${r.cnt.total}`);
    console.log(`  QPS=${r.qps.toFixed(1)}  ${scene === 'seckill' ? `TPS=${r.tps.toFixed(1)}  ` : ''}P50=${r.p.p50}ms  P95=${r.p.p95}ms  P99=${r.p.p99}ms`);
    const err = r.cnt.httpError + r.cnt.networkError;
    const errRate = r.cnt.total ? (err / r.cnt.total * 100).toFixed(2) : '0.00';
    let classify = `  错误=${err}(${errRate}%)`;
    if (scene === 'seckill') {
        classify += `  成功下单=${r.cnt.success}  OutOfStock=${r.cnt.outOfStock}  重复=${r.cnt.duplicate}  其他=${r.cnt.businessFail}`;
    }
    console.log(classify);
    return r;
}

// ---------- 秒杀前置 ----------
async function prepareSeckill(dealId, stock, tokens) {
    await redisRawOnly(['DEL', `flashdeal:order:${dealId}`]);
    await redisRawOnly(['SET', `flashdeal:stock:${dealId}`, String(stock)]);
    const now = Date.now();
    await redisRawOnly(['SET', `flashdeal:time:${dealId}`, `${now - 60000}|${now + 3600000}`]);
    // 等 Caffeine L1 TTL(1s) 过期，避免上一轮负缓存干扰
    await sleep(1200);
    if (stock === 0 && tokens && tokens.length) {
        // 预热一个请求，让 L1 负缓存立即建立（TP-04 真正测的是 L1 拦截路径）
        await req('POST', `/coupon-order/seckill/${dealId}`, { token: tokens[0] });
        await sleep(1100); // 等预热请求完成后 TTL 稳定
    }
    return { stock, dealId };
}

// ---------- 主流程 ----------
async function main() {
    const args = parseArgs(process.argv.slice(2));
    const scene = args.scene || 'merchant';
    const duration = parseInt(args.duration || '30', 10);
    const dealId = parseInt(args.deal || '12', 10);
    const merchantId = parseInt(args.merchant || '1', 10);
    const noPrepare = !!args['no-prepare'];
    const mysqlExe = args['mysql-exe'] || DEFAULT_MYSQL_EXE;

    if (!['merchant', 'seckill', 'post-hot'].includes(scene)) {
        console.error(`未知场景: ${scene}（可选 merchant | seckill | post-hot）`);
        process.exit(1);
    }

    let tokens = [];
    let stock = 10000;
    if (scene === 'seckill') {
        tokens = loadTokens(args.tokens || 'tools/.tokens.json');
        stock = parseInt(args.stock || '10000', 10);
        console.log(`秒杀场景：deal=${dealId} 库存=${stock} token池=${tokens.length}`);
        if (stock > 0 && tokens.length < 1000) {
            console.warn(`  ⚠ token 池仅 ${tokens.length} 个，持续压测会因「每用户仅购一次」耗尽、转成重复请求；建议 ≥ 并发×时长×预估TPS（如 1000+）。`);
        }
    }

    const ctx = { merchantId, dealId, tokens };
    const workerFn = makeWorker(scene, ctx);

    // 阶梯列表
    let concurrencyList;
    if (args.staircase) {
        concurrencyList = args.staircase.split(',').map(s => parseInt(s.trim(), 10)).filter(n => n > 0);
        if (!concurrencyList.length) { console.error('--staircase 格式错误'); process.exit(1); }
    } else {
        concurrencyList = [parseInt(args.concurrency || '100', 10)];
    }

    const dbBefore = (scene === 'seckill' && stock > 0) ? mysqlCount(dealId, mysqlExe) : null;

    const results = [];
    for (let i = 0; i < concurrencyList.length; i++) {
        const c = concurrencyList[i];
        if (scene === 'seckill' && !noPrepare) {
            console.log(`\n[前置] 重置库存/时间窗/去重（stock=${stock}）...`);
            await prepareSeckill(dealId, stock, tokens);
        }
        console.log(`\n[压测 ${i + 1}/${concurrencyList.length}] 并发=${c} 时长=${duration}s`);
        const r = await runOnce(scene, c, duration, workerFn);
        results.push(r);
        printResult(`C=${c}`, r, scene);
        printSec(r.perSec, scene);
    }

    // 阶梯汇总表
    if (results.length > 1) {
        console.log('\n════════ 阶梯加压汇总 ════════');
        console.log('  并发   |   QPS    |   TPS    |  P50   |  P99   |  错误率');
        console.log('  -------+----------+----------+--------+--------+--------');
        for (const r of results) {
            const err = r.cnt.httpError + r.cnt.networkError;
            const errRate = r.cnt.total ? (err / r.cnt.total * 100).toFixed(2) : '0.00';
            console.log(`  ${String(r.concurrency).padStart(6)} | ${r.qps.toFixed(1).padStart(8)} | ${r.tps.toFixed(1).padStart(8)} | ${String(r.p.p50).padStart(6)} | ${String(r.p.p99).padStart(6)} | ${errRate.padStart(5)}%`);
        }
    }

    // 落库核对（秒杀、库存充足、非阶梯）
    if (dbBefore !== null && results.length === 1) {
        const success = results[0].cnt.success;
        // 异步落库：Consumer 可能滞后，轮询等待落库收敛（最多 ~90s），
        // 避免在消费者消化积压前就断言"不一致"造成误报。
        let dbAfter = mysqlCount(dealId, mysqlExe);
        let stable = 0;
        const deadline = Date.now() + 90000;
        while (dbAfter !== null && dbAfter - dbBefore < success && Date.now() < deadline) {
            await sleep(1000);
            const next = mysqlCount(dealId, mysqlExe);
            if (next === dbAfter) { if (++stable >= 3) break; } else { stable = 0; dbAfter = next; }
        }
        if (dbAfter !== null) {
            const delta = dbAfter - dbBefore;
            console.log(`\n[落库核对] tb_voucher_order 增量=${delta} 成功下单=${success} ${delta === success ? '✅ 一致' : '❌ 不一致（请排查）'}`);
        } else {
            console.log('\n[落库核对] 跳过（未找到 mysql.exe，可用 --mysql-exe 指定）');
        }
    }
}

main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
