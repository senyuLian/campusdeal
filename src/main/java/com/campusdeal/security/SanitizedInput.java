package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 清洗后的用户输入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanitizedInput {

    /** 清洗后的文本（PII 已脱敏） */
    private String cleanedText;

    /** 是否包含 PII */
    private boolean containsPii;

    /** PII 脱敏映射（如 phone → 138****1234） */
    private Map<String, String> piiReplacements;

    /** 安全置信度（0-1，低于阈值拒绝） */
    private double safetyScore;

    /** 安全告警列表 */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
