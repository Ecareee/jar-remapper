package com.ecaree.jarremapper;

import com.ecaree.jarremapper.task.InjectJarAnnotationsTask;
import com.ecaree.jarremapper.task.MigrateJavaTask;
import com.ecaree.jarremapper.task.MigrateSmaliTask;
import com.ecaree.jarremapper.task.RemapJarTask;
import com.ecaree.jarremapper.task.RemapJavaTask;
import com.ecaree.jarremapper.task.RemapSmaliTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

import java.util.ArrayList;
import java.util.List;

public class JarRemapperPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "jarRemapper";
    public static final String TASK_GROUP = "jarRemapper";

    @Override
    public void apply(Project project) {
        JarRemapperExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, JarRemapperExtension.class, project);

        registerRemapJarTask(project, extension);
        registerInjectJarAnnotationsTask(project, extension);
        registerRemapSmaliTask(project, extension);
        registerMigrateSmaliTask(project, extension);
        registerRemapJavaTask(project, extension);
        registerMigrateJavaTask(project, extension);

        configureTaskDependencies(project, extension);
    }

    private void registerRemapJarTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("remapJar", RemapJarTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated JAR to readable naming using SpecialSource");
            t.setExtension(extension);
            t.onlyIf(spec -> extension.getRemapJar().get());
        });
    }

    private void registerInjectJarAnnotationsTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("injectJarAnnotations", InjectJarAnnotationsTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Inject mapping annotations into remapped JAR");
            t.setExtension(extension);
            t.dependsOn("remapJar");
            t.onlyIf(spec -> extension.getInjectBytecodeAnnotations().get());
        });
    }

    private void registerRemapSmaliTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("remapSmali", RemapSmaliTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated smali source to readable naming");
            t.setExtension(extension);
            t.onlyIf(spec -> extension.getRemapSmali().get());
        });
    }

    private void registerMigrateSmaliTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("migrateSmaliToReadable", MigrateSmaliTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Migrate remapped smali back to project directory");
            t.setExtension(extension);
            t.dependsOn("remapSmali");
            t.onlyIf(spec -> extension.getEnableSmaliMigrateTask().get());
        });
    }

    private void registerRemapJavaTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("remapJava", RemapJavaTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated Java source to readable naming");
            t.setExtension(extension);
        });
    }

    private void registerMigrateJavaTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register("migrateJavaToReadable", MigrateJavaTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Migrate remapped Java source back to project directory");
            t.setExtension(extension);
            t.dependsOn("remapJava");
            t.onlyIf(spec -> extension.getEnableJavaMigrateTask().get());
        });
    }

    private void configureTaskDependencies(Project project, JarRemapperExtension extension) {
        project.afterEvaluate(p -> {
            if (!extension.getRemapJar().get()) return;

            List<String> remapTasks = new ArrayList<>();
            remapTasks.add("remapJar");
            if (extension.getInjectBytecodeAnnotations().get()) {
                remapTasks.add("injectJarAnnotations");
            }

            // Android 项目挂载到 preBuild
            Task preBuildTask = p.getTasks().findByName("preBuild");
            if (preBuildTask != null) {
                preBuildTask.dependsOn(remapTasks);
                p.getLogger().lifecycle("JarRemapper: Hooked to preBuild task");
                return;
            }

            // Java 项目挂载到 compileJava
            Task compileJavaTask = p.getTasks().findByName("compileJava");
            if (compileJavaTask != null) {
                compileJavaTask.dependsOn(remapTasks);
                p.getLogger().lifecycle("JarRemapper: Hooked to compileJava task");
            }
        });
    }
}