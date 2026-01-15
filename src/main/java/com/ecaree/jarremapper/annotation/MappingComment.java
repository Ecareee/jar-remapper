package com.ecaree.jarremapper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 映射注释注解
 * 用于将 YAML 映射文件中的 comment 字段写入字节码
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface MappingComment {
    /**
     * 注释内容
     */
    String value();
}