package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.md_5.specialsource.JarMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smali 重映射
 * 基于文本直接正则匹配重映射
 * 弃用了 smali -> dex -> remap dex -> baksmali 流程，因为会丢失注释和格式
 */
@Slf4j
@RequiredArgsConstructor
public class SmaliRemapper {
    private static final Pattern CLASS_DEF_PATTERN = Pattern.compile(
            "^\\.class\\s+.*?(L[^;]+;)");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "L([a-zA-Z_][a-zA-Z0-9_/$]*);");
    private static final Pattern FIELD_DEF_PATTERN = Pattern.compile(
            "^\\.field\\s+(?:[^:]+\\s+)?([a-zA-Z_][a-zA-Z0-9_$]*):(\\[*(?:L[^;]+;|[ZBCSIJFD]))");
    private static final Pattern METHOD_DEF_PATTERN = Pattern.compile(
            "^\\.method\\s+(?:.+\\s+)?([a-zA-Z_<][a-zA-Z0-9_>$]*)\\(([^)]*)\\)(.+)");
    private static final Pattern MEMBER_REF_PATTERN = Pattern.compile(
            "(L[^;]+;)->([a-zA-Z_<][a-zA-Z0-9_>$]*)([:（(])");
    private static final Pattern STRING_PATTERN = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"");
    private final MappingData mappingData;

    /**
     * 重映射 Smali 目录
     * 基于文本直接正则匹配重映射
     *
     * @param inputDir  输入 Smali 目录
     * @param outputDir 输出 Smali 目录
     * @throws IOException 如果 IO 操作失败
     */
    public void remapSmali(File inputDir, File outputDir) throws IOException {
        if (!inputDir.exists()) {
            throw new IOException("Input directory does not exist: " + inputDir);
        }

        List<File> smaliFiles = new ArrayList<>();
        collectSmaliFiles(inputDir, smaliFiles);

        if (smaliFiles.isEmpty()) {
            log.warn("No smali files found in: {}", inputDir);
            FileUtils.ensureDirectory(outputDir);
            return;
        }

        log.info("Starting smali remapping");
        log.info("Input: {}", inputDir);
        log.info("Output: {}", outputDir);
        log.info("Found {} smali files", smaliFiles.size());

        FileUtils.deleteDirectory(outputDir);
        FileUtils.ensureDirectory(outputDir);

        JarMapping jarMapping = mappingData.getJarMapping();
        int processedCount = 0;
        int remappedCount = 0;

        for (File smaliFile : smaliFiles) {
            RemapResult result = processSmaliFile(smaliFile, inputDir, outputDir, jarMapping);
            processedCount++;
            if (result.wasRemapped) {
                remappedCount++;
            }
        }

        log.info("Smali remapping completed: {}/{} files remapped", remappedCount, processedCount);
    }

    private RemapResult processSmaliFile(File inputFile, File inputDir, File outputDir,
                                         JarMapping jarMapping) throws IOException {
        String content = FileUtils.readFileToString(inputFile);
        String[] lines = content.split("\n", -1);

        String currentClass = extractCurrentClass(lines);
        if (currentClass == null) {
            Path relativePath = inputDir.toPath().relativize(inputFile.toPath());
            File outputFile = new File(outputDir, relativePath.toString());
            FileUtils.copyFile(inputFile, outputFile);
            return new RemapResult(false);
        }

        StringBuilder result = new StringBuilder();
        boolean anyRemapped = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String remappedLine = remapLine(line, currentClass, jarMapping);
            if (!remappedLine.equals(line)) {
                anyRemapped = true;
            }
            result.append(remappedLine);
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        String mappedClassName = jarMapping.classes.getOrDefault(currentClass, currentClass);
        String relativePath = mappedClassName.replace('/', File.separatorChar) + ".smali";
        File outputFile = new File(outputDir, relativePath);

        FileUtils.writeStringToFile(outputFile, result.toString());

        return new RemapResult(anyRemapped);
    }

    private String extractCurrentClass(String[] lines) {
        for (String line : lines) {
            Matcher m = CLASS_DEF_PATTERN.matcher(line);
            if (m.find()) {
                String typeDescriptor = m.group(1);
                return typeDescriptor.substring(1, typeDescriptor.length() - 1);
            }
        }
        return null;
    }

    private String remapLine(String line, String currentClass, JarMapping jarMapping) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return line;
        }

        int commentIdx = findCommentIndex(line);
        String codePart = commentIdx >= 0 ? line.substring(0, commentIdx) : line;
        String commentPart = commentIdx >= 0 ? line.substring(commentIdx) : "";

        if (codePart.trim().isEmpty()) {
            return line;
        }

        String remappedCode = remapCodePart(codePart, currentClass, jarMapping);

        return remappedCode + commentPart;
    }

    private int findCommentIndex(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return i;
            }
        }
        return -1;
    }

    private String remapCodePart(String code, String currentClass, JarMapping jarMapping) {
        List<int[]> stringRanges = findStringRanges(code);

        Matcher fieldDefMatcher = FIELD_DEF_PATTERN.matcher(code);
        if (fieldDefMatcher.find()) {
            String fieldName = fieldDefMatcher.group(1);
            String fieldType = fieldDefMatcher.group(2);

            String key = currentClass + "/" + fieldName;
            String mappedName = jarMapping.fields.getOrDefault(key, fieldName);
            String mappedType = remapTypeDescriptor(fieldType, jarMapping);

            code = code.substring(0, fieldDefMatcher.start(1)) + mappedName + ":"
                    + mappedType + code.substring(fieldDefMatcher.end(2));
            stringRanges = findStringRanges(code);
        }

        Matcher methodDefMatcher = METHOD_DEF_PATTERN.matcher(code);
        if (methodDefMatcher.find()) {
            String methodName = methodDefMatcher.group(1);
            String params = methodDefMatcher.group(2);
            String returnType = methodDefMatcher.group(3);

            String descriptor = "(" + params + ")" + returnType.split("\\s")[0];
            String key = currentClass + "/" + methodName + " " + descriptor;
            String mappedName = jarMapping.methods.getOrDefault(key, methodName);

            String mappedParams = remapTypeDescriptor(params, jarMapping);
            String mappedReturn = remapTypeDescriptor(returnType, jarMapping);

            int nameStart = methodDefMatcher.start(1);
            int returnEnd = methodDefMatcher.end(3);
            code = code.substring(0, nameStart) + mappedName + "(" + mappedParams + ")" + mappedReturn
                    + code.substring(returnEnd);
            stringRanges = findStringRanges(code);
        }

        StringBuilder sb = new StringBuilder();
        Matcher memberRefMatcher = MEMBER_REF_PATTERN.matcher(code);
        int lastEnd = 0;

        while (memberRefMatcher.find()) {
            if (isInStringRange(memberRefMatcher.start(), stringRanges)) {
                continue;
            }

            String ownerType = memberRefMatcher.group(1);
            String memberName = memberRefMatcher.group(2);
            String separator = memberRefMatcher.group(3);

            String ownerClass = ownerType.substring(1, ownerType.length() - 1);
            String mappedOwner = jarMapping.classes.getOrDefault(ownerClass, ownerClass);

            String mappedName = memberName;
            if (separator.equals(":")) {
                String fieldKey = ownerClass + "/" + memberName;
                mappedName = jarMapping.fields.getOrDefault(fieldKey, memberName);
            } else if (separator.equals("(")) {
                int closeParenIdx = code.indexOf(')', memberRefMatcher.end());
                if (closeParenIdx > 0) {
                    int returnEnd = findReturnTypeEnd(code, closeParenIdx + 1);
                    String params = code.substring(memberRefMatcher.end(), closeParenIdx);
                    String returnType = code.substring(closeParenIdx + 1, returnEnd);
                    String descriptor = "(" + params + ")" + returnType;
                    String methodKey = ownerClass + "/" + memberName + " " + descriptor;
                    mappedName = jarMapping.methods.getOrDefault(methodKey, memberName);
                }
            }

            sb.append(code, lastEnd, memberRefMatcher.start());
            sb.append("L").append(mappedOwner).append(";->").append(mappedName).append(separator);
            lastEnd = memberRefMatcher.end();
        }
        sb.append(code.substring(lastEnd));
        code = sb.toString();
        stringRanges = findStringRanges(code);

        code = remapStandaloneTypes(code, jarMapping, stringRanges);

        return code;
    }

    private String remapStandaloneTypes(String code, JarMapping jarMapping, List<int[]> stringRanges) {
        StringBuffer sb = new StringBuffer();
        Matcher typeMatcher = TYPE_PATTERN.matcher(code);

        while (typeMatcher.find()) {
            if (isInStringRange(typeMatcher.start(), stringRanges)) {
                continue;
            }

            String className = typeMatcher.group(1);
            String mappedClass = jarMapping.classes.getOrDefault(className, className);
            typeMatcher.appendReplacement(sb, Matcher.quoteReplacement("L" + mappedClass + ";"));
        }
        typeMatcher.appendTail(sb);

        return sb.toString();
    }

    private String remapTypeDescriptor(String descriptor, JarMapping jarMapping) {
        if (descriptor == null || descriptor.isEmpty()) {
            return descriptor;
        }

        StringBuffer sb = new StringBuffer();
        Matcher m = TYPE_PATTERN.matcher(descriptor);
        while (m.find()) {
            String className = m.group(1);
            String mapped = jarMapping.classes.getOrDefault(className, className);
            m.appendReplacement(sb, Matcher.quoteReplacement("L" + mapped + ";"));
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private int findReturnTypeEnd(String code, int start) {
        if (start >= code.length()) return code.length();

        char c = code.charAt(start);
        if (c == '[') {
            return findReturnTypeEnd(code, start + 1);
        } else if (c == 'L') {
            int semiIdx = code.indexOf(';', start);
            return semiIdx >= 0 ? semiIdx + 1 : code.length();
        } else if ("ZBCSIJFDV".indexOf(c) >= 0) {
            return start + 1;
        }
        return start;
    }

    private List<int[]> findStringRanges(String code) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = STRING_PATTERN.matcher(code);
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
        }
        return ranges;
    }

    private boolean isInStringRange(int pos, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (pos >= range[0] && pos < range[1]) {
                return true;
            }
        }
        return false;
    }

    private void collectSmaliFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                collectSmaliFiles(child, files);
            } else if (child.getName().endsWith(".smali")) {
                files.add(child);
            }
        }
    }

    @RequiredArgsConstructor
    private static class RemapResult {
        final boolean wasRemapped;
    }
}