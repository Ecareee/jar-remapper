package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.remap.JarRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

public class RemapJarTask extends DefaultTask {
    /**
     * 独立模式：直接指定输入 JAR
     * 优先级高于 extension 配置
     */
    @InputFile
    @Optional
    @Getter
    private final RegularFileProperty inputJar = getProject().getObjects().fileProperty();

    /**
     * 独立模式：直接指定输出 JAR
     * 优先级高于 extension 配置
     */
    @OutputFile
    @Optional
    @Getter
    private final RegularFileProperty outputJar = getProject().getObjects().fileProperty();

    /**
     * 独立模式：直接指定映射文件
     * 优先级高于 extension 配置
     */
    @InputFile
    @Optional
    @Getter
    private final RegularFileProperty mappingFile = getProject().getObjects().fileProperty();

    /**
     * 是否反向映射
     * 用于将可读命名转回混淆命名
     */
    @Input
    @Optional
    @Getter
    private final Property<Boolean> reverse = getProject().getObjects().property(Boolean.class);

    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputFile
    public File getEffectiveInputJar() {
        if (inputJar.isPresent()) {
            return inputJar.get().getAsFile();
        }
        return extension.getInputJar().get().getAsFile();
    }

    @OutputFile
    public File getEffectiveOutputJar() {
        if (outputJar.isPresent()) {
            return outputJar.get().getAsFile();
        }
        return extension.getOutputJar().get().getAsFile();
    }

    @InputFile
    @Optional
    public File getEffectiveMappingFile() {
        if (mappingFile.isPresent()) {
            return mappingFile.get().getAsFile();
        }
        return extension != null ? extension.getEffectiveMappingFile() : null;
    }

    @TaskAction
    public void remapJar() throws IOException {
        File inputJar = getEffectiveInputJar();
        File outputJar = getEffectiveOutputJar();
        File mappingFile = getEffectiveMappingFile();
        boolean reverse = this.reverse.getOrElse(false);

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
        getLogger().lifecycle("Reverse: {}", reverse);

        MappingData mappingData = MappingLoader.load(mappingFile, reverse);

        if (extension != null) {
            for (String pkg : extension.getExcludedPackages().getOrElse(Collections.emptyList())) {
                mappingData.addExcludedPackage(pkg);
            }
            if (!mappingData.getExcludedPackages().isEmpty()) {
                getLogger().lifecycle("Excluded packages: {}", mappingData.getExcludedPackages());
            }
        }

        getLogger().lifecycle("Loaded mappings: {} classes, {} fields, {} methods",
                mappingData.getClassCount(),
                mappingData.getFieldCount(),
                mappingData.getMethodCount());

        JarRemapper remapper = new JarRemapper(mappingData);
        remapper.remapJar(inputJar, outputJar);

        getLogger().lifecycle("JAR remapping completed: {}", outputJar);
    }
}