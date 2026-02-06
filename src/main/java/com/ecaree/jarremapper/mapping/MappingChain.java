package com.ecaree.jarremapper.mapping;

import lombok.extern.slf4j.Slf4j;
import net.md_5.specialsource.JarMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 映射链
 * 支持多步映射串联，如 obf -> intermediary -> named
 * 自动合并为单个映射 obf -> named
 */
@Slf4j
public class MappingChain {
    private final List<MappingData> chain = new ArrayList<>();

    public MappingChain add(MappingData mapping) {
        chain.add(mapping);
        return this;
    }

    public MappingChain add(MappingData mapping, boolean reverse) {
        if (reverse) {
            chain.add(MappingLoader.reverseMapping(mapping));
        } else {
            chain.add(mapping);
        }
        return this;
    }

    public int size() {
        return chain.size();
    }

    public boolean isEmpty() {
        return chain.isEmpty();
    }

    public MappingData merge() {
        if (chain.isEmpty()) {
            throw new IllegalStateException("Mapping chain is empty");
        }
        if (chain.size() == 1) {
            log.info("Chain contains only one mapping, returning as-is");
            return chain.get(0);
        }

        log.info("Merging {} mappings in chain", chain.size());

        MappingData result = chain.get(0);
        for (int i = 1; i < chain.size(); i++) {
            log.info("Composing mapping {} of {}", i + 1, chain.size());
            result = composeMappings(result, chain.get(i));
            log.info("Intermediate result: {} classes, {} fields, {} methods",
                    result.getClassCount(), result.getFieldCount(), result.getMethodCount());
        }

        log.info("Chain merge completed: {} classes, {} fields, {} methods",
                result.getClassCount(), result.getFieldCount(), result.getMethodCount());

        return result;
    }

    private MappingData composeMappings(MappingData first, MappingData second) {
        JarMapping composed = new JarMapping();
        Map<String, MappingEntry> entries = new HashMap<>();

        JarMapping firstMap = first.getJarMapping();
        JarMapping secondMap = second.getJarMapping();

        composePackages(composed, firstMap, secondMap);
        composeClasses(composed, entries, first, second);
        composeFields(composed, entries, first, second);
        composeMethods(composed, entries, first, second);

        return new MappingData(composed, entries);
    }

    private void composePackages(JarMapping composed, JarMapping firstMap, JarMapping secondMap) {
        for (Map.Entry<String, String> entry : firstMap.packages.entrySet()) {
            String obfPkg = entry.getKey();
            String intermediatePkg = entry.getValue();
            String finalPkg = secondMap.packages.getOrDefault(intermediatePkg, intermediatePkg);
            composed.packages.put(obfPkg, finalPkg);
        }
        for (Map.Entry<String, String> entry : secondMap.packages.entrySet()) {
            if (!composed.packages.containsValue(entry.getKey())) {
                String srcPkg = entry.getKey();
                if (!firstMap.packages.containsValue(srcPkg)) {
                    composed.packages.putIfAbsent(srcPkg, entry.getValue());
                }
            }
        }
    }

    private void composeClasses(JarMapping composed, Map<String, MappingEntry> entries,
                                MappingData first, MappingData second) {
        JarMapping firstMap = first.getJarMapping();
        JarMapping secondMap = second.getJarMapping();

        for (Map.Entry<String, String> entry : firstMap.classes.entrySet()) {
            String obfName = entry.getKey();
            String intermediateName = entry.getValue();
            String finalName = mapClassThroughSecond(intermediateName, secondMap);
            composed.classes.put(obfName, finalName);

            MappingEntry firstEntry = first.getClassEntry(intermediateName);
            MappingEntry secondEntry = second.getClassEntry(finalName);
            String comment = mergeComments(
                    firstEntry != null ? firstEntry.getComment() : null,
                    secondEntry != null ? secondEntry.getComment() : null
            );
            MappingEntry composedEntry = MappingEntry.forClass(obfName, finalName, comment);
            entries.put(composedEntry.getReadableKey(), composedEntry);
        }
    }

