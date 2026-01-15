package com.ecaree.jarremapper.mapping;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 统一的映射条目
 * 用于存储类/字段/方法的映射信息及注释
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MappingEntry {
    private final Type type;
    private final String obfOwner;      // 类：null，字段/方法：所属类（内部格式）
    private final String obfName;       // 混淆名称
    private final String obfDescriptor; // 类：null，字段：类型描述符，方法：方法描述符
    private final String readableOwner;
    private final String readableName;
    private final String readableDescriptor;
    private final String comment;

    public static MappingEntry forClass(String obfName, String readableName, String comment) {
        return new MappingEntry(Type.CLASS, null, obfName, null, null, readableName, null, comment);
    }

    public static MappingEntry forField(String obfOwner, String obfName, String obfDescriptor,
                                        String readableOwner, String readableName, String readableDescriptor,
                                        String comment) {
        return new MappingEntry(Type.FIELD, obfOwner, obfName, obfDescriptor,
                readableOwner, readableName, readableDescriptor, comment);
    }

    public static MappingEntry forMethod(String obfOwner, String obfName, String obfDescriptor,
                                         String readableOwner, String readableName, String readableDescriptor,
                                         String comment) {
        return new MappingEntry(Type.METHOD, obfOwner, obfName, obfDescriptor,
                readableOwner, readableName, readableDescriptor, comment);
    }

    public boolean hasComment() {
        return comment != null && !comment.isEmpty();
    }

    /**
     * 生成用于查找的 key
     * 类：readable 类名（内部格式）
     * 字段：readableOwner/readableName
     * 方法：readableOwner/readableName readableDescriptor
     */
    public String getReadableKey() {
        return switch (type) {
            case CLASS -> readableName;
            case FIELD -> readableOwner + "/" + readableName;
            case METHOD -> readableOwner + "/" + readableName + " " + readableDescriptor;
        };
    }

    public enum Type {
        CLASS,
        FIELD,
        METHOD
    }
}