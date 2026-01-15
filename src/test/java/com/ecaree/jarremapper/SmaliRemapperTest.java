package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.SmaliRemapper;
import com.ecaree.jarremapper.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SmaliRemapperTest {
    @TempDir
    Path tempDir;

    private MappingData mappingData;
    private File smaliInputDir;
    private File smaliOutputDir;

    @BeforeEach
    public void setUp() throws IOException {
        File yamlFile = tempDir.resolve("mappings.yaml").toFile();
        Files.writeString(yamlFile.toPath(), """
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
                """);

        mappingData = MappingLoader.loadYaml(yamlFile);

        smaliInputDir = tempDir.resolve("smali-input").toFile();
        smaliOutputDir = tempDir.resolve("smali-output").toFile();
        FileUtils.ensureDirectory(smaliOutputDir);

        File packageDir = new File(smaliInputDir, "a");
        FileUtils.ensureDirectory(packageDir);

        Files.writeString(new File(packageDir, "b.smali").toPath(), """
                .class public La/b;
                .super Ljava/lang/Object;
                
                .field private a:I
                
                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
                
                .method public a()I
                    .registers 2
                    iget v0, p0, La/b;->a:I
                    return v0
                .end method
                """);
    }

    @Test
    public void testRemapSmali() throws IOException {
        SmaliRemapper service = new SmaliRemapper(mappingData);
        service.remapSmali(smaliInputDir, smaliOutputDir);

        File expectedFile = new File(smaliOutputDir, "com/example/TestClass.smali");
        assertTrue(expectedFile.exists(), "Remapped smali file should exist");

        String content = Files.readString(expectedFile.toPath());
        assertTrue(content.contains("Lcom/example/TestClass;"), "Should contain remapped class name");
    }

    @Test
    public void testRemapSmaliMethodCalls() throws IOException {
        File aDir = new File(smaliInputDir, "a");
        Files.writeString(new File(aDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .method public test()V
                    .registers 2
                    new-instance v0, La/b;
                    invoke-direct {v0}, La/b;-><init>()V
                    invoke-virtual {v0}, La/b;->a()I
                    return-void
                .end method
                """);

        SmaliRemapper service = new SmaliRemapper(mappingData);
        service.remapSmali(smaliInputDir, smaliOutputDir);

        File callerOutput = new File(smaliOutputDir, "com/example/Helper.smali");
        assertTrue(callerOutput.exists(), "Remapped caller smali file should exist");

        String content = Files.readString(callerOutput.toPath());
        assertTrue(content.contains("Lcom/example/TestClass;"), "Should contain remapped class reference");
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        File emptyDir = tempDir.resolve("empty").toFile();
        FileUtils.ensureDirectory(emptyDir);
        File outputDir = tempDir.resolve("empty-output").toFile();

        SmaliRemapper service = new SmaliRemapper(mappingData);
        service.remapSmali(emptyDir, outputDir);

        assertTrue(outputDir.exists(), "Output directory should be created");
    }
}