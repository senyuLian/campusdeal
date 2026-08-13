package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 敏感操作确认请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmRequest {

    /** 前端回传的确认 ID（来自 GuardDecision.confirmationId） */
    private String confirmationId;

    /** 用户是否批准该操作 */
    private boolean approved;
}
