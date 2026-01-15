package com.ecaree.jarremapper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 完整映射信息注解
 * 记录重映射前后的完整信息
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface MappingInfo {
    /**
     * 混淆所有者类名（内部格式）
     */
    String obfOwner() default "";

    /**
     * 混淆名称
     */
    String obfName();

    /**
     * 混淆描述符
     */
    String obfDescriptor() default "";

    /**
     * 可读所有者类名（内部格式）
     */
    String readableOwner() default "";

    /**
     * 可读名称
     */
    String readableName();

    /**
     * 可读描述符
     */
    String readableDescriptor() default "";
}