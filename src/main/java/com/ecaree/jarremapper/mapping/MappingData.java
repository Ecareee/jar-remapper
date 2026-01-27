package com.ecaree.jarremapper.mapping;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.md_5.specialsource.JarMapping;

import java.util.Map;

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
     * 映射类名，支持内部类和包映射
     * 参考 SpecialSource JarRemapper.mapClassName
     */
    public String mapClass(String className) {
        if (className == null) return null;

        String mapped = jarMapping.classes.get(className);
        if (mapped != null) {
            return mapped;
        }

        int dollarIdx = className.lastIndexOf('$');
        if (dollarIdx != -1) {
            String outer = className.substring(0, dollarIdx);
            String innerPart = className.substring(dollarIdx);
            String mappedOuter = mapClass(outer);
            if (mappedOuter != null && !mappedOuter.equals(outer)) {
                return mappedOuter + innerPart;
            }
        }

        for (Map.Entry<String, String> entry : jarMapping.packages.entrySet()) {
            String oldPkg = entry.getKey();
            if (matchPackage(oldPkg, className)) {
                String newPkg = entry.getValue();
                return movePackage(oldPkg, newPkg, className);
            }
        }

        return className;
    }

    private boolean matchPackage(String packageName, String className) {
        if (".".equals(packageName)) {
            return className.indexOf('/') == -1;
        }
        return className.startsWith(packageName);
    }

    private String movePackage(String oldPkg, String newPkg, String className) {
        if (".".equals(oldPkg)) {
            if (".".equals(newPkg)) {
                return className;
            }
            return newPkg + className;
        }
        String simpleName = className.substring(oldPkg.length());
        if (".".equals(newPkg)) {
            return simpleName;
        }
        return newPkg + simpleName;
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