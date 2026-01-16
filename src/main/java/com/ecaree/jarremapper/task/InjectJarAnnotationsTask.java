package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.AnnotationInjector;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;

public class InjectJarAnnotationsTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputFile
    public File getInputJar() {
        return extension.getOutputJar().get().getAsFile();
    }

    @InputFile
    public File getMappingFile() {
        return extension.getEffectiveMappingFile();
    }

    @OutputFile
    public File getOutputJar() {
        return extension.getOutputJar().get().getAsFile();
    }

    @Input
    public boolean getIncludeReadableInfo() {
        return extension.getInjectReadableInfo().get();
    }

    @TaskAction
    public void injectAnnotations() throws IOException {
        File jar = getInputJar();
        File mappingFile = getMappingFile();
        boolean includeReadable = getIncludeReadableInfo();

        if (!jar.exists()) {
            throw new RuntimeException("JAR file does not exist: " + jar + ", please run remapJar task first");
        }

        if (mappingFile == null || !mappingFile.exists()) {
            throw new RuntimeException("Mapping file does not exist");
        }

        getLogger().lifecycle("Starting annotation injection");
        getLogger().lifecycle("JAR: {}", jar);
        getLogger().lifecycle("Mapping: {}", mappingFile);
        getLogger().lifecycle("Include readable info: {}", includeReadable);

        MappingData mappingData = MappingLoader.load(mappingFile);

        AnnotationInjector injector = new AnnotationInjector(mappingData, includeReadable);
        injector.injectAnnotations(jar, jar);

        getLogger().lifecycle("Annotation injection completed");
    }
}