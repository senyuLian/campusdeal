/**
 * 模块 07：Agent 敏感操作二次确认 运行时验证（T3 修复回归 + CONF-01/02）
 *
 * 驱动真实 LLM（DeepSeek，local profile）调用 apply_refund：
 *  1) 有 token 登录 → 触发聊天，LLM 调用 apply_refund
 *  2) 断言 SSE 出现 confirm 事件（T3：apply_refund 不再被绕过）
 *  3) 批准 /agent/confirm → 断言订单 status 变 5（退款中）
 *  4) 清理：恢复 status=1
 *
 * 用法: node tools/agent-confirm-test.js
 * 说明：依赖 DeepSeek Key + LLM 调用 apply_refund；若 LLM 未协作则本脚本失败并列出事件，
 *      但 T3 修复本身已由单测 SG-07/08/09 确定性覆盖。
 */
const net = require('net');
const { execFileSync } = require('child_process');

const BASE = 'http://localhost:8081';
const PHONE = '13800001111';
const MYSQL = ['mysql', '-h127.0.0.1', '-uroot', '-p123456', 'campusdeal', '-N', '-e'];
let pass = 0, fail = 0;
const report = (name, ok, detail = '') => { ok ? pass++ : fail++; console.log((ok ? '  ✅ ' : '  ❌ ') + name + (detail ? '  —  ' + detail : '')); };

