package com.campusdeal.security;

import com.campusdeal.agent.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感操作门禁实现。
 *
 * <p>白名单外的工具直接放行；确认名单内的工具（如退款）生成确认 ID 并缓存待确认上下文，
 * 用户通过 {@link #handleConfirmation} 批准后由 {@link ToolRegistry} 实际执行。</p>
 */
@Slf4j
@Component
public class SensitiveGuardImpl implements SensitiveGuard {

    @Resource
    private SecurityProperties securityProperties;
    @Resource
    private ToolRegistry toolRegistry;

    /** 确认 ID → 待确认的操作上下文 */
    private final Map<String, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

    /** T8：确认上下文超时时间（默认 60s，与 GuardDecision.timeoutSeconds 一致；测试可缩短） */
    long confirmationTimeoutMs = 60_000;

    @Override
    public GuardDecision evaluate(String toolName, String arguments, Long userId) {
        // === 1. 拒绝列表中的工具直接拒绝 ===
        if (matches(securityProperties.getDeniedTools(), toolName)) {
            return GuardDecision.builder()
                    .action("DENIED")
                    .message("此操作暂不支持，请联系人工客服。")
                    .build();
        }

        // === 2. 确认列表中的工具需要二次确认 ===
        if (matches(securityProperties.getConfirmTools(), toolName)) {
            String confirmId = UUID.randomUUID().toString();
            pendingConfirmations.put(confirmId, new PendingConfirmation(
                    toolName, arguments, userId, System.currentTimeMillis()));
            return GuardDecision.builder()
                    .action("CONFIRM")
                    .message(buildConfirmMessage(toolName, arguments))
                    .confirmationId(confirmId)
                    .timeoutSeconds(60)
                    .build();
        }

        // === 3. 默认放行 ===
        return GuardDecision.builder().action("ALLOWED").build();
    }

    @Override
    public GuardResult handleConfirmation(String confirmationId, boolean approved, Long userId) {
        PendingConfirmation pending = pendingConfirmations.remove(confirmationId);
        if (pending == null) {
            return GuardResult.builder()
                    .executed(false)
                    .message("确认已过期或不存在，请重新操作。")
                    .build();
        }

        // T8：超时校验 —— 即便 Map 中还残留（调度清理未跑），也不允许执行
        if (System.currentTimeMillis() - pending.createdAt() > confirmationTimeoutMs) {
            return GuardResult.builder()
                    .executed(false)
                    .message("确认已过期或不存在，请重新操作。")
                    .build();
        }

        // T8：越权校验 —— 只有创建该确认的用户本人才能批准
        if (pending.userId() == null || userId == null || !pending.userId().equals(userId)) {
            return GuardResult.builder()
                    .executed(false)
                    .message("无权确认该操作。")
                    .build();
        }

        if (!approved) {
            return GuardResult.builder()
                    .executed(false)
                    .message("操作已取消。")
                    .build();
        }

        // 执行原工具调用
        String result = toolRegistry.execute(pending.toolName(), pending.arguments());
        return GuardResult.builder()
                .executed(true)
                .result(result)
                .message(result == null || !result.contains("\"error\"") ? "操作成功" : "操作失败")
                .build();
    }

    /**
     * T8：定期清理超时的待确认上下文（@EnableScheduling 已开启）。
     */
    @Scheduled(fixedRate = 30_000)
    void evictExpired() {
        long now = System.currentTimeMillis();
        pendingConfirmations.entrySet().removeIf(e ->
                now - e.getValue().createdAt() > confirmationTimeoutMs);
    }

    /**
     * 判断工具名是否命中配置列表。T3 修复：配置项与工具注册名存在 camelCase / snake_case 混用
     * （如配置 {@code applyRefund}、工具注册名 {@code apply_refund}），统一归一化后再比较。
     */
    private static boolean matches(List<String> configured, String toolName) {
        String normalized = toSnakeCase(toolName);
        return configured.stream()
                .map(SensitiveGuardImpl::toSnakeCase)
                .anyMatch(c -> c.equals(normalized));
    }

    /** camelCase → snake_case（已是 snake_case 原样返回） */
    private static String toSnakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private String buildConfirmMessage(String toolName, String arguments) {
        return switch (toSnakeCase(toolName)) {
            case "apply_refund" -> "您确定要申请退款吗？退款后优惠券将失效。";
            default -> "确定要执行此操作吗？";
        };
    }

    private record PendingConfirmation(String toolName, String arguments, Long userId, long createdAt) {
    }
}
