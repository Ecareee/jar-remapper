package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.mapping.MappingChain;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.mapping.MappingLoader;
import com.ecaree.jarremapper.mapping.MappingResolver;
import com.ecaree.jarremapper.remap.JarRemapper;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 链式重映射任务
 * 支持多个映射文件按顺序应用，自动合并为单个映射
 */
public abstract class ChainRemapTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    @InputFile
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    /**
     * 映射文件列表
     * 按顺序应用
     */
    @InputFiles
    public abstract ConfigurableFileCollection getMappingFiles();

    /**
     * Maven 映射坐标列表
     * 按顺序应用
     */
    @Input
    @Optional
    public abstract ListProperty<String> getMappingCoordinates();

    /**
     * 每个映射的源命名空间
     * 与映射文件/坐标一一对应
     */
    @Input
    @Optional
    public abstract ListProperty<String> getSourceNamespaces();

    /**
     * 每个映射的目标命名空间
     * 与映射文件/坐标一一对应
     */
    @Input
    @Optional
    public abstract ListProperty<String> getTargetNamespaces();

    /**
     * 每个映射是否反向
     * 与映射文件/坐标一一对应
     */
    @Input
    @Optional
    public abstract ListProperty<Boolean> getReverseFlags();

    @InputFiles
    @Optional
    public abstract ConfigurableFileCollection getLibraryJars();

    @TaskAction
    public void remap() throws IOException {
        File inputJar = getInputJar().get().getAsFile();
        File outputJar = getOutputJar().get().getAsFile();

        if (!inputJar.exists()) {
            throw new RuntimeException("Input JAR does not exist: " + inputJar);
        }

        getLogger().lifecycle("Starting chain remap");
        getLogger().lifecycle("Input: {}", inputJar);
        getLogger().lifecycle("Output: {}", outputJar);

        List<MappingSource> sources = collectMappingSources();

        if (sources.isEmpty()) {
            throw new RuntimeException("No mapping files or coordinates specified");
        }

        getLogger().lifecycle("Loading {} mappings", sources.size());

        MappingChain chain = new MappingChain();
        MappingResolver resolver = new MappingResolver(getProject());

        for (int i = 0; i < sources.size(); i++) {
            MappingSource source = sources.get(i);
            getLogger().lifecycle("Loading mapping {}/{}: {}", i + 1, sources.size(), source.description);

            MappingData mapping = loadMapping(resolver, source);

            getLogger().lifecycle("  {} classes, {} fields, {} methods",
                    mapping.getClassCount(), mapping.getFieldCount(), mapping.getMethodCount());

            if (source.reverse) {
                getLogger().lifecycle("  Reversing mapping");
            }
            chain.add(mapping, source.reverse);
        }

        getLogger().lifecycle("Merging mapping chain...");
        MappingData merged = chain.merge();

        getLogger().lifecycle("Merged result: {} classes, {} fields, {} methods",
                merged.getClassCount(), merged.getFieldCount(), merged.getMethodCount());

        JarRemapper remapper = new JarRemapper(merged);
        File[] libs = getLibraryJars().getFiles().toArray(new File[0]);

        if (libs.length > 0) {
            getLogger().lifecycle("Using {} library JARs for inheritance resolution", libs.length);
            remapper.remapJarWithLibraries(inputJar, outputJar, libs);
        } else {
            remapper.remapJar(inputJar, outputJar);
        }

        getLogger().lifecycle("Chain remap completed: {}", outputJar);
    }

    private List<MappingSource> collectMappingSources() {
        List<MappingSource> sources = new ArrayList<>();

        List<String> sourceNs = getSourceNamespaces().getOrElse(Collections.emptyList());
        List<String> targetNs = getTargetNamespaces().getOrElse(Collections.emptyList());
        List<Boolean> reverseFlags = getReverseFlags().getOrElse(Collections.emptyList());

        int index = 0;

        for (File file : getMappingFiles().getFiles()) {
            sources.add(createMappingSource(file, null, file.getName(), sourceNs, targetNs, reverseFlags, index));
            index++;
        }

        for (String coord : getMappingCoordinates().getOrElse(Collections.emptyList())) {
            sources.add(createMappingSource(null, coord, coord, sourceNs, targetNs, reverseFlags, index));
            index++;
        }

        return sources;
    }

    private MappingSource createMappingSource(File file, String coordinates, String description,
                                              List<String> sourceNs, List<String> targetNs,
                                              List<Boolean> reverseFlags, int index) {
        MappingSource source = new MappingSource();
        source.file = file;
        source.coordinates = coordinates;
        source.description = description;
        source.sourceNamespace = index < sourceNs.size() ? sourceNs.get(index) : null;
        source.targetNamespace = index < targetNs.size() ? targetNs.get(index) : null;
        source.reverse = index < reverseFlags.size() && reverseFlags.get(index);
        return source;
    }

    private MappingData loadMapping(MappingResolver resolver, MappingSource source) throws IOException {
        File file;
        if (source.file != null) {
            file = source.file;
        } else if (source.coordinates != null) {
            file = resolver.resolve(source.coordinates);
        } else {
            throw new RuntimeException("Invalid mapping source: no file or coordinates");
        }

        return MappingLoader.load(file, source.sourceNamespace, source.targetNamespace);
    }

    private static class MappingSource {
        File file;
        String coordinates;
        String description;
        String sourceNamespace;
        String targetNamespace;
        boolean reverse;
    }
}