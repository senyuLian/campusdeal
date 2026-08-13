package com.campusdeal.security;

import com.campusdeal.agent.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出校验实现：幻觉检测 + 输出 PII 脱敏 + 长度截断。
 *
 * <p>幻觉检测规则：
 * <ol>
 *   <li>工具未返回数据，但 LLM 声称已获取数据 → HIGH（替换为安全回复）</li>
 *   <li>LLM 输出数字与工具结果不一致 → LOW（降低置信度）</li>
 * </ol>
 * 规则匹配是兜底：即使 Prompt Injection 绕过输入层，编造的数据也会在此被拦截。</p>
 */
@Slf4j
@Component
public class OutputVerifierImpl implements OutputVerifier {

    @Resource
    private SecurityProperties securityProperties;

    private static final int MAX_OUTPUT_LENGTH = 4000;
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final List<String> DATA_CLAIM_KEYWORDS = List.of(
            "您的订单", "您的优惠券", "您的退款", "为您查询到", "已为您");

    @Override
    public VerificationResult verify(String llmOutput, VerificationContext context) {
        // === 1. 空输出检查 ===
        if (llmOutput == null || llmOutput.isBlank()) {
            return VerificationResult.builder()
                    .passed(false)
                    .confidence(0.0)
                    .correctedOutput("抱歉，我暂时无法回答您的问题，请稍后再试。")
                    .build();
        }

        boolean passed = true;
        double confidence = 1.0;
        String hallucinationDesc = null;
        boolean hallucinated = false;

        // === 2. 幻觉检测（如开关开启） ===
        if (securityProperties.isHallucinationCheck()) {
            HallucinationResult hr = detectHallucination(llmOutput, context);
            if (hr.detected()) {
                hallucinated = true;
                confidence -= 0.5;
                hallucinationDesc = hr.description();
                log.warn("Hallucination detected: {}", hallucinationDesc);
                if ("HIGH".equals(hr.severity())) {
                    return VerificationResult.builder()
                            .passed(false)
                            .confidence(confidence)
                            .hallucinationDetected(true)
                            .hallucinationDescription(hallucinationDesc)
                            .correctedOutput("抱歉，我暂时无法准确回答您的问题。建议您联系人工客服确认。")
                            .build();
                }
            }
        }

        // === 3. 输出 PII 脱敏（防止 LLM 泄露训练数据中的 PII） ===
        String cleaned = outputPiiMask(llmOutput);

        // === 4. 输出截断 ===
        if (cleaned.length() > MAX_OUTPUT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_OUTPUT_LENGTH) + "\n\n...（回复过长，已截断）";
        }

        return VerificationResult.builder()
                .passed(passed)
                .confidence(confidence)
                .hallucinationDetected(hallucinated)
                .hallucinationDescription(hallucinationDesc)
                .correctedOutput(cleaned)
                .build();
    }

    private HallucinationResult detectHallucination(String output, VerificationContext context) {
        List<ToolResult> toolResults = context.getToolResults();

        // 规则 1：工具没有返回数据，但 LLM 声称已获取数据 → HIGH
        if (toolResults == null || toolResults.isEmpty()) {
            for (String kw : DATA_CLAIM_KEYWORDS) {
                if (output.contains(kw)) {
                    return HallucinationResult.high("LLM 在没有工具结果的情况下声称获取了数据");
                }
            }
        } else {
            // 规则 2：LLM 输出的数字与工具返回的数字不一致 → LOW
            List<String> toolNumbers = new ArrayList<>();
            for (ToolResult tr : toolResults) {
                if (tr.getResult() != null) {
                    Matcher m = NUMBER.matcher(tr.getResult());
                    while (m.find()) {
                        toolNumbers.add(m.group());
                    }
                }
            }
            Matcher out = NUMBER.matcher(output);
            while (out.find()) {
                if (!toolNumbers.contains(out.group())) {
                    return HallucinationResult.low("输出数字与工具结果不一致: " + out.group());
                }
            }
        }
        return HallucinationResult.none();
    }

    private String outputPiiMask(String output) {
        Matcher phone = PiiPatterns.PHONE.matcher(output);
        return phone.replaceAll(m -> maskPhone(m.group()));
    }

    private String maskPhone(String original) {
        if (original.length() <= 4) {
            return "****";
        }
        return original.charAt(0) + "****" + original.charAt(original.length() - 1);
    }

    private record HallucinationResult(boolean detected, String severity, String description) {
        static HallucinationResult high(String d) {
            return new HallucinationResult(true, "HIGH", d);
        }

        static HallucinationResult low(String d) {
            return new HallucinationResult(true, "LOW", d);
        }

        static HallucinationResult none() {
            return new HallucinationResult(false, null, null);
        }
    }
}
