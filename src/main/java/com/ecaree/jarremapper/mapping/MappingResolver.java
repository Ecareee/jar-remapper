package com.ecaree.jarremapper.mapping;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * 映射文件解析器
 * 支持从 Maven 仓库自动获取映射文件
 */
@Slf4j
@RequiredArgsConstructor
public class MappingResolver {
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Gson GSON = new Gson();
    private final Project project;

    public static String normalizeSpigotVersion(String version) {
        String[] pieces = version.split("-");
        String mcVersion = pieces[0];
        String r = pieces.length > 1 ? pieces[1] : "R0.1";
        String tag = pieces.length > 2 ? pieces[2] : "SNAPSHOT";
        return mcVersion + "-" + r + "-" + tag;
    }

    public File resolve(String coords) {
        log.info("Resolving mapping from Maven: {}", coords);
        Dependency dep = project.getDependencies().create(coords);
        Configuration config = project.getConfigurations().detachedConfiguration(dep);
        config.setTransitive(false);
        File file = config.getSingleFile();
        log.info("Resolved mapping file: {}", file.getAbsolutePath());
        return file;
    }

    public MappingData resolveAndLoad(String coords, String sourceNamespace, String targetNamespace) throws IOException {
        File file = resolve(coords);
        return MappingLoader.load(file, sourceNamespace, targetNamespace);
    }

    public MappingData resolveAndLoad(String coords) throws IOException {
        return resolveAndLoad(coords, null, null);
    }

    public File resolveFabricIntermediary(String mcVersion) throws IOException {
        File jarFile = resolve("net.fabricmc:intermediary:" + mcVersion + ":v2");
        return extractMappingFromJar(jarFile, "intermediary-" + mcVersion);
    }

    public File resolveFabricYarn(String yarnVersion) throws IOException {
        File jarFile = resolve("net.fabricmc:yarn:" + yarnVersion + ":v2");
        return extractMappingFromJar(jarFile, "yarn-" + yarnVersion);
    }