function mysql(sql) {
    try {
        return execFileSync(MYSQL[0], [...MYSQL.slice(1), sql], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (e) {
        throw new Error(String(e.stderr || e.message).trim().split('\n').pop());
    }
}
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
async function login() {
    await req('POST', '/user/code', { query: { phone: PHONE } });
    const code = await redis(`GET login:code:${PHONE}`);
    if (!code) return null;
    const r = await req('POST', '/user/login', { body: { phone: PHONE, code } });
    return r.json && r.json.data;
}

/** 读 SSE 流，返回 [{name, data}] */
async function readSse(url, token, timeoutMs = 45000) {
    const res = await fetch(url, { method: 'POST', headers: { Authorization: token } });
    const events = [];
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '', timedOut = false;
    const deadline = setTimeout(() => { timedOut = true; reader.cancel().catch(() => {}); }, timeoutMs);
    try {
        while (true) {
            const { value, done: rd } = await reader.read();
            if (rd) break;
            buf += decoder.decode(value, { stream: true });
            const blocks = buf.split('\n\n');
            buf = blocks.pop();
            for (const b of blocks) {
                const ev = { name: '', data: '' };
                for (const line of b.split('\n')) {
                    if (line.startsWith('event:')) ev.name = line.slice(6).trim();
                    else if (line.startsWith('data:')) ev.data = line.slice(5).trim();
                }
                if (ev.name) events.push(ev);
            }
        }
    } finally { clearTimeout(deadline); }
    return { events, timedOut };
}

async function main() {
    console.log('══ 模块07：Agent 敏感操作二次确认 ══');
    const token = await login();
    report('登录（13800001111）', !!token, token ? token.slice(0, 8) + '…' : 'FAILED');
    if (!token) process.exit(1);

    // 选一个可退款的测试订单（status=1/2），无则跳过（说明数据不足）
    const row = mysql(`SELECT id, user_id FROM tb_voucher_order WHERE status IN (1,2) AND user_id=1013 LIMIT 1`);
    if (!row) { console.log('⚠️ 无可用测试订单，跳过运行时确认验证'); process.exit(0); }
    const [orderId, ownerId] = row.split('\t');
    report('选择可退款测试订单', !!orderId, `orderId=${orderId}`);
    const beforeStatus = mysql(`SELECT status FROM tb_voucher_order WHERE id=${orderId}`);

    const sessionId = 'm7-confirm-' + Date.now();
    // 注意：不要在小消息里带数字订单 ID —— 会被 PII 掩码层打成 ********，LLM 无法匹配。
    // 让 LLM 先从 query_order 拿到真实订单 ID（工具结果 JSON 不掩码），再调用 apply_refund。
    const prompt = '请查询我的订单列表，然后对列表中第一笔未支付（status 为 1）的订单，直接使用 apply_refund 工具申请退款。';
    const { events, timedOut } = await readSse(
        `${BASE}/agent/chat?message=${encodeURIComponent(prompt)}&sessionId=${sessionId}`, token);
    for (const e of events) {
        if (['tool_call', 'tool_result', 'confirm', 'error'].includes(e.name)) {
            console.log(`   [${e.name}] ${e.data.slice(0, 300)}`);
        }
    }
    const chunkText = events.filter(e => e.name === 'chunk').map(e => {
        try { return JSON.parse(e.data).content || JSON.parse(e.data).delta || ''; } catch { return e.data; }
    }).join('').slice(-400);
    if (chunkText) console.log('   [answer…] ' + chunkText.replace(/\n/g, ' '));
    const names = events.map(e => e.name);
    report('SSE 流正常收尾（不挂起）', !timedOut, 'timeout=' + timedOut + ' events=' + names.join(','));
    report('流中包含 thinking/tool 类事件', names.includes('thinking') || names.includes('tool_call'), names.join(','));

    const confirmEvent = events.find(e => e.name === 'confirm');
    report('**T3 回归：apply_refund 触发 confirm 事件（未被绕过）**', !!confirmEvent,
        confirmEvent ? confirmEvent.data.slice(0, 80) : '未出现 confirm（LLM 可能未调用 apply_refund）');

    // 从 apply_refund 的 tool_call 事件提取真实订单 ID。
    // ⚠️ 订单 ID 超过 2^53，JS Number 会丢精度，必须用正则按字符串提取，禁止 JSON.parse 成数字。
    let refundedOrderId = null;
    for (const e of events) {
        if (e.name !== 'tool_call') continue;
        try {
            const ev = JSON.parse(e.data);
            const inner = JSON.parse(ev.content);
            if (inner.name === 'apply_refund') {
                const m = String(inner.arguments || '').match(/"orderId"\s*:\s*"?(\d+)"?/);
                if (m) refundedOrderId = m[1];  // 字符串原样，保留全精度
            }
        } catch { /* ignore */ }
    }
    report('识别到 apply_refund 调用', !!refundedOrderId, 'orderId=' + refundedOrderId);

    if (confirmEvent) {
        // content 为二次编码 JSON：AgentEvent.content = JSON 字符串(GuardDecision)
        let confirmationId = null;
        try {
            const eventObj = JSON.parse(confirmEvent.data);
            const decision = JSON.parse(eventObj.content);
            confirmationId = decision.confirmationId;
            report('confirm 事件含 confirmationId', !!confirmationId, String(confirmationId).slice(0, 8) + '…');
        } catch (e) {
            report('解析 confirmationId', false, e.message);
        }
        if (confirmationId) {
            const r = await req('POST', '/agent/confirm', { token, body: { confirmationId, approved: true } });
            report('批准 /agent/confirm', r.status === 200 && r.json && r.json.success === true,
                JSON.stringify(r.json).slice(0, 100));
            const target = refundedOrderId || orderId;
            const afterStatus = mysql(`SELECT status FROM tb_voucher_order WHERE id=${target}`);
            report('CONF-02：退款已执行（status 1/2 → 5）', afterStatus === '5', `orderId=${target} before=${beforeStatus} after=${afterStatus}`);
        }
    }

    console.log('\n══ 清理 ══');
    const cleanupId = refundedOrderId || orderId;
    const st = mysql(`SELECT status FROM tb_voucher_order WHERE id=${cleanupId}`);
    mysql(`UPDATE tb_voucher_order SET status=${st === '5' ? 1 : st}, refund_time=NULL WHERE id=${cleanupId}`);
    report('恢复订单状态', true, `orderId=${cleanupId} → ${st === '5' ? '1' : st}`);

    console.log('\n════════ Agent 确认流运行时验证汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    await new Promise(r => setTimeout(r, 200));
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
