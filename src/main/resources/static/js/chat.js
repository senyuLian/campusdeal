/**
 * CampusDeal 智能助手前端。
 *
 * 为什么不用 EventSource：EventSource 无法携带 Authorization 请求头，而 /agent/chat
 * 受 LoginInterceptor 保护，需要 token。因此改用 fetch + ReadableStream 手写 SSE 解析。
 */
(function () {
    'use strict';

    let sessionId = null;
    let currentAssistantMsg = null;
    let currentContentDiv = null;
    let thinkingEl = null;
    let busy = false;
    let codeCountdown = null;

    // 登录态：主应用登录后 token 存在 localStorage['campusdeal-token']
    const TOKEN_KEY = 'campusdeal-token';

    function getToken() {
        return localStorage.getItem(TOKEN_KEY) || '';
    }

    // ======================= 登录 =======================

    function setLoginMsg(text, isError) {
        const el = $('#login-msg');
        if (el) {
            el.textContent = text;
            el.className = 'login-msg' + (isError ? ' error' : '');
        }
    }

    function showLoginPanel() {
        $('#login-panel').classList.remove('hidden');
        $('#chat-input-area').classList.add('hidden');
        $('#logout-btn').classList.add('hidden');
    }

    function hideLoginPanel() {
        $('#login-panel').classList.add('hidden');
        $('#chat-input-area').classList.remove('hidden');
        $('#logout-btn').classList.remove('hidden');
    }

    function sendCode() {
        const phone = $('#login-phone').value.trim();
        if (!/^1[3-9]\d{9}$/.test(phone)) {
            setLoginMsg('请输入正确的手机号', true);
            return;
        }
        setLoginMsg('');
        fetch('/user/code?phone=' + encodeURIComponent(phone), { method: 'POST' })
            .then(function (resp) { return resp.json(); })
            .then(function (result) {
                if (result && result.success) {
                    setLoginMsg('验证码已发送（本地开发请从 Redis 查看）');
                    startCodeCountdown();
                } else {
                    setLoginMsg((result && result.errorMsg) || '验证码发送失败', true);
                }
            })
            .catch(function () { setLoginMsg('网络异常，验证码发送失败', true); });
    }

    function startCodeCountdown() {
        const btn = $('#code-btn');
        if (codeCountdown) clearInterval(codeCountdown);
        let left = 60;
        btn.disabled = true;
        codeCountdown = setInterval(function () {
            btn.textContent = (--left) + '秒后可重发';
            if (left <= 0) {
                clearInterval(codeCountdown);
                codeCountdown = null;
                btn.disabled = false;
                btn.textContent = '获取验证码';
            }
        }, 1000);
    }

    function doLogin() {
        const phone = $('#login-phone').value.trim();
        const code = $('#login-code').value.trim();
        if (!/^1[3-9]\d{9}$/.test(phone)) {
            setLoginMsg('请输入正确的手机号', true);
            return;
        }
        if (!code) {
            setLoginMsg('请输入验证码', true);
            return;
        }
        fetch('/user/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ phone: phone, code: code })
        })
            .then(function (resp) { return resp.json(); })
            .then(function (result) {
                if (result && result.success && result.data) {
                    localStorage.setItem(TOKEN_KEY, result.data);
                    hideLoginPanel();
                    appendMessage('assistant', '✅ 登录成功！我是 CampusDeal 智能助手，可以帮您查询订单、浏览优惠券、查找商户等。');
                    setLoginMsg('');
                } else {
                    setLoginMsg((result && result.errorMsg) || '登录失败，请检查验证码', true);
                }
            })
            .catch(function () { setLoginMsg('网络异常，登录失败', true); });
    }

    function logout() {
        localStorage.removeItem(TOKEN_KEY);
        showLoginPanel();
        $('#chat-messages').innerHTML = '';
        $('#session-badge').textContent = '未登录';
    }

    // ======================= DOM 工具 =======================

    function $(sel) { return document.querySelector(sel); }

    function scrollToBottom() {
        const container = $('#chat-messages');
        container.scrollTop = container.scrollHeight;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 极简 Markdown 渲染（离线环境无 marked.js）：代码块 / 行内代码 / 加粗 / 换行
    function renderMarkdown(text) {
        let html = escapeHtml(text);
        // 代码块 ```lang ... ```
        html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function (_, lang, code) {
            return '<pre><code>' + code.replace(/\n$/, '') + '</code></pre>';
        });
        // 行内代码 `xxx`
        html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>');
        // 加粗 **xxx**
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        // 换行
        html = html.replace(/\n/g, '<br>');
        return html;
    }

    function appendMessage(role, text) {
        const wrapper = document.createElement('div');
        wrapper.className = 'message ' + role;
        wrapper.innerHTML = '<div class="avatar">' + (role === 'user' ? '👤' : '🤖') + '</div>' +
            '<div class="message-content">' + (text ? renderMarkdown(text) : '') + '</div>';
        $('#chat-messages').appendChild(wrapper);
        scrollToBottom();
        return wrapper;
    }

    function showThinking(text) {
        if (!thinkingEl) {
            thinkingEl = document.createElement('div');
            thinkingEl.className = 'thinking-indicator';
            currentContentDiv.appendChild(thinkingEl);
        }
        thinkingEl.textContent = '💭 ' + text;
        scrollToBottom();
    }

    function clearThinking() {
        if (thinkingEl) {
            thinkingEl.remove();
            thinkingEl = null;
        }
    }

    function appendHtml(html) {
        currentContentDiv.insertAdjacentHTML('beforeend', html);
        scrollToBottom();
    }

    function showToolCallCard(toolCall) {
        let args = '{}';
        try {
            args = toolCall.arguments || '{}';
        } catch (e) { /* ignore */ }
        appendHtml(
            '<div class="tool-call-card">' +
            '<div class="tool-header">🔧 正在使用: <strong>' + escapeHtml(toolCall.name || toolCall.tool || '') + '</strong></div>' +
            '<div class="tool-args">参数: ' + escapeHtml(args) + '</div>' +
            '</div>'
        );
    }

    function showToolResult(text) {
        const card = document.createElement('div');
        card.className = 'tool-call-card';
        card.innerHTML = '<div class="tool-result success">✅ ' + escapeHtml(text) + '</div>';
        currentContentDiv.appendChild(card);
        scrollToBottom();
    }

    function showError(text) {
        clearThinking();
        appendHtml('<p class="error-text">⚠️ ' + escapeHtml(text) + '</p>');
    }

    function setBusy(b) {
        busy = b;
        $('#send-btn').disabled = b;
    }

    // ======================= 操作确认 =======================

    function showConfirmDialog(decision) {
        const overlay = document.createElement('div');
        overlay.className = 'confirm-overlay';
        overlay.innerHTML =
            '<div class="confirm-dialog">' +
            '<p>⚠️ ' + escapeHtml(decision.message || '请确认是否继续此操作') + '</p>' +
            '<div class="confirm-actions">' +
            '<button class="btn-confirm">确认</button>' +
            '<button class="btn-cancel">取消</button>' +
            '</div>' +
            '</div>';
        document.body.appendChild(overlay);

        overlay.querySelector('.btn-confirm').addEventListener('click', function () {
            overlay.remove();
            confirmAction(decision.confirmationId, true);
        });
        overlay.querySelector('.btn-cancel').addEventListener('click', function () {
            overlay.remove();
            confirmAction(decision.confirmationId, false);
        });
    }

    async function confirmAction(confirmationId, approved) {
        try {
            const resp = await fetch('/agent/confirm', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': getToken()
                },
                body: JSON.stringify({ confirmationId: confirmationId, approved: approved })
            });
            const result = await resp.json();
            if (result && result.data && result.data.result) {
                showToolResult('确认结果: ' + (approved ? '已执行' : '已取消'));
            } else if (result && result.errorMsg) {
                showToolResult(result.errorMsg);
            }
        } catch (e) {
            showToolResult('确认请求失败，请重试');
        }
    }

    // ======================= SSE 解析 =======================

    function parseSseBlock(block) {
        const lines = block.split('\n');
        const event = { name: 'message', data: [] };
        for (const line of lines) {
            if (line.startsWith('event:')) {
                event.name = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                event.data.push(line.slice(5).trim());
            } else if (line.startsWith(':')) {
                // comment, ignore
            }
        }
        event.body = event.data.join('\n');
        return event;
    }

    async function consumeSse(response, handlers) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            // SSE 块以空行分隔
            let idx;
            while ((idx = buffer.indexOf('\n\n')) !== -1) {
                const block = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 2);
                const blockTrimmed = block.trim();
                if (!blockTrimmed) continue;
                const event = parseSseBlock(blockTrimmed);
                handlers[event.name] && handlers[event.name](event.body, event);
            }
        }
    }

    // ======================= 主流程 =======================

    async function sendMessage() {
        const input = $('#chat-input');
        const message = input.value.trim();
        if (!message || busy) return;

        const token = getToken();
        if (!token) {
            showLoginPanel();
            showError('请先登录后再发送消息。');
            return;
        }

        // 显示用户消息
        appendMessage('user', message);
        input.value = '';

        // 创建 AI 消息容器
        currentAssistantMsg = appendMessage('assistant', '');
        currentContentDiv = currentAssistantMsg.querySelector('.message-content');
        setBusy(true);

        // 构造请求
        const params = new URLSearchParams();
        params.append('message', message);
        if (sessionId) params.append('sessionId', sessionId);

        try {
            const resp = await fetch('/agent/chat?' + params.toString(), {
                method: 'POST',
                headers: { 'Authorization': token }
            });

            if (!resp.ok) {
                showError('请求失败（HTTP ' + resp.status + '）');
                setBusy(false);
                return;
            }

            let answer = '';
            await consumeSse(resp, {
                'thinking': function (data) {
                    try {
                        const evt = JSON.parse(data);
                        showThinking(evt.content);
                    } catch (e) { showThinking(data); }
                },
                'tool_call': function (data) {
                    try {
                        const evt = JSON.parse(data);
                        const toolCall = JSON.parse(evt.content);
                        clearThinking();
                        showToolCallCard(toolCall);
                    } catch (e) { /* ignore */ }
                },
                'tool_result': function (data) {
                    try {
                        const evt = JSON.parse(data);
                        clearThinking();
                        showToolResult(evt.content);
                    } catch (e) { /* ignore */ }
                },
                'confirm': function (data) {
                    try {
                        const evt = JSON.parse(data);
                        const decision = JSON.parse(evt.content);
                        showConfirmDialog(decision);
                    } catch (e) { /* ignore */ }
                },
                'chunk': function (data) {
                    clearThinking();
                    answer += data;
                    // 渐进渲染：重绘当前内容
                    currentContentDiv.innerHTML = renderMarkdown(answer);
                    scrollToBottom();
                },
                'done': function (data) {
                    try {
                        const respData = JSON.parse(data);
                        sessionId = respData.sessionId;
                        $('#session-badge').textContent = '会话: ' + sessionId.substring(0, 8);
                    } catch (e) { /* ignore */ }
                },
                'error': function (data) {
                    try {
                        const evt = JSON.parse(data);
                        showError(evt.content || '处理您的请求时出现了问题');
                    } catch (e) {
                        showError(data);
                    }
                }
            });
        } catch (e) {
            showError('网络异常，无法连接 Agent 服务');
        } finally {
            clearThinking();
            setBusy(false);
        }
    }

    // ======================= 初始化 =======================

    document.addEventListener('DOMContentLoaded', function () {
        const input = $('#chat-input');
        input.addEventListener('keydown', function (e) {
            // Ctrl/Cmd + Enter 发送
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                sendMessage();
            }
        });
        if (!getToken()) {
            $('#session-badge').textContent = '未登录';
            showLoginPanel();
        } else {
            hideLoginPanel();
            appendMessage('assistant', '您好！我是 CampusDeal 智能助手，可以帮您查询订单、浏览优惠券、查找商户等。');
        }
    });

    // 暴露给 HTML onclick
    window.sendMessage = sendMessage;
    window.sendCode = sendCode;
    window.doLogin = doLogin;
    window.logout = logout;
})();
