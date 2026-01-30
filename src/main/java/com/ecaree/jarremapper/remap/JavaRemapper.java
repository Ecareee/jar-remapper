package com.ecaree.jarremapper.remap;

import com.ecaree.jarremapper.mapping.MappingData;
import com.ecaree.jarremapper.util.FileUtils;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
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
import lombok.extern.slf4j.Slf4j;
import net.md_5.specialsource.JarMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 重映射
 * 使用 JavaParser + SymbolSolver 进行类型感知重映射
 */
@Slf4j
public class JavaRemapper {
    private final MappingData mappingData;
    private final List<File> libraryJars;
    private final Map<String, List<String>> simpleNameToObfClasses;

    public JavaRemapper(MappingData mappingData) {
        this(mappingData, new ArrayList<>());
    }

    public JavaRemapper(MappingData mappingData, List<File> libraryJars) {
        this.mappingData = mappingData;
        this.libraryJars = libraryJars != null ? libraryJars : new ArrayList<>();
        this.simpleNameToObfClasses = buildSimpleNameIndex();
    }

    /**
     * 使用 sout 打印当前 logger，不使用 log.info 打印
     * 如果输出 org.gradle.internal.logging.slf4j.OutputEventListenerBackedLogger 说明 slf4j 绑定到了 Gradle 内部的 logger，日志可能不会输出
     * 如果输出 org.apache.logging.slf4j.Log4jLogger 说明 slf4j 绑定到了 log4j-slf4j2-impl，日志可以正常输出
     */
    private static void printCurrentLogger() {
        System.out.println("Current logger: " + log.getClass().getName());
    }

