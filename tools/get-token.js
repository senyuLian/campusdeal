/**
 * 获取一个有效登录 token（每个测试模块通用）
 * 用法: node tools/get-token.js [phone]
 * 输出: token（末尾换行）
 */
const net = require('net');

const PHONE = process.argv[2] || '13800001111';
const BASE = 'http://localhost:8081';

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
        setTimeout(() => { sock.destroy(); resolve(data); }, 2000);
    });
}
function parseBulk(data) {
    const re = /\$(\d+)\r\n([\s\S]*?)\r\n/;
    const m = data.match(re);
    return m ? m[2] : null;
}

async function main() {
    await fetch(`${BASE}/user/code?phone=${PHONE}`, { method: 'POST' });
    const code = await redisRaw([['AUTH', '123456'], ['GET', `login:code:${PHONE}`]]).then(parseBulk);
    const r = await fetch(`${BASE}/user/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone: PHONE, code }),
    });
    const j = await r.json();
    if (j.success && j.data) {
        process.stdout.write(j.data);
    } else {
        console.error('登录失败:', JSON.stringify(j));
        process.exit(1);
    }
}
main();
