# jar-remapper

一个 Gradle 插件，用于将混淆的 JAR/Java/Smali 代码通过映射文件重映射为可读命名。

## 安装

### Android 项目

```groovy
// 根目录 build.gradle
buildscript {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }
    }
    dependencies {
        classpath 'com.github.Ecareee:jar-remapper:0.2.0'
    }
}

// app 模块 build.gradle
apply plugin: 'com.ecaree.jarremapper'
```

### Java 项目

```groovy
// settings.gradle
pluginManagement {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'com.ecaree.jarremapper') {
                useModule("com.github.Ecareee:jar-remapper:${requested.version}")
            }
        }
    }
}

// build.gradle
plugins {
    id 'com.ecaree.jarremapper' version '0.2.0'
}
```

## 映射格式

1. 自定义：YAML（支持注释）

   格式：
   ```yaml
   version: "1.0"
   
   classes:
     - obfuscated: a/b
       readable: com/example/TestClass
       comment: "这是一个测试类"
   
       fields:
         - obfuscated: a
           readable: mValue
           type: I
           comment: "值字段"
   
       methods:
         - obfuscated: a
           readable: getValue
           descriptor: ()I
           comment: "获取值"
   ```
    - 类名使用 JVM 内部格式
    - 字段的 `type` 和方法的 `descriptor` 使用 JVM 描述符格式
    - `comment` 可选

2. [mapping-io](https://github.com/FabricMC/mapping-io):
   Tiny/Tiny2/Enigma/ProGuard/SRG/XSRG/JAM/CSRG/TSRG/TSRG2/JOBF/...

## 配置

### 最小配置

```groovy
jarRemapper {
    mappingsFile = file('mappings.yaml') // 映射文件
}
```

### 完整配置

<details>
<summary>点击查看所有配置项</summary>

```groovy
jarRemapper {
    // 映射文件路径
    // YAML/Tiny/Tiny2/SRG/CSRG/TSRG/TSRG2/ProGuard/Enigma/JAM/JOBF 等格式
    // 默认 mappings.yaml
    mappingsFile = file('mappings.yaml')

    // 源命名空间
    // 用于 Tiny/TSRG2 等多命名空间格式
    // 默认使用映射文件的第一个命名空间
    sourceNamespace = 'official'

    // 目标命名空间
    // 用于 Tiny/TSRG2 等多命名空间格式
    // 默认使用映射文件的第二个命名空间
    targetNamespace = 'named'

    // 排除的包名列表，这些包下的类不会被重映射
    excludedPackages = ['android/', 'androidx/']

    // 输入 JAR 文件
    // 默认 original/classes.jar
    inputJar = file('original/classes.jar')

    // 输出 JAR 文件
    // 默认 original/classes-readable.jar
    outputJar = file('original/classes-readable.jar')

    // 是否启用 JAR 重映射
    // 默认 true
    remapJar = true

    // 是否注入字节码注解
    // 默认 true
    injectBytecodeAnnotations = true

    // 是否在注解中包含 readable 信息
    // 默认 false，因为这些信息可从代码上下文获取
    injectReadableInfo = false

    // Smali 输入目录
    // 默认 src/main/smali/classes
    smaliInputDir = file('src/main/smali/classes')

    // Smali 输出目录
    // 默认 build/generated/remappedSmali/classes
    smaliOutputDir = layout.buildDirectory.dir('generated/remappedSmali/classes')

    // 是否启用 Smali 重映射
    // 默认 true
    remapSmali = true

    // Smali 备份目录
    // 默认 src/main/smali/classes-obf-backup
    smaliBackupDir = file('src/main/smali/classes-obf-backup')

    // 是否启用 Smali 迁移任务
    // 默认 true
    enableSmaliMigrateTask = true

    // Java 源码输入目录
    // 默认 src/main/java
    javaInputDir = file('src/main/java')

    // Java 源码输出目录
    // 默认 build/generated/remappedJava
    javaOutputDir = layout.buildDirectory.dir('generated/remappedJava')

    // Java 备份目录
    // 默认 src/main/java-obf-backup
    javaBackupDir = file('src/main/java-obf-backup')

    // 是否启用 Java 迁移任务
    // 默认 true
    enableJavaMigrateTask = true

    // Java 重映射额外需要的库 JAR，用于类型解析
    // outputJar 会自动添加，这里配置 Android SDK 等额外依赖
    javaLibraryJars = files("${android.sdkDirectory}/platforms/${android.compileSdkVersion}/android.jar")

    // 报告输出目录
    // 默认 build/reports/jarRemapper
    reportsDir = layout.buildDirectory.dir('reports/jarRemapper')
}
```

</details>

另请参阅 [JarRemapperExtension](https://github.com/Ecareee/jar-remapper/blob/main/src/main/java/com/ecaree/jarremapper/JarRemapperExtension.java)

## Gradle 任务

| 任务名称                    | 说明                                                                   |
|-------------------------|----------------------------------------------------------------------|
| `remapJar`/`jrRemapJar` | 使用 [SpecialSource](https://github.com/md-5/SpecialSource) 重映射混淆的 JAR |
| `injectJarAnnotations`  | 对重映射后的 JAR 注入映射注解                                                    |
| `remapSmali`            | 重映射 Smali 代码                                                         |
| `migrateSmali`          | 将重映射后的 Smali 代码覆盖到项目目录                                               |
| `remapJava`             | 重映射 Java 代码                                                          |
| `migrateJava`           | 将重映射后的 Java 代码覆盖到项目目录                                                |
| `chainRemapJar`         | 使用多个映射文件重映射 JAR                                                      |

- `remapJar` 任务在 Android 项目中自动挂载到 `preBuild` 任务，在 Java 项目中自动挂载到 `compileJava` 任务
- 在安装插件或映射文件更新后需要执行一次 `remapJar` 和 `injectJarAnnotations` 任务
  <!-- @formatter:off -->
- 如果项目使用了 [SmaliPlugin](https://github.com/Mosect/Android-SmaliPlugin) 插件，**最终参与打包的产物必须只存在一套命名空间**，建议在执行
  `remapJava`/`migrateJava` 任务后也执行一次 `remapSmali`/`migrateSmali` 任务，防止 Java/Smali 链接错误
  <!-- @formatter:on -->