    private Map<String, List<String>> buildSimpleNameIndex() {
        Map<String, List<String>> index = new HashMap<>();
        for (String obfClass : mappingData.getJarMapping().classes.keySet()) {
            int lastSlash = obfClass.lastIndexOf('/');
            String simpleName = lastSlash >= 0 ? obfClass.substring(lastSlash + 1) : obfClass;
            index.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(obfClass);
        }
        return index;
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
        printCurrentLogger();

        if (!inputDir.exists()) {
            throw new IOException("Input directory does not exist: " + inputDir);
        }

        log.info("Starting Java source remapping");
        log.info("Input: {}", inputDir);
        log.info("Output: {}", outputDir);

        FileUtils.deleteDirectory(outputDir);
        FileUtils.ensureDirectory(outputDir);

        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(inputDir, javaFiles);

        log.info("Found {} Java files", javaFiles.size());

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
                    log.info("Added library JAR: {}", jarFile);
                } catch (IOException e) {
                    log.warn("Failed to add JAR to type solver: {}", jarFile);
                }
            }
        }

        ParserConfiguration config = new ParserConfiguration();
        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(config);

        int processedCount = 0;

        for (File javaFile : javaFiles) {
            processJavaFile(parser, javaFile, inputDir, outputDir);
            processedCount++;
        }

        log.info("Java source remapping completed: {} files", processedCount);
        return processedCount;
    }

    private void processJavaFile(JavaParser parser, File inputFile, File inputDir, File outputDir) throws IOException {
        ParseResult<CompilationUnit> parseResult = parser.parse(inputFile);

        if (!parseResult.isSuccessful()) {
            // 解析失败，直接复制到对应位置
            Path relativePath = inputDir.toPath().relativize(inputFile.toPath());
            File outputFile = new File(outputDir, relativePath.toString());
            FileUtils.copyFile(inputFile, outputFile);
            log.warn("Parse failed, copying as-is: {}", inputFile.getName());
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

        RemappingVisitor visitor = new RemappingVisitor(mappingData, simpleNameToObfClasses);
        visitor.initImports(cu);

        cu.accept(visitor, null);
        remapPackageDeclaration(cu);

        File outputFile = calculateOutputFile(cu, inputFile, inputDir, outputDir);

        FileUtils.ensureDirectory(outputFile.getParentFile());
        FileUtils.writeStringToFile(outputFile, LexicalPreservingPrinter.print(cu));
    }

    private void remapPackageDeclaration(CompilationUnit cu) {
        cu.getPackageDeclaration().ifPresent(pkg -> {
            String pkgName = pkg.getNameAsString();
            String internalPkg = pkgName.replace('.', '/');

            JarMapping jarMapping = mappingData.getJarMapping();

            for (Map.Entry<String, String> entry : jarMapping.packages.entrySet()) {
                String obfPkg = entry.getKey();
                String readablePkg = entry.getValue();
                if (internalPkg.equals(obfPkg) || internalPkg.startsWith(obfPkg)) {
                    String newPkg = internalPkg.replaceFirst(
                            obfPkg.endsWith("/") ? obfPkg.substring(0, obfPkg.length() - 1) : obfPkg,
                            readablePkg.endsWith("/") ? readablePkg.substring(0, readablePkg.length() - 1) : readablePkg
                    );
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
        private final MappingData mappingData;
        private final Map<String, List<String>> simpleNameToObfClasses;
        private final Map<String, String> simpleNameCache = new HashMap<>();
        private final Map<String, String> importedClasses = new HashMap<>();
        private String currentPackage;

        private JarMapping getJarMapping() {
            return mappingData.getJarMapping();
        }

        public void initImports(CompilationUnit cu) {
            importedClasses.clear();
            simpleNameCache.clear();

            currentPackage = cu.getPackageDeclaration()
                    .map(pkg -> pkg.getNameAsString().replace('.', '/'))
                    .orElse("");

            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isAsterisk() && !imp.isStatic()) {
                    String fullName = imp.getNameAsString().replace('.', '/');
                    int lastSlash = fullName.lastIndexOf('/');
                    String simpleName = lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
                    importedClasses.put(simpleName, fullName);
                }
            }
        }


        @Override
        public void visit(ImportDeclaration n, Void arg) {
            String importName = n.getNameAsString();
            String internalName = importName.replace('.', '/');

            String remapped = mappingData.mapClass(internalName);
            if (!remapped.equals(internalName)) {
                n.setName(remapped.replace('/', '.'));
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ClassOrInterfaceType n, Void arg) {
            String simpleName = n.getNameAsString();
            String remapped = remapSimpleName(simpleName, n);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            log.debug("Visiting class: {}", n.getNameAsString());
            /*
             * 字段/方法重映射时，getEnclosingClassName() 通过 SymbolResolver 获取原始混淆类名来匹配 mapping
             * 如果先修改类名，SymbolResolver 返回的是修改后的类名，会导致 mapping 查找失败
             * 所以必须先遍历子节点，最后再修改类名
             */
            super.visit(n, arg);

            String simpleName = n.getNameAsString();
            String remapped = remapSimpleName(simpleName, n);
            log.debug("After super.visit, class name: {} -> {}", simpleName, remapped);
            if (remapped != null) {
                n.setName(remapped);
            }
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            for (VariableDeclarator var : n.getVariables()) {
                Type type = var.getType();

                log.debug("Field type: {}", type);
                log.debug("isPhantom: {}", type.isPhantom());
                log.debug("type range: {}", type.getRange().orElse(null));
                log.debug("var range: {}", var.getRange().orElse(null));

                Type newType = remapAndCloneType(type);
                if (newType != null) {
                    /*
                     * FieldDeclaration 中的类型是 phantom 节点
                     * LexicalPreservingPrinter 将其 tokens 存储为 TokenTextElement，而非 ChildTextElement
                     * 直接修改节点不会更新 TokenTextElement，只有通过 setType() 触发 LexicalPreservingPrinter Observer 才能更新 NodeText
                     */
                    var.setType(newType);
                }
            }

            String ownerClass = getEnclosingClassName(n);
            if (ownerClass != null) {
                for (VariableDeclarator var : n.getVariables()) {
                    tryRemapField(var, ownerClass, var.getNameAsString());
                }
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(VariableDeclarationExpr n, Void arg) {
            // 局部变量与 FieldDeclaration 有相同的 phantom 类型问题，需要替换整个类型节点
            for (VariableDeclarator var : n.getVariables()) {
                Type newType = remapAndCloneType(var.getType());
                if (newType != null) {
                    var.setType(newType);
                }
            }
            super.visit(n, arg);
        }

        /**
         * 克隆并重映射类型
         * 返回 null 表示不需要修改
         */
        private Type remapAndCloneType(Type type) {
            if (type instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType classType = (ClassOrInterfaceType) type;
                String newName = remapSimpleName(classType.getNameAsString(), classType);
                boolean needsRemap = newName != null;

                // 检查泛型参数是否需要重映射
                if (classType.getTypeArguments().isPresent()) {
                    for (Type arg : classType.getTypeArguments().get()) {
                        if (needsRemapType(arg)) {
                            needsRemap = true;
                            break;
                        }
                    }
                }

                if (needsRemap) {
                    ClassOrInterfaceType newType = new ClassOrInterfaceType();
                    newType.setName(newName != null ? newName : classType.getNameAsString());

                    // 处理泛型参数
                    if (classType.getTypeArguments().isPresent()) {
                        NodeList<Type> newArgs = new NodeList<>();
                        for (Type arg : classType.getTypeArguments().get()) {
                            Type remappedArg = remapAndCloneType(arg);
                            newArgs.add(remappedArg != null ? remappedArg : arg.clone());
                        }
                        newType.setTypeArguments(newArgs);
                    }
                    return newType;
                }
            } else if (type instanceof ArrayType) {
                ArrayType arrayType = (ArrayType) type;
                Type newComponentType = remapAndCloneType(arrayType.getComponentType());
                if (newComponentType != null) {
                    ArrayType newArrayType = arrayType.clone();
                    newArrayType.setComponentType(newComponentType);
                    return newArrayType;
                }
            }
            return null;
        }

        /**
         * 检查类型是否需要重映射
         * 不实际修改
         */
        private boolean needsRemapType(Type type) {
            if (type instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType classType = (ClassOrInterfaceType) type;
                if (remapSimpleName(classType.getNameAsString(), classType) != null) {
                    return true;
                }
                if (classType.getTypeArguments().isPresent()) {
                    for (Type arg : classType.getTypeArguments().get()) {
                        if (needsRemapType(arg)) {
                            return true;
                        }
                    }
                }
            } else if (type instanceof ArrayType) {
                return needsRemapType(((ArrayType) type).getComponentType());
            }
            return false;
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
        public void visit(MethodDeclaration n, Void arg) {
            String ownerClass = getEnclosingClassName(n);
            if (ownerClass != null) {
                tryRemapMethod(n, ownerClass, n.getNameAsString(), buildDescriptor(n));
            }
            super.visit(n, arg);
        }

        private void tryRemapField(NodeWithSimpleName<?> node, String ownerClass, String fieldName) {
            String key = ownerClass + "/" + fieldName;
            String remapped = getJarMapping().fields.get(key);
            if (remapped != null) {
                node.setName(remapped);
            }
        }

        private void tryRemapMethod(NodeWithSimpleName<?> node, String ownerClass, String methodName, String descriptor) {
            String key = ownerClass + "/" + methodName + " " + descriptor;
            String remapped = getJarMapping().methods.get(key);
            if (remapped != null) {
                node.setName(remapped);
            }
        }

        /**
         * 根据简单名查找映射
         * 优先使用 SymbolSolver 解析完整类名，回退到简单名匹配
         */
        private String remapSimpleName(String simpleName, Node context) {
            // 1. 优先尝试 SymbolSolver 解析
            try {
                if (context instanceof ClassOrInterfaceType) {
                    ClassOrInterfaceType type = (ClassOrInterfaceType) context;
                    ResolvedType resolved = type.resolve();
                    if (resolved.isReferenceType()) {
                        String fullName = resolved.asReferenceType().getQualifiedName().replace('.', '/');
                        String mapped = mappingData.mapClass(fullName);
                        if (!mapped.equals(fullName)) {
                            int lastSlash = mapped.lastIndexOf('/');
                            return lastSlash >= 0 ? mapped.substring(lastSlash + 1) : mapped;
                        }
                        return null;
                    }
                }
            } catch (Exception e) {
                // SymbolSolver 失败，进入回退逻辑
            }

            // 2. 检查缓存
            if (simpleNameCache.containsKey(simpleName)) {
                return simpleNameCache.get(simpleName);
            }

            // 3. 检查是否存在该简单名的映射
            List<String> candidates = simpleNameToObfClasses.get(simpleName);
            if (candidates == null || candidates.isEmpty()) {
                simpleNameCache.put(simpleName, null);
                return null;
            }

            String result;
            if (candidates.size() == 1) {
                // 4. 无冲突：直接使用唯一匹配
                result = getRemappedSimpleName(candidates.get(0));
            } else {
                // 5. 有冲突：尝试从 import 推断
                result = resolveConflictFromImports(simpleName, candidates);
            }

            simpleNameCache.put(simpleName, result);
            return result;
        }

        private String resolveConflictFromImports(String simpleName, List<String> candidates) {
            String importedClass = importedClasses.get(simpleName);
            if (importedClass != null) {
                for (String candidate : candidates) {
                    if (candidate.equals(importedClass)) {
                        return getRemappedSimpleName(candidate);
                    }
                }
            }
            
            if (currentPackage != null && !currentPackage.isEmpty()) {
                String samePackageClass = currentPackage + "/" + simpleName;
                for (String candidate : candidates) {
                    if (candidate.equals(samePackageClass)) {
                        return getRemappedSimpleName(candidate);
                    }
                }
            }

            log.warn("Ambiguous simple name '{}' matches multiple classes: {}, skipping remapping",
                    simpleName, candidates);
            return null;
        }

        private String getRemappedSimpleName(String obfClass) {
            String readableClass = getJarMapping().classes.get(obfClass);
            if (readableClass == null) return null;
            int lastSlash = readableClass.lastIndexOf('/');
            return lastSlash >= 0 ? readableClass.substring(lastSlash + 1) : readableClass;
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