package com.ecaree.jarremapper.util;

import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 迁移报告生成器
 */
@RequiredArgsConstructor
public class ReportGenerator {
    private final File reportsDir;
    private final String taskName;

    private final List<String> summary = new ArrayList<>();
    private final List<String> mappedItems = new ArrayList<>();
    private final List<String> unmappedItems = new ArrayList<>();
    private final List<String> conflicts = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public void addSummary(String message) {
        summary.add(message);
    }

    public void addMapped(String item) {
        mappedItems.add(item);
    }

    public void addUnmapped(String item) {
        unmappedItems.add(item);
    }

    public void addConflict(String conflict) {
        conflicts.add(conflict);
    }

    public void addError(String error) {
        errors.add(error);
    }

    public File generate() throws IOException {
        FileUtils.ensureDirectory(reportsDir);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File reportFile = new File(reportsDir, taskName + "_" + timestamp + ".txt");

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile))) {
            writer.println("Migration Report: " + taskName);
            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println();

            writer.println("[Summary]");
            for (String s : summary) {
                writer.println("  " + s);
            }
            writer.println();

            writer.println("[Statistics]");
            writer.println("Mapped items:   " + mappedItems.size());
            writer.println("Unmapped items: " + unmappedItems.size());
            writer.println("Conflicts:      " + conflicts.size());
            writer.println("Errors:         " + errors.size());
            writer.println();

            if (!mappedItems.isEmpty()) {
                writer.println("[Mapped Items]");
                for (String item : mappedItems) {
                    writer.println("+ " + item);
                }
                writer.println();
            }

            if (!unmappedItems.isEmpty()) {
                writer.println("[Unmapped Items]");
                for (String item : unmappedItems) {
                    writer.println("? " + item);
                }
                writer.println();
            }

            if (!conflicts.isEmpty()) {
                writer.println("[Conflicts]");
                for (String conflict : conflicts) {
                    writer.println("! " + conflict);
                }
                writer.println();
            }

            if (!errors.isEmpty()) {
                writer.println("[Errors]");
                for (String error : errors) {
                    writer.println("× " + error);
                }
                writer.println();
            }

            writer.println("End of Report");
        }

        return reportFile;
    }

    public int getMappedCount() {
        return mappedItems.size();
    }

    public int getUnmappedCount() {
        return unmappedItems.size();
    }

    public int getConflictCount() {
        return conflicts.size();
    }

    public int getErrorCount() {
        return errors.size();
    }
}