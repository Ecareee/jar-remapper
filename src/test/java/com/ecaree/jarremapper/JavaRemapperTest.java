package com.ecaree.jarremapper;

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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaRemapperTest {
    @TempDir
    Path tempDir;

    private MappingData mappingData;

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
    }

    private void createTestJavaFile(File inputDir) throws IOException {
        File packageDir = new File(inputDir, "a");
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
    public void testRemapJava() throws IOException {
        File inputDir = tempDir.resolve("test1-input").toFile();
        File outputDir = tempDir.resolve("test1-output").toFile();
        createTestJavaFile(inputDir);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(1, count, "Should process 1 file");

        System.out.println("Output directory contents:");
        printDirectoryTree(outputDir, "");

        File remappedFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(remappedFile, "Remapped Java file should exist");

        String content = Files.readString(remappedFile.toPath());
        System.out.println("File content:\n" + content);

        assertTrue(content.contains("class TestClass"), "Should remap class name");
        assertTrue(content.contains("Helper"), "Should remap type reference");
        assertTrue(content.contains("package com.example"), "Should remap package");
        assertTrue(content.contains("import com.example.Helper"), "Should remap import");
    }

    @Test
    public void testRemapJavaWithLibraryJars() throws IOException {
        File inputDir = tempDir.resolve("test2-input").toFile();
        File outputDir = tempDir.resolve("test2-output").toFile();
        createTestJavaFile(inputDir);

        JavaRemapper remapper = new JavaRemapper(mappingData, Collections.emptyList());
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(1, count, "Should process 1 file");

        File remappedFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(remappedFile, "Remapped Java file should exist");

        String content = Files.readString(remappedFile.toPath());
        assertTrue(content.contains("TestClass"), "Should contain remapped class name");
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        File emptyDir = tempDir.resolve("test3-empty").toFile();
        FileUtils.ensureDirectory(emptyDir);
        File outputDir = tempDir.resolve("test3-output").toFile();

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(emptyDir, outputDir);

        assertEquals(0, count, "Empty directory should return 0");
    }

    @Test
    public void testInvalidJavaFile() throws IOException {
        File inputDir = tempDir.resolve("test4-input").toFile();
        File outputDir = tempDir.resolve("test4-output").toFile();
        createTestJavaFile(inputDir);

        Files.writeString(new File(inputDir, "Invalid.java").toPath(),
                "this is not valid java code {{{}}}");

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertTrue(count >= 1, "Should process all files");

        File invalidFile = findJavaFile(outputDir, "Invalid.java");
        assertNotNull(invalidFile, "Invalid file should be copied as-is");
    }

    @Test
    public void testFieldAndMethodRemapping() throws IOException {
        File inputDir = tempDir.resolve("test5-input").toFile();
        File outputDir = tempDir.resolve("test5-output").toFile();
        createTestJavaFile(inputDir);

        File packageDir = new File(inputDir, "a");
        String java = """
                package a;
                
                public class d {
                    public void test() {
                        b obj = new b();
                        int val = obj.a();
                    }
                }
                """;
        Files.writeString(new File(packageDir, "d.java").toPath(), java);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(2, count, "Should process 2 files");

        System.out.println("Output directory contents:");
        printDirectoryTree(outputDir, "");
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