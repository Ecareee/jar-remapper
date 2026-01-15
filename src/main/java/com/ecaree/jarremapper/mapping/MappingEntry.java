package com.ecaree.jarremapper.mapping;

/**
 * 统一的映射条目
 * 用于存储类/字段/方法的映射信息及注释
 */
public record MappingEntry(
        Type type,
        String obfOwner,      // 类：null，字段/方法：所属类（内部格式）
        String obfName,       // 混淆名称
        String obfDescriptor, // 类：null，字段：类型描述符，方法：方法描述符
        String readableOwner,
        String readableName,
        String readableDescriptor,
        String comment
) {
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
    public String readableKey() {
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