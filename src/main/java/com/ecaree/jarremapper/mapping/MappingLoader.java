package com.ecaree.jarremapper.mapping;

import net.md_5.specialsource.JarMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 映射加载器
 * 支持以下格式：
 * - YAML（自定义，支持注释）
 * - SRG/CSRG/TSRG/TSRG2/ProGuard（SpecialSource 原生）
 */
public class MappingLoader {
    public static MappingData load(File mappingFile) throws IOException {
        String fileName = mappingFile.getName().toLowerCase();

        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return loadYaml(mappingFile);
        } else {
            // SRG/CSRG/TSRG/TSRG2/ProGuard 格式，使用 SpecialSource 加载
            return loadSpecialSource(mappingFile);
        }
    }

    public static MappingData loadYaml(File yamlFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(YamlMappingModel.class, options));

        try (InputStream is = Files.newInputStream(yamlFile.toPath())) {
            YamlMappingModel model = yaml.load(is);
            return convertYamlToMappingData(model);
        }
    }

    /**
     * 加载 SpecialSource 格式映射（SRG/CSRG/TSRG/TSRG2/ProGuard）
     * SpecialSource JarMapping.loadMappings 方法会自动检测格式
     */
    public static MappingData loadSpecialSource(File srgFile) throws IOException {
        JarMapping jarMapping = new JarMapping();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(srgFile.toPath()), StandardCharsets.UTF_8))) {
            jarMapping.loadMappings(reader, null, null, false);
        }

        return convertJarMappingToMappingData(jarMapping);
    }

    /**
     * 加载映射并应用反向映射
     *
     * @param mappingFile 映射文件
     * @param reverse     是否反转映射
     */
    public static MappingData load(File mappingFile, boolean reverse) throws IOException {
        MappingData data = load(mappingFile);

        if (reverse) {
            return reverseMapping(data);
        }

        return data;
    }

    private static MappingData reverseMapping(MappingData original) {
        JarMapping reversed = new JarMapping();
        Map<String, MappingEntry> reversedEntries = new HashMap<>();

        JarMapping orig = original.getJarMapping();

        for (Map.Entry<String, String> entry : orig.packages.entrySet()) {
            reversed.packages.put(entry.getValue(), entry.getKey());
        }

        for (Map.Entry<String, String> entry : orig.classes.entrySet()) {
            reversed.classes.put(entry.getValue(), entry.getKey());
        }

        for (Map.Entry<String, String> entry : orig.fields.entrySet()) {
            String key = entry.getKey(); // obfOwner/obfName
            String readableName = entry.getValue();

            // 检查是否有空格分隔的描述符
            int spaceIdx = key.indexOf(' ');
            String keyWithoutDesc = spaceIdx > 0 ? key.substring(0, spaceIdx) : key;
            String obfDesc = spaceIdx > 0 ? key.substring(spaceIdx + 1) : null;

            int slashIdx = keyWithoutDesc.lastIndexOf('/');
            String obfOwner = keyWithoutDesc.substring(0, slashIdx);
            String obfName = keyWithoutDesc.substring(slashIdx + 1);
            String readableOwner = orig.classes.getOrDefault(obfOwner, obfOwner);

            // 反转时保留描述符格式
            String newKey = obfDesc != null
                    ? readableOwner + "/" + readableName + " " + remapDescriptor(obfDesc, orig)
                    : readableOwner + "/" + readableName;
            reversed.fields.put(newKey, obfName);
        }

        for (Map.Entry<String, String> entry : orig.methods.entrySet()) {
            String key = entry.getKey(); // obfOwner/obfName desc
            String readableName = entry.getValue();

            int spaceIdx = key.indexOf(' ');
            String ownerAndName = key.substring(0, spaceIdx);
            String obfDescriptor = key.substring(spaceIdx + 1);

            int slashIdx = ownerAndName.lastIndexOf('/');
            String obfOwner = ownerAndName.substring(0, slashIdx);
            String readableOwner = orig.classes.getOrDefault(obfOwner, obfOwner);

            String readableDescriptor = remapDescriptor(obfDescriptor, orig);

            String newKey = readableOwner + "/" + readableName + " " + readableDescriptor;
            String obfName = ownerAndName.substring(slashIdx + 1);
            reversed.methods.put(newKey, obfName);
        }

        for (Map.Entry<String, MappingEntry> entry : original.getEntries().entrySet()) {
            MappingEntry origEntry = entry.getValue();
            MappingEntry reversedEntry;

            switch (origEntry.getType()) {
                case CLASS:
                    reversedEntry = MappingEntry.forClass(
                            origEntry.getReadableName(),
                            origEntry.getObfName(),
                            origEntry.getComment());
                    break;
                case FIELD:
                    reversedEntry = MappingEntry.forField(
                            origEntry.getReadableOwner(), origEntry.getReadableName(), origEntry.getReadableDescriptor(),
                            origEntry.getObfOwner(), origEntry.getObfName(), origEntry.getObfDescriptor(),
                            origEntry.getComment());
                    break;
                case METHOD:
                    reversedEntry = MappingEntry.forMethod(
                            origEntry.getReadableOwner(), origEntry.getReadableName(), origEntry.getReadableDescriptor(),
                            origEntry.getObfOwner(), origEntry.getObfName(), origEntry.getObfDescriptor(),
                            origEntry.getComment());
                    break;
                default:
                    continue;
            }

            reversedEntries.put(reversedEntry.getReadableKey(), reversedEntry);
        }

        return new MappingData(reversed, reversedEntries);
    }

    private static MappingData convertYamlToMappingData(YamlMappingModel model) {
        JarMapping jarMapping = new JarMapping();
        Map<String, MappingEntry> entries = new HashMap<>();

        if (model == null || model.getClasses() == null) {
            return new MappingData(jarMapping, entries);
        }

        for (YamlMappingModel.ClassMapping classMapping : model.getClasses()) {
            String obfClass = toInternalName(classMapping.getObfuscated());
            String readableClass = toInternalName(classMapping.getReadable());

            jarMapping.classes.put(obfClass, readableClass);

            MappingEntry classEntry = MappingEntry.forClass(
                    obfClass, readableClass, classMapping.getComment());
            entries.put(classEntry.getReadableKey(), classEntry);

            if (classMapping.getFields() != null) {
                for (YamlMappingModel.FieldMapping fieldMapping : classMapping.getFields()) {
                    String obfFieldKey = obfClass + "/" + fieldMapping.getObfuscated();
                    jarMapping.fields.put(obfFieldKey, fieldMapping.getReadable());

                    String readableDescriptor = remapDescriptor(fieldMapping.getType(), jarMapping);

                    MappingEntry fieldEntry = MappingEntry.forField(
                            obfClass, fieldMapping.getObfuscated(), fieldMapping.getType(),
                            readableClass, fieldMapping.getReadable(), readableDescriptor,
                            fieldMapping.getComment());
                    entries.put(fieldEntry.getReadableKey(), fieldEntry);
                }
            }

            if (classMapping.getMethods() != null) {
                for (YamlMappingModel.MethodMapping methodMapping : classMapping.getMethods()) {
                    String obfMethodKey = obfClass + "/" + methodMapping.getObfuscated() + " " + methodMapping.getDescriptor();
                    jarMapping.methods.put(obfMethodKey, methodMapping.getReadable());

                    String readableDescriptor = remapDescriptor(methodMapping.getDescriptor(), jarMapping);

                    MappingEntry methodEntry = MappingEntry.forMethod(
                            obfClass, methodMapping.getObfuscated(), methodMapping.getDescriptor(),
                            readableClass, methodMapping.getReadable(), readableDescriptor,
                            methodMapping.getComment());
                    entries.put(methodEntry.getReadableKey(), methodEntry);
                }
            }
        }

        return new MappingData(jarMapping, entries);
    }

    private static MappingData convertJarMappingToMappingData(JarMapping jarMapping) {
        Map<String, MappingEntry> entries = new HashMap<>();

        for (Map.Entry<String, String> entry : jarMapping.classes.entrySet()) {
            String obfClass = entry.getKey();
            String readableClass = entry.getValue();

            MappingEntry classEntry = MappingEntry.forClass(obfClass, readableClass, null);
            entries.put(classEntry.getReadableKey(), classEntry);
        }

        for (Map.Entry<String, String> entry : jarMapping.fields.entrySet()) {
            String key = entry.getKey(); // obfOwner/obfName 或 obfOwner/obfName/obfDesc
            String readableName = entry.getValue();

            // 解析字段 key，可能包含描述符
            String obfOwner;
            String obfName;
            String obfDesc = null;

            // 检查是否有描述符（TSRG2 格式）
            String[] parts = key.split("/");
            if (parts.length >= 2) {
                obfOwner = String.join("/", java.util.Arrays.copyOfRange(parts, 0, parts.length - 1));
                String lastPart = parts[parts.length - 1];

                // 检查最后一部分是否包含描述符
                int spaceIdx = lastPart.indexOf(' ');
                if (spaceIdx > 0) {
                    obfName = lastPart.substring(0, spaceIdx);
                    obfDesc = lastPart.substring(spaceIdx + 1);
                } else {
                    obfName = lastPart;
                }
            } else {
                obfOwner = "";
                obfName = key;
            }

            String readableOwner = jarMapping.classes.getOrDefault(obfOwner, obfOwner);

            MappingEntry fieldEntry = MappingEntry.forField(
                    obfOwner, obfName, obfDesc,
                    readableOwner, readableName, obfDesc != null ? remapDescriptor(obfDesc, jarMapping) : null,
                    null);
            entries.put(fieldEntry.getReadableKey(), fieldEntry);
        }

        for (Map.Entry<String, String> entry : jarMapping.methods.entrySet()) {
            String key = entry.getKey();  // obfOwner/obfName desc
            String readableName = entry.getValue();

            int spaceIdx = key.indexOf(' ');
            if (spaceIdx < 0) continue;

            String ownerAndName = key.substring(0, spaceIdx);
            String obfDescriptor = key.substring(spaceIdx + 1);

            int slashIdx = ownerAndName.lastIndexOf('/');
            if (slashIdx < 0) continue;

            String obfOwner = ownerAndName.substring(0, slashIdx);
            String obfName = ownerAndName.substring(slashIdx + 1);

            String readableOwner = jarMapping.classes.getOrDefault(obfOwner, obfOwner);
            String readableDescriptor = remapDescriptor(obfDescriptor, jarMapping);

            MappingEntry methodEntry = MappingEntry.forMethod(
                    obfOwner, obfName, obfDescriptor,
                    readableOwner, readableName, readableDescriptor,
                    null);
            entries.put(methodEntry.getReadableKey(), methodEntry);
        }

        return new MappingData(jarMapping, entries);
    }

    /**
     * 将类名转换为内部格式（点号 -> 斜杠）
     */
    private static String toInternalName(String className) {
        if (className == null) return null;
        return className.replace('.', '/');
    }

    private static String remapDescriptor(String descriptor, JarMapping jarMapping) {
        if (descriptor == null) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);

            if (c == 'L') {
                // 对象类型
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    result.append(descriptor.substring(i));
                    break;
                }
                String className = descriptor.substring(i + 1, end);
                String mappedClass = jarMapping.classes.getOrDefault(className, className);
                result.append('L').append(mappedClass).append(';');
                i = end + 1;
            } else if (c == '[') {
                // 数组
                result.append('[');
                i++;
            } else {
                // 基本类型或其他
                result.append(c);
                i++;
            }
        }

        return result.toString();
    }
}