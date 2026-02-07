package com.ecaree.jarremapper.mapping;

import lombok.extern.slf4j.Slf4j;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
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
import java.util.List;
import java.util.Map;

/**
 * 映射加载器
 * 支持以下格式：
 * 1. 自定义：YAML（支持注释）
 * 2. mapping-io：Tiny/Tiny2/Enigma/ProGuard/SRG/XSRG/JAM/CSRG/TSRG/TSRG2/JOBF/...
 * 3. SpecialSource：SRG/CSRG/TSRG/TSRG2/ProGuard，作为回退
 */
@Slf4j
public class MappingLoader {
    public static MappingData load(File mappingFile) throws IOException {
        return load(mappingFile, null, null);
    }

    /**
     * 加载映射文件（指定命名空间）
     *
     * @param mappingFile     映射文件
     * @param sourceNamespace 源命名空间，用于 Tiny/TSRG2 等多命名空间格式，null 表示使用默认
     * @param targetNamespace 目标命名空间，用于 Tiny/TSRG2 等多命名空间格式，null 表示使用默认
     */
    public static MappingData load(File mappingFile, String sourceNamespace, String targetNamespace) throws IOException {
        String fileName = mappingFile.getName().toLowerCase();

        // 1. YAML
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return loadYaml(mappingFile);
        }

        // 2. 检查是否包含 SRG 的 PK: 行，mapping-io 不支持包映射，回退到 SpecialSource
        if (containsPackageMapping(mappingFile)) {
            log.info("File contains package mappings (PK:), using SpecialSource (mapping-io does not support package mappings)");
            return loadSpecialSource(mappingFile);
        }

        // 3. mapping-io
        try {
            MappingFormat format = MappingReader.detectFormat(mappingFile.toPath());
            if (format != null) {
                log.info("Detected mapping format: {} (via mapping-io)", format.name);
                return loadMappingIo(mappingFile, format, sourceNamespace, targetNamespace);
            }
        } catch (Exception e) {
            log.debug("mapping-io detection failed, falling back to SpecialSource: {}", e.getMessage());
        }

