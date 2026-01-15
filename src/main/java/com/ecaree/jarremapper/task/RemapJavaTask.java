package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.JavaRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;

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
    public File getMappingFile() {
        return extension.getEffectiveMappingFile();
    }

    @OutputDirectory
    public File getOutputDir() {
        return extension.getJavaOutputDir().get().getAsFile();
    }

    @Input
    public JarRemapperExtension.JavaRemapperMode getRemapMode() {
        return extension.getRemapMode().get();
    }

    @TaskAction
    public void remapJava() throws IOException {
        File inputDir = extension.getJavaInputDir().get().getAsFile();
        File outputDir = getOutputDir();
        File mappingFile = getMappingFile();
        JarRemapperExtension.JavaRemapperMode mode = getRemapMode();

        if (!inputDir.exists()) {
            getLogger().warn("Java input directory does not exist: {}, skipping remapping", inputDir);
            return;
        }

        if (mappingFile == null || !mappingFile.exists()) {
            throw new RuntimeException("Mapping file does not exist");
        }

        getLogger().lifecycle("Starting Java source remapping");
        getLogger().lifecycle("Input: {}", inputDir);
        getLogger().lifecycle("Output: {}", outputDir);
        getLogger().lifecycle("Mapping: {}", mappingFile);
        getLogger().lifecycle("Mode: {}", mode);

        MappingData mappingData = MappingLoader.load(mappingFile);
        getLogger().lifecycle("  Loaded mappings: {} classes", mappingData.getClassCount());

        JavaRemapper service = new JavaRemapper(mappingData, mode);
        int processedCount = service.remapJavaSource(inputDir, outputDir);

        getLogger().lifecycle("Java source remapping completed: {} files", processedCount);
    }
}