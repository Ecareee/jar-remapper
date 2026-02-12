package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingChain;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MappingChainTest {
    @TempDir
    Path tempDir;

    @Test
    public void testSimpleChain() throws IOException {
        File mapping1 = tempDir.resolve("a_to_b.yaml").toFile();
        Files.writeString(mapping1.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/A
                    readable: b/B
                
                    fields:
                      - obfuscated: x
                        readable: y
                        type: I
                
                    methods:
                      - obfuscated: m
                        readable: n
                        descriptor: ()V
                """);

        File mapping2 = tempDir.resolve("b_to_c.yaml").toFile();
        Files.writeString(mapping2.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: b/B
                    readable: c/C
                
                    fields:
                      - obfuscated: y
                        readable: z
                        type: I
                
                    methods:
                      - obfuscated: n
                        readable: o
                        descriptor: ()V
                """);

        MappingData first = MappingLoader.load(mapping1);
        MappingData second = MappingLoader.load(mapping2);

        MappingChain chain = new MappingChain()
                .add(first)
                .add(second);

        MappingData merged = chain.merge();

        assertEquals("c/C", merged.mapClass("a/A"), "Class a/A should be mapped to c/C");
        assertEquals("z", merged.mapField("a/A", "x"), "Field x should be mapped to z");
        assertEquals("o", merged.mapMethod("a/A", "m", "()V"), "Method m should be mapped to o");
    }

    @Test
    public void testChainWithDescriptorRemapping() throws IOException {
        File mapping1 = tempDir.resolve("first.yaml").toFile();
        Files.writeString(mapping1.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/Param
                    readable: b/Parameter
                
                  - obfuscated: a/Return
                    readable: b/Result
                
                  - obfuscated: a/Main
                    readable: b/Main
                
                    methods:
                      - obfuscated: m
                        readable: process
                        descriptor: (La/Param;)La/Return;
                """);

        File mapping2 = tempDir.resolve("second.yaml").toFile();
        Files.writeString(mapping2.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: b/Parameter
                    readable: c/Input
                
                  - obfuscated: b/Result
                    readable: c/Output
                
                  - obfuscated: b/Main
                    readable: c/Processor
                
                    methods:
                      - obfuscated: process
                        readable: execute
                        descriptor: (Lb/Parameter;)Lb/Result;
                """);

        MappingData first = MappingLoader.load(mapping1);
        MappingData second = MappingLoader.load(mapping2);

        MappingData merged = new MappingChain()
                .add(first)
                .add(second)
                .merge();

        assertEquals("c/Input", merged.mapClass("a/Param"), "Class a/Param should be mapped to c/Input");
        assertEquals("c/Output", merged.mapClass("a/Return"), "Class a/Return should be mapped to c/Output");
        assertEquals("c/Processor", merged.mapClass("a/Main"), "Class a/Main should be mapped to c/Processor");
        assertEquals("execute", merged.mapMethod("a/Main", "m", "(La/Param;)La/Return;"),
                "Method m should be mapped to execute through chain");
    }

    @Test
    public void testChainWithReverse() throws IOException {
        File mapping = tempDir.resolve("mapping.yaml").toFile();
        Files.writeString(mapping.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/A
                    readable: b/B
                """);

        MappingData data = MappingLoader.load(mapping);

        MappingChain forwardChain = new MappingChain().add(data);
        MappingData forward = forwardChain.merge();
        assertEquals("b/B", forward.mapClass("a/A"), "Forward mapping: a/A should map to b/B");

        MappingChain reverseChain = new MappingChain().add(data, true);
        MappingData reversed = reverseChain.merge();
        assertEquals("a/A", reversed.mapClass("b/B"), "Reverse mapping: b/B should map to a/A");
    }

    @Test
    public void testEmptyChain() {
        MappingChain chain = new MappingChain();
        assertTrue(chain.isEmpty(), "New chain should be empty");
        assertThrows(IllegalStateException.class, chain::merge, "Merging empty chain should throw exception");
    }

    @Test
    public void testSingleMappingChain() throws IOException {
        File mapping = tempDir.resolve("single.yaml").toFile();
        Files.writeString(mapping.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/A
                    readable: b/B
                """);

        MappingData data = MappingLoader.load(mapping);
        MappingChain chain = new MappingChain().add(data);

        assertEquals(1, chain.size(), "Chain should have size 1");
        MappingData merged = chain.merge();
        assertEquals("b/B", merged.mapClass("a/A"), "Single mapping chain should work correctly");
    }

    @Test
    public void testInnerClassChain() throws IOException {
        File mapping1 = tempDir.resolve("first.yaml").toFile();
        Files.writeString(mapping1.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/Outer
                    readable: b/Middle
                """);

        File mapping2 = tempDir.resolve("second.yaml").toFile();
        Files.writeString(mapping2.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: b/Middle
                    readable: c/Final
                """);

        MappingData first = MappingLoader.load(mapping1);
        MappingData second = MappingLoader.load(mapping2);

        MappingData merged = new MappingChain()
                .add(first)
                .add(second)
                .merge();

        assertEquals("c/Final", merged.mapClass("a/Outer"), "Outer class should be mapped");
        assertEquals("c/Final$Inner", merged.mapClass("a/Outer$Inner"),
                "Inner class should follow outer class mapping");
    }

    @Test
    public void testThreeStepChain() throws IOException {
        File mapping1 = tempDir.resolve("a_to_b.yaml").toFile();
        Files.writeString(mapping1.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: a/A
                    readable: b/B
                """);

        File mapping2 = tempDir.resolve("b_to_c.yaml").toFile();
        Files.writeString(mapping2.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: b/B
                    readable: c/C
                """);

        File mapping3 = tempDir.resolve("c_to_d.yaml").toFile();
        Files.writeString(mapping3.toPath(), """
                version: "1.0"
                
                classes:
                  - obfuscated: c/C
                    readable: d/D
                """);

        MappingData first = MappingLoader.load(mapping1);
        MappingData second = MappingLoader.load(mapping2);
        MappingData third = MappingLoader.load(mapping3);

        MappingData merged = new MappingChain()
                .add(first)
                .add(second)
                .add(third)
                .merge();

        assertEquals("d/D", merged.mapClass("a/A"), "Three-step chain: a/A should map to d/D");
    }
}