        // 4. 回退到 SpecialSource
        log.info("Loading mappings via SpecialSource (fallback)");
        return loadSpecialSource(mappingFile);
    }

    private static boolean containsPackageMapping(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".jar") || fileName.endsWith(".zip")) {
            return false;
        }

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 100) {
                lineCount++;
                String trimmed = line.trim();
                if (trimmed.startsWith("PK:")) {
                    return true;
                }
                if (trimmed.startsWith("CL:") || trimmed.startsWith("FD:") || trimmed.startsWith("MD:")) {
                    return false;
                }
            }
        }
        return false;
    }

    public static MappingData loadYaml(File yamlFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(YamlMappingModel.class, options));

        try (InputStream is = Files.newInputStream(yamlFile.toPath())) {
            YamlMappingModel model = yaml.load(is);
            return convertYamlToMappingData(model);
        }
    }

    public static MappingData loadMappingIo(File mappingFile, MappingFormat format,
                                            String sourceNamespace, String targetNamespace) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(mappingFile.toPath(), format, tree);

        return convertMappingTreeToMappingData(tree, sourceNamespace, targetNamespace);
    }

    public static MappingData loadMappingIo(File mappingFile, String sourceNamespace, String targetNamespace) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(mappingFile.toPath(), tree);
        return convertMappingTreeToMappingData(tree, sourceNamespace, targetNamespace);
    }

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
        return load(mappingFile, null, null, reverse);
    }

    /**
     * 加载映射并应用反向映射
     * 指定命名空间
     */
    public static MappingData load(File mappingFile, String sourceNamespace, String targetNamespace, boolean reverse) throws IOException {
        MappingData data = load(mappingFile, sourceNamespace, targetNamespace);

        if (reverse) {
            return reverseMapping(data);
        }

        return data;
    }

    private static MappingData convertMappingTreeToMappingData(MemoryMappingTree tree,
                                                               String sourceNamespace,
                                                               String targetNamespace) {
        JarMapping jarMapping = new JarMapping();
        Map<String, MappingEntry> entries = new HashMap<>();

        List<String> dstNamespaces = tree.getDstNamespaces();
        String srcNs = tree.getSrcNamespace();

        int srcNsId = -1;
        int dstNsId = 0;

        if (sourceNamespace != null && !sourceNamespace.equals(srcNs)) {
            int idx = dstNamespaces.indexOf(sourceNamespace);
            if (idx >= 0) {
                srcNsId = idx;
            } else {
                log.warn("Source namespace '{}' not found, using default: {}", sourceNamespace, srcNs);
            }
        }

        if (targetNamespace != null) {
            if (targetNamespace.equals(srcNs)) {
                log.warn("Target namespace '{}' is the source namespace, consider using reverse mode", targetNamespace);
            } else {
                int idx = dstNamespaces.indexOf(targetNamespace);
                if (idx >= 0) {
                    dstNsId = idx;
                } else {
                    log.warn("Target namespace '{}' not found, using default: {}",
                            targetNamespace, dstNamespaces.isEmpty() ? "none" : dstNamespaces.get(0));
                }
            }
        }

        String effectiveSrcNs = srcNsId == -1 ? srcNs : dstNamespaces.get(srcNsId);
        String effectiveDstNs = dstNamespaces.isEmpty() ? "none" : dstNamespaces.get(dstNsId);
        log.info("Using namespaces: {} -> {}", effectiveSrcNs, effectiveDstNs);

        for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
            String srcClassName = srcNsId == -1 ? classMapping.getSrcName() : classMapping.getDstName(srcNsId);
            String dstClassName = classMapping.getDstName(dstNsId);

            if (srcClassName == null) continue;
            if (dstClassName == null) dstClassName = srcClassName;

            jarMapping.classes.put(srcClassName, dstClassName);

            String comment = classMapping.getComment();
            MappingEntry classEntry = MappingEntry.forClass(srcClassName, dstClassName, comment);
            entries.put(classEntry.getReadableKey(), classEntry);

            for (MappingTree.FieldMapping fieldMapping : classMapping.getFields()) {
                String srcFieldName = srcNsId == -1 ? fieldMapping.getSrcName() : fieldMapping.getDstName(srcNsId);
                String dstFieldName = fieldMapping.getDstName(dstNsId);
                String srcDesc = srcNsId == -1 ? fieldMapping.getSrcDesc() : fieldMapping.getDstDesc(srcNsId);

                if (srcFieldName == null) continue;
                if (dstFieldName == null) dstFieldName = srcFieldName;

                String fieldKey = srcClassName + "/" + srcFieldName;
                jarMapping.fields.put(fieldKey, dstFieldName);

                String dstDesc = fieldMapping.getDstDesc(dstNsId);
                if (dstDesc == null) dstDesc = remapDescriptor(srcDesc, jarMapping);

                String fieldComment = fieldMapping.getComment();
                MappingEntry fieldEntry = MappingEntry.forField(
                        srcClassName, srcFieldName, srcDesc,
                        dstClassName, dstFieldName, dstDesc,
                        fieldComment);
                entries.put(fieldEntry.getReadableKey(), fieldEntry);
            }

            for (MappingTree.MethodMapping methodMapping : classMapping.getMethods()) {
                String srcMethodName = srcNsId == -1 ? methodMapping.getSrcName() : methodMapping.getDstName(srcNsId);
                String dstMethodName = methodMapping.getDstName(dstNsId);
                String srcDesc = srcNsId == -1 ? methodMapping.getSrcDesc() : methodMapping.getDstDesc(srcNsId);

                if (srcMethodName == null || srcDesc == null) continue;
                if (dstMethodName == null) dstMethodName = srcMethodName;

                String methodKey = srcClassName + "/" + srcMethodName + " " + srcDesc;
                jarMapping.methods.put(methodKey, dstMethodName);

                String dstDesc = methodMapping.getDstDesc(dstNsId);
                if (dstDesc == null) dstDesc = remapDescriptor(srcDesc, jarMapping);

                String methodComment = methodMapping.getComment();
                MappingEntry methodEntry = MappingEntry.forMethod(
                        srcClassName, srcMethodName, srcDesc,
                        dstClassName, dstMethodName, dstDesc,
                        methodComment);
                entries.put(methodEntry.getReadableKey(), methodEntry);
            }
        }

        log.info("Loaded {} classes, {} fields, {} methods via mapping-io",
                jarMapping.classes.size(), jarMapping.fields.size(), jarMapping.methods.size());

        return new MappingData(jarMapping, entries);
    }

    public static MappingData reverseMapping(MappingData original) {
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
            String key = entry.getKey();
            String readableName = entry.getValue();

            MappingKeyParser.FieldKey fieldKey = MappingKeyParser.parseFieldKey(key);
            String readableOwner = orig.classes.getOrDefault(fieldKey.getOwner(), fieldKey.getOwner());

            String newKey;
            if (fieldKey.getDescriptor() != null) {
                String readableDesc = remapDescriptor(fieldKey.getDescriptor(), orig);
                newKey = readableOwner + "/" + readableName + " " + readableDesc;
            } else {
                newKey = readableOwner + "/" + readableName;
            }
            reversed.fields.put(newKey, fieldKey.getName());
        }

        for (Map.Entry<String, String> entry : orig.methods.entrySet()) {
            String key = entry.getKey();
            String readableName = entry.getValue();

            MappingKeyParser.MethodKey methodKey = MappingKeyParser.parseMethodKey(key);
            if (methodKey == null) continue;

            String readableOwner = orig.classes.getOrDefault(methodKey.getOwner(), methodKey.getOwner());
            String readableDescriptor = remapDescriptor(methodKey.getDescriptor(), orig);

            String newKey = readableOwner + "/" + readableName + " " + readableDescriptor;
            reversed.methods.put(newKey, methodKey.getName());
        }

        for (Map.Entry<String, MappingEntry> entry : original.getEntries().entrySet()) {
            MappingEntry origEntry = entry.getValue();
            MappingEntry reversedEntry = reverseEntry(origEntry);
            if (reversedEntry != null) {
                reversedEntries.put(reversedEntry.getReadableKey(), reversedEntry);
            }
        }

        return new MappingData(reversed, reversedEntries);
    }

    private static MappingEntry reverseEntry(MappingEntry origEntry) {
        switch (origEntry.getType()) {
            case CLASS:
                return MappingEntry.forClass(
                        origEntry.getReadableName(),
                        origEntry.getObfName(),
                        origEntry.getComment());
            case FIELD:
                return MappingEntry.forField(
                        origEntry.getReadableOwner(), origEntry.getReadableName(), origEntry.getReadableDescriptor(),
                        origEntry.getObfOwner(), origEntry.getObfName(), origEntry.getObfDescriptor(),
                        origEntry.getComment());
            case METHOD:
                return MappingEntry.forMethod(
                        origEntry.getReadableOwner(), origEntry.getReadableName(), origEntry.getReadableDescriptor(),
                        origEntry.getObfOwner(), origEntry.getObfName(), origEntry.getObfDescriptor(),
                        origEntry.getComment());
            default:
                return null;
        }
    }

    private static MappingData convertYamlToMappingData(YamlMappingModel model) {
        JarMapping jarMapping = new JarMapping();
        Map<String, MappingEntry> entries = new HashMap<>();

        if (model == null || model.getClasses() == null) {
            return new MappingData(jarMapping, entries);
        }

        for (YamlMappingModel.ClassMapping classMapping : model.getClasses()) {
            if (classMapping == null) {
                continue;
            }

            String obfClass = toInternalName(classMapping.getObfuscated());
            String readableClass = toInternalName(classMapping.getReadable());

            if (obfClass == null || readableClass == null) {
                log.warn("Skipping invalid class mapping: obfuscated={}, readable={}",
                        classMapping.getObfuscated(), classMapping.getReadable());
                continue;
            }

            if (jarMapping.classes.containsKey(obfClass)) {
                String existing = jarMapping.classes.get(obfClass);
                if (!readableClass.equals(existing)) {
                    throw new IllegalArgumentException("Duplicate class mapping: " + obfClass + " -> " + readableClass
                            + " but already mapped to " + existing);
                }
                continue;
            }

            jarMapping.classes.put(obfClass, readableClass);

            MappingEntry classEntry = MappingEntry.forClass(
                    obfClass, readableClass, classMapping.getComment());
            entries.put(classEntry.getReadableKey(), classEntry);

            if (classMapping.getFields() != null) {
                for (YamlMappingModel.FieldMapping fieldMapping : classMapping.getFields()) {
                    if (fieldMapping == null) {
                        continue;
                    }

                    String obfFieldName = fieldMapping.getObfuscated();
                    String readableFieldName = fieldMapping.getReadable();

                    if (obfFieldName == null || readableFieldName == null) {
                        log.warn("Skipping invalid field mapping: class={}, obfuscated={}, readable={}",
                                readableClass, obfFieldName, readableFieldName);
                        continue;
                    }

                    String obfFieldKey = obfClass + "/" + obfFieldName;

                    if (jarMapping.fields.containsKey(obfFieldKey)) {
                        String existing = jarMapping.fields.get(obfFieldKey);
                        if (!readableFieldName.equals(existing)) {
                            throw new IllegalArgumentException("Duplicate field mapping: " + obfFieldKey + " -> " + readableFieldName
                                    + " but already mapped to " + existing);
                        }
                        continue;
                    }

                    jarMapping.fields.put(obfFieldKey, readableFieldName);

                    String readableDescriptor = remapDescriptor(fieldMapping.getType(), jarMapping);

                    MappingEntry fieldEntry = MappingEntry.forField(
                            obfClass, obfFieldName, fieldMapping.getType(),
                            readableClass, readableFieldName, readableDescriptor,
                            fieldMapping.getComment());
                    entries.put(fieldEntry.getReadableKey(), fieldEntry);
                }
            }

            if (classMapping.getMethods() != null) {
                for (YamlMappingModel.MethodMapping methodMapping : classMapping.getMethods()) {
                    if (methodMapping == null) {
                        continue;
                    }

                    String obfMethodName = methodMapping.getObfuscated();
                    String readableMethodName = methodMapping.getReadable();
                    String descriptor = methodMapping.getDescriptor();

                    if (obfMethodName == null || readableMethodName == null || descriptor == null) {
                        log.warn("Skipping invalid method mapping: class={}, obfuscated={}, readable={}, descriptor={}",
                                readableClass, obfMethodName, readableMethodName, descriptor);
                        continue;
                    }

                    String obfMethodKey = obfClass + "/" + obfMethodName + " " + descriptor;

                    if (jarMapping.methods.containsKey(obfMethodKey)) {
                        String existing = jarMapping.methods.get(obfMethodKey);
                        if (!readableMethodName.equals(existing)) {
                            throw new IllegalArgumentException("Duplicate method mapping: " + obfMethodKey + " -> " + readableMethodName
                                    + " but already mapped to " + existing);
                        }
                        continue;
                    }

                    jarMapping.methods.put(obfMethodKey, readableMethodName);

                    String readableDescriptor = remapDescriptor(descriptor, jarMapping);

                    MappingEntry methodEntry = MappingEntry.forMethod(
                            obfClass, obfMethodName, descriptor,
                            readableClass, readableMethodName, readableDescriptor,
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
            String key = entry.getKey();
            String readableName = entry.getValue();

            MappingKeyParser.FieldKey fieldKey = MappingKeyParser.parseFieldKey(key);
            String readableOwner = jarMapping.classes.getOrDefault(fieldKey.getOwner(), fieldKey.getOwner());
            String readableDesc = fieldKey.getDescriptor() != null
                    ? remapDescriptor(fieldKey.getDescriptor(), jarMapping)
                    : null;

            MappingEntry fieldEntry = MappingEntry.forField(
                    fieldKey.getOwner(), fieldKey.getName(), fieldKey.getDescriptor(),
                    readableOwner, readableName, readableDesc,
                    null);
            entries.put(fieldEntry.getReadableKey(), fieldEntry);
        }

        for (Map.Entry<String, String> entry : jarMapping.methods.entrySet()) {
            String key = entry.getKey();
            String readableName = entry.getValue();

            MappingKeyParser.MethodKey methodKey = MappingKeyParser.parseMethodKey(key);
            if (methodKey == null) continue;

            String readableOwner = jarMapping.classes.getOrDefault(methodKey.getOwner(), methodKey.getOwner());
            String readableDescriptor = remapDescriptor(methodKey.getDescriptor(), jarMapping);

            MappingEntry methodEntry = MappingEntry.forMethod(
                    methodKey.getOwner(), methodKey.getName(), methodKey.getDescriptor(),
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

    static String remapDescriptor(String descriptor, JarMapping jarMapping) {
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