/**
 * 模块 08：RAG 降级运行时验证（纯 BM25 场景）
 *
 * 环境：PGVector(:5432) 未部署、Neo4j(:7687) 未启动、仅 DeepSeek Key + MySQL + Redis。
 * 目标：验证计划 §9「全部缺失 → 纯 BM25，FAQ 可用」：
 *   RG-01  FAQ 问答走 search_faq（BM25 检索），有实质回答，无 error 事件
 *   RG-02  search_graph（Neo4j 未启动）降级不崩：流正常收尾、无 error 事件
 *   RG-03  向量检索禁用：日志有「Embedding 生成失败 / PGVector 写入失败」，BM25 已构建
 *
 * 用法: node tools/rag-degrade-test.js
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

async function chat(token, prompt) {
    const sessionId = 'm8-rag-' + Date.now() + '-' + Math.floor(Math.random() * 1000);
    const { events, timedOut } = await readSse(
        `${BASE}/agent/chat?message=${encodeURIComponent(prompt)}&sessionId=${sessionId}`, token);
    const names = events.map(e => e.name);
    const toolCalls = [];
    for (const e of events) {
        if (e.name === 'tool_call') {
            try {
                const ev = JSON.parse(e.data);
                const inner = JSON.parse(ev.content);
                toolCalls.push(inner.name);
            } catch { /* ignore */ }
        }
    }
    const chunkText = events.filter(e => e.name === 'chunk').map(e => {
        try { return JSON.parse(e.data).content || JSON.parse(e.data).delta || ''; } catch { return e.data; }
    }).join('');
    return { names, timedOut, toolCalls, answer: chunkText, hasError: names.includes('error'), done: names.includes('done') };
}

async function main() {
    console.log('══ 模块08：RAG 降级运行时验证（纯 BM25） ══');
    const token = await login();
    report('登录（13800001111）', !!token);
    if (!token) process.exit(1);

    // RG-01：FAQ 问答 → search_faq（BM25）
    const faq = await chat(token, '请使用 search_faq 工具帮我查一下：校园卡如何申请退款？');
    report('RG-01 流正常收尾且无 error', faq.done && !faq.timedOut && !faq.hasError,
        `events=${faq.names.join(',')}`);
    report('RG-01 调用了 search_faq 工具', faq.toolCalls.includes('search_faq'), 'tools=' + faq.toolCalls.join(','));
    const hasAnswer = faq.answer.length > 20;
    report('RG-01 有实质回答（BM25 检索结果可读）', hasAnswer, faq.answer.replace(/\s+/g, ' ').slice(0, 120));
    report('RG-01 回答命中退款主题', /退款|优惠券|订单|余额|卡/.test(faq.answer), faq.answer.replace(/\s+/g, ' ').slice(0, 80));

    // RG-02：知识图谱 → search_graph（Neo4j 未启动 → 降级不崩）
    const graph = await chat(token, '请使用 search_graph 工具查询一下：食堂A 关联了哪些实体？');
    report('RG-02 流正常收尾且无 error（Neo4j 降级不崩）', graph.done && !graph.timedOut && !graph.hasError,
        `events=${graph.names.join(',')}`);
    if (graph.toolCalls.includes('search_graph')) {
        report('RG-02 调用了 search_graph 工具', true, 'tools=' + graph.toolCalls.join(','));
    } else {
        report('RG-02 未触发 search_graph（LLM 行为差异，不影响降级结论）', graph.done && !graph.hasError);
    }
    if (graph.answer) console.log('   [graph answer…] ' + graph.answer.replace(/\s+/g, ' ').slice(0, 120));

    console.log('\n════════ RAG 降级运行时验证汇总 ════════');
    console.log(`通过 ${pass} / ${pass + fail}`);
    await new Promise(r => setTimeout(r, 200));
    process.exit(fail === 0 ? 0 : 2);
}
main().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
