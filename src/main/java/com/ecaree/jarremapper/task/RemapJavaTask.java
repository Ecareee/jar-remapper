package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingHelper;
import com.ecaree.jarremapper.remap.JavaRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RemapJavaTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputDirectory
    @Optional
    public File getInputDir() {
        File dir = extension.getJavaInputDir().get().getAsFile();
        return dir.exists() ? dir : null;
    }

    @InputFile
    @Optional
    public File getMappingFile() {
        return extension.getEffectiveMappingFile();
    }

    @OutputDirectory
    public File getOutputDir() {
        return extension.getJavaOutputDir().get().getAsFile();
    }

    @InputFiles
    @Optional
    public FileCollection getLibraryJars() {
        return extension.getJavaLibraryJars();
    }

    @TaskAction
    public void remapJava() throws IOException {
        File inputDir = extension.getJavaInputDir().get().getAsFile();
        File outputDir = getOutputDir();
        FileCollection libraryJars = getLibraryJars();

        if (!inputDir.exists()) {
            getLogger().warn("Java input directory does not exist: {}, skipping remapping", inputDir);
            return;
        }

        getLogger().lifecycle("Starting Java source remapping");
        getLogger().lifecycle("Input: {}", inputDir);
        getLogger().lifecycle("Output: {}", outputDir);
        getLogger().lifecycle("Mapping: {}", extension.getEffectiveMappingFile());

        List<File> jarList = new ArrayList<>();

        // 1. 自动添加 outputJar，重映射后的 JAR 包含类型信息
        File outputJar = extension.getOutputJar().get().getAsFile();
        if (outputJar.exists()) {
            jarList.add(outputJar);
            getLogger().lifecycle("Auto added output JAR for type resolution: {}", outputJar);
        }

        // 2. 用户配置的额外库 JAR
        if (libraryJars != null) {
            for (File jar : libraryJars) {
                if (jar.exists() && !jar.equals(outputJar)) {
                    jarList.add(jar);
                    getLogger().lifecycle("Library JAR: {}", jar);
                }
            }
        }

        MappingData mappingData = MappingHelper.loadFromExtension(extension);

        if (!mappingData.getExcludedPackages().isEmpty()) {
            getLogger().lifecycle("Excluded packages: {}", mappingData.getExcludedPackages());
        }

        getLogger().lifecycle("Loaded mappings: {} classes, {} fields, {} methods",
                mappingData.getClassCount(),
                mappingData.getFieldCount(),
                mappingData.getMethodCount());

        JavaRemapper remapper = new JavaRemapper(mappingData, jarList);
        int processedCount = remapper.remapJavaSource(inputDir, outputDir);

        getLogger().lifecycle("Java source remapping completed: {} files", processedCount);
    }
}