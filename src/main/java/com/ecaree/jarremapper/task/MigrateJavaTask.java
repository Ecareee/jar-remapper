package com.ecaree.jarremapper.task;

import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

import java.io.File;

public class MigrateJavaTask extends AbstractMigrateTask {
    @InputDirectory
    @Optional
    @Override
    protected File getSourceDir() {
        File dir = getExtension().getJavaOutputDir().get().getAsFile();
        return dir.exists() ? dir : null;
    }

    @Internal
    @Override
    protected File getTargetDir() {
        return getExtension().getJavaInputDir().get().getAsFile();
    }

    @Internal
    @Override
    protected File getBackupDir() {
        return getExtension().getJavaBackupDir().get().getAsFile();
    }

    @Internal
    @Override
    protected File getReportsDir() {
        return getExtension().getReportsDir().get().getAsFile();
    }

    @Override
    protected String getReportName() {
        return "migrateJava";
    }

    @Override
    protected String getFileExtension() {
        return ".java";
    }

    @Override
    protected String getFileTypeDescription() {
        return "Java";
    }
}