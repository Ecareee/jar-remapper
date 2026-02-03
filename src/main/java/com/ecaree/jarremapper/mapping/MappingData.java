package com.ecaree.jarremapper.mapping;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.NodeType;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 映射数据容器
 * 包含 SpecialSource 的 JarMapping 和带注释的 MappingEntry
 */
@Getter
@RequiredArgsConstructor
public class MappingData {
    /**
     * SpecialSource 使用的映射对象
     */
    private final JarMapping jarMapping;

    /**
     * 带注释的映射条目
     * Key: readable 格式的标识
     */
    private final Map<String, MappingEntry> entries;

    /**
     * 排除的包
     * 这些包下的类不会被重映射
     */
    private final Set<String> excludedPackages = new HashSet<>();

    /**
     * 根据可读类名查找类映射条目
     */
    public MappingEntry getClassEntry(String readableClassName) {
        return entries.get(readableClassName);
    }

    /**
     * 根据可读所有者和字段名查找字段映射条目
     */
    public MappingEntry getFieldEntry(String readableOwner, String readableName) {
        return entries.get(readableOwner + "/" + readableName);
    }

    /**
     * 根据可读所有者、方法名和描述符查找方法映射条目
     */
    public MappingEntry getMethodEntry(String readableOwner, String readableName, String readableDescriptor) {
        return entries.get(readableOwner + "/" + readableName + " " + readableDescriptor);
    }

    /**
     * 映射类名
     * 支持内部类和包映射
     */
    public String mapClass(String className) {
        if (isExcluded(className)) {
            return className;
        }
        return JarRemapper.mapTypeName(className, jarMapping.packages, jarMapping.classes, className);
    }

    public String mapField(String owner, String name) {
        if (isExcluded(owner)) {
            return name;
        }
        String mapped = jarMapping.tryClimb(jarMapping.fields, NodeType.FIELD, owner, name, null, -1);
        return mapped != null ? mapped : name;
    }

    public String mapMethod(String owner, String name, String descriptor) {
        if (isExcluded(owner)) {
            return name;
        }
        String mapped = jarMapping.tryClimb(jarMapping.methods, NodeType.METHOD, owner, name + " " + descriptor, null, -1);
        return mapped != null ? mapped : name;
    }

    public void addExcludedPackage(String packageName) {
        String normalized = packageName.endsWith("/") ? packageName : packageName + "/";
        excludedPackages.add(normalized);
    }

    public boolean isExcluded(String className) {
        for (String pkg : excludedPackages) {
            if (className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    public int getClassCount() {
        return jarMapping.classes.size();
    }

    public int getFieldCount() {
        return jarMapping.fields.size();
    }

    public int getMethodCount() {
        return jarMapping.methods.size();
    }

    public int getPackageCount() {
        return jarMapping.packages.size();
    }
}