    private void composeFields(JarMapping composed, Map<String, MappingEntry> entries,
                               MappingData first, MappingData second) {
        JarMapping firstMap = first.getJarMapping();
        JarMapping secondMap = second.getJarMapping();

        for (Map.Entry<String, String> entry : firstMap.fields.entrySet()) {
            String key = entry.getKey();
            String intermediateName = entry.getValue();

            MappingKeyParser.FieldKey fieldKey = MappingKeyParser.parseFieldKey(key);
            String intermediateOwner = firstMap.classes.getOrDefault(fieldKey.getOwner(), fieldKey.getOwner());
            String finalOwner = mapClassThroughSecond(intermediateOwner, secondMap);

            String finalName = lookupFieldInSecond(intermediateName, intermediateOwner,
                    fieldKey.getDescriptor(), firstMap, secondMap);

            composed.fields.put(key, finalName);

            MappingEntry firstEntry = first.getFieldEntry(intermediateOwner, intermediateName);
            MappingEntry secondEntry = second.getFieldEntry(finalOwner, finalName);
            String comment = mergeComments(
                    firstEntry != null ? firstEntry.getComment() : null,
                    secondEntry != null ? secondEntry.getComment() : null
            );
            String obfDesc = fieldKey.getDescriptor();
            String finalDesc = obfDesc != null ? remapDescriptorFully(obfDesc, firstMap, secondMap) : null;
            MappingEntry composedEntry = MappingEntry.forField(
                    fieldKey.getOwner(), fieldKey.getName(), obfDesc,
                    finalOwner, finalName, finalDesc,
                    comment
            );
            entries.put(composedEntry.getReadableKey(), composedEntry);
        }
    }

    private String lookupFieldInSecond(String intermediateName, String intermediateOwner,
                                       String obfDescriptor, JarMapping firstMap, JarMapping secondMap) {
        String secondKey = intermediateOwner + "/" + intermediateName;
        if (obfDescriptor != null) {
            String intermediateDesc = MappingLoader.remapDescriptor(obfDescriptor, firstMap);
            secondKey = intermediateOwner + "/" + intermediateName + " " + intermediateDesc;
        }
        String finalName = secondMap.fields.getOrDefault(secondKey, intermediateName);
        if (finalName.equals(intermediateName) && obfDescriptor != null) {
            String simpleKey = intermediateOwner + "/" + intermediateName;
            finalName = secondMap.fields.getOrDefault(simpleKey, intermediateName);
        }
        return finalName;
    }

    private void composeMethods(JarMapping composed, Map<String, MappingEntry> entries,
                                MappingData first, MappingData second) {
        JarMapping firstMap = first.getJarMapping();
        JarMapping secondMap = second.getJarMapping();

        for (Map.Entry<String, String> entry : firstMap.methods.entrySet()) {
            String key = entry.getKey();
            String intermediateName = entry.getValue();

            MappingKeyParser.MethodKey methodKey = MappingKeyParser.parseMethodKey(key);
            if (methodKey == null) continue;

            String intermediateOwner = firstMap.classes.getOrDefault(methodKey.getOwner(), methodKey.getOwner());
            String intermediateDesc = MappingLoader.remapDescriptor(methodKey.getDescriptor(), firstMap);
            String finalOwner = mapClassThroughSecond(intermediateOwner, secondMap);

            String secondKey = intermediateOwner + "/" + intermediateName + " " + intermediateDesc;
            String finalName = secondMap.methods.getOrDefault(secondKey, intermediateName);

            composed.methods.put(key, finalName);

            MappingEntry firstEntry = first.getMethodEntry(intermediateOwner, intermediateName, intermediateDesc);
            MappingEntry secondEntry = second.getMethodEntry(finalOwner, finalName,
                    MappingLoader.remapDescriptor(intermediateDesc, secondMap));
            String comment = mergeComments(
                    firstEntry != null ? firstEntry.getComment() : null,
                    secondEntry != null ? secondEntry.getComment() : null
            );
            String finalDesc = remapDescriptorFully(methodKey.getDescriptor(), firstMap, secondMap);
            MappingEntry composedEntry = MappingEntry.forMethod(
                    methodKey.getOwner(), methodKey.getName(), methodKey.getDescriptor(),
                    finalOwner, finalName, finalDesc,
                    comment
            );
            entries.put(composedEntry.getReadableKey(), composedEntry);
        }
    }

    /**
     * 通过 second 映射类名
     * 处理内部类
     */
    private String mapClassThroughSecond(String className, JarMapping secondMap) {
        String mapped = secondMap.classes.get(className);
        if (mapped != null) {
            return mapped;
        }

        int dollarIdx = className.indexOf('$');
        if (dollarIdx > 0) {
            String outerClass = className.substring(0, dollarIdx);
            String innerPart = className.substring(dollarIdx);
            String mappedOuter = secondMap.classes.get(outerClass);
            if (mappedOuter != null) {
                return mappedOuter + innerPart;
            }
        }

        return className;
    }

    /**
     * 完全重映射描述符
     * 通过两个映射
     */
    private String remapDescriptorFully(String descriptor, JarMapping first, JarMapping second) {
        String intermediate = MappingLoader.remapDescriptor(descriptor, first);
        return MappingLoader.remapDescriptor(intermediate, second);
    }

    private String mergeComments(String first, String second) {
        if (first == null || first.isEmpty()) return second;
        if (second == null || second.isEmpty()) return first;
        if (first.equals(second)) return first;
        return first + " | " + second;
    }
}