    private File extractMappingFromJar(File jarFile, String cacheName) throws IOException {
        Path cacheDir = project.getGradle().getGradleUserHomeDir().toPath()
                .resolve("caches/jarremapper/extracted-mappings");
        Files.createDirectories(cacheDir);

        Path cachedFile = cacheDir.resolve(cacheName + ".tiny");
        if (Files.exists(cachedFile) && Files.getLastModifiedTime(cachedFile).toMillis() >= jarFile.lastModified()) {
            log.info("Using cached extracted mapping: {}", cachedFile);
            return cachedFile.toFile();
        }

        log.info("Extracting {} from {}", "mappings/mappings.tiny", jarFile.getName());
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("mappings/mappings.tiny");
            if (entry == null) {
                throw new IOException("Entry not found in JAR: " + "mappings/mappings.tiny");
            }
            try (InputStream is = jar.getInputStream(entry)) {
                Files.copy(is, cachedFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        log.info("Extracted mapping to: {}", cachedFile);
        return cachedFile.toFile();
    }

    public MappingData loadFabricMappingChain(String mcVersion, String yarnVersion) throws IOException {
        File intermediaryFile = resolveFabricIntermediary(mcVersion);
        File yarnFile = resolveFabricYarn(yarnVersion);

        MappingData intermediary = MappingLoader.load(intermediaryFile, "official", "intermediary");
        MappingData yarn = MappingLoader.load(yarnFile, "intermediary", "named");

        return new MappingChain()
                .add(intermediary)
                .add(yarn)
                .merge();
    }

    public File resolveForgeSrg(String mcVersion) {
        return resolve("net.minecraftforge:forge:" + mcVersion + ":srg@tsrg");
    }

    public File resolveParchment(String mcVersion, String parchmentVersion) {
        return resolve("org.parchmentmc.data:parchment-" + mcVersion + ":" + parchmentVersion + "@zip");
    }

    public File resolveSpigotMojangMappings(String version) {
        return resolve("org.spigotmc:minecraft-server:" + version + ":maps-mojang@txt");
    }

    public File resolveSpigotMappings(String version) {
        return resolve("org.spigotmc:minecraft-server:" + version + ":maps-spigot@csrg");
    }

    public MappingData loadSpigotMappingChain(String version) throws IOException {
        File mojangFile = resolveSpigotMojangMappings(version);
        File spigotFile = resolveSpigotMappings(version);

        MappingData mojangToObf = MappingLoader.load(mojangFile, true);
        MappingData obfToSpigot = MappingLoader.load(spigotFile);

        return new MappingChain()
                .add(mojangToObf)
                .add(obfToSpigot)
                .merge();
    }

    public File resolveMojangMappings(String mcVersion, String side) throws IOException {
        if (!"client".equals(side) && !"server".equals(side)) {
            throw new IllegalArgumentException("Side must be 'client' or 'server', got: " + side);
        }

        Path cacheDir = project.getGradle().getGradleUserHomeDir().toPath()
                .resolve("caches/jarremapper/mojang-mappings");
        Files.createDirectories(cacheDir);

        Path cachedFile = cacheDir.resolve(mcVersion + "-" + side + ".txt");
        if (Files.exists(cachedFile)) {
            log.info("Using cached Mojang mappings: {}", cachedFile);
            return cachedFile.toFile();
        }

        log.info("Downloading Mojang {} mappings for {}", side, mcVersion);

        JsonObject manifest = downloadJson(VERSION_MANIFEST_URL);
        String versionUrl = findVersionUrl(manifest, mcVersion);
        if (versionUrl == null) {
            throw new IOException("Version not found in manifest: " + mcVersion);
        }

        JsonObject versionJson = downloadJson(versionUrl);
        String mappingUrl = findMappingUrl(versionJson, side);
        if (mappingUrl == null) {
            throw new IOException("Mappings not found for " + side + " in version " + mcVersion);
        }

        log.info("Downloading from: {}", mappingUrl);
        downloadFile(mappingUrl, cachedFile);

        log.info("Mojang mappings cached: {}", cachedFile);
        return cachedFile.toFile();
    }

    public MappingData loadMojangMappings(String mcVersion, String side) throws IOException {
        File mappingFile = resolveMojangMappings(mcVersion, side);
        return MappingLoader.load(mappingFile);
    }

    private String findVersionUrl(JsonObject manifest, String version) {
        JsonArray versions = manifest.getAsJsonArray("versions");
        if (versions == null) return null;

        for (JsonElement element : versions) {
            JsonObject versionObj = element.getAsJsonObject();
            String id = versionObj.get("id").getAsString();
            if (version.equals(id)) {
                return versionObj.get("url").getAsString();
            }
        }
        return null;
    }

    private String findMappingUrl(JsonObject versionJson, String side) {
        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        if (downloads == null) return null;

        String key = side + "_mappings";
        JsonObject mappingObj = downloads.getAsJsonObject(key);
        if (mappingObj == null) return null;

        JsonElement urlElement = mappingObj.get("url");
        return urlElement != null ? urlElement.getAsString() : null;
    }

    private JsonObject downloadJson(String urlStr) throws IOException {
        String content = downloadString(urlStr);
        return GSON.fromJson(content, JsonObject.class);
    }

    private String downloadString(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        try (InputStream is = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private void downloadFile(String urlStr, Path target) throws IOException {
        URL url = new URL(urlStr);
        try (InputStream is = url.openStream()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void configureRepositories() {
        project.getRepositories().maven(repo -> {
            repo.setName("FabricMC");
            repo.setUrl("https://maven.fabricmc.net/");
        });
        project.getRepositories().maven(repo -> {
            repo.setName("MinecraftForge");
            repo.setUrl("https://maven.minecraftforge.net/");
        });
        project.getRepositories().maven(repo -> {
            repo.setName("NeoForged");
            repo.setUrl("https://maven.neoforged.net/releases/");
        });
        project.getRepositories().maven(repo -> {
            repo.setName("ParchmentMC");
            repo.setUrl("https://maven.parchmentmc.org/");
        });
        project.getRepositories().mavenLocal();

        log.info("Configured mapping repositories");
    }
}