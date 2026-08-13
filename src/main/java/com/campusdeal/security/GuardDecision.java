package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 敏感操作门禁决策。
 *
 * <p>action 取值：ALLOWED（直接执行）/ CONFIRM（需用户确认）/ DENIED（拒绝执行）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardDecision {

    private String action;

    /** 拒绝原因或确认提示文案 */
    private String message;

    /** 确认 ID（CONFIRM 时生成） */
    private String confirmationId;

    /** 超时时间（秒），超时自动拒绝 */
    private int timeoutSeconds;
}
