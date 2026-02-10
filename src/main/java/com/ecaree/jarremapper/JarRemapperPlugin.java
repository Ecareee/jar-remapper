package com.ecaree.jarremapper;

import com.ecaree.jarremapper.task.ChainRemapTask;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JarRemapperPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "jarRemapper";
    public static final String TASK_GROUP = "jarRemapper";
    private static final String TASK_PREFIX = "jr";
    private final Map<String, String> resolvedNames = new HashMap<>();

    @Override
    public void apply(Project project) {
        JarRemapperExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, JarRemapperExtension.class, project);

        resolveTaskNames(project);

        registerRemapJarTask(project, extension);
        registerInjectJarAnnotationsTask(project, extension);
        registerRemapSmaliTask(project, extension);
        registerMigrateSmaliTask(project, extension);
        registerRemapJavaTask(project, extension);
        registerMigrateJavaTask(project, extension);
        registerChainRemapTask(project, extension);

        configureTaskDependencies(project, extension);
    }

    private void resolveTaskNames(Project project) {
        String[] names = {
                "remapJar", "injectJarAnnotations", "remapSmali",
                "migrateSmali", "remapJava", "migrateJava", "chainRemapJar"
        };
        for (String name : names) {
            if (project.getTasks().getNames().contains(name)) {
                String prefixed = TASK_PREFIX + name.substring(0, 1).toUpperCase() + name.substring(1);
                project.getLogger().warn("JarRemapper: Task '{}' already exists, using '{}'", name, prefixed);
                resolvedNames.put(name, prefixed);
            } else {
                resolvedNames.put(name, name);
            }
        }
    }

    private String resolveTaskName(String original) {
        return resolvedNames.getOrDefault(original, original);
    }

    private void registerRemapJarTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("remapJar"), RemapJarTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated JAR to readable naming using SpecialSource");
            t.setExtension(extension);
            t.onlyIf(spec -> extension.getRemapJar().get());
        });
    }

    private void registerInjectJarAnnotationsTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("injectJarAnnotations"), InjectJarAnnotationsTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Inject mapping annotations into remapped JAR");
            t.setExtension(extension);
            t.dependsOn(resolveTaskName("remapJar"));
            t.onlyIf(spec -> extension.getInjectBytecodeAnnotations().get());
        });
    }

    private void registerRemapSmaliTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("remapSmali"), RemapSmaliTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated smali source to readable naming");
            t.setExtension(extension);
            t.onlyIf(spec -> extension.getRemapSmali().get());
        });
    }

    private void registerMigrateSmaliTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("migrateSmali"), MigrateSmaliTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Migrate remapped smali back to project directory");
            t.setExtension(extension);
            t.dependsOn(resolveTaskName("remapSmali"));
            t.onlyIf(spec -> extension.getEnableSmaliMigrateTask().get());
        });
    }

    private void registerRemapJavaTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("remapJava"), RemapJavaTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap obfuscated Java source to readable naming");
            t.setExtension(extension);
        });
    }

    private void registerMigrateJavaTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("migrateJava"), MigrateJavaTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Migrate remapped Java source back to project directory");
            t.setExtension(extension);
            t.dependsOn(resolveTaskName("remapJava"));
            t.onlyIf(spec -> extension.getEnableJavaMigrateTask().get());
        });
    }

    private void registerChainRemapTask(Project project, JarRemapperExtension extension) {
        project.getTasks().register(resolveTaskName("chainRemapJar"), ChainRemapTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Remap JAR using a chain of mappings");
            t.setExtension(extension);
        });
    }

    private void configureTaskDependencies(Project project, JarRemapperExtension extension) {
        project.afterEvaluate(p -> {
            if (!extension.getRemapJar().get()) return;

            List<String> remapTasks = new ArrayList<>();
            remapTasks.add(resolveTaskName("remapJar"));
            if (extension.getInjectBytecodeAnnotations().get()) {
                remapTasks.add(resolveTaskName("injectJarAnnotations"));
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