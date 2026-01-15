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
    private File proguardFile;

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

        proguardFile = tempDir.resolve("mapping.txt").toFile();
        Files.writeString(proguardFile.toPath(), """
                a.b -> com.example.TestClass:
                    java.lang.String a -> mField
                    void a() -> testMethod
                """);
    }

    @Test
    public void testLoadYaml() throws IOException {
        MappingData data = MappingLoader.loadYaml(yamlFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");
        assertEquals(1, data.getFieldCount(), "Should have 1 field mapping");
        assertEquals(1, data.getMethodCount(), "Should have 1 method mapping");

        JarMapping jarMapping = data.jarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));

        MappingEntry classEntry = data.getClassEntry("com/example/TestClass");
        assertNotNull(classEntry, "Should find class mapping entry");
        assertEquals("Test class", classEntry.getComment());

        MappingEntry fieldEntry = data.getFieldEntry("com/example/TestClass", "mField");
        assertNotNull(fieldEntry, "Should find field mapping entry");
        assertEquals("Test field", fieldEntry.getComment());
    }

    @Test
    public void testLoadSrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(srgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");

        JarMapping jarMapping = data.jarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
    }

    @Test
    public void testLoadCsrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(csrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");

        JarMapping jarMapping = data.jarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
    }

    @Test
    public void testLoadTsrg() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(tsrgFile);

        assertNotNull(data, "Mapping data should not be null");
        assertEquals(1, data.getClassCount(), "Should have 1 class mapping");

        JarMapping jarMapping = data.jarMapping();
        assertEquals("com/example/TestClass", jarMapping.classes.get("a/b"));
    }

    @Test
    public void testLoadProguard() throws IOException {
        MappingData data = MappingLoader.loadSpecialSource(proguardFile);

        assertNotNull(data, "Mapping data should not be null");

        JarMapping jarMapping = data.jarMapping();
        assertFalse(jarMapping.classes.isEmpty(), "Should have class mappings");
    }

    @Test
    public void testAutoDetectFormat() throws IOException {
        MappingData yamlData = MappingLoader.load(yamlFile);
        assertNotNull(yamlData);
        assertEquals(1, yamlData.getClassCount());

        MappingData srgData = MappingLoader.load(srgFile);
        assertNotNull(srgData);
        assertEquals(1, srgData.getClassCount());
    }

    @Test
    public void testReverseMapping() throws IOException {
        MappingData reversed = MappingLoader.load(yamlFile, true);

        assertNotNull(reversed, "Reversed mapping should not be null");

        JarMapping reversedMapping = reversed.jarMapping();
        assertEquals("a/b", reversedMapping.classes.get("com/example/TestClass"));
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
}