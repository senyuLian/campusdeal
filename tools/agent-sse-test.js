/**
 * S4：Agent SSE 联调（方案 §4.4 S4）
 * 用法: node tools/agent-sse-test.js
 *
 * 断言：
 *  1) POST /agent/chat 需登录（无 token=401）
 *  2) 有 token → 200 + Content-Type=text/event-stream
 *  3) 流内至少收到一个事件（thinking/chunk/tool_call/done/error），且最终以 done/error 收尾（不挂起）
 *  4) GET /agent/history/{sessionId} 可回读会话
 * 环境无 DeepSeek Key（P1-5 已去明文），预期降级：thinking → error，而非 500/挂起。
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
    return parseBulk(await redisRaw([['AUTH', '123456'], args]));
}

async function login() {
    await req('POST', '/user/code', { query: { phone: PHONE } });
    const code = await redis(`GET login:code:${PHONE}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
    return r.json && r.json.data;
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

// 读 SSE 流（带超时），返回 { events, done, timedOut }
async function readSse(url, token, timeoutMs = 30000) {
    const res = await fetch(url, { method: 'POST', headers: { Authorization: token, Connection: 'close' } });
    const contentType = res.headers.get('content-type') || '';
    const events = [];
    let done = false, timedOut = false;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    const deadline = setTimeout(() => { timedOut = true; reader.cancel().catch(() => {}); }, timeoutMs);
    try {
        while (true) {
            const { value, done: rd } = await reader.read();
            if (rd) break;
            buf += decoder.decode(value, { stream: true });
            // SSE 事件按空行分隔
            const blocks = buf.split('\n\n');
            buf = blocks.pop();
            for (const b of blocks) {
                const m = /event:([^\n]+)/.exec(b);
                if (m) {
                    const name = m[1].trim();
                    events.push(name);
                    if (name === 'done' || name === 'error') done = true;
                }
            }
        }
    } finally {
        clearTimeout(deadline);
    }
    return { status: res.status, contentType, events, done, timedOut };
}

async function main() {
    console.log('══ S4：Agent SSE ══');
    const rAnon = await req('POST', '/agent/chat', { query: { message: 'hi' } });
    report('无 token /agent/chat = 401', rAnon.status === 401, 'got ' + rAnon.status);

    const token = await login();
    report('登录获取 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    const sessionId = 's4-test-' + Date.now();
    const r = await readSse(`${BASE}/agent/chat?message=${encodeURIComponent('查询我的订单')}&sessionId=${sessionId}`, token);
    report('POST /agent/chat = 200', r.status === 200, 'got ' + r.status);
    report('Content-Type = text/event-stream', r.contentType.includes('text/event-stream'), r.contentType);
    report('收到至少一个 SSE 事件', r.events.length > 0, 'events=' + r.events.join(','));
    report('流以 done/error 收尾（不挂起）', r.done && !r.timedOut, 'done=' + r.done + ' timeout=' + r.timedOut);
    report('降级不返回 500（空 Key）', r.status !== 500, 'status=' + r.status);

    const rHist = await req('GET', '/agent/history/' + sessionId, { token });
    report('GET /agent/history 可回读', rHist.json && rHist.json.success === true && !!rHist.json.data,
        JSON.stringify(rHist.json).slice(0, 100));

    console.log('\n════════ S4 汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    // 等待 undici 连接句柄自然关闭，避免 Windows 下 process.exit 触发 libuv 断言
    await new Promise(r => setTimeout(r, 200));
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
