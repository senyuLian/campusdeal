package com.campusdeal.security;

import com.campusdeal.agent.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
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

    @Override
    public GuardDecision evaluate(String toolName, String arguments, Long userId) {
        // === 1. 拒绝列表中的工具直接拒绝 ===
        if (securityProperties.getDeniedTools().contains(toolName)) {
            return GuardDecision.builder()
                    .action("DENIED")
                    .message("此操作暂不支持，请联系人工客服。")
                    .build();
        }

        // === 2. 确认列表中的工具需要二次确认 ===
        if (securityProperties.getConfirmTools().contains(toolName)) {
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
    public GuardResult handleConfirmation(String confirmationId, boolean approved) {
        PendingConfirmation pending = pendingConfirmations.remove(confirmationId);
        if (pending == null) {
            return GuardResult.builder()
                    .executed(false)
                    .message("确认已过期或不存在，请重新操作。")
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

    private String buildConfirmMessage(String toolName, String arguments) {
        return switch (toolName) {
            case "applyRefund" -> "您确定要申请退款吗？退款后优惠券将失效。";
            default -> "确定要执行此操作吗？";
        };
    }

    private record PendingConfirmation(String toolName, String arguments, Long userId, long createdAt) {
    }
}
