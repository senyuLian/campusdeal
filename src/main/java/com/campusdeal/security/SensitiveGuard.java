package com.campusdeal.security;

/**
 * 敏感操作门禁：退款/核销等操作需二次确认，高风险操作直接拒绝。
 */
public interface SensitiveGuard {

    /**
     * 检查操作是否需要确认或拒绝。
     *
     * @param toolName  工具名称（如 applyRefund）
     * @param arguments 工具参数（JSON）
     * @param userId    用户 ID
     * @return 决策结果
     */
    GuardDecision evaluate(String toolName, String arguments, Long userId);

    /**
     * 处理用户的确认响应。
     *
     * @param confirmationId 确认 ID
     * @param approved       用户是否同意
     * @return 如批准，执行原操作并返回结果
     */
    GuardResult handleConfirmation(String confirmationId, boolean approved);
}
