package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.JarRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;

public class RemapJarTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputFile
    public File getInputJar() {
        return extension.getInputJar().get().getAsFile();
    }

    @InputFile
    @Optional
    public File getMappingFile() {
        return extension.getEffectiveMappingFile();
    }

    @OutputFile
    public File getOutputJar() {
        return extension.getOutputJar().get().getAsFile();
    }

    @TaskAction
    public void remapJar() throws IOException {
        File inputJar = getInputJar();
        File outputJar = getOutputJar();
        File mappingFile = getMappingFile();

        if (!inputJar.exists()) {
            throw new RuntimeException("Input JAR file does not exist: " + inputJar);
        }

        if (mappingFile == null || !mappingFile.exists()) {
            throw new RuntimeException("Mapping file does not exist");
        }

        getLogger().lifecycle("Starting JAR remapping");
        getLogger().lifecycle("Input: {}", inputJar);
        getLogger().lifecycle("Output: {}", outputJar);
        getLogger().lifecycle("Mapping: {}", mappingFile);

        MappingData mappingData = MappingLoader.load(mappingFile);
        getLogger().lifecycle("Loaded mappings: {} classes, {} fields, {} methods",
                mappingData.getClassCount(),
                mappingData.getFieldCount(),
                mappingData.getMethodCount());

        JarRemapper service = new JarRemapper(mappingData);
        service.remapJar(inputJar, outputJar);

        getLogger().lifecycle("JAR remapping completed: {}", outputJar);
    }
}