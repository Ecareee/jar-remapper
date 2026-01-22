package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.JavaRemapper;
import com.ecaree.jarremapper.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
                      - obfuscated: d
                        readable: mHelper
                        type: La/c;
                    methods:
                      - obfuscated: a
                        readable: getValue
                        descriptor: ()I
                      - obfuscated: b
                        readable: getHelper
                        descriptor: ()La/c;
                  - obfuscated: a/c
                    readable: com/example/Helper
                    fields:
                      - obfuscated: x
                        readable: data
                        type: I
                """;

        File yamlFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(yamlFile.toPath(), yaml);

        mappingData = MappingLoader.loadYaml(yamlFile);
    }

    private void createTestJavaFiles(File inputDir) throws IOException {
        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classB = """
                package a;
                
                import a.c;
                
                public class b {
                    private int a;
                
                    private c d;
                
                    public int a() {
                        return this.a;
                    }
                
                    public c b() {
                        return d;
                    }
                
                    public void process(c helper) {
                        this.d = helper;
                    }
                
                    public void testLocal() {
                        c local = new c();
                        local.x = 10;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        String classC = """
                package a;
                
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);
    }

    @Test
    public void testRemapJava() throws IOException {
        File inputDir = tempDir.resolve("test1-input").toFile();
        File outputDir = tempDir.resolve("test1-output").toFile();
        createTestJavaFiles(inputDir);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(2, count, "Should process 2 files");

        File testClassFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(testClassFile, "TestClass.java should exist");

        String testClassContent = Files.readString(testClassFile.toPath());
        log.info("TestClass.java:\n{}", testClassContent);

        System.out.println(log.getClass().getName());

        // 1. 包声明重映射
        assertTrue(testClassContent.contains("package com.example;"),
                "Package should be remapped to com.example");

        // 2. import 重映射
        assertTrue(testClassContent.contains("import com.example.Helper;"),
                "Import should be remapped to com.example.Helper");

        // 3. 类名重映射
        assertTrue(testClassContent.contains("class TestClass"),
                "Class name should be remapped to TestClass");

        // 4. 字段类型重映射
        assertTrue(testClassContent.contains("private Helper"),
                "Field type 'c' should be remapped to 'Helper'");

        // 5. 字段名重映射
        assertTrue(testClassContent.contains("mHelper"),
                "Field name 'd' should be remapped to 'mHelper'");
        assertTrue(testClassContent.contains("mValue"),
                "Field name 'a' should be remapped to 'mValue'");

        // 6. 方法返回类型重映射
        assertTrue(testClassContent.contains("public Helper"),
                "Method return type 'c' should be remapped to 'Helper'");

        // 7. 方法名重映射
        assertTrue(testClassContent.contains("getValue()"),
                "Method name 'a' should be remapped to 'getValue'");
        assertTrue(testClassContent.contains("getHelper()"),
                "Method name 'b' should be remapped to 'getHelper'");

        // 8. 方法参数类型重映射
        assertTrue(testClassContent.contains("process(Helper"),
                "Method parameter type 'c' should be remapped to 'Helper'");

        // 9. 局部变量类型重映射
        assertTrue(testClassContent.contains("Helper local"),
                "Local variable type 'c' should be remapped to 'Helper'");

        // 10. new 表达式类型重映射
        assertTrue(testClassContent.contains("new Helper()"),
                "Constructor call 'new c()' should be remapped to 'new Helper()'");

        File helperFile = findJavaFile(outputDir, "Helper.java");
        assertNotNull(helperFile, "Helper.java should exist");

        String helperContent = Files.readString(helperFile.toPath());
        log.info("Helper.java:\n{}", helperContent);

        assertTrue(helperContent.contains("class Helper"),
                "Class name should be remapped to Helper");
        assertTrue(helperContent.contains("public int data"),
                "Field name 'x' should be remapped to 'data'");
    }

    @Test
    public void testRemapJavaWithLibraryJars() throws IOException {
        File inputDir = tempDir.resolve("test2-input").toFile();
        File outputDir = tempDir.resolve("test2-output").toFile();
        createTestJavaFiles(inputDir);

        JavaRemapper remapper = new JavaRemapper(mappingData, Collections.emptyList());
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(2, count, "Should process 2 files");

        File testClassFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(testClassFile, "TestClass.java should exist");

        String content = Files.readString(testClassFile.toPath());

        assertTrue(content.contains("class TestClass"), "Class should be remapped");
        assertTrue(content.contains("private Helper"), "Field type should be remapped");
    }

    @Test
    public void testRemapInnerClassFieldType() throws IOException {
        File inputDir = tempDir.resolve("test-inner-input").toFile();
        File outputDir = tempDir.resolve("test-inner-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                package a;
                
                public class b {
                    public int a;
                
                    public class Inner {
                        // 内部类中引用外部类类型
                        public final b parent;
                
                        public Inner(b p) {
                            this.parent = p;
                        }
                
                        public b getParent() {
                            return parent;
                        }
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(1, count, "Should process 1 file");

        File outputFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(outputFile, "TestClass.java should exist");

        String content = Files.readString(outputFile.toPath());
        log.info("Inner class test:\n{}", content);

        // 1. 内部类中的字段类型重映射
        assertTrue(content.contains("public final TestClass parent"),
                "Inner class field type 'b' should be remapped to 'TestClass'");

        // 2. 构造函数参数类型重映射
        assertTrue(content.contains("public Inner(TestClass"),
                "Inner class constructor parameter type should be remapped");

        // 3. 方法返回类型重映射
        assertTrue(content.contains("public TestClass getParent()"),
                "Inner class method return type should be remapped");
    }

    @Test
    public void testRemapGenericTypes() throws IOException {
        File inputDir = tempDir.resolve("test-generic-input").toFile();
        File outputDir = tempDir.resolve("test-generic-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                package a;
                
                import java.util.List;
                import java.util.Map;
                
                public class b {
                    private List<c> helpers;
                    private Map<String, c> helperMap;
                
                    public List<c> getHelpers() {
                        return helpers;
                    }
                
                    public void setHelpers(List<c> list) {
                        this.helpers = list;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);

        String classC = """
                package a;
                public class c {}
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File outputFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(outputFile, "TestClass.java should exist");

        String content = Files.readString(outputFile.toPath());
        log.info("Generic types test:\n{}", content);

        // 泛型参数中的类型重映射
        assertTrue(content.contains("List<Helper>"),
                "Generic type parameter 'c' should be remapped to 'Helper'");
        assertTrue(content.contains("Map<String, Helper>"),
                "Generic type parameter in Map should be remapped");
    }

    @Test
    public void testRemapArrayTypes() throws IOException {
        File inputDir = tempDir.resolve("test-array-input").toFile();
        File outputDir = tempDir.resolve("test-array-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                package a;
                
                public class b {
                    private c[] helpers;
                    private c[][] matrix;
                
                    public c[] getHelpers() {
                        return helpers;
                    }
                
                    public void setHelpers(c[] arr) {
                        this.helpers = arr;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);

        String classC = """
                package a;
                public class c {}
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File outputFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(outputFile, "TestClass.java should exist");

        String content = Files.readString(outputFile.toPath());
        log.info("Array types test:\n{}", content);

        // 数组元素类型重映射
        assertTrue(content.contains("Helper[]"),
                "Array element type 'c' should be remapped to 'Helper'");
        assertTrue(content.contains("Helper[][]"),
                "2D array element type should be remapped");
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        File emptyDir = tempDir.resolve("test-empty").toFile();
        FileUtils.ensureDirectory(emptyDir);
        File outputDir = tempDir.resolve("test-empty-output").toFile();

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(emptyDir, outputDir);

        assertEquals(0, count, "Empty directory should return 0");
    }

    @Test
    public void testInvalidJavaFile() throws IOException {
        File inputDir = tempDir.resolve("test-invalid-input").toFile();
        File outputDir = tempDir.resolve("test-invalid-output").toFile();
        FileUtils.ensureDirectory(inputDir);

        Files.writeString(new File(inputDir, "Invalid.java").toPath(),
                "this is not valid java code {{{}}}");

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(1, count, "Should process 1 file");

        File invalidFile = findJavaFile(outputDir, "Invalid.java");
        assertNotNull(invalidFile, "Invalid file should be copied as-is");
    }

    @Test
    public void testCommentedCodePreserved() throws IOException {
        File inputDir = tempDir.resolve("test-commented-input").toFile();
        File outputDir = tempDir.resolve("test-commented-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                //package a;
                //
                //public class b {
                //    private int a;
                //}
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        int count = remapper.remapJavaSource(inputDir, outputDir);

        assertEquals(1, count, "Should process 1 file");

        File outputFile = new File(outputDir, "a/b.java");
        assertTrue(outputFile.exists(), "Commented file should keep original path");
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
}