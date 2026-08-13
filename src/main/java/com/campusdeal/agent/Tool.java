package com.campusdeal.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Bean 的公共方法为可被 Agent 调用的工具。
 *
 * <p>被 {@link ToolAutoRegister} 在启动时扫描并注册到 {@link ToolRegistry}。
 * 参数名通过编译期 {@code -parameters} 保留，作为 JSON Schema 的属性名。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /** 工具名；为空时使用方法名 */
    String name() default "";

    /** 工具描述：会进入模型看到的工具规格（ToolSpecification） */
    String description() default "";

    /** 敏感操作是否需要用户二次确认（如退款） */
    boolean requireConfirmation() default false;

    /** 调用所需权限（预留，Module 06 结合权限模型使用） */
    String[] requiredPermissions() default {};
}
