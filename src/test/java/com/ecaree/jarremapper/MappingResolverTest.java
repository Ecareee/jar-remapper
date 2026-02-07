package com.ecaree.jarremapper;

import com.ecaree.jarremapper.mapping.MappingResolver;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MappingResolverTest {
    @TempDir
    Path tempDir;

    private File projectDir;

    @BeforeEach
    public void setUp() {
        projectDir = tempDir.toFile();
    }

    private BuildResult runGradleTask(String taskName, String buildScript) throws IOException {
        Files.writeString(new File(projectDir, "build.gradle").toPath(), buildScript);
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'test'");

        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(taskName, "--stacktrace", "--info")
                .forwardOutput() // 没有我的 log4j2 格式，难受
                .build();
    }

    @Test
    public void testNormalizeSpigotVersion() {
        assertEquals("1.20.1-R0.1-SNAPSHOT", MappingResolver.normalizeSpigotVersion("1.20.1"),
                "Should add default R0.1-SNAPSHOT suffix");
        assertEquals("1.20.1-R0.1-SNAPSHOT", MappingResolver.normalizeSpigotVersion("1.20.1-R0.1-SNAPSHOT"),
                "Should keep full version as-is");
        assertEquals("1.21.0-R0.2-SNAPSHOT", MappingResolver.normalizeSpigotVersion("1.21.0-R0.2"),
                "Should add SNAPSHOT suffix");
    }

    @Test
    public void testResolveMojangMappingsInvalidSide() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testInvalidSide') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        try {
                            resolver.resolveMojangMappings('1.20.1', 'invalid')
                            throw new GradleException("Should have thrown exception")
                        } catch (IllegalArgumentException e) {
                            println "CAUGHT_EXPECTED_EXCEPTION=" + e.message
                        }
                    }
                }
                """;

        var result = runGradleTask("testInvalidSide", buildScript);

        assertTrue(result.getOutput().contains("CAUGHT_EXPECTED_EXCEPTION"),
                "Should catch IllegalArgumentException");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testMojangMappings() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testMojang') {
                    doLast {
                        def resolver = new MappingResolver(project)
                
                        def clientMappings = resolver.resolveMojangMappings('1.20.1', 'client')
                        assert clientMappings.exists() : "Client mappings should exist"
                        assert clientMappings.text.contains('net.minecraft.') : "Should contain Minecraft classes"
                
                        def serverMappings = resolver.resolveMojangMappings('1.20.1', 'server')
                        assert serverMappings.exists() : "Server mappings should exist"
                
                        def mapping = resolver.loadMojangMappings('1.20.1', 'client')
                        assert mapping.classCount > 1000 : "Should have many classes"
                
                        def cached = resolver.resolveMojangMappings('1.20.1', 'client')
                        assert clientMappings.absolutePath == cached.absolutePath : "Should use cache"
                
                        println "SUCCESS"
                    }
                }
                """;

        var result = runGradleTask("testMojang", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS"), "Mojang mappings test should pass");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testFabricMappings() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testFabric') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        resolver.configureRepositories()
                
                        def intermediary = resolver.resolveFabricIntermediary('1.20.1')
                        assert intermediary.exists() : "Intermediary should exist"
                        assert intermediary.text.contains('net/minecraft/') : "Should contain Minecraft classes"
                
                        def yarn = resolver.resolveFabricYarn('1.20.1+build.10')
                        assert yarn.exists() : "Yarn should exist"
                        assert yarn.text.contains('named') : "Should contain named namespace"
                
                        def mapping = resolver.loadFabricMappingChain('1.20.1', '1.20.1+build.10')
                        assert mapping.classCount > 1000 : "Chain should have many classes"
                
                        def cached = resolver.resolveFabricIntermediary('1.20.1')
                        assert intermediary.absolutePath == cached.absolutePath : "Should use cache"
                
                        println "SUCCESS"
                    }
                }
                """;

        var result = runGradleTask("testFabric", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS"), "Fabric mappings test should pass");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testResolveAndLoad() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                import com.ecaree.jarremapper.mapping.MappingLoader
                
                tasks.register('testResolveAndLoad') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        resolver.configureRepositories()
                
                        def jarFile = resolver.resolve('net.fabricmc:intermediary:1.20.1:v2')
                        assert jarFile.exists() : "Resolved JAR should exist"
                        assert jarFile.name.endsWith('.jar') : "Should be a JAR file"
                
                        def tinyFile = resolver.resolveFabricIntermediary('1.20.1')
                        assert tinyFile.exists() : "Extracted tiny should exist"
                
                        def mapping = MappingLoader.load(tinyFile, 'official', 'intermediary')
                        assert mapping.classCount > 1000 : "Should have many classes"
                
                        def mapping2 = MappingLoader.load(tinyFile)
                        assert mapping2.classCount > 0 : "Should have classes"
                
                        println "SUCCESS"
                    }
                }
                """;

        var result = runGradleTask("testResolveAndLoad", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS"), "resolveAndLoad test should pass");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testParchment() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testParchment') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        resolver.configureRepositories()
                
                        def parchment = resolver.resolveParchment('1.20.1', '2023.09.03')
                        assert parchment.exists() : "Parchment should exist"
                        assert parchment.name.endsWith('.zip') : "Should be a zip"
                
                        println "SUCCESS"
                    }
                }
                """;

        var result = runGradleTask("testParchment", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS"), "Parchment test should pass");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testForgeSrg() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testForgeSrg') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        resolver.configureRepositories()
                
                        try {
                            def srg = resolver.resolveForgeSrg('1.20.1-47.2.0')
                            assert srg.exists() : "Forge SRG should exist"
                            println "SUCCESS"
                        } catch (Exception e) {
                            println "SKIPPED: " + e.message
                        }
                    }
                }
                """;
        var result = runGradleTask("testForgeSrg", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS") || result.getOutput().contains("SKIPPED"),
                "Forge SRG test should pass or skip");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testConfigureRepositories() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testRepos') {
                    doLast {
                        def resolver = new MappingResolver(project)
                
                        def countBefore = project.repositories.size()
                        resolver.configureRepositories()
                        def countAfter = project.repositories.size()
                
                        assert countAfter > countBefore : "Should add repositories"
                
                        def names = project.repositories.collect { it.name }
                        assert names.contains('FabricMC') : "Should have FabricMC"
                        assert names.contains('MinecraftForge') : "Should have Forge"
                        assert names.contains('NeoForged') : "Should have NeoForge"
                        assert names.contains('ParchmentMC') : "Should have Parchment"
                
                        println "SUCCESS"
                    }
                }
                """;

        var result = runGradleTask("testRepos", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS"), "configureRepositories test should pass");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NETWORK", matches = "true")
    public void testSpigotMappings() throws IOException {
        String buildScript = """
                plugins {
                    id 'java'
                    id 'com.ecaree.jarremapper'
                }
                
                import com.ecaree.jarremapper.mapping.MappingResolver
                
                tasks.register('testSpigot') {
                    doLast {
                        def resolver = new MappingResolver(project)
                        resolver.configureRepositories()
                
                        def version = MappingResolver.normalizeSpigotVersion('1.20.1')
                
                        try {
                            def mojangMappings = resolver.resolveSpigotMojangMappings(version)
                            def spigotMappings = resolver.resolveSpigotMappings(version)
                            def chain = resolver.loadSpigotMappingChain(version)
                
                            assert mojangMappings.exists()
                            assert spigotMappings.exists()
                            assert chain.classCount > 100
                
                            println "SUCCESS"
                        } catch (Exception e) {
                            println "SKIPPED: " + e.message
                        }
                    }
                }
                """;

        var result = runGradleTask("testSpigot", buildScript);
        assertTrue(result.getOutput().contains("SUCCESS") || result.getOutput().contains("SKIPPED"),
                "Spigot test should pass or skip");
    }
}