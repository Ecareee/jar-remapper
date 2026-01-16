package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.JarRemapperExtension.JavaRemapperMode;
import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import net.md_5.specialsource.JarMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java 重映射
 * 使用 JavaParser 进行 AST 级别重映射
 */
@Log
@RequiredArgsConstructor
public class JavaRemapper {
    private final MappingData mappingData;
    private final JavaRemapperMode remapMode;

    /**
     * 重映射 Java 源码目录
     *
     * @param inputDir  输入目录
     * @param outputDir 输出目录
     * @return 处理的文件数
     * @throws IOException 如果 IO 操作失败
     */
    public int remapJavaSource(File inputDir, File outputDir) throws IOException {
        if (!inputDir.exists()) {
            throw new IOException("Input directory does not exist: " + inputDir);
        }

        log.info("Starting Java source remapping");
        log.info("Input: " + inputDir);
        log.info("Output: " + outputDir);
        log.info("Mode: " + remapMode);

        FileUtils.ensureDirectory(outputDir);

        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(inputDir, javaFiles);

        log.info("Found " + javaFiles.size() + " Java files");

        JavaParser parser = new JavaParser();
        int processedCount = 0;

        for (File javaFile : javaFiles) {
            processJavaFile(parser, javaFile, inputDir, outputDir);
            processedCount++;
        }

        log.info("Java source remapping completed: " + processedCount + " files");
        return processedCount;
    }

    private void processJavaFile(JavaParser parser, File inputFile, File inputDir, File outputDir) throws IOException {
        ParseResult<CompilationUnit> parseResult = parser.parse(inputFile);

        if (!parseResult.isSuccessful()) {
            // 解析失败，直接复制到对应位置
            Path relativePath = inputDir.toPath().relativize(inputFile.toPath());
            File outputFile = new File(outputDir, relativePath.toString());
            FileUtils.copyFile(inputFile, outputFile);
            log.warning("Parse failed, copying as-is: " + inputFile.getName());
            return;
        }

        CompilationUnit cu = parseResult.getResult().orElse(null);
        if (cu == null) {
            Path relativePath = inputDir.toPath().relativize(inputFile.toPath());
            File outputFile = new File(outputDir, relativePath.toString());
            FileUtils.copyFile(inputFile, outputFile);
            return;
        }

        JarMapping jarMapping = mappingData.getJarMapping();

        if (remapMode == JavaRemapperMode.TYPES_ONLY) {
            cu.accept(new TypesOnlyRemapper(jarMapping), null);
        } else {
            cu.accept(new FullRemapper(jarMapping, mappingData), null);
        }

        remapPackageDeclaration(cu, jarMapping);

        File outputFile = calculateOutputFile(cu, inputFile, inputDir, outputDir);

        FileUtils.ensureDirectory(outputFile.getParentFile());
        FileUtils.writeStringToFile(outputFile, cu.toString());
    }

    private void remapPackageDeclaration(CompilationUnit cu, JarMapping jarMapping) {
        cu.getPackageDeclaration().ifPresent(pkg -> {
            String pkgName = pkg.getNameAsString();
            String internalPkg = pkgName.replace('.', '/');

            for (Map.Entry<String, String> entry : jarMapping.packages.entrySet()) {
                String obfPkg = entry.getKey();
                String readablePkg = entry.getValue();
                if (internalPkg.equals(obfPkg) || internalPkg.startsWith(obfPkg + "/")) {
                    String newPkg = internalPkg.replaceFirst(obfPkg, readablePkg);
                    pkg.setName(newPkg.replace('/', '.'));
                    return;
                }
            }

            for (Map.Entry<String, String> entry : jarMapping.classes.entrySet()) {
                String obfClass = entry.getKey();
                String readableClass = entry.getValue();

                int obfLastSlash = obfClass.lastIndexOf('/');
                if (obfLastSlash > 0) {
                    String obfPkg = obfClass.substring(0, obfLastSlash);
                    if (internalPkg.equals(obfPkg)) {
                        int readableLastSlash = readableClass.lastIndexOf('/');
                        if (readableLastSlash > 0) {
                            String newPkgInternal = readableClass.substring(0, readableLastSlash);
                            pkg.setName(newPkgInternal.replace('/', '.'));
                            return;
                        }
                    }
                }
            }
        });
    }

