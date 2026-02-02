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
                      - obfuscated: c
                        readable: process
                        descriptor: (La/c;)V
                  - obfuscated: a/c
                    readable: com/example/Helper
                    fields:
                      - obfuscated: x
                        readable: data
                        type: I
                    methods:
                      - obfuscated: y
                        readable: doWork
                        descriptor: ()V
                  - obfuscated: a/d
                    readable: com/example/Status
                  - obfuscated: a/e
                    readable: com/example/DataRecord
                  - obfuscated: a/f
                    readable: com/example/Marker
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
                
                    public void c(c helper) {
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
                
                    public void y() {}
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
    public void testRemapQualifiedTypes() throws IOException {
        File inputDir = tempDir.resolve("test-qualified-input").toFile();
        File outputDir = tempDir.resolve("test-qualified-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String java = """
                package a;
                
                public class b {
                    // 限定类型引用
                    private b.Inner inner;
                
                    // 限定类型作为泛型参数
                    private java.util.Map<String, b.Inner> map;
                
                    public b.Inner getInner() {
                        return inner;
                    }
                
                    public static class Inner {
                        public int value;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), java);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File outputFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(outputFile, "TestClass.java should exist");

        String content = Files.readString(outputFile.toPath());
        log.info("Qualified types test:\n{}", content);

        assertTrue(content.contains("private TestClass.Inner inner"),
                "Qualified field type scope should be remapped");
        assertTrue(content.contains("Map<String, TestClass.Inner>"),
                "Qualified type in generic should be remapped");
        assertTrue(content.contains("public TestClass.Inner getInner()"),
                "Qualified return type should be remapped");
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

    @Test
    public void testRemapEnumDeclaration() throws IOException {
        File inputDir = tempDir.resolve("test-enum-input").toFile();
        File outputDir = tempDir.resolve("test-enum-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String enumD = """
                package a;
                
                public enum d {
                    ACTIVE,
                    INACTIVE,
                    PENDING;
                
                    public boolean isActive() {
                        return this == ACTIVE;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "d.java").toPath(), enumD);

        String classB = """
                package a;
                
                public class b {
                    private d status;
                
                    public d getStatus() {
                        return status;
                    }
                
                    public void setStatus(d s) {
                        this.status = s;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File enumFile = findJavaFile(outputDir, "Status.java");
        assertNotNull(enumFile, "Status.java should exist");

        String enumContent = Files.readString(enumFile.toPath());
        log.info("Enum test:\n{}", enumContent);

        assertTrue(enumContent.contains("public enum Status"),
                "Enum name 'd' should be remapped to 'Status'");

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String classContent = Files.readString(classFile.toPath());
        log.info("Class using enum:\n{}", classContent);

        assertTrue(classContent.contains("private Status status"),
                "Enum field type should be remapped");
        assertTrue(classContent.contains("public Status getStatus()"),
                "Enum return type should be remapped");
        assertTrue(classContent.contains("setStatus(Status"),
                "Enum parameter type should be remapped");
    }

    @Test
    public void testRemapRecordDeclaration() throws IOException {
        File inputDir = tempDir.resolve("test-record-input").toFile();
        File outputDir = tempDir.resolve("test-record-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String recordE = """
                package a;
                
                public record e(String name, int value) {
                    public e {
                        if (value < 0) {
                            throw new IllegalArgumentException("value must be non-negative");
                        }
                    }
                
                    public String upperName() {
                        return name.toUpperCase();
                    }
                }
                """;
        Files.writeString(new File(packageDir, "e.java").toPath(), recordE);

        String classB = """
                package a;
                
                public class b {
                    private e data;
                
                    public e getData() {
                        return data;
                    }
                
                    public void setData(e d) {
                        this.data = d;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File recordFile = findJavaFile(outputDir, "DataRecord.java");
        assertNotNull(recordFile, "DataRecord.java should exist");

        String recordContent = Files.readString(recordFile.toPath());
        log.info("Record test:\n{}", recordContent);

        assertTrue(recordContent.contains("public record DataRecord"),
                "Record name 'e' should be remapped to 'DataRecord'");
        assertTrue(recordContent.contains("public DataRecord {"),
                "Compact constructor should be remapped");

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String classContent = Files.readString(classFile.toPath());

        assertTrue(classContent.contains("private DataRecord data"),
                "Record field type should be remapped");
        assertTrue(classContent.contains("public DataRecord getData()"),
                "Record return type should be remapped");
    }

    @Test
    public void testRemapAnnotationDeclaration() throws IOException {
        File inputDir = tempDir.resolve("test-annotation-input").toFile();
        File outputDir = tempDir.resolve("test-annotation-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String annotationF = """
                package a;
                
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface f {
                    String value() default "";
                }
                """;
        Files.writeString(new File(packageDir, "f.java").toPath(), annotationF);

        String classB = """
                package a;
                
                @f("test")
                public class b {
                    private int a;
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File annotationFile = findJavaFile(outputDir, "Marker.java");
        assertNotNull(annotationFile, "Marker.java should exist");

        String annotationContent = Files.readString(annotationFile.toPath());
        log.info("Annotation test:\n{}", annotationContent);

        assertTrue(annotationContent.contains("public @interface Marker"),
                "Annotation name 'f' should be remapped to 'Marker'");

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String classContent = Files.readString(classFile.toPath());
        log.info("Class with annotation:\n{}", classContent);

        assertTrue(classContent.contains("@Marker("),
                "Annotation usage should be remapped");
    }

    @Test
    public void testRemapMethodReference() throws IOException {
        File inputDir = tempDir.resolve("test-methodref-input").toFile();
        File outputDir = tempDir.resolve("test-methodref-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                
                public class c {
                    public int x;
                
                    public void y() {}
                
                    public static void staticMethod() {}
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                import java.util.List;
                import java.util.ArrayList;
                
                public class b {
                    public void testMethodRef() {
                        List<c> list = new ArrayList<>();
                        // 实例方法引用
                        list.forEach(c::y);
                        // 静态方法引用
                        Runnable r = c::staticMethod;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Method reference test:\n{}", content);

        assertTrue(content.contains("List<Helper>"),
                "Generic type should be remapped");
        assertTrue(content.contains("Helper::doWork"),
                "Method reference should be remapped");
    }

    @Test
    public void testRemapConstructorDeclaration() throws IOException {
        File inputDir = tempDir.resolve("test-constructor-input").toFile();
        File outputDir = tempDir.resolve("test-constructor-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classB = """
                package a;
                
                public class b {
                    private int a;
                    private c d;
                
                    public b() {
                        this.a = 0;
                    }
                
                    public b(int value) {
                        this.a = value;
                    }
                
                    public b(int value, c helper) {
                        this.a = value;
                        this.d = helper;
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

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Constructor test:\n{}", content);

        assertTrue(content.contains("public TestClass()"),
                "No-arg constructor should be remapped");
        assertTrue(content.contains("public TestClass(int value)"),
                "Single-arg constructor should be remapped");
        assertTrue(content.contains("public TestClass(int value, Helper helper)"),
                "Multi-arg constructor should be remapped");
    }

    @Test
    public void testRemapCastExpression() throws IOException {
        File inputDir = tempDir.resolve("test-cast-input").toFile();
        File outputDir = tempDir.resolve("test-cast-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                public class b {
                    public void testCast(Object obj) {
                        c helper = (c) obj;
                        helper.x = 10;
                    }
                
                    public c castAndReturn(Object obj) {
                        return (c) obj;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Cast expression test:\n{}", content);

        assertTrue(content.contains("(Helper) obj"),
                "Cast expression type should be remapped");
        assertTrue(content.contains("Helper helper = (Helper)"),
                "Variable type and cast should both be remapped");
    }

    @Test
    public void testRemapInstanceOfExpression() throws IOException {
        File inputDir = tempDir.resolve("test-instanceof-input").toFile();
        File outputDir = tempDir.resolve("test-instanceof-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                public class b {
                    public boolean isHelper(Object obj) {
                        return obj instanceof c;
                    }
                
                    public void processIfHelper(Object obj) {
                        if (obj instanceof c) {
                            c helper = (c) obj;
                            helper.x = 10;
                        }
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("InstanceOf expression test:\n{}", content);

        assertTrue(content.contains("obj instanceof Helper"),
                "InstanceOf type should be remapped");
    }

    @Test
    public void testRemapPatternMatching() throws IOException {
        File inputDir = tempDir.resolve("test-pattern-input").toFile();
        File outputDir = tempDir.resolve("test-pattern-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                public class b {
                    public void processWithPattern(Object obj) {
                        if (obj instanceof c helper) {
                            helper.x = 10;
                        }
                    }
                
                    public int getValueOrZero(Object obj) {
                        if (obj instanceof c h) {
                            return h.x;
                        }
                        return 0;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Pattern matching test:\n{}", content);

        assertTrue(content.contains("instanceof Helper helper"),
                "Pattern matching type should be remapped");
        assertTrue(content.contains("instanceof Helper h"),
                "Pattern matching with short variable should be remapped");
    }

    @Test
    public void testRemapArrayCreation() throws IOException {
        File inputDir = tempDir.resolve("test-arraycreation-input").toFile();
        File outputDir = tempDir.resolve("test-arraycreation-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                public class b {
                    public c[] createArray() {
                        return new c[10];
                    }
                
                    public c[][] createMatrix() {
                        return new c[5][5];
                    }
                
                    public c[] createWithInit() {
                        return new c[] { new c(), new c() };
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Array creation test:\n{}", content);

        assertTrue(content.contains("new Helper[10]"),
                "Array creation type should be remapped");
        assertTrue(content.contains("new Helper[5][5]"),
                "2D array creation type should be remapped");
        assertTrue(content.contains("new Helper[]"),
                "Array creation with initializer should be remapped");
    }

    @Test
    public void testRemapWildcardTypes() throws IOException {
        File inputDir = tempDir.resolve("test-wildcard-input").toFile();
        File outputDir = tempDir.resolve("test-wildcard-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                public class c {
                    public int x;
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                import java.util.List;
                
                public class b {
                    private List<? extends c> extendsHelper;
                    private List<? super c> superHelper;
                
                    public void setExtendsHelper(List<? extends c> list) {
                        this.extendsHelper = list;
                    }
                
                    public List<? extends c> getExtendsHelper() {
                        return extendsHelper;
                    }
                
                    public void setSuperHelper(List<? super c> list) {
                        this.superHelper = list;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Wildcard types test:\n{}", content);

        assertTrue(content.contains("List<? extends Helper>"),
                "Wildcard extends type should be remapped");
        assertTrue(content.contains("List<? super Helper>"),
                "Wildcard super type should be remapped");
    }

    @Test
    public void testRemapAnnotationUsage() throws IOException {
        File inputDir = tempDir.resolve("test-annotation-usage-input").toFile();
        File outputDir = tempDir.resolve("test-annotation-usage-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String annotationF = """
                package a;
                
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
                public @interface f {
                    String value() default "";
                    int count() default 0;
                }
                """;
        Files.writeString(new File(packageDir, "f.java").toPath(), annotationF);

        String classB = """
                package a;
                
                @f
                public class b {
                    @f("field")
                    private int a;
                
                    @f(value = "method", count = 5)
                    public int a() {
                        return this.a;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Annotation usage test:\n{}", content);

        // MarkerAnnotationExpr
        assertTrue(content.contains("@Marker\npublic class") || content.contains("@Marker\r\npublic class"),
                "Marker annotation should be remapped");

        // SingleMemberAnnotationExpr
        assertTrue(content.contains("@Marker(\"field\")"),
                "Single member annotation should be remapped");

        // NormalAnnotationExpr
        assertTrue(content.contains("@Marker(value = \"method\""),
                "Normal annotation should be remapped");
    }

    @Test
    public void testRemapStaticImportWithMethodReference() throws IOException {
        File inputDir = tempDir.resolve("test-static-methodref-input").toFile();
        File outputDir = tempDir.resolve("test-static-methodref-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classC = """
                package a;
                
                public class c {
                    public int x;
                
                    public void y() {}
                
                    public static c create() {
                        return new c();
                    }
                }
                """;
        Files.writeString(new File(packageDir, "c.java").toPath(), classC);

        String classB = """
                package a;
                
                import java.util.function.Supplier;
                
                public class b {
                    public void testSupplier() {
                        Supplier<c> supplier = c::create;
                        c instance = supplier.get();
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File classFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(classFile, "TestClass.java should exist");

        String content = Files.readString(classFile.toPath());
        log.info("Static method reference test:\n{}", content);

        assertTrue(content.contains("Supplier<Helper>"),
                "Generic type should be remapped");
        assertTrue(content.contains("Helper::create") || content.contains("Helper instance"),
                "Static method reference or variable type should be remapped");
    }

    @Test
    public void testLocalVariableShadowsField() throws IOException {
        File inputDir = tempDir.resolve("test-shadow-input").toFile();
        File outputDir = tempDir.resolve("test-shadow-output").toFile();

        File packageDir = new File(inputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        String classB = """
                package a;
                
                public class b {
                    private int a;
                
                    public void test() {
                        int a = 10;
                        System.out.println(a);
                    }
                
                    public int getField() {
                        return a;
                    }
                }
                """;
        Files.writeString(new File(packageDir, "b.java").toPath(), classB);

        JavaRemapper remapper = new JavaRemapper(mappingData);
        remapper.remapJavaSource(inputDir, outputDir);

        File outputFile = findJavaFile(outputDir, "TestClass.java");
        assertNotNull(outputFile, "TestClass.java should exist");

        String content = Files.readString(outputFile.toPath());
        log.info("Shadow test:\n{}", content);

        assertTrue(content.contains("int a = 10"),
                "Local variable 'a' should not be remapped");
        assertTrue(content.contains("System.out.println(a)"),
                "Local variable reference should not be remapped");
        assertTrue(content.contains("return mValue"),
                "Field reference 'a' should be remapped to 'mValue'");
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