package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认处理结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardResult {

    private boolean executed;

    /** 执行结果（JSON，工具返回） */
    private String result;

    private String message;
}
