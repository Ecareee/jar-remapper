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
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 重映射
 * 使用 JavaParser + SymbolSolver 进行类型感知重映射
 */
@Slf4j
public class JavaRemapper {
    private final MappingData mappingData;
    private final List<File> libraryJars;
    private final Map<String, List<String>> simpleNameToObfClasses;
    private final Map<String, Map<String, String>> staticFieldIndex;
    private final Map<String, Map<String, String>> staticMethodIndex;
    private final Map<String, String> packageMappingIndex;
    private final Map<String, String> uniqueFieldMappings;

    public JavaRemapper(MappingData mappingData) {
        this(mappingData, new ArrayList<>());
    }

    public JavaRemapper(MappingData mappingData, List<File> libraryJars) {
        this.mappingData = mappingData;
        this.libraryJars = libraryJars != null ? libraryJars : new ArrayList<>();
        this.simpleNameToObfClasses = buildSimpleNameIndex();
        this.staticFieldIndex = buildStaticFieldIndex();
        this.staticMethodIndex = buildStaticMethodIndex();
        this.packageMappingIndex = buildPackageMappingIndex();
        this.uniqueFieldMappings = buildUniqueFieldMappings();
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
            int lastSeparator = Math.max(obfClass.lastIndexOf('/'), obfClass.lastIndexOf('$'));
            String simpleName = lastSeparator >= 0 ? obfClass.substring(lastSeparator + 1) : obfClass;
            index.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(obfClass);
        }
        return index;
    }

    private Map<String, Map<String, String>> buildStaticFieldIndex() {
        Map<String, Map<String, String>> index = new HashMap<>();
        JarMapping jarMapping = mappingData.getJarMapping();

        for (Map.Entry<String, String> entry : jarMapping.fields.entrySet()) {
            String key = entry.getKey();
            int slashIdx = key.lastIndexOf('/');
            if (slashIdx > 0) {
                String owner = key.substring(0, slashIdx);
                String name = key.substring(slashIdx + 1);
                int spaceIdx = name.indexOf(' ');
                if (spaceIdx > 0) {
                    name = name.substring(0, spaceIdx);
                }
                index.computeIfAbsent(owner, k -> new HashMap<>()).put(name, entry.getValue());
            }
        }

        return index;
    }

    private Map<String, Map<String, String>> buildStaticMethodIndex() {
        Map<String, Map<String, String>> index = new HashMap<>();
        JarMapping jarMapping = mappingData.getJarMapping();

        for (Map.Entry<String, String> entry : jarMapping.methods.entrySet()) {
            String key = entry.getKey();
            int spaceIdx = key.indexOf(' ');
            if (spaceIdx > 0) {
                String ownerAndName = key.substring(0, spaceIdx);
                int slashIdx = ownerAndName.lastIndexOf('/');
                if (slashIdx > 0) {
                    String owner = ownerAndName.substring(0, slashIdx);
                    String name = ownerAndName.substring(slashIdx + 1);
                    String remapped = entry.getValue();
                    Map<String, String> memberMap = index.computeIfAbsent(owner, k -> new HashMap<>());
                    if (memberMap.containsKey(name)) {
                        String existing = memberMap.get(name);
                        if (existing != null && !existing.equals(remapped)) {
                            // 同名方法不同重载映射到不同名称，标记为冲突
                            memberMap.put(name, null);
                        }
                    } else {
                        memberMap.put(name, remapped);
                    }
                }
            }
        }

        return index;
    }

    private Map<String, String> buildPackageMappingIndex() {
        Map<String, String> index = new HashMap<>();
        JarMapping jarMapping = mappingData.getJarMapping();

        // 从 packages 映射构建
        for (Map.Entry<String, String> entry : jarMapping.packages.entrySet()) {
            String obfPkg = entry.getKey();
            String readablePkg = entry.getValue();
            if (obfPkg.endsWith("/")) {
                obfPkg = obfPkg.substring(0, obfPkg.length() - 1);
            }
            if (readablePkg.endsWith("/")) {
                readablePkg = readablePkg.substring(0, readablePkg.length() - 1);
            }
            index.put(obfPkg, readablePkg);
        }

        // 从 classes 映射推断包名
        for (Map.Entry<String, String> entry : jarMapping.classes.entrySet()) {
            String obfClass = entry.getKey();
            String readableClass = entry.getValue();
            int obfLastSlash = obfClass.lastIndexOf('/');
            int readableLastSlash = readableClass.lastIndexOf('/');
            if (obfLastSlash > 0 && readableLastSlash > 0) {
                String obfPkg = obfClass.substring(0, obfLastSlash);
                String readablePkg = readableClass.substring(0, readableLastSlash);
                index.putIfAbsent(obfPkg, readablePkg);
            }
        }

        return index;
    }

    private Map<String, String> buildUniqueFieldMappings() {
        Map<String, String> index = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        JarMapping jarMapping = mappingData.getJarMapping();

        for (Map.Entry<String, String> entry : jarMapping.fields.entrySet()) {
            String key = entry.getKey();
            int slashIdx = key.lastIndexOf('/');
            if (slashIdx > 0) {
                String name = key.substring(slashIdx + 1);
                int spaceIdx = name.indexOf(' ');
                if (spaceIdx > 0) {
                    name = name.substring(0, spaceIdx);
                }
                String remapped = entry.getValue();
                if (conflicts.contains(name)) {
                    continue;
                }
                if (index.containsKey(name)) {
                    if (!index.get(name).equals(remapped)) {
                        index.remove(name);
                        conflicts.add(name);
                    }
                } else {
                    index.put(name, remapped);
                }
            }
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
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
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

        RemappingVisitor visitor = new RemappingVisitor(
                mappingData, simpleNameToObfClasses, staticFieldIndex, staticMethodIndex,
                packageMappingIndex, uniqueFieldMappings);
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
            String remapped = packageMappingIndex.get(internalPkg);
            if (remapped != null) {
                pkg.setName(remapped.replace('/', '.'));
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
        private final Map<String, Map<String, String>> staticFieldIndex;
        private final Map<String, Map<String, String>> staticMethodIndex;
        private final Map<String, String> packageMappingIndex;
        private final Map<String, String> uniqueFieldMappings;
        private final Map<String, String> simpleNameCache = new HashMap<>();
        private final Map<String, String> importedClasses = new HashMap<>();
        private final Set<String> importedPackages = new HashSet<>();
        private final Map<String, String> staticImportedMembers = new HashMap<>();
        private final Set<String> staticAsteriskClasses = new HashSet<>();
        private String currentPackage;

        private JarMapping getJarMapping() {
            return mappingData.getJarMapping();
        }

        public void initImports(CompilationUnit cu) {
            simpleNameCache.clear();
            importedClasses.clear();
            importedPackages.clear();
            staticImportedMembers.clear();
            staticAsteriskClasses.clear();

            currentPackage = cu.getPackageDeclaration()
                    .map(pkg -> pkg.getNameAsString().replace('.', '/'))
                    .orElse("");

            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.isAsterisk()) {
                    if (imp.isStatic()) {
                        String className = imp.getNameAsString().replace('.', '/');
                        staticAsteriskClasses.add(className);
                    } else {
                        String pkgName = imp.getNameAsString().replace('.', '/');
                        importedPackages.add(pkgName);
                    }
                    continue;
                }

                String fullName = imp.getNameAsString();

                if (imp.isStatic()) {
                    int lastDot = fullName.lastIndexOf('.');
                    if (lastDot > 0) {
                        String className = fullName.substring(0, lastDot);
                        String memberName = fullName.substring(lastDot + 1);
                        String internalName = className.replace('.', '/');
                        staticImportedMembers.put(memberName, internalName);
                        int classLastDot = className.lastIndexOf('.');
                        String simpleName = classLastDot >= 0 ? className.substring(classLastDot + 1) : className;
                        importedClasses.put(simpleName, internalName);
                    }
                } else {
                    String internalName = fullName.replace('.', '/');
                    int lastSlash = internalName.lastIndexOf('/');
                    String simpleName = lastSlash >= 0 ? internalName.substring(lastSlash + 1) : internalName;
                    importedClasses.put(simpleName, internalName);
                }
            }
        }

        @Override
        public void visit(ImportDeclaration n, Void arg) {
            if (n.isAsterisk()) {
                if (n.isStatic()) {
                    // 静态星号 import
                    String className = n.getNameAsString();
                    String internalName = className.replace('.', '/');
                    String remapped = mappingData.mapClass(internalName);
                    if (!remapped.equals(internalName)) {
                        n.setName(remapped.replace('/', '.'));
                    }
                } else {
                    // 普通星号 import
                    String pkgName = n.getNameAsString().replace('.', '/');
                    String remappedPkg = packageMappingIndex.get(pkgName);
                    if (remappedPkg != null) {
                        n.setName(remappedPkg.replace('/', '.'));
                    }
                }
            } else if (n.isStatic()) {
                // 静态 import
                String fullName = n.getNameAsString();
                int lastDot = fullName.lastIndexOf('.');
                if (lastDot > 0) {
                    String className = fullName.substring(0, lastDot);
                    String memberName = fullName.substring(lastDot + 1);
                    String internalName = className.replace('.', '/');
                    String remappedClass = mappingData.mapClass(internalName);
                    String remappedMember = remapStaticMember(internalName, memberName);
                    if (!remappedClass.equals(internalName) || !remappedMember.equals(memberName)) {
                        n.setName(remappedClass.replace('/', '.') + "." + remappedMember);
                    }
                }
            } else {
                // 普通 import
                String importName = n.getNameAsString();
                String internalName = importName.replace('.', '/');
                String remapped = mappingData.mapClass(internalName);
                if (!remapped.equals(internalName)) {
                    n.setName(remapped.replace('/', '.'));
                }
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
        public void visit(EnumDeclaration n, Void arg) {
            super.visit(n, arg);
            String remapped = remapSimpleName(n.getNameAsString(), n);
            if (remapped != null) {
                n.setName(remapped);
            }
        }

        @Override
        public void visit(RecordDeclaration n, Void arg) {
            super.visit(n, arg);
            String remapped = remapSimpleName(n.getNameAsString(), n);
            if (remapped != null) {
                n.setName(remapped);
            }
        }

        @Override
        public void visit(AnnotationDeclaration n, Void arg) {
            super.visit(n, arg);
            String remapped = remapSimpleName(n.getNameAsString(), n);
            if (remapped != null) {
                n.setName(remapped);
            }
        }

        @Override
        public void visit(ClassOrInterfaceType n, Void arg) {
            String remapped = remapSimpleName(n.getNameAsString(), n);
            if (remapped != null) {
                n.setName(remapped);
            }
            super.visit(n, arg);
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

        @Override
        public void visit(FieldAccessExpr n, Void arg) {
            boolean remapped = false;
            String fieldName = n.getNameAsString();

            try {
                ResolvedType scopeType = n.getScope().calculateResolvedType();
                if (scopeType.isReferenceType()) {
                    String ownerClass = toInternalName(scopeType.asReferenceType().getQualifiedName());
                    String remappedField = tryGetFieldMapping(ownerClass, fieldName);
                    if (remappedField != null) {
                        n.setName(remappedField);
                        remapped = true;
                    }
                }
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve field access '{}': {}", n, e.getMessage());
            }

            // 回退：使用唯一字段名映射
            if (!remapped) {
                String fallbackRemapped = uniqueFieldMappings.get(fieldName);
                if (fallbackRemapped != null) {
                    n.setName(fallbackRemapped);
                    log.debug("Field '{}' remapped to '{}' via fallback", fieldName, fallbackRemapped);
                }
            }

            super.visit(n, arg);
        }


        @Override
        public void visit(NameExpr n, Void arg) {
            String name = n.getNameAsString();

            // 1. 检查静态导入成员
            String ownerClass = staticImportedMembers.get(name);
            if (ownerClass != null) {
                String remapped = remapStaticField(ownerClass, name);
                if (!remapped.equals(name)) {
                    n.setName(remapped);
                }
                super.visit(n, arg);
                return;
            }

            // 2. 尝试使用 SymbolSolver 解析
            try {
                ResolvedValueDeclaration resolved = n.resolve();
                if (resolved.isField()) {
                    ResolvedFieldDeclaration field = resolved.asField();
                    String declaringType = field.declaringType().getQualifiedName();
                    String ownerInternal = toInternalName(declaringType);
                    String remapped = tryGetFieldMapping(ownerInternal, name);
                    if (remapped != null) {
                        n.setName(remapped);
                    }
                }
                super.visit(n, arg);
                return;
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve NameExpr '{}': {}", name, e.getMessage());
            }

            // 3. SymbolSolver 失败时的回退：检查是否被局部变量遮蔽
            if (!isShadowedByLocalVariable(n, name)) {
                String enclosingClass = getEnclosingClassName(n);
                if (enclosingClass != null) {
                    String remapped = tryGetFieldMapping(enclosingClass, name);
                    if (remapped != null) {
                        n.setName(remapped);
                        super.visit(n, arg);
                        return;
                    }
                }
            }

            // 4. 检查静态星号导入
            String foundOwner = null;
            String foundRemapped = null;
            for (String asteriskClass : staticAsteriskClasses) {
                String remapped = remapStaticField(asteriskClass, name);
                if (!remapped.equals(name)) {
                    if (foundOwner != null && !foundOwner.equals(asteriskClass)) {
                        log.warn("Ambiguous static member '{}' found in multiple asterisk imports: {}, {}",
                                name, foundOwner, asteriskClass);
                        super.visit(n, arg);
                        return;
                    }
                    foundOwner = asteriskClass;
                    foundRemapped = remapped;
                }
            }

            if (foundRemapped != null) {
                n.setName(foundRemapped);
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

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            boolean remapped = false;

            try {
                ResolvedMethodDeclaration resolved = n.resolve();
                String ownerClass = toInternalName(resolved.declaringType().getQualifiedName());
                tryRemapMethod(n, ownerClass, n.getNameAsString(), buildDescriptor(resolved));
                remapped = true;
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve method call '{}': {}", n.getNameAsString(), e.getMessage());
            }

            if (!remapped && !n.getScope().isPresent()) {
                String methodName = n.getNameAsString();

                String ownerClass = staticImportedMembers.get(methodName);
                if (ownerClass != null) {
                    String remappedName = remapStaticMethod(ownerClass, methodName);
                    if (!remappedName.equals(methodName)) {
                        n.setName(remappedName);
                    }
                } else {
                    for (String asteriskClass : staticAsteriskClasses) {
                        String remappedName = remapStaticMethod(asteriskClass, methodName);
                        if (!remappedName.equals(methodName)) {
                            n.setName(remappedName);
                            break;
                        }
                    }
                }
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(MethodReferenceExpr n, Void arg) {
            String methodName = n.getIdentifier();

            try {
                String identifier = n.getIdentifier();
                if ("new".equals(identifier)) {
                    super.visit(n, arg);
                    return;
                }

                Expression scope = n.getScope();
                if (scope.isTypeExpr()) {
                    ResolvedType type = scope.asTypeExpr().getType().resolve();
                    if (type.isReferenceType()) {
                        String ownerClass = toInternalName(type.asReferenceType().getQualifiedName());
                        String remapped = remapStaticMethod(ownerClass, methodName);
                        if (!remapped.equals(methodName)) {
                            n.setIdentifier(remapped);
                        }
                    }
                } else {
                    ResolvedType scopeType = scope.calculateResolvedType();
                    if (scopeType.isReferenceType()) {
                        String ownerClass = toInternalName(scopeType.asReferenceType().getQualifiedName());
                        String remapped = remapStaticMethod(ownerClass, methodName);
                        if (!remapped.equals(methodName)) {
                            n.setIdentifier(remapped);
                        }
                    }
                }
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve method reference '{}': {}", n, e.getMessage());
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            remapConstructorName(n);
            super.visit(n, arg);
        }

        @Override
        public void visit(CompactConstructorDeclaration n, Void arg) {
            remapConstructorName(n);
            super.visit(n, arg);
        }

        private void remapConstructorName(NodeWithSimpleName<?> node) {
            String ownerClass = getEnclosingClassName((Node) node);
            if (ownerClass == null) return;
            String remappedClass = mappingData.mapClass(ownerClass);
            if (!remappedClass.equals(ownerClass)) {
                int lastSeparator = Math.max(remappedClass.lastIndexOf('/'), remappedClass.lastIndexOf('$'));
                String newName = lastSeparator >= 0 ? remappedClass.substring(lastSeparator + 1) : remappedClass;
                node.setName(newName);
            }
        }

        @Override
        public void visit(MarkerAnnotationExpr n, Void arg) {
            remapAnnotationName(n);
            super.visit(n, arg);
        }

        @Override
        public void visit(SingleMemberAnnotationExpr n, Void arg) {
            remapAnnotationName(n);
            super.visit(n, arg);
        }

        @Override
        public void visit(NormalAnnotationExpr n, Void arg) {
            remapAnnotationName(n);
            super.visit(n, arg);
        }

        private void remapAnnotationName(AnnotationExpr n) {
            String name = n.getNameAsString();
            int lastDot = name.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? name.substring(lastDot + 1) : name;
            String remapped = remapSimpleName(simpleName, n);
            if (remapped != null) {
                if (lastDot >= 0) {
                    String prefix = name.substring(0, lastDot + 1);
                    n.setName(prefix + remapped);
                } else {
                    n.setName(remapped);
                }
            }
        }

        @Override
        public void visit(CastExpr n, Void arg) {
            Type newType = remapAndCloneType(n.getType());
            if (newType != null) {
                n.setType(newType);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(InstanceOfExpr n, Void arg) {
            if (!n.getPattern().isPresent()) { // 如果有 pattern，类型会通过 TypePatternExpr 的 visit 处理，此处只处理无 pattern 情况
                Type newType = remapAndCloneType(n.getType());
                if (newType instanceof ReferenceType) {
                    n.setType((ReferenceType) newType);
                }
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ArrayCreationExpr n, Void arg) {
            Type newType = remapAndCloneType(n.getElementType());
            if (newType != null) {
                n.setElementType(newType);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(TypePatternExpr n, Void arg) {
            remapPatternType(n.getType());
            super.visit(n, arg);
        }

        @Override
        public void visit(RecordPatternExpr n, Void arg) {
            remapPatternType(n.getType());
            super.visit(n, arg);
        }

        private void remapPatternType(Type type) {
            if (type instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType classType = (ClassOrInterfaceType) type;
                String remapped = remapSimpleName(classType.getNameAsString(), classType);
                if (remapped != null) {
                    // 直接修改名称，避免替换整个节点导致 LexicalPreservingPrinter 问题
                    classType.setName(remapped);
                }
            }
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

                // 检查 scope 是否需要重映射
                ClassOrInterfaceType remappedScope = null;
                if (classType.getScope().isPresent()) {
                    Type scopeResult = remapAndCloneType(classType.getScope().get());
                    if (scopeResult instanceof ClassOrInterfaceType) {
                        remappedScope = (ClassOrInterfaceType) scopeResult;
                        needsRemap = true;
                    }
                }

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

                    // 设置 scope
                    if (classType.getScope().isPresent()) {
                        if (remappedScope != null) {
                            newType.setScope(remappedScope);
                        } else {
                            newType.setScope(classType.getScope().get().clone());
                        }
                    }

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
            } else if (type instanceof WildcardType) {
                WildcardType wildcardType = (WildcardType) type;
                boolean needsRemap = false;
                Type newExtended = null;
                Type newSuper = null;

                if (wildcardType.getExtendedType().isPresent()) {
                    newExtended = remapAndCloneType(wildcardType.getExtendedType().get());
                    if (newExtended != null) needsRemap = true;
                }
                if (wildcardType.getSuperType().isPresent()) {
                    newSuper = remapAndCloneType(wildcardType.getSuperType().get());
                    if (newSuper != null) needsRemap = true;
                }

                if (needsRemap) {
                    WildcardType newWildcard = new WildcardType();
                    if (wildcardType.getExtendedType().isPresent()) {
                        newWildcard.setExtendedType((ReferenceType) (newExtended != null ?
                                newExtended : wildcardType.getExtendedType().get().clone()));
                    }
                    if (wildcardType.getSuperType().isPresent()) {
                        newWildcard.setSuperType((ReferenceType) (newSuper != null ?
                                newSuper : wildcardType.getSuperType().get().clone()));
                    }
                    return newWildcard;
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

                if (classType.getScope().isPresent()) {
                    if (needsRemapType(classType.getScope().get())) {
                        return true;
                    }
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
            } else if (type instanceof WildcardType) {
                WildcardType wt = (WildcardType) type;
                if (wt.getExtendedType().isPresent() && needsRemapType(wt.getExtendedType().get())) {
                    return true;
                }
                return wt.getSuperType().isPresent() && needsRemapType(wt.getSuperType().get());
            }
            return false;
        }

        private String tryGetFieldMapping(String ownerClass, String fieldName) {
            String key = ownerClass + "/" + fieldName;
            return getJarMapping().fields.get(key);
        }

        private void tryRemapField(NodeWithSimpleName<?> node, String ownerClass, String fieldName) {
            String remapped = tryGetFieldMapping(ownerClass, fieldName);
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

        private boolean isShadowedByLocalVariable(Node node, String name) {
            Node current = node.getParentNode().orElse(null);

            while (current != null) {
                if (current instanceof MethodDeclaration) {
                    MethodDeclaration method = (MethodDeclaration) current;
                    for (Parameter param : method.getParameters()) {
                        if (param.getNameAsString().equals(name)) {
                            return true;
                        }
                    }
                    break;
                }
                if (current instanceof ConstructorDeclaration) {
                    ConstructorDeclaration ctor = (ConstructorDeclaration) current;
                    for (Parameter param : ctor.getParameters()) {
                        if (param.getNameAsString().equals(name)) {
                            return true;
                        }
                    }
                    break;
                }
                if (current instanceof LambdaExpr) {
                    LambdaExpr lambda = (LambdaExpr) current;
                    for (Parameter param : lambda.getParameters()) {
                        if (param.getNameAsString().equals(name)) {
                            return true;
                        }
                    }
                }

                // 检查局部变量在当前节点之前的声明
                if (current instanceof BlockStmt) {
                    BlockStmt block = (BlockStmt) current;
                    for (Statement stmt : block.getStatements()) {
                        if (containsNode(stmt, node)) {
                            break;
                        }
                        if (stmt instanceof ExpressionStmt) {
                            Expression expr = ((ExpressionStmt) stmt).getExpression();
                            if (expr instanceof VariableDeclarationExpr) {
                                for (VariableDeclarator var : ((VariableDeclarationExpr) expr).getVariables()) {
                                    if (var.getNameAsString().equals(name)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }

                if (current instanceof ForStmt) {
                    ForStmt forStmt = (ForStmt) current;
                    for (Expression init : forStmt.getInitialization()) {
                        if (init instanceof VariableDeclarationExpr) {
                            for (VariableDeclarator var : ((VariableDeclarationExpr) init).getVariables()) {
                                if (var.getNameAsString().equals(name)) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                if (current instanceof ForEachStmt) {
                    ForEachStmt forEach = (ForEachStmt) current;
                    if (forEach.getVariable().getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(name))) {
                        return true;
                    }
                }

                if (current instanceof CatchClause) {
                    CatchClause catchClause = (CatchClause) current;
                    if (catchClause.getParameter().getNameAsString().equals(name)) {
                        return true;
                    }
                }

                if (current instanceof TryStmt) {
                    TryStmt tryStmt = (TryStmt) current;
                    for (Expression res : tryStmt.getResources()) {
                        if (res instanceof VariableDeclarationExpr) {
                            for (VariableDeclarator var : ((VariableDeclarationExpr) res).getVariables()) {
                                if (var.getNameAsString().equals(name)) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                current = current.getParentNode().orElse(null);
            }

            return false;
        }

        private boolean containsNode(Node parent, Node target) {
            if (parent == target) {
                return true;
            }
            for (Node child : parent.getChildNodes()) {
                if (containsNode(child, target)) {
                    return true;
                }
            }
            return false;
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
                        String fullName = toInternalName(resolved.asReferenceType().getQualifiedName());
                        String mapped = mappingData.mapClass(fullName);
                        if (!mapped.equals(fullName)) {
                            int lastSeparator = Math.max(mapped.lastIndexOf('/'), mapped.lastIndexOf('$'));
                            return lastSeparator >= 0 ? mapped.substring(lastSeparator + 1) : mapped;
                        }
                        return null;
                    }
                }
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve type '{}', falling back to simple name matching: {}",
                        simpleName, e.getMessage());
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

            List<String> asteriskImportMatches = new ArrayList<>();
            for (String candidate : candidates) {
                int lastSlash = candidate.lastIndexOf('/');
                String candidatePkg = lastSlash >= 0 ? candidate.substring(0, lastSlash) : "";
                if (importedPackages.contains(candidatePkg)) {
                    asteriskImportMatches.add(candidate);
                }
            }
            if (asteriskImportMatches.size() == 1) {
                return getRemappedSimpleName(asteriskImportMatches.get(0));
            } else if (asteriskImportMatches.size() > 1) {
                log.warn("Ambiguous simple name '{}' matches multiple classes from asterisk imports: {}",
                        simpleName, asteriskImportMatches);
            }

            log.warn("Ambiguous simple name '{}' matches multiple classes: {}, skipping remapping",
                    simpleName, candidates);
            return null;
        }

        private String getRemappedSimpleName(String obfClass) {
            String readableClass = getJarMapping().classes.get(obfClass);
            if (readableClass == null) return null;
            int lastSeparator = Math.max(readableClass.lastIndexOf('/'), readableClass.lastIndexOf('$'));
            return lastSeparator >= 0 ? readableClass.substring(lastSeparator + 1) : readableClass;
        }

        private String remapStaticMember(String ownerClass, String memberName) {
            String fieldRemapped = remapStaticField(ownerClass, memberName);
            if (!fieldRemapped.equals(memberName)) {
                return fieldRemapped;
            }
            return remapStaticMethod(ownerClass, memberName);
        }

        private String remapStaticField(String ownerClass, String fieldName) {
            Map<String, String> memberMap = staticFieldIndex.get(ownerClass);
            if (memberMap == null) {
                return fieldName;
            }
            String remapped = memberMap.get(fieldName);
            return remapped != null ? remapped : fieldName;
        }

        private String remapStaticMethod(String ownerClass, String methodName) {
            Map<String, String> memberMap = staticMethodIndex.get(ownerClass);
            if (memberMap == null) {
                return methodName;
            }
            String remapped = memberMap.get(methodName);
            if (remapped == null && memberMap.containsKey(methodName)) {
                // 值为 null 但 key 存在，表示有冲突
                log.warn("Ambiguous static method import: {}.{} maps to multiple names",
                        ownerClass.replace('/', '.'), methodName);
                return methodName;
            }
            return remapped != null ? remapped : methodName;
        }

        /**
         * 将 Java 完全限定名转换为 JVM 内部名
         * 例如 com.example.Outer.Inner -> com/example/Outer$Inner
         */
        private String toInternalName(String qualifiedName) {
            StringBuilder sb = new StringBuilder();
            String[] parts = qualifiedName.split("\\.");
            boolean inClass = false;
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append(inClass ? '$' : '/');
                }
                String part = parts[i];
                // 简单启发式判断，首字母大写认为是类名
                if (!inClass && !part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                    inClass = true;
                }
                sb.append(part);
            }
            return sb.toString();
        }

        private String getEnclosingClassName(Node node) {
            Node current = node;
            while (current != null) {
                if (current instanceof ClassOrInterfaceDeclaration
                        || current instanceof EnumDeclaration
                        || current instanceof RecordDeclaration
                        || current instanceof AnnotationDeclaration) {
                    try {
                        ResolvedReferenceTypeDeclaration resolved = ((TypeDeclaration<?>) current).resolve();
                        return toInternalName(resolved.getQualifiedName());
                    } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                        log.debug("Failed to resolve enclosing class: {}", e.getMessage());
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
                } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                    log.debug("Failed to resolve parameter type '{}', using Object: {}",
                            param.getType(), e.getMessage());
                    sb.append("Ljava/lang/Object;");
                }
            }
            sb.append(")");
            try {
                sb.append(toDescriptor(method.getType().resolve()));
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.debug("Failed to resolve return type '{}', using void: {}",
                        method.getType(), e.getMessage());
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
                String name = toInternalName(type.asReferenceType().getQualifiedName());
                return "L" + name + ";";
            }
            return "Ljava/lang/Object;";
        }
    }
}