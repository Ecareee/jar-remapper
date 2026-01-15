package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.annotation.MappingComment;
import com.ecaree.jarremapper.annotation.MappingInfo;
import com.ecaree.jarremapper.annotation.OriginalName;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 注解注入器
 * 对重映射后的 JAR 进行 ASM 二次遍历，注入映射相关注解
 */
@Log
@RequiredArgsConstructor
public class AnnotationInjector {
    private static final String MAPPING_COMMENT_DESC = Type.getDescriptor(MappingComment.class);
    private static final String ORIGINAL_NAME_DESC = Type.getDescriptor(OriginalName.class);
    private static final String MAPPING_INFO_DESC = Type.getDescriptor(MappingInfo.class);

    private final MappingData mappingData;

    /**
     * 注入所有映射注解到目标
     */
    private static void injectAnnotations(MappingEntry entry, AnnotationTarget target) {
        if (entry == null) return;

        if (entry.hasComment()) {
            AnnotationVisitor av = target.visitAnnotation(MAPPING_COMMENT_DESC);
            av.visit("value", entry.getComment());
            av.visitEnd();
        }

        AnnotationVisitor av = target.visitAnnotation(ORIGINAL_NAME_DESC);
        visitIfNotNull(av, "owner", entry.getObfOwner());
        av.visit("name", entry.getObfName());
        visitIfNotNull(av, "descriptor", entry.getObfDescriptor());
        av.visitEnd();

        av = target.visitAnnotation(MAPPING_INFO_DESC);
        visitIfNotNull(av, "obfOwner", entry.getObfOwner());
        av.visit("obfName", entry.getObfName());
        visitIfNotNull(av, "obfDescriptor", entry.getObfDescriptor());
        visitIfNotNull(av, "readableOwner", entry.getReadableOwner());
        av.visit("readableName", entry.getReadableName());
        visitIfNotNull(av, "readableDescriptor", entry.getReadableDescriptor());
        av.visitEnd();
    }

    private static void visitIfNotNull(AnnotationVisitor av, String name, String value) {
        if (value != null) av.visit(name, value);
    }

    /**
     * 对 JAR 文件注入注解
     *
     * @param inputJar  输入 JAR（已重映射为可读命名）
     * @param outputJar 输出 JAR（带注解）
     * @throws IOException 如果 IO 操作失败
     */
    public void injectAnnotations(File inputJar, File outputJar) throws IOException {
        // 如果输入输出相同，使用临时文件
        log.info("Starting annotation injection");
        log.info("Input: " + inputJar);
        log.info("Output: " + outputJar);

        File tempFile = null;
        File actualOutputJar = outputJar;

        if (inputJar.getCanonicalPath().equals(outputJar.getCanonicalPath())) {
            tempFile = new File(inputJar.getParentFile(), inputJar.getName() + ".tmp");
            actualOutputJar = tempFile;
        }

        int classCount = 0;
        int annotatedCount = 0;

        try (JarFile jarFile = new JarFile(inputJar);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(actualOutputJar))) {

            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                jos.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
                manifest.write(jos);
                jos.closeEntry();
            }

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals(JarFile.MANIFEST_NAME)) continue;

                try (InputStream is = jarFile.getInputStream(entry)) {
                    if (name.endsWith(".class")) {
                        byte[] classBytes = is.readAllBytes();
                        byte[] modifiedBytes = processClass(classBytes, name);

                        jos.putNextEntry(new JarEntry(name));
                        jos.write(modifiedBytes);
                        jos.closeEntry();

                        classCount++;
                        if (modifiedBytes.length > classBytes.length) {
                            annotatedCount++;
                        }
                    } else {
                        jos.putNextEntry(new JarEntry(name));
                        is.transferTo(jos);
                        jos.closeEntry();
                    }
                }
            }
        }

        if (tempFile != null) {
            if (!inputJar.delete()) {
                throw new IOException("Failed to delete original file: " + inputJar);
            }
            if (!tempFile.renameTo(inputJar)) {
                throw new IOException("Failed to rename temp file: " + tempFile + " -> " + inputJar);
            }
        }

        log.info("Annotation injection completed: " + annotatedCount + "/" + classCount + " classes annotated");
    }

    private byte[] processClass(byte[] classBytes, String entryName) {
        String className = entryName.substring(0, entryName.length() - 6); // 去掉 .class

        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor visitor = new AnnotationInjectingClassVisitor(writer, className, mappingData);

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    @FunctionalInterface
    private interface AnnotationTarget {
        AnnotationVisitor visitAnnotation(String descriptor);
    }

    private static class AnnotationInjectingClassVisitor extends ClassVisitor {
        private final String className;
        private final MappingData mappingData;

        AnnotationInjectingClassVisitor(ClassWriter writer, String className, MappingData mappingData) {
            super(Opcodes.ASM9, writer);
            this.className = className;
            this.mappingData = mappingData;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            injectAnnotations(
                    mappingData.getClassEntry(className),
                    desc -> super.visitAnnotation(desc, true)
            );
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
            injectAnnotations(
                    mappingData.getFieldEntry(className, name),
                    desc -> fv.visitAnnotation(desc, true)
            );
            return fv;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            injectAnnotations(
                    mappingData.getMethodEntry(className, name, descriptor),
                    desc -> mv.visitAnnotation(desc, true)
            );
            return mv;
        }
    }
}