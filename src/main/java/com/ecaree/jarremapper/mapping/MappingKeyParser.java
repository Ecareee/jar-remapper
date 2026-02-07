package com.ecaree.jarremapper.mapping;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 映射 key 解析器
 * 用于解析 JarMapping 中的字段/方法 key
 */
public class MappingKeyParser {
    /**
     * 解析字段 key
     * 格式：owner/name 或 owner/name desc
     */
    public static FieldKey parseFieldKey(String key) {
        int spaceIdx = key.indexOf(' ');
        String keyWithoutDesc = spaceIdx > 0 ? key.substring(0, spaceIdx) : key;
        String descriptor = spaceIdx > 0 ? key.substring(spaceIdx + 1) : null;

        int slashIdx = keyWithoutDesc.lastIndexOf('/');
        String owner = slashIdx > 0 ? keyWithoutDesc.substring(0, slashIdx) : "";
        String name = slashIdx > 0 ? keyWithoutDesc.substring(slashIdx + 1) : keyWithoutDesc;

        return new FieldKey(owner, name, descriptor);
    }

    /**
     * 解析方法 key
     * 格式：owner/name desc
     */
    public static MethodKey parseMethodKey(String key) {
        int spaceIdx = key.indexOf(' ');
        if (spaceIdx < 0) {
            return null;
        }

        String ownerAndName = key.substring(0, spaceIdx);
        String descriptor = key.substring(spaceIdx + 1);

        int slashIdx = ownerAndName.lastIndexOf('/');
        if (slashIdx < 0) {
            return null;
        }

        String owner = ownerAndName.substring(0, slashIdx);
        String name = ownerAndName.substring(slashIdx + 1);

        return new MethodKey(owner, name, descriptor);
    }

    @Getter
    @RequiredArgsConstructor
    public static class FieldKey {
        private final String owner;
        private final String name;
        private final String descriptor;

        public String toKey() {
            if (descriptor != null) {
                return owner + "/" + name + " " + descriptor;
            }
            return owner + "/" + name;
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class MethodKey {
        private final String owner;
        private final String name;
        private final String descriptor;

        public String toKey() {
            return owner + "/" + name + " " + descriptor;
        }
    }
}