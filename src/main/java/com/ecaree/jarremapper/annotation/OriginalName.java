package com.ecaree.jarremapper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 原始名称注解
 * 记录重映射前的混淆名称信息
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface OriginalName {
    /**
     * 原始（混淆）所有者类名（内部格式，如 r0/c）
     */
    String owner() default "";

    /**
     * 原始（混淆）名称
     */
    String name();

    /**
     * 原始（混淆）描述符（字段为类型描述符，方法为方法描述符）
     */
    String descriptor() default "";
}