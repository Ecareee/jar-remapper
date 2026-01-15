package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.SmaliRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;

public class RemapSmaliTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputDirectory
    @Optional
    public File getInputDir() {
        File dir = extension.getSmaliInputDir().get().getAsFile();
        return dir.exists() ? dir : null;
    }

    @InputFile
    public File getMappingFile() {
        return extension.getEffectiveMappingFile();
    }

    @OutputDirectory
    public File getOutputDir() {
        return extension.getSmaliOutputDir().get().getAsFile();
    }

    @TaskAction
    public void remapSmali() throws IOException {
        File inputDir = extension.getSmaliInputDir().get().getAsFile();
        File outputDir = getOutputDir();
        File mappingFile = getMappingFile();

        if (!inputDir.exists()) {
            getLogger().warn("Smali input directory does not exist: {}, skipping remapping", inputDir);
            return;
        }

        if (mappingFile == null || !mappingFile.exists()) {
            throw new RuntimeException("Mapping file does not exist");
        }

        getLogger().lifecycle("Starting smali remapping");
        getLogger().lifecycle("Input: {}", inputDir);
        getLogger().lifecycle("Output: {}", outputDir);
        getLogger().lifecycle("Mapping: {}", mappingFile);

        MappingData mappingData = MappingLoader.load(mappingFile);
        getLogger().lifecycle("Loaded mappings: {} classes", mappingData.getClassCount());

        SmaliRemapper service = new SmaliRemapper(mappingData);
        service.remapSmali(inputDir, outputDir);

        getLogger().lifecycle("Smali remapping completed: {}", outputDir);
    }
}