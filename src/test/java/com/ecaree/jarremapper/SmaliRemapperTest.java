package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.SmaliRemapper;
import com.ecaree.jarremapper.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
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
                
                      - obfuscated: b
                        readable: mHelper
                        type: La/c;
                
                    methods:
                      - obfuscated: a
                        readable: getValue
                        descriptor: ()I
                
                      - obfuscated: b
                        readable: setHelper
                        descriptor: (La/c;)V
                
                  - obfuscated: a/b$a
                    readable: com/example/TestClass$Handler
                
                    fields:
                      - obfuscated: a
                        readable: callback
                        type: Ljava/lang/Runnable;
                
                    methods:
                      - obfuscated: a
                        readable: execute
                        descriptor: ()V
                
                  - obfuscated: a/b$b
                    readable: com/example/TestClass$Builder
                
                    methods:
                      - obfuscated: a
                        readable: build
                        descriptor: ()La/b;
                
                  - obfuscated: a/c
                    readable: com/example/Helper
                
                    fields:
                      - obfuscated: x
                        readable: data
                        type: I
                
                    methods:
                      - obfuscated: m
                        readable: doWork
                        descriptor: ()V
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
                
                .field private b:La/c;
                
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
                
                .method public b(La/c;)V
                    .registers 2
                    iput-object p1, p0, La/b;->b:La/c;
                    return-void
                .end method
                """);
    }

    @Test
    public void testRemapSmali() throws IOException {
        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File expectedFile = new File(smaliOutputDir, "com/example/TestClass.smali");
        assertTrue(expectedFile.exists(), "Remapped smali file should exist at new path");

        File oldFile = new File(smaliOutputDir, "a/b.smali");
        assertFalse(oldFile.exists(), "Old path should not exist");

        String content = Files.readString(expectedFile.toPath());
        log.info("TestClass.smali:\n{}", content);

        assertTrue(content.contains(".class public Lcom/example/TestClass;"),
                "Class declaration should be remapped");
        assertFalse(content.contains("La/b;"),
                "Should not contain old class reference");

        assertTrue(content.contains(".field private mValue:I"),
                "Field name should be remapped");
        assertFalse(content.contains(".field private a:I"),
                "Should not contain old field declaration");

        assertTrue(content.contains(".field private mHelper:Lcom/example/Helper;"),
                "Field with object type should have both name and type remapped");

        assertTrue(content.contains(".method public getValue()I"),
                "Method name should be remapped");
        assertFalse(content.contains(".method public a()I"),
                "Should not contain old method declaration");

        assertTrue(content.contains(".method public setHelper(Lcom/example/Helper;)V"),
                "Method parameter type should be remapped");

        assertTrue(content.contains("iget v0, p0, Lcom/example/TestClass;->mValue:I"),
                "Field reference in iget should be fully remapped");

        assertTrue(content.contains("iput-object p1, p0, Lcom/example/TestClass;->mHelper:Lcom/example/Helper;"),
                "Field reference in iput-object should be fully remapped");
    }

    @Test
    public void testRemapSmaliMethodCalls() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .field public x:I
                
                .method public m()V
                    .registers 2
                    new-instance v0, La/b;
                    invoke-direct {v0}, La/b;-><init>()V
                    invoke-virtual {v0}, La/b;->a()I
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File callerOutput = new File(smaliOutputDir, "com/example/Helper.smali");
        assertTrue(callerOutput.exists(), "Remapped caller smali file should exist");

        String content = Files.readString(callerOutput.toPath());
        log.info("Helper.smali:\n{}", content);

        assertTrue(content.contains(".class public Lcom/example/Helper;"),
                "Class declaration should be remapped");

        assertTrue(content.contains(".field public data:I"),
                "Field name should be remapped");

        assertTrue(content.contains(".method public doWork()V"),
                "Method name should be remapped");

        assertTrue(content.contains("new-instance v0, Lcom/example/TestClass;"),
                "new-instance type should be remapped");

        assertTrue(content.contains("invoke-direct {v0}, Lcom/example/TestClass;-><init>()V"),
                "Constructor call should have owner remapped");

        assertTrue(content.contains("invoke-virtual {v0}, Lcom/example/TestClass;->getValue()I"),
                "Method call should have owner and name remapped");

        assertFalse(content.contains("La/b;"), "Should not contain old class reference");
        assertFalse(content.contains("La/c;"), "Should not contain old self class reference");
    }

    @Test
    public void testRemapInnerClass() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "b$a.smali").toPath(), """
                .class public La/b$a;
                .super Ljava/lang/Object;
                
                .annotation system Lx/A;
                    value = La/b;
                .end annotation
                
                .annotation system Lx/B;
                    accessFlags = 0x1
                    name = "a"
                .end annotation
                
                .field final synthetic this$0:La/b;
                
                .field private a:Ljava/lang/Runnable;
                
                .method public constructor <init>(La/b;)V
                    .registers 2
                    iput-object p1, p0, La/b$a;->this$0:La/b;
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method
                
                .method public a()V
                    .registers 2
                    iget-object v0, p0, La/b$a;->this$0:La/b;
                    invoke-virtual {v0}, La/b;->a()I
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File innerClassFile = new File(smaliOutputDir, "com/example/TestClass$Handler.smali");
        assertTrue(innerClassFile.exists(), "Inner class should be output with fully remapped name");

        File oldInnerClassFile = new File(smaliOutputDir, "a/b$a.smali");
        assertFalse(oldInnerClassFile.exists(), "Old inner class path should not exist");

        String content = Files.readString(innerClassFile.toPath());
        log.info("TestClass$Handler.smali:\n{}", content);

        assertTrue(content.contains(".class public Lcom/example/TestClass$Handler;"),
                "Inner class declaration should be fully remapped");

        assertTrue(content.contains("value = Lcom/example/TestClass;"),
                "EnclosingClass annotation value should be remapped");

        assertTrue(content.contains(".field final synthetic this$0:Lcom/example/TestClass;"),
                "Synthetic outer reference field type should be remapped");

        assertTrue(content.contains(".field private callback:Ljava/lang/Runnable;"),
                "Inner class field should be remapped");

        assertTrue(content.contains("<init>(Lcom/example/TestClass;)V"),
                "Constructor parameter type should be remapped");

        assertTrue(content.contains("iput-object p1, p0, Lcom/example/TestClass$Handler;->this$0:Lcom/example/TestClass;"),
                "Field reference should have both owner and type remapped");

        assertTrue(content.contains("iget-object v0, p0, Lcom/example/TestClass$Handler;->this$0:Lcom/example/TestClass;"),
                "Field access should be fully remapped");

        assertTrue(content.contains(".method public execute()V"),
                "Inner class method should be remapped");

        assertTrue(content.contains("invoke-virtual {v0}, Lcom/example/TestClass;->getValue()I"),
                "Method call on outer class should be remapped");

        assertFalse(content.contains("La/b;"), "Should not contain old outer class reference");
        assertFalse(content.contains("La/b$a;"), "Should not contain old inner class reference");
    }

    @Test
    public void testRemapInnerClassWithReturnType() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "b$b.smali").toPath(), """
                .class public La/b$b;
                .super Ljava/lang/Object;
                
                .annotation system Lx/A;
                    value = La/b;
                .end annotation
                
                .field public final synthetic this$0:La/b;
                
                .method public a()La/b;
                    .registers 2
                    iget-object v0, p0, La/b$b;->this$0:La/b;
                    return-object v0
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File innerClassFile = new File(smaliOutputDir, "com/example/TestClass$Builder.smali");
        assertTrue(innerClassFile.exists(), "Inner class should be output with fully remapped name");

        String content = Files.readString(innerClassFile.toPath());
        log.info("TestClass$Builder.smali:\n{}", content);

        assertTrue(content.contains(".class public Lcom/example/TestClass$Builder;"),
                "Inner class declaration should be fully remapped");

        assertTrue(content.contains(".method public build()Lcom/example/TestClass;"),
                "Method with outer class return type should be fully remapped");

        assertTrue(content.contains("iget-object v0, p0, Lcom/example/TestClass$Builder;->this$0:Lcom/example/TestClass;"),
                "Field access should be fully remapped");

        assertFalse(content.contains("La/b$b;"), "Should not contain old inner class reference");
    }

    @Test
    public void testRemapInnerClassReference() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .field public x:I
                
                .method public m()V
                    .registers 3
                    new-instance v0, La/b;
                    invoke-direct {v0}, La/b;-><init>()V
                    new-instance v1, La/b$a;
                    invoke-direct {v1, v0}, La/b$a;-><init>(La/b;)V
                    invoke-virtual {v1}, La/b$a;->a()V
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File outputFile = new File(smaliOutputDir, "com/example/Helper.smali");
        String content = Files.readString(outputFile.toPath());
        log.info("Helper.smali (with inner class reference):\n{}", content);

        assertTrue(content.contains("new-instance v1, Lcom/example/TestClass$Handler;"),
                "Inner class instantiation should be remapped");

        assertTrue(content.contains("invoke-direct {v1, v0}, Lcom/example/TestClass$Handler;-><init>(Lcom/example/TestClass;)V"),
                "Inner class constructor call should be fully remapped");

        assertTrue(content.contains("invoke-virtual {v1}, Lcom/example/TestClass$Handler;->execute()V"),
                "Inner class method call should be remapped");

        assertFalse(content.contains("La/b$a;"), "Should not contain old inner class reference");
    }

    @Test
    public void testPreserveComments() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "b.smali").toPath(), """
                .class public La/b;
                .super Ljava/lang/Object;
                
                # This is a comment about the field
                .field private a:I # inline comment
                
                # Reference in comment: La/b;->a:I should not be remapped
                .method public a()I
                    .registers 2
                    # Get the value from La/b;
                    iget v0, p0, La/b;->a:I # load field La/b;->a:I
                    return v0
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File outputFile = new File(smaliOutputDir, "com/example/TestClass.smali");
        String content = Files.readString(outputFile.toPath());
        log.info("TestClass.smali (with comments):\n{}", content);

        assertTrue(content.contains("# This is a comment about the field"),
                "Standalone comment should be preserved");
        assertTrue(content.contains("# inline comment"),
                "Inline comment should be preserved");

        assertTrue(content.contains("# Reference in comment: La/b;->a:I should not be remapped"),
                "Type reference in standalone comment should not be remapped");
        assertTrue(content.contains("# Get the value from La/b;"),
                "Type reference in comment should not be remapped");
        assertTrue(content.contains("# load field La/b;->a:I"),
                "Field reference in inline comment should not be remapped");

        assertTrue(content.contains(".field private mValue:I # inline comment"),
                "Field should be remapped while preserving inline comment");
        assertTrue(content.contains("iget v0, p0, Lcom/example/TestClass;->mValue:I # load field La/b;->a:I"),
                "Code should be remapped but inline comment preserved with original reference");
    }

    @Test
    public void testPreserveStrings() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .field public x:I
                
                .method public m()V
                    .registers 2
                    const-string v0, "La/b;->a:I"
                    const-string v1, "Class a/b has field a"
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File outputFile = new File(smaliOutputDir, "com/example/Helper.smali");
        String content = Files.readString(outputFile.toPath());
        log.info("Helper.smali (with strings):\n{}", content);

        assertTrue(content.contains("const-string v0, \"La/b;->a:I\""),
                "String literal should not be remapped");
        assertTrue(content.contains("const-string v1, \"Class a/b has field a\""),
                "String content should not be remapped");

        assertTrue(content.contains(".class public Lcom/example/Helper;"),
                "Class declaration should still be remapped");
    }

    @Test
    public void testRemapArrayTypes() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .field public x:I
                
                .field private arr:[La/b;
                
                .field private arr2:[[La/b;
                
                .method public getArray()[La/b;
                    .registers 2
                    iget-object v0, p0, La/c;->arr:[La/b;
                    return-object v0
                .end method
                
                .method public setArray([La/b;)V
                    .registers 2
                    iput-object p1, p0, La/c;->arr:[La/b;
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File outputFile = new File(smaliOutputDir, "com/example/Helper.smali");
        String content = Files.readString(outputFile.toPath());
        log.info("Helper.smali (with arrays):\n{}", content);

        assertTrue(content.contains(".field private arr:[Lcom/example/TestClass;"),
                "1D array field type should be remapped");
        assertTrue(content.contains(".field private arr2:[[Lcom/example/TestClass;"),
                "2D array field type should be remapped");

        assertTrue(content.contains(".method public getArray()[Lcom/example/TestClass;"),
                "Array return type should be remapped");

        assertTrue(content.contains(".method public setArray([Lcom/example/TestClass;)V"),
                "Array parameter type should be remapped");

        assertTrue(content.contains("iget-object v0, p0, Lcom/example/Helper;->arr:[Lcom/example/TestClass;"),
                "Array field reference should be fully remapped");
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        File emptyDir = tempDir.resolve("empty").toFile();
        FileUtils.ensureDirectory(emptyDir);
        File outputDir = tempDir.resolve("empty-output").toFile();

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(emptyDir, outputDir);

        assertTrue(outputDir.exists(), "Output directory should be created");
        String[] files = outputDir.list();
        assertNotNull(files, "Output directory list should not be null");
        assertEquals(0, files.length, "Output directory should be empty");
    }

    @Test
    public void testUnmappedClassPreserved() throws IOException {
        File unmappedDir = new File(smaliInputDir, "unmapped");
        FileUtils.ensureDirectory(unmappedDir);

        Files.writeString(new File(unmappedDir, "Foo.smali").toPath(), """
                .class public Lunmapped/Foo;
                .super Ljava/lang/Object;
                
                .field private bar:I
                
                .method public getBar()I
                    .registers 2
                    iget v0, p0, Lunmapped/Foo;->bar:I
                    return v0
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File unmappedOutput = new File(smaliOutputDir, "unmapped/Foo.smali");
        assertTrue(unmappedOutput.exists(), "Unmapped class should be preserved at original path");

        String content = Files.readString(unmappedOutput.toPath());
        log.info("Foo.smali (unmapped):\n{}", content);

        assertTrue(content.contains(".class public Lunmapped/Foo;"),
                "Unmapped class declaration should be preserved");
        assertTrue(content.contains(".field private bar:I"),
                "Unmapped field should be preserved");
        assertTrue(content.contains("Lunmapped/Foo;->bar:I"),
                "Unmapped field reference should be preserved");
    }

    @Test
    public void testMultipleMappingsInOneLine() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "c.smali").toPath(), """
                .class public La/c;
                .super Ljava/lang/Object;
                
                .field public x:I
                
                .method public transfer(La/b;La/c;)V
                    .registers 4
                    invoke-virtual {p1}, La/b;->a()I
                    move-result v0
                    iput v0, p2, La/c;->x:I
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File outputFile = new File(smaliOutputDir, "com/example/Helper.smali");
        String content = Files.readString(outputFile.toPath());
        log.info("Helper.smali (multiple mappings):\n{}", content);

        assertTrue(content.contains(".method public transfer(Lcom/example/TestClass;Lcom/example/Helper;)V"),
                "Multiple parameter types should all be remapped");

        assertTrue(content.contains("invoke-virtual {p1}, Lcom/example/TestClass;->getValue()I"),
                "Method call should be remapped");

        assertTrue(content.contains("iput v0, p2, Lcom/example/Helper;->data:I"),
                "Field access should be remapped");
    }

    @Test
    public void testUnmappedInnerClassFollowsOuter() throws IOException {
        File packageDir = new File(smaliInputDir, "a");
        Files.writeString(new File(packageDir, "b$c.smali").toPath(), """
                .class public La/b$c;
                .super Ljava/lang/Object;
                
                .field public final synthetic this$0:La/b;
                
                .method public test()V
                    .registers 2
                    iget-object v0, p0, La/b$c;->this$0:La/b;
                    invoke-virtual {v0}, La/b;->a()I
                    return-void
                .end method
                """);

        SmaliRemapper remapper = new SmaliRemapper(mappingData);
        remapper.remapSmali(smaliInputDir, smaliOutputDir);

        File innerClassFile = new File(smaliOutputDir, "com/example/TestClass$c.smali");
        assertTrue(innerClassFile.exists(), "Unmapped inner class should follow outer class path");

        String content = Files.readString(innerClassFile.toPath());
        log.info("TestClass$c.smali (unmapped inner):\n{}", content);

        assertTrue(content.contains(".class public Lcom/example/TestClass$c;"),
                "Unmapped inner class should have outer remapped but inner name preserved");

        assertTrue(content.contains(".field public final synthetic this$0:Lcom/example/TestClass;"),
                "Outer class reference should be remapped");

        assertTrue(content.contains("iget-object v0, p0, Lcom/example/TestClass$c;->this$0:Lcom/example/TestClass;"),
                "Field access should be remapped");

        assertTrue(content.contains("invoke-virtual {v0}, Lcom/example/TestClass;->getValue()I"),
                "Method call on outer class should be remapped");
    }
}