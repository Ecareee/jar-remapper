package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingEntry;
import com.ecaree.jarremapper.mapping.MappingLoader;
import net.md_5.specialsource.JarMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MappingLoaderTest {
    @TempDir
    Path tempDir;

    private File yamlFile;
    private File srgFile;
    private File csrgFile;
    private File tsrgFile;
    private File tsrg2File;
    private File proguardFile;
    private File tinyFile;
    private File tiny2File;

    @BeforeEach
    public void setUp() throws IOException {
        yamlFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(yamlFile.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/b
                    readable: com/example/TestClass
                    comment: "Test class"
                
                    fields:
                      - obfuscated: a
                        readable: mField
                        type: Ljava/lang/String;
                        comment: "Test field"
                
                    methods:
                      - obfuscated: a
                        readable: testMethod
                        descriptor: ()V
                        comment: "Test method"
                """);

        srgFile = tempDir.resolve("mappings.srg").toFile();
        Files.writeString(srgFile.toPath(), """
                CL: a/b com/example/TestClass
                FD: a/b/a com/example/TestClass/mField
                MD: a/b/a ()V com/example/TestClass/testMethod ()V
                """);

        csrgFile = tempDir.resolve("mappings.csrg").toFile();
        Files.writeString(csrgFile.toPath(), """
                a/b com/example/TestClass
                a/b a mField
                a/b a ()V testMethod
                """);

        tsrgFile = tempDir.resolve("mappings.tsrg").toFile();
        Files.writeString(tsrgFile.toPath(), """
                a/b com/example/TestClass
                \ta mField
                \ta ()V testMethod
                """);

        tsrg2File = tempDir.resolve("mappings_v2.tsrg").toFile();
        Files.writeString(tsrg2File.toPath(), """
                tsrg2 left right
                a/b com/example/TestClass
                \ta mField
                \ta ()V testMethod
                \t\t0 this this
                """);

        proguardFile = tempDir.resolve("mapping.txt").toFile();
        Files.writeString(proguardFile.toPath(), """
                a.b -> com.example.TestClass:
                    java.lang.String a -> mField
                    void a() -> testMethod
                """);

        tinyFile = tempDir.resolve("mappings.tiny").toFile();
        Files.writeString(tinyFile.toPath(), """
                v1\tintermediary\tnamed
                CLASS\ta/b\tcom/example/TestClass
                FIELD\ta/b\tLjava/lang/String;\ta\tmField
                METHOD\ta/b\t()V\ta\ttestMethod
                """);

        tiny2File = tempDir.resolve("mappings_v2.tiny").toFile();
        Files.writeString(tiny2File.toPath(), """
                tiny\t2\t0\tintermediary\tnamed
                c\ta/b\tcom/example/TestClass
                \tf\tLjava/lang/String;\ta\tmField
                \tm\t()V\ta\ttestMethod
                """);
    }

    @Test
    public void testLoadYaml() throws IOException {
        MappingData data = MappingLoader.loadYaml(yamlFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));

        MappingEntry classEntry = data.getClassEntry("com/example/TestClass");
        assertNotNull(classEntry, "Should find class mapping entry");
        assertEquals("Test class", classEntry.getComment());

        MappingEntry fieldEntry = data.getFieldEntry("com/example/TestClass", "mField");
        assertNotNull(fieldEntry, "Should find field mapping entry");
        assertEquals("Test field", fieldEntry.getComment());

        MappingEntry methodEntry = data.getMethodEntry("com/example/TestClass", "testMethod", "()V");
        assertNotNull(methodEntry, "Should find method mapping entry");
        assertEquals("Test method", methodEntry.getComment());
    }

    @Test
    public void testLoadSrg() throws IOException {
        MappingData data = MappingLoader.load(srgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadCsrg() throws IOException {
        MappingData data = MappingLoader.load(csrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadTsrg() throws IOException {
        MappingData data = MappingLoader.load(tsrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadTsrg2() throws IOException {
        MappingData data = MappingLoader.load(tsrg2File, "left", "right");

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadProguard() throws IOException {
        MappingData data = MappingLoader.load(proguardFile);

        assertNotNull(data, "Mapping data should not be null");

        JarMapping jarMapping = data.getJarMapping();
        assertFalse(jarMapping.classes.isEmpty(), "Should have class mappings");
        assertFalse(jarMapping.fields.isEmpty(), "Should have field mappings");
        assertFalse(jarMapping.methods.isEmpty(), "Should have method mappings");

        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadTiny() throws IOException {
        MappingData data = MappingLoader.load(tinyFile, "intermediary", "named");

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testLoadTiny2() throws IOException {
        MappingData data = MappingLoader.load(tiny2File, "intermediary", "named");

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testAutoDetectFormat() throws IOException {
        MappingData yamlData = MappingLoader.load(yamlFile);
        assertNotNull(yamlData);
        assertEquals(1, yamlData.getClassCount());
        assertEquals(1, yamlData.getFieldCount());
        assertEquals(1, yamlData.getMethodCount());

        MappingData srgData = MappingLoader.load(srgFile);
        assertNotNull(srgData);
        assertEquals(1, srgData.getClassCount());
        assertEquals(1, srgData.getFieldCount());
        assertEquals(1, srgData.getMethodCount());

        MappingData tinyData = MappingLoader.load(tinyFile);
        assertNotNull(tinyData);
        assertEquals(1, tinyData.getClassCount());

        MappingData tiny2Data = MappingLoader.load(tiny2File);
        assertNotNull(tiny2Data);
        assertEquals(1, tiny2Data.getClassCount());
    }

    @Test
    public void testReverseMapping() throws IOException {
        MappingData reversed = MappingLoader.load(yamlFile, true);

        assertNotNull(reversed, "Reversed mapping should not be null");

        JarMapping reversedMapping = reversed.getJarMapping();
        assertEquals("a/b", reversedMapping.classes.get("com/example/TestClass"));
        assertEquals("a", reversedMapping.fields.get("com/example/TestClass/mField"));
        assertEquals("a", reversedMapping.methods.get("com/example/TestClass/testMethod ()V"));
    }

    @Test
    public void testDescriptorRemapping() throws IOException {
        File complexYaml = tempDir.resolve("complex.yaml").toFile();
        Files.writeString(complexYaml.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/A
                    readable: com/example/ClassA
                
                  - obfuscated: a/B
                    readable: com/example/ClassB
                
                    methods:
                      - obfuscated: m
                        readable: process
                        descriptor: (La/A;[La/A;)La/A;
                """);

        MappingData data = MappingLoader.loadYaml(complexYaml);

        MappingEntry methodEntry = data.getMethodEntry("com/example/ClassB", "process",
                "(Lcom/example/ClassA;[Lcom/example/ClassA;)Lcom/example/ClassA;");
        assertNotNull(methodEntry, "Should find method mapping with remapped descriptor");
    }

    @Test
    public void testPackageMapping() throws IOException {
        File pkgSrg = tempDir.resolve("packages.srg").toFile();
        Files.writeString(pkgSrg.toPath(), """
                PK: a/ com/example/
                CL: a/b com/example/TestClass
                """);

        MappingData data = MappingLoader.load(pkgSrg);

        assertNotNull(data);
        assertEquals(1, data.getPackageCount(), "Should have 1 package mapping");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/", jarMapping.packages.get("a/"));
    }

    @Test
    public void testMapClassWithInnerClass() throws IOException {
        File innerClassYaml = tempDir.resolve("inner.yaml").toFile();
        Files.writeString(innerClassYaml.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/b
                    readable: com/example/Outer
                """);

        MappingData data = MappingLoader.loadYaml(innerClassYaml);

        assertEquals("com/example/Outer", data.mapClass("a/b"));
        assertEquals("com/example/Outer$c", data.mapClass("a/b$c"));
        assertEquals("com/example/Outer$c$d", data.mapClass("a/b$c$d"));
        assertEquals("x/y$z", data.mapClass("x/y$z"));
    }

    @Test
    public void testNamespaceSelection() throws IOException {
        File multiNsTiny = tempDir.resolve("multi_ns.tiny").toFile();
        Files.writeString(multiNsTiny.toPath(), """
                tiny\t2\t0\tofficial\tintermediary\tnamed
                c\ta/b\tclass_123\tcom/example/TestClass
                \tf\tI\ta\tfield_456\tmValue
                \tm\t()V\ta\tmethod_789\tgetValue
                """);

        MappingData data1 = MappingLoader.load(multiNsTiny, "official", "named");
        assertNotNull(data1, "Mapping data should not be null");
        assertEquals("com/example/TestClass", data1.getJarMapping().classes.get("a/b"),
                "Should map official -> named class");
        assertEquals("mValue", data1.getJarMapping().fields.get("a/b/a"),
                "Should map official -> named field");
        assertEquals("getValue", data1.getJarMapping().methods.get("a/b/a ()V"),
                "Should map official -> named method");

        MappingData data2 = MappingLoader.load(multiNsTiny, "official", "intermediary");
        assertNotNull(data2, "Mapping data should not be null");
        assertEquals("class_123", data2.getJarMapping().classes.get("a/b"),
                "Should map official -> intermediary class");
        assertEquals("field_456", data2.getJarMapping().fields.get("a/b/a"),
                "Should map official -> intermediary field");
        assertEquals("method_789", data2.getJarMapping().methods.get("a/b/a ()V"),
                "Should map official -> intermediary method");

        MappingData data3 = MappingLoader.load(multiNsTiny, "intermediary", "named");
        assertNotNull(data3, "Mapping data should not be null");
        assertEquals("com/example/TestClass", data3.getJarMapping().classes.get("class_123"),
                "Should map intermediary -> named class");
        assertEquals("mValue", data3.getJarMapping().fields.get("class_123/field_456"),
                "Should map intermediary -> named field");
        assertEquals("getValue", data3.getJarMapping().methods.get("class_123/method_789 ()V"),
                "Should map intermediary -> named method");
    }

    @Test
    public void testSpecialSourceFallbackSrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(srgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testSpecialSourceFallbackCsrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(csrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testSpecialSourceFallbackTsrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(tsrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testSpecialSourceFallbackTsrg2() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(tsrg2File);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.getJarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
        assertEquals("mField", jarMapping.fields.get("a/b/a"));
        assertEquals("testMethod", jarMapping.methods.get("a/b/a ()V"));
    }

    @Test
    public void testSpecialSourceFallbackProguard() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(proguardFile);

        assertNotNull(data, "Mapping data should not be null");

        JarMapping jarMapping = data.getJarMapping();
        assertFalse(jarMapping.classes.isEmpty(), "Should have class mappings");
        assertFalse(jarMapping.fields.isEmpty(), "Should have field mappings");
        assertFalse(jarMapping.methods.isEmpty(), "Should have method mappings");

        assertEquals("a/b", jarMapping.classes.get("com/example/TestClass"));
        assertEquals("a", jarMapping.fields.get("com/example/TestClass/mField/Ljava/lang/String;"));
        assertEquals("a", jarMapping.methods.get("com/example/TestClass/testMethod ()V"));
    }
}