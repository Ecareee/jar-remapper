package com.ecaree.jarremapper.remap;

import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import com.google.common.collect.ImmutableList;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import net.md_5.specialsource.JarMapping;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Smali 重映射
 * 使用 smali -> dex -> remap dex -> baksmali 流程
 */
@Log
@RequiredArgsConstructor
public class SmaliRemapper {
    private final MappingData mappingData;

    /**
     * 重映射 Smali 目录
     * 流程：smali 目录 -> DEX -> 重映射 DEX -> baksmali -> 输出目录
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
            log.warning("No smali files found in: " + inputDir);
            FileUtils.ensureDirectory(outputDir);
            return;
        }

        log.info("Starting smali remapping");
        log.info("Input: " + inputDir);
        log.info("Output: " + outputDir);
        log.info("Found " + smaliFiles.size() + " smali files");

        // 使用 build 目录下的临时目录，避免清理问题
        Path tempDir = Paths.get("build", "tmp", "smali-remap-" + System.currentTimeMillis());
        Files.createDirectories(tempDir);
        File tempDex = tempDir.resolve("classes.dex").toFile();
        File remappedDex = tempDir.resolve("classes-remapped.dex").toFile();

        try {
            // 1. 编译 smali 到 dex
            log.info("1. Compiling smali to dex");
            compileSmaliToDex(smaliFiles, tempDex);
            log.info("Dex created: " + tempDex.length() + " bytes");

            // 2. 重映射 dex
            log.info("2. Remapping dex");
            remapDex(tempDex, remappedDex);
            log.info("Remapped dex created: " + remappedDex.length() + " bytes");

            // 3. 反编译 dex 到 smali
            log.info("3. Disassembling dex to smali");
            FileUtils.deleteDirectory(outputDir);
            FileUtils.ensureDirectory(outputDir);
            disassembleDexToSmali(remappedDex, outputDir);

            log.info("Smali remapping completed successfully");

        } finally {
            // 不强制清理临时文件，让 gradle clean 处理
            try {
                FileUtils.deleteDirectory(tempDir.toFile());
            } catch (Exception e) {
                log.warning("Could not clean temp directory (will be cleaned by gradle clean): " + tempDir);
            }
        }
    }

    private void compileSmaliToDex(List<File> smaliFiles, File dexFile) throws IOException {
        SmaliOptions options = new SmaliOptions();
        options.apiLevel = 30;
        options.outputDexFile = dexFile.getAbsolutePath();

        List<String> smaliPaths = new ArrayList<>();
        for (File file : smaliFiles) {
            smaliPaths.add(file.getAbsolutePath());
        }

        boolean success = Smali.assemble(options, smaliPaths);
        if (!success) {
            throw new IOException("Failed to compile smali files");
        }
    }

    private void remapDex(File inputDex, File outputDex) throws IOException {
        JarMapping jarMapping = mappingData.getJarMapping();

        DexBackedDexFile dexFile;
        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(inputDex.toPath()))) {
            dexFile = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), bis);
        }

        RewriterModule rewriterModule = new MappingRewriterModule(jarMapping);
        DexRewriter rewriter = new DexRewriter(rewriterModule);

        DexFile rewrittenDex = rewriter.getDexFileRewriter().rewrite(dexFile);

        DexPool dexPool = new DexPool(Opcodes.getDefault());
        for (ClassDef classDef : rewrittenDex.getClasses()) {
            dexPool.internClass(classDef);
        }

        dexPool.writeTo(new FileDataStore(outputDex));
    }

    private void disassembleDexToSmali(File dexFile, File outputDir) throws IOException {
        DexBackedDexFile dex;
        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(dexFile.toPath()))) {
            dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), bis);
        }

        BaksmaliOptions options = new BaksmaliOptions();

        boolean success = Baksmali.disassembleDexFile(dex, outputDir, 4, options);
        if (!success) {
            throw new IOException("Failed to disassemble dex file");
        }
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
    private static class MappingRewriterModule extends RewriterModule {
        private final JarMapping jarMapping;

        @Nonnull
        @Override
        public Rewriter<String> getTypeRewriter(@Nonnull Rewriters rewriters) {
            return this::remapType;
        }

        @Nonnull
        @Override
        public Rewriter<FieldReference> getFieldReferenceRewriter(@Nonnull Rewriters rewriters) {
            return fieldRef -> {
                String definingClass = remapType(fieldRef.getDefiningClass());
                String name = remapFieldName(fieldRef.getDefiningClass(), fieldRef.getName());
                String type = remapType(fieldRef.getType());

                // 使用 dexlib2 的不可变实现，确保能被 DexPool 识别
                return new ImmutableFieldReference(definingClass, name, type);
            };
        }

        @Nonnull
        @Override
        public Rewriter<MethodReference> getMethodReferenceRewriter(@Nonnull Rewriters rewriters) {
            return methodRef -> {
                String definingClass = remapType(methodRef.getDefiningClass());
                String name = remapMethodName(methodRef.getDefiningClass(), methodRef.getName(),
                        buildMethodDescriptor(methodRef));

                List<String> paramTypes = new ArrayList<>();
                for (CharSequence param : methodRef.getParameterTypes()) {
                    paramTypes.add(remapType(param.toString()));
                }
                String returnType = remapType(methodRef.getReturnType());

                // 使用 dexlib2 的不可变实现，确保能被 DexPool 识别
                return new ImmutableMethodReference(definingClass, name,
                        ImmutableList.copyOf(paramTypes), returnType);
            };
        }

        private String remapType(String type) {
            if (type == null) return null;

            // 数组
            int arrayDim = 0;
            String baseType = type;
            while (baseType.startsWith("[")) {
                arrayDim++;
                baseType = baseType.substring(1);
            }

            // 对象类型 L...;
            if (baseType.startsWith("L") && baseType.endsWith(";")) {
                String className = baseType.substring(1, baseType.length() - 1);
                String mapped = jarMapping.classes.get(className);
                if (mapped != null) {
                    baseType = "L" + mapped + ";";
                }
            }

            // 恢复数组维度
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arrayDim; i++) {
                sb.append('[');
            }
            sb.append(baseType);
            return sb.toString();
        }

        private String remapFieldName(String owner, String name) {
            String ownerClass = owner;
            if (owner.startsWith("L") && owner.endsWith(";")) {
                ownerClass = owner.substring(1, owner.length() - 1);
            }

            String key = ownerClass + "/" + name;
            return jarMapping.fields.getOrDefault(key, name);
        }

        private String remapMethodName(String owner, String name, String descriptor) {
            if (name.equals("<init>") || name.equals("<clinit>")) {
                return name;
            }

            String ownerClass = owner;
            if (owner.startsWith("L") && owner.endsWith(";")) {
                ownerClass = owner.substring(1, owner.length() - 1);
            }

            String key = ownerClass + "/" + name + " " + descriptor;
            return jarMapping.methods.getOrDefault(key, name);
        }

        private String buildMethodDescriptor(MethodReference methodRef) {
            StringBuilder sb = new StringBuilder("(");
            for (CharSequence param : methodRef.getParameterTypes()) {
                sb.append(param);
            }
            sb.append(")");
            sb.append(methodRef.getReturnType());
            return sb.toString();
        }
    }
}