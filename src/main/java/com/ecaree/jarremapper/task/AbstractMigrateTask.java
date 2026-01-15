package com.ecaree.jarremapper.task;

import com.ecaree.jarremapper.JarRemapperExtension;
import com.ecaree.jarremapper.util.FileUtils;
import com.ecaree.jarremapper.util.ReportGenerator;
import lombok.Getter;
import lombok.Setter;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public abstract class AbstractMigrateTask extends DefaultTask {
    @Internal
    @Getter
    @Setter
    private JarRemapperExtension extension;

    protected abstract File getSourceDir();

    protected abstract File getTargetDir();

    protected abstract File getBackupDir();

    protected abstract File getReportsDir();

    protected abstract String getReportName();

    protected abstract String getFileExtension();

    protected abstract String getFileTypeDescription();

    @TaskAction
    public void migrate() throws IOException {
        File sourceDir = getSourceDir();
        File targetDir = getTargetDir();
        File backupDir = getBackupDir();

        if (sourceDir == null || !sourceDir.exists()) {
            throw new RuntimeException("Source directory does not exist, please run remap task first");
        }

        ReportGenerator report = new ReportGenerator(getReportsDir(), getReportName());

        getLogger().lifecycle("Starting {} migration to readable namespace", getFileTypeDescription());
        getLogger().lifecycle("Source: {}", sourceDir);
        getLogger().lifecycle("Target: {}", targetDir);

        if (targetDir.exists()) {
            getLogger().lifecycle("Backing up to: {}", backupDir);
            FileUtils.deleteDirectory(backupDir);
            FileUtils.copyDirectory(targetDir, backupDir);
            report.addSummary("Backed up original directory to: " + backupDir);
        }

        FileUtils.deleteDirectory(targetDir);
        FileUtils.copyDirectory(sourceDir, targetDir);

        String ext = getFileExtension();
        int[] fileCount = {0};
        Files.walkFileTree(targetDir.toPath(), new SimpleFileVisitor<>() {
            @Nonnull
            @Override
            public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) {
                if (file.toString().endsWith(ext)) {
                    fileCount[0]++;
                    report.addMapped(file.toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        report.addSummary("Migration completed, " + fileCount[0] + " " + getFileTypeDescription() + " files");

        File reportFile = report.generate();

        getLogger().lifecycle("Migration completed: {} files", fileCount[0]);
        getLogger().lifecycle("Report generated: {}", reportFile);
    }
}