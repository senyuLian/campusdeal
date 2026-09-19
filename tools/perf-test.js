/**
 * 模块 10：性能测试
 * 用法: node tools/perf-test.js
 *
 * 场景：
 *  S1  秒杀并发成功延迟（30 用户 / 库存 30）→ 逐请求延迟 P50/P95/P99
 *  S2  库存耗尽后快速失败延迟（100 并发）→ P50/P95/P99
 *  C1  商户详情缓存命中 P99（预热后 2000 次）→ P50/P95/P99
 *  A1  Agent 首 token 延迟（简单问答）→ 首个 chunk 到达时间
 *  A2  Agent 流式吞吐（字符/s）
 *  R1  RAG 问答（search_faq 触发）→ 首个 chunk / tool_call 到达时间
 *
 * 注意：本机 Redis socket 超时设 200ms，避免命令等待拖慢压测（模块 09 教训）。
 */
const net = require('net');

const BASE = 'http://localhost:8081';
const DEAL_ID = 12;
const SHOP_ID = 1;
const runId = Date.now().toString().slice(-7);

const percentiles = (arr) => {
    const sorted = [...arr].sort((a, b) => a - b);
    const f = p => sorted[Math.max(0, Math.min(Math.ceil((p / 100) * sorted.length) - 1, sorted.length - 1))];
    return { p50: f(50), p95: f(95), p99: f(99), min: sorted[0], max: sorted[sorted.length - 1], n: sorted.length };
};

