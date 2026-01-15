package com.ecaree.jarremapper.util;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

public class FileUtils {
    /**
     * 递归复制目录
     */
    public static void copyDirectory(File source, File target) throws IOException {
        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();

        Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
            @Nonnull
            @Override
            public FileVisitResult preVisitDirectory(@Nonnull Path dir, @Nonnull BasicFileAttributes attrs) throws IOException {
                Path targetDir = targetPath.resolve(sourcePath.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Nonnull
            @Override
            public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) throws IOException {
                Path targetFile = targetPath.resolve(sourcePath.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 递归删除目录
     */
    public static void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) return;

        try (var paths = Files.walk(directory.toPath())) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    public static void ensureDirectory(File directory) throws IOException {
        if (directory != null && !directory.exists()) {
            Files.createDirectories(directory.toPath());
        }
    }

    public static String readFileToString(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    public static void writeStringToFile(File file, String content) throws IOException {
        ensureDirectory(file.getParentFile());
        Files.writeString(file.toPath(), content);
    }

    public static void copyFile(File source, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static String getExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : "";
    }
}