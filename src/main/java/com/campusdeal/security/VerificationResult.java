package com.campusdeal.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 输出验证结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationResult {

    /** 是否通过验证 */
    private boolean passed;

    /** 置信度（0-1） */
    private double confidence;

    /** 是否检测到幻觉 */
    private boolean hallucinationDetected;

    /** 幻觉描述 */
    private String hallucinationDescription;

    /** 修正后的输出（如果原输出需要修正） */
    private String correctedOutput;

    /** 安全提示（附加到回复末尾） */
    private String safetyNote;
}
