package com.ecaree.jarremapper;

import lombok.Getter;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import java.io.File;

@Getter
public class JarRemapperExtension {
    private final Project project;

    /**
     * YAML 映射文件路径
     * 与 mappingsSpecialSource 二选一，若两者都存在优先使用 YAML
     * 默认 mappings.yaml
     */
    private final RegularFileProperty mappingsYaml;

    /**
     * SpecialSource 格式映射文件路径
     * srg/csrg/tsrg/proguard，与 mappingsYaml 二选一，若两者都存在优先使用 YAML
     * 可选
     */
    private final RegularFileProperty mappingsSpecialSource;

    /**
     * 输入 JAR 文件
     * 默认 original/classes.jar
     */
    private final RegularFileProperty inputJar;

    /**
     * 输出 JAR 文件
     * 默认 original/classes-readable.jar
     */
    private final RegularFileProperty outputJar;

    /**
     * 是否启用 JAR 重映射
     * 默认 true
     */
    private final Property<Boolean> remapJar;

    /**
     * 是否注入字节码注解
     * 默认 true
     */
    private final Property<Boolean> injectBytecodeAnnotations;

    /**
     * 是否在注解中包含 readable 信息
     * 默认 false，因为这些信息可从代码上下文获取
     */
    private final Property<Boolean> injectReadableInfo;

    /**
     * Smali 输入目录
     * 默认 src/main/smali/classes
     */
    private final DirectoryProperty smaliInputDir;

    /**
     * Smali 输出目录
     * 默认 build/generated/remappedSmali/classes
     */
    private final DirectoryProperty smaliOutputDir;

    /**
     * 是否启用 Smali 重映射
     * 默认 true
     */
    private final Property<Boolean> remapSmali;

    /**
     * Smali 备份目录
     * 默认 src/main/smali/classes-obf-backup
     */
    private final DirectoryProperty smaliBackupDir;

    /**
     * 是否启用 Smali 迁移任务
     * 默认 true
     */
    private final Property<Boolean> enableSmaliMigrateTask;

    /**
     * Java 源码输入目录
     * 默认 src/main/java
     */
    private final DirectoryProperty javaInputDir;

    /**
     * Java 源码输出目录
     * 默认 build/generated/remappedJava
     */
    private final DirectoryProperty javaOutputDir;

    /**
     * Java 备份目录
     * 默认 src/main/java-obf-backup
     */
    private final DirectoryProperty javaBackupDir;

    /**
     * 是否启用 Java 迁移任务
     * 默认 true
     */
    private final Property<Boolean> enableJavaMigrateTask;

    /**
     * Java 重映射额外需要的库 JAR，用于类型解析
     * outputJar 会自动添加，这里配置 Android SDK 等额外依赖
     */
    private final ConfigurableFileCollection javaLibraryJars;

    /**
     * 报告输出目录
     * 默认 build/reports/jarRemapper
     */
    private final DirectoryProperty reportsDir;

    public JarRemapperExtension(Project project) {
        this.project = project;
        ObjectFactory objects = project.getObjects();
        ProjectLayout layout = project.getLayout();

        this.mappingsYaml = objects.fileProperty();
        this.mappingsSpecialSource = objects.fileProperty();
        this.inputJar = objects.fileProperty();
        this.outputJar = objects.fileProperty();
        this.remapJar = objects.property(Boolean.class);
        this.injectBytecodeAnnotations = objects.property(Boolean.class);
        this.injectReadableInfo = objects.property(Boolean.class);
        this.smaliInputDir = objects.directoryProperty();
        this.smaliOutputDir = objects.directoryProperty();
        this.remapSmali = objects.property(Boolean.class);
        this.smaliBackupDir = objects.directoryProperty();
        this.enableSmaliMigrateTask = objects.property(Boolean.class);
        this.javaInputDir = objects.directoryProperty();
        this.javaOutputDir = objects.directoryProperty();
        this.javaBackupDir = objects.directoryProperty();
        this.enableJavaMigrateTask = objects.property(Boolean.class);
        this.javaLibraryJars = objects.fileCollection();
        this.reportsDir = objects.directoryProperty();

        mappingsYaml.convention(layout.getProjectDirectory().file("mappings.yaml"));
        inputJar.convention(layout.getProjectDirectory().file("original/classes.jar"));
        outputJar.convention(layout.getProjectDirectory().file("original/classes-readable.jar"));
        remapJar.convention(true);
        injectBytecodeAnnotations.convention(true);
        injectReadableInfo.convention(false);

        smaliInputDir.convention(layout.getProjectDirectory().dir("src/main/smali/classes"));
        smaliOutputDir.convention(layout.getBuildDirectory().dir("generated/remappedSmali/classes"));
        smaliBackupDir.convention(layout.getProjectDirectory().dir("src/main/smali/classes-obf-backup"));
        remapSmali.convention(true);
        enableSmaliMigrateTask.convention(true);

        javaInputDir.convention(layout.getProjectDirectory().dir("src/main/java"));
        javaOutputDir.convention(layout.getBuildDirectory().dir("generated/remappedJava"));
        javaBackupDir.convention(layout.getProjectDirectory().dir("src/main/java-obf-backup"));
        enableJavaMigrateTask.convention(true);

        reportsDir.convention(layout.getBuildDirectory().dir("reports/jarRemapper"));
    }

    /**
     * 获取有效的映射文件
     * 优先使用 YAML
     */
    public File getEffectiveMappingFile() {
        if (mappingsYaml.isPresent() && mappingsYaml.get().getAsFile().exists()) {
            return mappingsYaml.get().getAsFile();
        }
        if (mappingsSpecialSource.isPresent() && mappingsSpecialSource.get().getAsFile().exists()) {
            return mappingsSpecialSource.get().getAsFile();
        }
        return null;
    }
}