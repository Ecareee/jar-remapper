package com.ecaree.jarremapper.task;

import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

import java.io.File;

public class MigrateSmaliTask extends AbstractMigrateTask {
    @InputDirectory
    @Optional
    @Override
    protected File getSourceDir() {
        File dir = getExtension().getSmaliOutputDir().get().getAsFile();
        return dir.exists() ? dir : null;
    }

    @Internal
    @Override
    protected File getTargetDir() {
        return getExtension().getSmaliInputDir().get().getAsFile();
    }

    @Internal
    @Override
    protected File getBackupDir() {
        return getExtension().getSmaliBackupDir().get().getAsFile();
    }

    @Internal
    @Override
    protected File getReportsDir() {
        return getExtension().getReportsDir().get().getAsFile();
    }

    @Override
    protected String getReportName() {
        return "migrateSmali";
    }

    @Override
    protected String getFileExtension() {
        return ".smali";
    }

    @Override
    protected String getFileTypeDescription() {
        return "smali";
    }
}