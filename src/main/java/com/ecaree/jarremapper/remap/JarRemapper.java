package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JAR 重映射
 * 封装 SpecialSource 的核心功能
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class JarRemapper {
    private final MappingData mappingData;

    public void remapJar(File inputJar, File outputJar) throws IOException {
        remapJarWithLibraries(inputJar, outputJar);
    }

    /**
     * 使用多个输入 JAR 进行重映射（用于处理继承依赖）
     */
    public void remapJarWithLibraries(File inputJar, File outputJar, File... libraryJars) throws IOException {
        log.info("Starting JAR remapping");
        log.info("Input: {}", inputJar);
        log.info("Output: {}", outputJar);
        if (libraryJars.length > 0) {
            log.info("Libraries: {}", libraryJars.length);
        }

        List<Jar> openedJars = new ArrayList<>();

        try {
            Jar jar = Jar.init(inputJar);
            openedJars.add(jar);

            JointProvider inheritanceProviders = new JointProvider();
            inheritanceProviders.add(new JarProvider(jar));

            for (File libJar : libraryJars) {
                if (libJar.exists()) {
                    try {
                        Jar lib = Jar.init(libJar);
                        openedJars.add(lib);
                        inheritanceProviders.add(new JarProvider(lib));
                    } catch (IOException e) {
                        log.warn("Failed to load library JAR: {}", libJar);
                    }
                }
            }

            inheritanceProviders.add(new ClassLoaderProvider(ClassLoader.getSystemClassLoader()));

            JarMapping jarMapping = mappingData.getJarMapping();
            jarMapping.setFallbackInheritanceProvider(inheritanceProviders);

            net.md_5.specialsource.JarRemapper remapper = new net.md_5.specialsource.JarRemapper(null, jarMapping, null);

            FileUtils.ensureDirectory(outputJar.getParentFile());

            remapper.remapJar(jar, outputJar);

            log.info("JAR remapping completed: {}", outputJar);
        } finally {
            for (Jar jar : openedJars) {
                try {
                    jar.close();
                } catch (Exception e) {
                    log.warn("Failed to close JAR: {}", e.getMessage());
                }
            }
        }
    }
}