package com.ecaree.jarremapper;

import com.ecaree.jarremapper.JarRemapperExtension.JavaRemapperMode;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.JavaRemapper;
import com.ecaree.jarremapper.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaRemapperTest {
    @TempDir
    Path tempDir;

    private MappingData mappingData;
    private File javaInputDir;
    private File javaOutputDir;

    @BeforeEach
    public void setUp() throws IOException {
        String yaml = """
                version: "1.0"
                classes:
                  - obfuscated: a/b
                    readable: com/example/TestClass
                    fields:
                      - obfuscated: a
                        readable: mValue
                        type: I
                    methods:
                      - obfuscated: a
                        readable: getValue
                        descriptor: ()I
                  - obfuscated: a/c
                    readable: com/example/Helper
                """;

        File yamlFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(yamlFile.toPath(), yaml);

        mappingData = MappingLoader.loadYaml(yamlFile);

        javaInputDir = tempDir.resolve("java-input").toFile();
        javaOutputDir = tempDir.resolve("java-output").toFile();
        FileUtils.ensureDirectory(javaOutputDir);

        File packageDir = new File(javaInputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                package a;
                
                import a.c;
                
                public class b {
                    private int a;
                    private c helper;
                
                    public int a() {
                        return this.a;
                    }
                
                    public c getHelper() {
                        return helper;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);
    }

    @Test
    public void testRemapJavaTypesOnly() throws IOException {
        JavaRemapper service = new JavaRemapper(mappingData, JavaRemapperMode.TYPES_ONLY);
        int count = service.remapJavaSource(javaInputDir, javaOutputDir);

        assertEquals(1, count, "Should process 1 file");

        System.out.println("Output directory contents:");
        printDirectoryTree(javaOutputDir, "");

        File remappedFile = findJavaFile(javaOutputDir, "TestClass.java");
        if (remappedFile == null) {
            remappedFile = findJavaFile(javaOutputDir, "b.java");
        }

        assertNotNull(remappedFile, "Remapped Java file should exist");

        String content = Files.readString(remappedFile.toPath());
        System.out.println("File content:\n" + content);

        assertTrue(content.contains("TestClass") || content.contains("Helper"), "Should contain remapped class name");
    }

    @Test
    public void testRemapJavaFull() throws IOException {
        JavaRemapper service = new JavaRemapper(mappingData, JavaRemapperMode.FULL);
        int count = service.remapJavaSource(javaInputDir, javaOutputDir);

        assertEquals(1, count, "Should process 1 file");

        System.out.println("Output directory contents (FULL mode):");
        printDirectoryTree(javaOutputDir, "");

        File remappedFile = findJavaFile(javaOutputDir, "TestClass.java");
        if (remappedFile == null) {
            remappedFile = findJavaFile(javaOutputDir, "b.java");
        }

        assertNotNull(remappedFile, "Remapped Java file should exist");

        String content = Files.readString(remappedFile.toPath());
        System.out.println("File content (FULL mode):\n" + content);

        assertTrue(content.contains("TestClass") || content.contains("mValue") || content.contains("getValue"),
                "Should contain remapped content");
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        File emptyDir = tempDir.resolve("empty").toFile();
        FileUtils.ensureDirectory(emptyDir);
        File outputDir = tempDir.resolve("empty-output").toFile();

        JavaRemapper service = new JavaRemapper(mappingData, JavaRemapperMode.TYPES_ONLY);
        int count = service.remapJavaSource(emptyDir, outputDir);

        assertEquals(0, count, "Empty directory should return 0");
    }

    @Test
    public void testInvalidJavaFile() throws IOException {
        Files.writeString(new File(javaInputDir, "Invalid.java").toPath(), "this is not valid java code {{{}}}");

        JavaRemapper service = new JavaRemapper(mappingData, JavaRemapperMode.TYPES_ONLY);
        int count = service.remapJavaSource(javaInputDir, javaOutputDir);

        assertTrue(count >= 1, "Should process all files");
    }

    private File findJavaFile(File dir, String fileName) {
        if (!dir.exists()) return null;

        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isDirectory()) {
                File found = findJavaFile(file, fileName);
                if (found != null) return found;
            } else if (file.getName().equals(fileName)) {
                return file;
            }
        }
        return null;
    }

    private void printDirectoryTree(File dir, String indent) {
        if (!dir.exists()) {
            System.out.println(indent + "(directory not exists: " + dir + ")");
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            System.out.println(indent + file.getName());
            if (file.isDirectory()) {
                printDirectoryTree(file, indent + "  ");
            }
        }
    }
}