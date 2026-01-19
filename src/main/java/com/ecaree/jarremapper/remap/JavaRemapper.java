package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
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
 * 使用 JavaParser + SymbolSolver 进行类型感知重映射
 */
@Log
public class JavaRemapper {
    private final MappingData mappingData;
    private final List<File> libraryJars;

    public JavaRemapper(MappingData mappingData) {
        this(mappingData, new ArrayList<>());
    }

    public JavaRemapper(MappingData mappingData, List<File> libraryJars) {
        this.mappingData = mappingData;
        this.libraryJars = libraryJars != null ? libraryJars : new ArrayList<>();
    }

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

        FileUtils.ensureDirectory(outputDir);

        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(inputDir, javaFiles);

        log.info("Found " + javaFiles.size() + " Java files");

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();

        // 1. JDK 类型
        typeSolver.add(new ReflectionTypeSolver());

        // 2. 源码目录本身
        typeSolver.add(new JavaParserTypeSolver(inputDir));

        // 3. 用户配置的库 JAR
        for (File jarFile : libraryJars) {
            if (jarFile.exists()) {
                try {
                    typeSolver.add(new JarTypeSolver(jarFile));
                    log.info("Added library JAR: " + jarFile);
                } catch (IOException e) {
                    log.warning("Failed to add JAR to type solver: " + jarFile);
                }
            }
        }

        ParserConfiguration config = new ParserConfiguration();
        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(config);

        JarMapping jarMapping = mappingData.getJarMapping();
        int processedCount = 0;

        for (File javaFile : javaFiles) {
            processJavaFile(parser, jarMapping, javaFile, inputDir, outputDir);
            processedCount++;
        }

        log.info("Java source remapping completed: " + processedCount + " files");
        return processedCount;
    }

    private void processJavaFile(JavaParser parser, JarMapping jarMapping,
                                 File inputFile, File inputDir, File outputDir) throws IOException {
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

        // 保持原有代码风格
        LexicalPreservingPrinter.setup(cu);

        cu.accept(new RemappingVisitor(jarMapping), null);
        remapPackageDeclaration(cu, jarMapping);

        File outputFile = calculateOutputFile(cu, inputFile, inputDir, outputDir);

        FileUtils.ensureDirectory(outputFile.getParentFile());
        FileUtils.writeStringToFile(outputFile, LexicalPreservingPrinter.print(cu));
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
//        String typeName = cu.getPrimaryTypeName().orElse(null);
        // cu.getPrimaryTypeName() 只能获取原始文件名，应直接从 AST 的类型声明中读取已被重映射的类名
        String typeName = cu.getTypes().stream()
                .findFirst()
                .map(NodeWithSimpleName::getNameAsString)
                .orElse(null);

        String pkgPath = cu.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString().replace('.', File.separatorChar))
                .orElse(null);

        // 无效文件保持原始相对路径
        if (typeName == null || pkgPath == null) {
            Path relativePath = inputDir.toPath().relativize(inputFile.toPath());
            return new File(outputDir, relativePath.toString());
        }

        return new File(outputDir, pkgPath + File.separator + typeName + ".java");
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
     * 类型感知的重映射访问器
     */
    @RequiredArgsConstructor
    private static class RemappingVisitor extends VoidVisitorAdapter<Void> {
        private final JarMapping jarMapping;

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
            String remapped = remapSimpleName(n.getNameAsString());
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            String remapped = remapSimpleName(n.getNameAsString());
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldAccessExpr n, Void arg) {
            try {
                ResolvedType scopeType = n.getScope().calculateResolvedType();
                if (scopeType.isReferenceType()) {
                    String ownerClass = scopeType.asReferenceType().getQualifiedName().replace('.', '/');
                    tryRemapField(n, ownerClass, n.getNameAsString());
                }
            } catch (Exception ignored) {
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            try {
                ResolvedMethodDeclaration resolved = n.resolve();
                String ownerClass = resolved.declaringType().getQualifiedName().replace('.', '/');
                tryRemapMethod(n, ownerClass, n.getNameAsString(), buildDescriptor(resolved));
            } catch (Exception ignored) {
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            String ownerClass = getEnclosingClassName(n);
            if (ownerClass != null) {
                for (VariableDeclarator var : n.getVariables()) {
                    tryRemapField(var, ownerClass, var.getNameAsString());
                }
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            String ownerClass = getEnclosingClassName(n);
            if (ownerClass != null) {
                tryRemapMethod(n, ownerClass, n.getNameAsString(), buildDescriptor(n));
            }
            super.visit(n, arg);
        }

        private void tryRemapField(NodeWithSimpleName<?> node, String ownerClass, String fieldName) {
            String key = ownerClass + "/" + fieldName;
            String remapped = jarMapping.fields.get(key);
            if (remapped != null) {
                node.setName(remapped);
            }
        }

        private void tryRemapMethod(NodeWithSimpleName<?> node, String ownerClass, String methodName, String descriptor) {
            String key = ownerClass + "/" + methodName + " " + descriptor;
            String remapped = jarMapping.methods.get(key);
            if (remapped != null) {
                node.setName(remapped);
            }
        }

        private String remapSimpleName(String simpleName) {
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

        private String getEnclosingClassName(Node node) {
            Node current = node;
            while (current != null) {
                if (current instanceof ClassOrInterfaceDeclaration) {
                    try {
                        ResolvedReferenceTypeDeclaration resolved = ((ClassOrInterfaceDeclaration) current).resolve();
                        return resolved.getQualifiedName().replace('.', '/');
                    } catch (Exception e) {
                        return null;
                    }
                }
                current = current.getParentNode().orElse(null);
            }
            return null;
        }

        private String buildDescriptor(ResolvedMethodDeclaration method) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < method.getNumberOfParams(); i++) {
                sb.append(toDescriptor(method.getParam(i).getType()));
            }
            sb.append(")");
            sb.append(toDescriptor(method.getReturnType()));
            return sb.toString();
        }

        private String buildDescriptor(MethodDeclaration method) {
            StringBuilder sb = new StringBuilder("(");
            for (Parameter param : method.getParameters()) {
                try {
                    sb.append(toDescriptor(param.getType().resolve()));
                } catch (Exception e) {
                    sb.append("Ljava/lang/Object;");
                }
            }
            sb.append(")");
            try {
                sb.append(toDescriptor(method.getType().resolve()));
            } catch (Exception e) {
                sb.append("V");
            }
            return sb.toString();
        }

        private String toDescriptor(ResolvedType type) {
            if (type.isPrimitive()) {
                switch (type.asPrimitive().name()) {
                    case "BOOLEAN":
                        return "Z";
                    case "BYTE":
                        return "B";
                    case "CHAR":
                        return "C";
                    case "SHORT":
                        return "S";
                    case "INT":
                        return "I";
                    case "LONG":
                        return "J";
                    case "FLOAT":
                        return "F";
                    case "DOUBLE":
                        return "D";
                    default:
                        return "V";
                }
            } else if (type.isVoid()) {
                return "V";
            } else if (type.isArray()) {
                return "[" + toDescriptor(type.asArrayType().getComponentType());
            } else if (type.isReferenceType()) {
                String name = type.asReferenceType().getQualifiedName().replace('.', '/');
                return "L" + name + ";";
            }
            return "Ljava/lang/Object;";
        }
    }
}