function redisRaw(commands, timeoutMs = 200) {
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
        setTimeout(() => { sock.destroy(); resolve(data); }, timeoutMs);
    });
}
function parseBulk(data) {
    const parts = data.split('\r\n');
    for (let i = 0; i < parts.length; i++) {
        if (parts[i][0] === '$') { const n = parseInt(parts[i].slice(1), 10); if (n < 0) return null; return parts[i + 1]; }
    }
    return null;
}
async function redisCmd(...cmd) {
    return parseBulk(await redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], cmd]));
}
async function req(method, path, { token, body, query } = {}) {
    let url = BASE + path;
    if (query) { const qs = Object.entries(query).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&'); if (qs) url += '?' + qs; }
    const h = { 'Content-Type': 'application/json' };
    if (token) h['Authorization'] = token;
    const res = await fetch(url, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    let json = null; try { json = await res.json(); } catch { }
    return { status: res.status, json };
}
async function login(phone) {
    await req('POST', '/user/code', { query: { phone } });
    const code = await redisCmd('GET', `login:code:${phone}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone, code } });
    return r.json && r.json.data;
}
async function readSseEvents(url, token, timeoutMs = 30000) {
    const tStart = Date.now();
    const res = await fetch(url, { method: 'POST', headers: { Authorization: token } });
    const events = [];
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    const deadline = setTimeout(() => { reader.cancel().catch(() => {}); }, timeoutMs);
    try {
        while (true) {
            const { value, done: rd } = await reader.read();
            if (rd) break;
            buf += decoder.decode(value, { stream: true });
            const blocks = buf.split('\n\n');
            buf = blocks.pop();
            for (const b of blocks) {
                const ev = { name: '', data: '', at: Date.now() - tStart };
                for (const line of b.split('\n')) {
                    if (line.startsWith('event:')) ev.name = line.slice(6).trim();
                    else if (line.startsWith('data:')) ev.data = line.slice(5).trim();
                }
                if (ev.name) events.push(ev);
            }
        }
    } finally { clearTimeout(deadline); }
    return { events, totalMs: Date.now() - tStart };
}

let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

(async () => {
    console.log('══ 模块10：性能测试 ══\n');

    // ============ S1/S2 秒杀 ============
    console.log('── S1 秒杀并发成功延迟 ──');
    const STOCK = 30;
    await redisCmd('SET', `flashdeal:stock:${DEAL_ID}`, String(STOCK));
    await redisCmd('DEL', `flashdeal:order:${DEAL_ID}`);
    // T15 补充：重置活动时间窗为「过去 60s 起、未来 1h 止」，避免依赖 DB 中 deal 的窗口
    // （deal 12 的 endTime 到点后 S1 会误报「秒杀已经结束」，与代码无关）
    await redisCmd('SET', `flashdeal:time:${DEAL_ID}`, `${Date.now() - 60000}|${Date.now() + 3600000}`);
    await new Promise(r => setTimeout(r, 1200)); // 等 Caffeine L1 TTL(1s) 过期

    const tokens = [];
    const tLogin0 = Date.now();
    for (let i = 1; i <= STOCK; i++) {
        const phone = '137' + runId.slice(0, 6) + String(i).padStart(2, '0'); // 11 位
        const t = await login(phone);
        if (t) tokens.push(t);
    }
    console.log(`  登录用户: ${tokens.length}/${STOCK}（${Date.now() - tLogin0}ms）`);
    if (tokens.length < STOCK) { console.log('  登录不足，终止'); process.exit(1); }

    const latS1 = [];
    const t0 = Date.now();
    const results = await Promise.all(tokens.map(tk => {
        const ts = Date.now();
        return req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token: tk }).then(r => { latS1.push(Date.now() - ts); return r; });
    }));
    const elapsedS1 = Date.now() - t0;
    let ok1 = 0, other1 = 0;
    for (const r of results) {
        if (r.json && r.json.success === true) ok1++;
        else other1++;
    }
    const ps1 = percentiles(latS1);
    console.log(`  成功=${ok1}/30 其他=${other1}  总耗时=${elapsedS1}ms`);
    console.log(`  逐请求延迟: ${JSON.stringify(ps1)} ms`);
    report('S1 全部并发成功（无超卖/无 500）', ok1 === STOCK && other1 === 0, `ok=${ok1} other=${other1}`);
    report('S1 成功 P99 < 150ms', ps1.p99 < 150, `p99=${ps1.p99}ms`);

    // ---- S2 库存耗尽快速失败 ----
    console.log('\n── S2 库存耗尽后快速失败 ──');
    const latOos = [];
    const t2 = Date.now();
    const oosResults = await Promise.all(Array.from({ length: 100 }, (_, i) => {
        const ts = Date.now();
        return req('POST', `/coupon-order/seckill/${DEAL_ID}`, { token: tokens[i % tokens.length] }).then(r => { latOos.push(Date.now() - ts); return r; });
    }));
    const elapsedS2 = Date.now() - t2;
    let oos2 = 0, other2 = 0;
    for (const r of oosResults) {
        if (/Out of stock/i.test((r.json && r.json.errorMsg) || '')) oos2++;
        else other2++;
    }
    const ps2 = percentiles(latOos);
    console.log(`  OutOfStock=${oos2} 其他=${other2}  总耗时=${elapsedS2}ms`);
    console.log(`  耗尽后响应延迟: ${JSON.stringify(ps2)} ms`);
    report('S2 耗尽后全部 Out of stock', oos2 === 100, `oos=${oos2}`);
    report('S2 耗尽后 P99 < 20ms', ps2.p99 < 20, `p99=${ps2.p99}ms`);

    // ============ C1 商户缓存 ============
    console.log('\n── C1 商户详情缓存命中 ──');
    await req('GET', `/merchant/${SHOP_ID}`); // 预热
    const latC1 = [];
    for (let i = 0; i < 2000; i++) {
        const ts = Date.now();
        await req('GET', `/merchant/${SHOP_ID}`);
        latC1.push(Date.now() - ts);
    }
    const pc1 = percentiles(latC1);
    console.log(`  2000 次 GET /merchant/${SHOP_ID}: ${JSON.stringify(pc1)} ms`);
    report('C1 商户缓存 P99 < 5ms', pc1.p99 < 5, `p99=${pc1.p99}ms`);

    // ============ A1/A2 Agent ============
    console.log('\n── A1/A2 Agent 首 token 与流式吞吐 ──');
    const aToken = tokens[0];
    const msg = '你好，请用两句话介绍一下你自己。';
    const a = await readSseEvents(`${BASE}/agent/chat?message=${encodeURIComponent(msg)}&sessionId=perf-a1-${Date.now()}`, aToken);
    const firstChunk = a.events.find(e => e.name === 'chunk');
    const chunks = a.events.filter(e => e.name === 'chunk');
    const totalChars = chunks.reduce((s, e) => s + e.data.length, 0);
    const ttft = firstChunk ? firstChunk.at : -1;
    console.log(`  事件: ${a.events.map(e => e.name).join(',')}`);
    console.log(`  首 token(TTFT)=${ttft}ms  总时长=${a.totalMs}ms  字符=${totalChars}  速率=${(totalChars / Math.max(a.totalMs, 1) * 1000).toFixed(1)} 字/s`);
    report('A1 TTFT < 2s', ttft > 0 && ttft < 2000, `ttft=${ttft}ms`);
    report('A2 流式吞吐 > 20 字/s', totalChars / Math.max(a.totalMs, 1) * 1000 > 20, `${(totalChars / Math.max(a.totalMs, 1) * 1000).toFixed(1)} 字/s`);

    // ============ R1 RAG ============
    console.log('\n── R1 RAG 检索问答 ──');
    const rmsg = '我想申请退款，请问流程是什么？';
    const r1 = await readSseEvents(`${BASE}/agent/chat?message=${encodeURIComponent(rmsg)}&sessionId=perf-r1-${Date.now()}`, aToken);
    const r1firstChunk = r1.events.find(e => e.name === 'chunk');
    const r1tool = r1.events.find(e => e.name === 'tool_call');
    console.log(`  事件: ${r1.events.map(e => e.name).join(',')}`);
    console.log(`  首 tool_call=${r1tool ? r1tool.at + 'ms' : '-'}  首 chunk=${r1firstChunk ? r1firstChunk.at + 'ms' : '-'}  总=${r1.totalMs}ms`);
    report('R1 RAG 链路无 error', !r1.events.some(e => e.name === 'error'), '');
    report('R1 首 chunk < 4s（含 LLM 往返）', r1firstChunk ? r1firstChunk.at < 4000 : false, `chunk=${r1firstChunk ? r1firstChunk.at : '-'}ms`);

    console.log('\n════════ 性能测试汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    console.log('\n提示：S1 各层耗时（bloom/caffeine/lua ns）见后端日志 "Flash deal success: ..." 行。');
    await new Promise(r => setTimeout(r, 100));
    process.exit(fail === 0 ? 0 : 2);
})().catch(e => { console.error('FATAL:', e.message, e.stack); process.exit(1); });
