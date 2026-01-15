package com.ecaree.jarremapper;

import lombok.Getter;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

import java.io.File;

@Getter
public class JarRemapperExtension {
    private final Project project;

    /**
     * YAML 映射文件路径
     */
    private final RegularFileProperty mappingsYaml;

    /**
     * SpecialSource 格式映射文件路径（srg/csrg/tsrg/proguard，与 mappingsYaml 二选一）
     */
    private final RegularFileProperty mappingsSpecialSource;

    /**
     * 输入 JAR 文件
     */
    private final RegularFileProperty inputJar;

    /**
     * 输出 JAR 文件
     */
    private final RegularFileProperty outputJar;

    /**
     * 是否启用 JAR 重映射
     */
    private final Property<Boolean> remapJar;

    /**
     * 是否注入字节码注解
     */
    private final Property<Boolean> injectBytecodeAnnotations;

    /**
     * Smali 输入目录
     */
    private final DirectoryProperty smaliInputDir;

    /**
     * Smali 输出目录
     */
    private final DirectoryProperty smaliOutputDir;

    /**
     * 是否启用 Smali 重映射
     */
    private final Property<Boolean> remapSmali;

    /**
     * Smali 备份目录
     */
    private final DirectoryProperty smaliBackupDir;

    /**
     * 是否启用 Smali 迁移任务
     */
    private final Property<Boolean> enableSmaliMigrateTask;

    /**
     * Java 源码输入目录
     */
    private final DirectoryProperty javaInputDir;

    /**
     * Java 源码输出目录
     */
    private final DirectoryProperty javaOutputDir;

    /**
     * Java 重映射模式
     */
    private final Property<JavaRemapperMode> remapMode;

    /**
     * Java 备份目录
     */
    private final DirectoryProperty javaBackupDir;

    /**
     * 是否启用 Java 迁移任务
     */
    private final Property<Boolean> enableJavaMigrateTask;

    /**
     * 报告输出目录
     */
    private final DirectoryProperty reportsDir;

    public JarRemapperExtension(Project project) {
        this.project = project;
        var objects = project.getObjects();
        var layout = project.getLayout();

        this.mappingsYaml = objects.fileProperty();
        this.mappingsSpecialSource = objects.fileProperty();
        this.inputJar = objects.fileProperty();
        this.outputJar = objects.fileProperty();
        this.remapJar = objects.property(Boolean.class);
        this.injectBytecodeAnnotations = objects.property(Boolean.class);
        this.smaliInputDir = objects.directoryProperty();
        this.smaliOutputDir = objects.directoryProperty();
        this.remapSmali = objects.property(Boolean.class);
        this.smaliBackupDir = objects.directoryProperty();
        this.enableSmaliMigrateTask = objects.property(Boolean.class);
        this.javaInputDir = objects.directoryProperty();
        this.javaOutputDir = objects.directoryProperty();
        this.remapMode = objects.property(JavaRemapperMode.class);
        this.javaBackupDir = objects.directoryProperty();
        this.enableJavaMigrateTask = objects.property(Boolean.class);
        this.reportsDir = objects.directoryProperty();

        mappingsYaml.convention(layout.getProjectDirectory().file("mappings.yaml"));
        inputJar.convention(layout.getProjectDirectory().file("original/classes.jar"));
        outputJar.convention(layout.getProjectDirectory().file("original/classes-readable.jar"));
        remapJar.convention(true);
        injectBytecodeAnnotations.convention(true);

        smaliInputDir.convention(layout.getProjectDirectory().dir("src/main/smali/classes"));
        smaliOutputDir.convention(layout.getBuildDirectory().dir("generated/remappedSmali/classes"));
        smaliBackupDir.convention(layout.getProjectDirectory().dir("src/main/smali/classes-obf-backup"));
        remapSmali.convention(true);
        enableSmaliMigrateTask.convention(true);

        javaInputDir.convention(layout.getProjectDirectory().dir("src/main/java"));
        javaOutputDir.convention(layout.getBuildDirectory().dir("generated/remappedJava"));
        javaBackupDir.convention(layout.getProjectDirectory().dir("src/main/java-obf-backup"));
        remapMode.convention(JavaRemapperMode.TYPES_ONLY);
        enableJavaMigrateTask.convention(true);

        reportsDir.convention(layout.getBuildDirectory().dir("reports/jarRemapper"));
    }

    public File getEffectiveMappingFile() {
        if (mappingsYaml.isPresent() && mappingsYaml.get().getAsFile().exists()) {
            return mappingsYaml.get().getAsFile();
        }
        if (mappingsSpecialSource.isPresent() && mappingsSpecialSource.get().getAsFile().exists()) {
            return mappingsSpecialSource.get().getAsFile();
        }
        return null;
    }

    public enum JavaRemapperMode {
        /**
         * 仅重映射类型（包名、类名、import、类型引用）
         */
        TYPES_ONLY,

        /**
         * 完整重映射（包含字段和方法调用点）
         */
        FULL
    }
}