    private File calculateOutputFile(CompilationUnit cu, File inputFile, File inputDir, File outputDir) {
        String typeName = cu.getPrimaryTypeName().orElse(null);
        if (typeName == null) {
            // 如果没有主类型，使用原文件名
            typeName = inputFile.getName().replace(".java", "");
        }

        String pkgPath = cu.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString().replace('.', File.separatorChar))
                .orElse("");

        if (pkgPath.isEmpty()) {
            return new File(outputDir, typeName + ".java");
        } else {
            return new File(outputDir, pkgPath + File.separator + typeName + ".java");
        }
    }

    private void collectJavaFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, files);
            } else if (child.getName().endsWith(".java")) {
                files.add(child);
            }
        }
    }

    /**
     * 仅类型重映射访问器
     * 只处理包名、类名、import 语句和类型引用
     */
    @RequiredArgsConstructor
    private static class TypesOnlyRemapper extends VoidVisitorAdapter<Void> {
        protected final JarMapping jarMapping;

        @Override
        public void visit(ImportDeclaration n, Void arg) {
            String importName = n.getNameAsString();
            String internalName = importName.replace('.', '/');

            String remapped = jarMapping.classes.get(internalName);
            if (remapped != null) {
                n.setName(remapped.replace('/', '.'));
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ClassOrInterfaceType n, Void arg) {
            String typeName = n.getNameAsString();
            String remapped = remapSimpleName(typeName);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            String className = n.getNameAsString();
            String remapped = remapSimpleName(className);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        protected String remapSimpleName(String simpleName) {
            for (Map.Entry<String, String> entry : jarMapping.classes.entrySet()) {
                String obfClass = entry.getKey();
                String readableClass = entry.getValue();

                int lastSlash = obfClass.lastIndexOf('/');
                String obfSimple = lastSlash >= 0 ? obfClass.substring(lastSlash + 1) : obfClass;

                if (obfSimple.equals(simpleName)) {
                    int newLastSlash = readableClass.lastIndexOf('/');
                    return newLastSlash >= 0 ? readableClass.substring(newLastSlash + 1) : readableClass;
                }
            }
            return null;
        }
    }

    /**
     * 完整重映射访问器
     * 包含类型、字段和方法调用点
     */
    private static class FullRemapper extends TypesOnlyRemapper {
        private final MappingData mappingData;

        public FullRemapper(JarMapping jarMapping, MappingData mappingData) {
            super(jarMapping);
            this.mappingData = mappingData;
        }

        @Override
        public void visit(FieldAccessExpr n, Void arg) {
            String fieldName = n.getNameAsString();
            String remapped = remapFieldName(fieldName);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            String methodName = n.getNameAsString();
            String remapped = remapMethodName(methodName);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            for (VariableDeclarator var : n.getVariables()) {
                String fieldName = var.getNameAsString();
                String remapped = remapFieldName(fieldName);
                if (remapped != null) {
                    var.setName(remapped);
                }
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            String methodName = n.getNameAsString();
            String remapped = remapMethodName(methodName);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        private String remapFieldName(String fieldName) {
            for (Map.Entry<String, String> entry : mappingData.getJarMapping().fields.entrySet()) {
                String key = entry.getKey();
                int slashIdx = key.lastIndexOf('/');
                if (slashIdx >= 0) {
                    String obfName = key.substring(slashIdx + 1);
                    if (obfName.equals(fieldName)) {
                        return entry.getValue();
                    }
                }
            }
            return null;
        }

        private String remapMethodName(String methodName) {
            for (Map.Entry<String, String> entry : mappingData.getJarMapping().methods.entrySet()) {
                String key = entry.getKey();
                int spaceIdx = key.indexOf(' ');
                if (spaceIdx > 0) {
                    String ownerAndName = key.substring(0, spaceIdx);
                    int slashIdx = ownerAndName.lastIndexOf('/');
                    if (slashIdx >= 0) {
                        String obfName = ownerAndName.substring(slashIdx + 1);
                        if (obfName.equals(methodName)) {
                            return entry.getValue();
                        }
                    }
                }
            }
            return null;
        }
    }
}