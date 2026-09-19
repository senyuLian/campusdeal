/**
 * P0 安全回归：异步 SSE 请求导致 ThreadLocal 越权泄漏的验证
 * 用法: node tools/auth-leak-test.js
 *
 * 背景：RefreshTokenInterceptor 在 token 为空时未清 ThreadLocal；SSE 请求 afterCompletion
 * 延迟执行，容器线程复用后，匿名请求可能读到上一个用户的残留 UserHolder，从而绕过 LoginInterceptor。
 * 修复：preHandle 开头 UserHolder.removeUser()。
 *
 * 断言：多次带 token 的 SSE 请求「污染」线程后，紧接着的匿名受保护请求必须全部 401。
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
    return parseBulk(await redisRaw([['AUTH', process.env.CAMPUSDEAL_REDIS_PASSWORD || ''], args]));
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
// 消费一次 SSE（带 token），制造异步请求，污染容器线程
async function fireSse(token) {
    const res = await fetch(`${BASE}/agent/chat?message=hi&sessionId=leak-${Date.now()}`, {
        method: 'POST', headers: { Authorization: token, Connection: 'close' },
    });
    const reader = res.body.getReader();
    while (true) {
        const { done } = await reader.read();
        if (done) break;
    }
}

async function main() {
    const token = await login();
    report('登录获取 token', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) { console.log('无法登录，终止'); process.exit(1); }

    // 制造多轮 SSE 异步请求，污染线程池（修复前会随机泄漏 UserHolder）
    for (let i = 0; i < 10; i++) {
        await fireSse(token);
    }

    // 立即并发匿名请求受保护接口，全部应 401
    const N = 40;
    const results = await Promise.all(Array.from({ length: N }, () => req('GET', '/user/me', {})));
    const leaked = results.filter(r => r.status !== 401).length;
    report(`匿名 GET /user/me 全部 401（${N} 次并发）`, leaked === 0, `泄漏/异常=${leaked}`);

    console.log('\n════════ 越权泄漏回归 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
