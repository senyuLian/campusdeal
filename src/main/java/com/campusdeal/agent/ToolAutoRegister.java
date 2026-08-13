package com.campusdeal.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时扫描 {@link Tool} 注解方法并注册到 {@link ToolRegistry}。
 *
 * <p>在 {@code afterSingletonsInstantiated} 阶段执行（所有单例已创建），
 * 且只对「确实声明了 @Tool 方法」的 Bean 调用 getBean，避免触发懒加载单例（如 RedissonClient）的提前实例化。</p>
 */
@Slf4j
@Component
public class ToolAutoRegister implements SmartInitializingSingleton {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ToolRegistry toolRegistry;

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (type == null || !hasToolMethods(type)) {
                continue;
            }
            Object bean = applicationContext.getBean(beanName);
            registerMethods(bean, type);
        }
    }

    private boolean hasToolMethods(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Tool.class)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private void registerMethods(Object bean, Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                ToolMeta meta = ToolMeta.builder()
                        .name(name)
                        .description(annotation.description())
                        .parametersSchema(buildSchema(method))
                        .requireConfirmation(annotation.requireConfirmation())
                        .requiredPermissions(annotation.requiredPermissions())
                        .build();
                toolRegistry.register(meta, argumentsJson -> invoke(method, bean, argumentsJson));
            }
            current = current.getSuperclass();
        }
    }

    /** 根据方法参数生成 langchain4j 工具参数 Schema */
    private ToolParameters buildSchema(Method method) {
        Parameter[] params = method.getParameters();
        Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
        for (Parameter p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", toJsonType(p.getType()));
            properties.put(p.getName(), prop);
        }
        return ToolParameters.builder()
                .type("object")
                .properties(properties)
                .required(params.length == 0 ? List.of()
                        : Arrays.stream(params).map(Parameter::getName).toList())
                .build();
    }

    private static String toJsonType(Class<?> type) {
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return "string";
    }

    private String invoke(Method method, Object bean, String argumentsJson) throws Exception {
        JsonNode node = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            JsonNode value = node.get(params[i].getName());
            if (value == null || value.isNull()) {
                args[i] = null;
            } else {
                args[i] = objectMapper.convertValue(value, params[i].getType());
            }
        }
        Object result = method.invoke(bean, args);
        return serializeResult(result);
    }

    private String serializeResult(Object result) throws JsonProcessingException {
        if (result == null) {
            return "{}";
        }
        if (result instanceof String s) {
            return s;
        }
        return objectMapper.writeValueAsString(result);
    }
}
