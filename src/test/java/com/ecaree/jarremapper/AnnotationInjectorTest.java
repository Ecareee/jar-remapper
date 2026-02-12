package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.AnnotationInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnnotationInjectorTest {
    @TempDir
    Path tempDir;

    private MappingData mappingData;
    private File inputJar;
    private File outputJar;

    @BeforeEach
    public void setUp() throws IOException {
        String yaml = """
                version: "1.0"
                
                classes:
                  - obfuscated: a/b
                    readable: com/example/TestClass
                    comment: This is a test class
                
                    fields:
                      - obfuscated: a
                        readable: mValue
                        type: I
                        comment: "Value field"
                
                    methods:
                      - obfuscated: a
                        readable: getValue
                        descriptor: ()I
                        comment: "Get value"
                """;

        File yamlFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(yamlFile.toPath(), yaml);

        mappingData = MappingLoader.loadYaml(yamlFile);

        inputJar = tempDir.resolve("input.jar").toFile();
        outputJar = tempDir.resolve("output.jar").toFile();

        createTestJar(inputJar);
    }

    private void createTestJar(File jarFile) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TestClass",
                    null, "java/lang/Object", null);

            cw.visitField(Opcodes.ACC_PRIVATE, "mValue", "I", null, null).visitEnd();

            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getValue", "()I", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, "com/example/TestClass", "mValue", "I");
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();

            cw.visitEnd();

            jos.putNextEntry(new ZipEntry("com/example/TestClass.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }
    }

    @Test
    public void testInjectAnnotations() throws IOException {
        AnnotationInjector injector = new AnnotationInjector(mappingData);
        injector.injectAnnotations(inputJar, outputJar);

        assertTrue(outputJar.exists(), "Output JAR should exist");

        try (JarFile jarFile = new JarFile(outputJar)) {
            JarEntry entry = jarFile.getJarEntry("com/example/TestClass.class");
            assertNotNull(entry, "Should contain class file");
        }

        assertTrue(outputJar.length() > 0, "Output JAR should have content");
    }

    @Test
    public void testInjectAnnotationsInPlace() throws IOException {
        File tempJar = tempDir.resolve("temp.jar").toFile();
        createTestJar(tempJar);

        long originalSize = tempJar.length();

        AnnotationInjector injector = new AnnotationInjector(mappingData);
        injector.injectAnnotations(tempJar, tempJar);

        assertTrue(tempJar.exists(), "JAR should exist");
        assertTrue(tempJar.length() >= originalSize, "JAR size should be same or larger after injection");
    }
}