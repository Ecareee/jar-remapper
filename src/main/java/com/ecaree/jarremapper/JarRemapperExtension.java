package com.ecaree.jarremapper;

import lombok.Getter;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import java.io.File;
import java.util.Collections;
import java.util.List;

@Getter
public class JarRemapperExtension {
    private final Project project;

    /**
     * 映射文件路径
     * YAML/Tiny/Tiny2/SRG/CSRG/TSRG/TSRG2/ProGuard/Enigma/JAM/JOBF 等格式
     * 默认 mappings.yaml
     */
    private final RegularFileProperty mappingsFile;

    /**
     * 源命名空间
     * 用于 Tiny/TSRG2 等多命名空间格式
     * 默认使用映射文件的第一个命名空间
     */
    private final Property<String> sourceNamespace;

    /**
     * 目标命名空间
     * 用于 Tiny/TSRG2 等多命名空间格式
     * 默认使用映射文件的第二个命名空间
     */
    private final Property<String> targetNamespace;

    /**
     * 排除的包名列表，这些包下的类不会被重映射
     */
    private final ListProperty<String> excludedPackages;

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

        this.mappingsFile = objects.fileProperty();
        this.sourceNamespace = objects.property(String.class);
        this.targetNamespace = objects.property(String.class);
        this.excludedPackages = objects.listProperty(String.class);
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

        mappingsFile.convention(layout.getProjectDirectory().file("mappings.yaml"));
        excludedPackages.convention(Collections.emptyList());
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

    public void setMappingsFile(Object file) {
        this.mappingsFile.fileValue(project.file(file));
    }

    public void setSourceNamespace(String namespace) {
        this.sourceNamespace.set(namespace);
    }

    public void setTargetNamespace(String namespace) {
        this.targetNamespace.set(namespace);
    }

    public void setExcludedPackages(List<String> packages) {
        this.excludedPackages.set(packages);
    }

    public void setInputJar(Object file) {
        this.inputJar.fileValue(project.file(file));
    }

    public void setOutputJar(Object file) {
        this.outputJar.fileValue(project.file(file));
    }

    public void setRemapJar(boolean value) {
        this.remapJar.set(value);
    }

    public void setInjectBytecodeAnnotations(boolean value) {
        this.injectBytecodeAnnotations.set(value);
    }

    public void setInjectReadableInfo(boolean value) {
        this.injectReadableInfo.set(value);
    }

    public void setSmaliInputDir(Object dir) {
        this.smaliInputDir.fileValue(project.file(dir));
    }

    public void setSmaliOutputDir(Object dir) {
        this.smaliOutputDir.fileValue(project.file(dir));
    }

    public void setRemapSmali(boolean value) {
        this.remapSmali.set(value);
    }

    public void setSmaliBackupDir(Object dir) {
        this.smaliBackupDir.fileValue(project.file(dir));
    }

    public void setEnableSmaliMigrateTask(boolean value) {
        this.enableSmaliMigrateTask.set(value);
    }

    public void setJavaInputDir(Object dir) {
        this.javaInputDir.fileValue(project.file(dir));
    }

    public void setJavaOutputDir(Object dir) {
        this.javaOutputDir.fileValue(project.file(dir));
    }

    public void setJavaBackupDir(Object dir) {
        this.javaBackupDir.fileValue(project.file(dir));
    }

    public void setEnableJavaMigrateTask(boolean value) {
        this.enableJavaMigrateTask.set(value);
    }

    public void setJavaLibraryJars(Object... files) {
        this.javaLibraryJars.setFrom(files);
    }

    public void setReportsDir(Object dir) {
        this.reportsDir.fileValue(project.file(dir));
    }

    /**
     * 获取有效的映射文件
     */
    public File getEffectiveMappingFile() {
        if (mappingsFile.isPresent() && mappingsFile.get().getAsFile().exists()) {
            return mappingsFile.get().getAsFile();
        }
        return null;
    }
}