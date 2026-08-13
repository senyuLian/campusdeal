package com.campusdeal.security;

import com.campusdeal.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OV-01..05：输出验证测试（幻觉检测 + PII 脱敏 + 截断，无外部依赖）。
 */
@ExtendWith(MockitoExtension.class)
class OutputVerifierTest {

    @Mock
    private SecurityProperties securityProperties;

    @InjectMocks
    private OutputVerifierImpl verifier;

    @BeforeEach
    void setUp() {
        // OV-05 空输出在幻觉检测前就返回，故用 lenient
        lenient().when(securityProperties.isHallucinationCheck()).thenReturn(true);
    }

    private VerificationContext context(ToolResult... results) {
        List<ToolResult> list = results == null ? new ArrayList<>() : List.of(results);
        return VerificationContext.builder()
                .userQuestion("查订单")
                .toolResults(list)
                .totalIterations(1)
                .build();
    }

    @Test
    @DisplayName("OV-01 正常输出：通过且置信度高")
    void ov01_normalOutputPasses() {
        ToolResult tr = ToolResult.builder().success(true)
                .result("{\"count\":2}").build();
        VerificationResult result = verifier.verify("为您查询到订单，共 2 笔。", context(tr));

        assertTrue(result.isPassed());
        assertTrue(result.getConfidence() >= 0.8);
        assertFalse(result.isHallucinationDetected());
        assertNull(result.getHallucinationDescription());
    }

    @Test
    @DisplayName("OV-02 幻觉：工具无结果但 LLM 声称已获取数据 → 拒绝")
    void ov02_fabricatedDataRejected() {
        VerificationResult result = verifier.verify("您的订单号是123", context());

        assertFalse(result.isPassed());
        assertTrue(result.isHallucinationDetected());
        assertNotNull(result.getHallucinationDescription());
        assertEquals("抱歉，我暂时无法准确回答您的问题。建议您联系人工客服确认。",
                result.getCorrectedOutput());
    }

    @Test
    @DisplayName("OV-03 数字矛盾：置信度降低且记录幻觉描述")
    void ov03_numberDiscrepancyLowersConfidence() {
        ToolResult tr = ToolResult.builder().success(true)
                .result("{\"amount\":15}").build();
        VerificationResult result = verifier.verify("这个订单价格25元", context(tr));

        assertTrue(result.isPassed());
        assertTrue(result.getConfidence() < 1.0);
        assertTrue(result.isHallucinationDetected());
        assertNotNull(result.getHallucinationDescription());
    }

    @Test
    @DisplayName("OV-04 输出含 PII：手机号被脱敏")
    void ov04_outputPiiMasked() {
        VerificationResult result = verifier.verify("请致电13812345678咨询", context());

        assertTrue(result.getCorrectedOutput().contains("1****8"));
        assertFalse(result.getCorrectedOutput().contains("13812345678"));
    }

    @Test
    @DisplayName("OV-05 空输出：未通过且返回兜底文案")
    void ov05_emptyOutputFallback() {
        VerificationResult nullResult = verifier.verify(null, context());
        assertFalse(nullResult.isPassed());
        assertEquals(0.0, nullResult.getConfidence(), 0.001);
        assertEquals("抱歉，我暂时无法回答您的问题，请稍后再试。", nullResult.getCorrectedOutput());

        VerificationResult blankResult = verifier.verify("   ", context());
        assertFalse(blankResult.isPassed());
    }
}
