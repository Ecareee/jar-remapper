package com.ecaree.jarremapper;

import com.ecaree.jarremapper.JarRemapperExtension.JavaRemapperMode;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.AnnotationInjector;
import com.ecaree.jarremapper.remap.JarRemapper;
import com.ecaree.jarremapper.remap.JavaRemapper;
import com.ecaree.jarremapper.remap.SmaliRemapper;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationTest {
    // 使用 build 目录下的测试目录，不使用 @TempDir，避免 JUnit @TempDir 的清理问题
    private static Path getTestDir(String testName) throws IOException {
        Path buildDir = Path.of("build", "test-tmp", testName + "-" + System.currentTimeMillis());
        Files.createDirectories(buildDir);
        return buildDir;
    }

    private File createMappingFile(Path tempDir) throws IOException {
        String yaml = """
                version: "1.0"
                classes:
                  - obfuscated: a/A
                    readable: com/example/app/MainActivity
                    comment: Main activity
                    fields:
                      - obfuscated: a
                        readable: mBinding
                        type: La/B;
                        comment: "View binding"
                    methods:
                      - obfuscated: a
                        readable: onCreate
                        descriptor: (Landroid/os/Bundle;)V
                        comment: "Create callback"
                  - obfuscated: a/B
                    readable: com/example/app/databinding/ActivityMainBinding
                    comment: Main activity binding
                  - obfuscated: b/C
                    readable: com/example/util/Helper
                    methods:
                      - obfuscated: a
                        readable: doWork
                        descriptor: ()V
                """;

        File mappingFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(mappingFile.toPath(), yaml);
        return mappingFile;
    }

    private File createObfuscatedJar(Path tempDir) throws IOException {
        File jarFile = tempDir.resolve("obfuscated.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            // 创建 a/A 类
            {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/A",
                        null, "java/lang/Object", null);

                cw.visitField(Opcodes.ACC_PRIVATE, "a", "La/B;", null, null).visitEnd();

                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "a",
                        "(Landroid/os/Bundle;)V", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 2);
                mv.visitEnd();

                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                cw.visitEnd();

                jos.putNextEntry(new ZipEntry("a/A.class"));
                jos.write(cw.toByteArray());
                jos.closeEntry();
            }

            // 创建 a/B 类
            {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B",
                        null, "java/lang/Object", null);

                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                cw.visitEnd();

                jos.putNextEntry(new ZipEntry("a/B.class"));
                jos.write(cw.toByteArray());
                jos.closeEntry();
            }

            // 创建 b/C 类
            {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "b/C",
                        null, "java/lang/Object", null);

                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "a", "()V", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 1);
                mv.visitEnd();

                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                cw.visitEnd();

                jos.putNextEntry(new ZipEntry("b/C.class"));
                jos.write(cw.toByteArray());
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    @Test
    public void testFullWorkflow() throws IOException {
        Path tempDir = getTestDir("fullWorkflow");

        File mappingFile = createMappingFile(tempDir);
        File obfuscatedJar = createObfuscatedJar(tempDir);

        MappingData mappingData = MappingLoader.load(mappingFile);
        assertNotNull(mappingData, "Mapping data should not be null");
        assertEquals(3, mappingData.getClassCount(), "Should have 3 class mappings");

        File remappedJar = tempDir.resolve("remapped.jar").toFile();
        JarRemapper jarService = new JarRemapper(mappingData);
        jarService.remapJar(obfuscatedJar, remappedJar);

        assertTrue(remappedJar.exists(), "Remapped JAR should exist");

        try (JarFile jar = new JarFile(remappedJar)) {
            assertNotNull(jar.getEntry("com/example/app/MainActivity.class"), "Should contain MainActivity");
            assertNotNull(jar.getEntry("com/example/app/databinding/ActivityMainBinding.class"), "Should contain ActivityMainBinding");
            assertNotNull(jar.getEntry("com/example/util/Helper.class"), "Should contain Helper");
            assertNull(jar.getEntry("a/A.class"), "Should not contain original class a/A");
        }

        File annotatedJar = tempDir.resolve("annotated.jar").toFile();
        AnnotationInjector injector = new AnnotationInjector(mappingData);
        injector.injectAnnotations(remappedJar, annotatedJar);

        assertTrue(annotatedJar.exists(), "Annotated JAR should exist");
    }

    @Test
    public void testSmaliWorkflow() throws IOException {
        Path tempDir = getTestDir("smaliWorkflow");

        File mappingFile = createMappingFile(tempDir);
        MappingData mappingData = MappingLoader.load(mappingFile);

        File smaliInput = tempDir.resolve("smali-in").toFile();
        File smaliOutput = tempDir.resolve("smali-out").toFile();
        smaliInput.mkdirs();

        File aDir = new File(smaliInput, "a");
        aDir.mkdirs();

        String smali = """
                .class public La/A;
                .super Ljava/lang/Object;
                
                .field private a:La/B;
                
                .method public a(Landroid/os/Bundle;)V
                    .registers 2
                    return-void
                .end method
                """;
        Files.writeString(new File(aDir, "A.smali").toPath(), smali);

        SmaliRemapper smaliService = new SmaliRemapper(mappingData);
        smaliService.remapSmali(smaliInput, smaliOutput);

        File expectedFile = new File(smaliOutput, "com/example/app/MainActivity.smali");
        assertTrue(expectedFile.exists(), "Remapped smali file should exist");

        String content = Files.readString(expectedFile.toPath());
        assertTrue(content.contains("Lcom/example/app/MainActivity;"), "Should contain remapped class name");
        assertTrue(content.contains("Lcom/example/app/databinding/ActivityMainBinding;"), "Should contain remapped field type");
    }

    @Test
    public void testJavaWorkflow() throws IOException {
        Path tempDir = getTestDir("javaWorkflow");

        File mappingFile = createMappingFile(tempDir);
        MappingData mappingData = MappingLoader.load(mappingFile);

        File javaInput = tempDir.resolve("java-in").toFile();
        File javaOutput = tempDir.resolve("java-out").toFile();
        javaInput.mkdirs();

        File aDir = new File(javaInput, "a");
        aDir.mkdirs();

        String java = """
                package a;
                
                import android.os.Bundle;
                
                public class A {
                    private B a;
                
                    public void a(Bundle bundle) {
                    }
                }
                """;
        Files.writeString(new File(aDir, "A.java").toPath(), java);

        JavaRemapper javaService = new JavaRemapper(mappingData, JavaRemapperMode.TYPES_ONLY);
        int count = javaService.remapJavaSource(javaInput, javaOutput);

        assertEquals(1, count, "Should process 1 file");

        File expectedFile = findFile(javaOutput, "MainActivity.java");
        if (expectedFile == null) {
            expectedFile = findFile(javaOutput, "A.java");
        }
        assertNotNull(expectedFile, "Remapped Java file should exist");

        String content = Files.readString(expectedFile.toPath());
        assertTrue(content.contains("MainActivity") || content.contains("ActivityMainBinding"), "Should contain remapped class name");
    }

    private File findFile(File dir, String name) {
        if (!dir.exists()) return null;

        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isDirectory()) {
                File found = findFile(file, name);
                if (found != null) return found;
            } else if (file.getName().equals(name)) {
                return file;
            }
        }
        return null;
    }
}