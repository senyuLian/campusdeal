package com.campusdeal.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * TR-01..04：工具注册表测试。
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    ToolExecutor executor;

    private final ToolRegistry registry = new ToolRegistry();

    @Test
    @DisplayName("TR-01 注册与查询：register 后可列出、可命中")
    void tr01_registerAndQuery() {
        registry.register(ToolMeta.builder().name("query_order").description("查询订单").build(), executor);

        assertTrue(registry.contains("query_order"));
        assertTrue(registry.getMeta("query_order").isPresent());
        assertEquals("查询订单", registry.getMeta("query_order").get().getDescription());
        assertEquals(1, registry.listTools().size());
        assertEquals("query_order", registry.listTools().get(0).getName());
    }

    @Test
    @DisplayName("TR-02 执行成功：返回工具 JSON 结果")
    void tr02_executeSuccess() throws Exception {
        registry.register(ToolMeta.builder().name("a").build(), executor);
        when(executor.execute("{}")).thenReturn("{\"ok\":true}");

        assertEquals("{\"ok\":true}", registry.execute("a", "{}"));
    }

    @Test
    @DisplayName("TR-03 未知工具：返回 error JSON，不影响主流程")
    void tr03_executeUnknownTool() {
        String result = registry.execute("no_such_tool", "{}");

        assertTrue(result.contains("unknown tool"));
        assertFalse(registry.contains("no_such_tool"));
    }

    @Test
    @DisplayName("TR-04 执行异常：异常被捕获并转为 error JSON")
    void tr04_executeFailure() throws Exception {
        registry.register(ToolMeta.builder().name("a").build(), executor);
        when(executor.execute("{}")).thenThrow(new RuntimeException("boom"));

        String result = registry.execute("a", "{}");

        assertTrue(result.contains("tool execution failed"));
        assertFalse(result.contains("boom"));
    }

    @Test
    @DisplayName("TR-05 生成模型工具规格：buildToolSpecifications 与注册工具一一对应")
    void tr05_buildToolSpecifications() {
        registry.register(ToolMeta.builder().name("a").description("工具A").build(), executor);
        registry.register(ToolMeta.builder().name("b").description("工具B").build(), executor);

        List<dev.langchain4j.agent.tool.ToolSpecification> specs = registry.buildToolSpecifications();

        assertEquals(2, specs.size());
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("a")));
        assertTrue(specs.stream().anyMatch(s -> s.description().equals("工具B")));
    }
}
