package me.cryo.zrlauncher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Isolated Forge instance per map: installs the map's mod list, then launches that instance.
 */
public final class InstanceManager {

    private static final String RUNTIME_URL = "https://raw.githubusercontent.com/Cryo60/ZR-Launcher/main/runtime.json";
    private static final Pattern MODRINTH = Pattern.compile("modrinth\\.com/(?:mod|plugin)/([^/?#]+)(?:/version/([^/?#]+))?");
    private static final Pattern GITHUB_ASSET = Pattern.compile("github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^?#]+)");
    private static final Pattern GITHUB_REPO = Pattern.compile("github\\.com/([^/]+)/([^/#?]+)/?$");

    private final boolean french;
    private final Consumer<String> status;

    public InstanceManager(boolean french, Consumer<String> status) {
        this.french = french;
        this.status = status;
    }

    public void play(JsonObject map) throws Exception {
        String mapId = map.get("id").getAsString();
        String mapName = map.get("name").getAsString();
        String downloadUrl = map.get("download_url").getAsString();
        String sha = map.has("sha256") ? map.get("sha256").getAsString() : "";

        File root = new File(System.getProperty("user.home"), ".zombierool" + File.separator + "instances");
        File instance = new File(root, sanitize(mapId));
        instance.mkdirs();

        JsonObject runtime = loadRuntime();
        String mc = runtime.has("minecraft") ? runtime.get("minecraft").getAsString() : "1.20.1";
        String forge = runtime.has("forge") ? runtime.get("forge").getAsString() : "47.1.3";

        status.accept(french ? "Préparation de Forge..." : "Preparing Forge...");
        ensureForge(instance, runtime, mc, forge);

        status.accept(french ? "Installation des mods..." : "Installing mods...");
        syncMods(instance, map, runtime, mc);

        status.accept(french ? "Installation des gunpacks..." : "Installing gunpacks...");
        syncGunpacks(instance, map, mc);

        status.accept(french ? "Installation de la map..." : "Installing map...");
        installMap(instance, mapName, downloadUrl, sha);

        status.accept(french ? "Lancement..." : "Launching...");
        launch(instance, mc, forge, mapName);
    }

    private JsonObject loadRuntime() throws IOException {
        try {
            HttpURLConnection conn = open(RUNTIME_URL + "?t=" + System.currentTimeMillis());
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json != null && json.has("forge_installer")) return json;
            }
        } catch (Exception ignored) {}
        try (InputStream in = InstanceManager.class.getResourceAsStream("/runtime.json")) {
            if (in == null) throw new IOException(french ? "runtime.json introuvable." : "runtime.json is missing.");
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private void ensureForge(File instance, JsonObject runtime, String mc, String forge) throws Exception {
        File marker = new File(instance, "forge.version");
        String want = mc + "-" + forge;
        if (marker.exists() && want.equals(Files.readString(marker.toPath()).trim()) && findVersionJson(instance, mc) != null) {
            return;
        }
        String installerUrl = runtime.get("forge_installer").getAsString();
        File cache = new File(instance.getParentFile(), "cache");
        cache.mkdirs();
        File installer = new File(cache, "forge-" + want + "-installer.jar");
        download(installerUrl, installer);
        File java = javaBin();
        ProcessBuilder pb = new ProcessBuilder(
                java.getAbsolutePath(), "-jar", installer.getAbsolutePath(), "--installClient", instance.getAbsolutePath());
        pb.directory(instance);
        pb.redirectErrorStream(true);
        File log = new File(instance, "forge-install.log");
        pb.redirectOutput(log);
        Process process = pb.start();
        if (!process.waitFor(15, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IOException(french
                    ? "L'installation de Forge a échoué. Voir " + log.getAbsolutePath()
                    : "Forge install failed. See " + log.getAbsolutePath());
        }
        if (findVersionJson(instance, mc) == null) {
            throw new IOException(french ? "Forge est installé mais le json de version est absent." : "Forge installed but the version json is missing.");
        }
        Files.writeString(marker.toPath(), want);
    }

    private void syncMods(File instance, JsonObject map, JsonObject runtime, String mc) throws Exception {
        File mods = new File(instance, "mods");
        mods.mkdirs();
        File cache = new File(instance.getParentFile(), "cache");
        cache.mkdirs();
        Map<String, File> wanted = new LinkedHashMap<>();

        File zr = resolveZombieRool(runtime, cache);
        wanted.put(zr.getName(), zr);

        if (runtime.has("required_mods")) {
            for (JsonElement el : runtime.getAsJsonArray("required_mods")) {
                JsonObject mod = el.getAsJsonObject();
                File file = resolveUrl(mod.get("url").getAsString(), cache, mc, true);
                wanted.put(file.getName(), file);
            }
        }
        if (map.has("mods") && map.get("mods").isJsonArray()) {
            for (JsonElement el : map.getAsJsonArray("mods")) {
                File file = resolveUrl(el.getAsString(), cache, mc, true);
                wanted.put(file.getName(), file);
            }
        }

        File[] existing = mods.listFiles((dir, name) -> name.endsWith(".jar"));
        if (existing != null) {
            for (File jar : existing) {
                if (!wanted.containsKey(jar.getName())) jar.delete();
            }
        }
        for (Map.Entry<String, File> entry : wanted.entrySet()) {
            File dest = new File(mods, entry.getKey());
            if (!dest.exists() || dest.length() != entry.getValue().length()) {
                Files.copy(entry.getValue().toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private File resolveZombieRool(JsonObject runtime, File cache) throws Exception {
        if (runtime.has("zombierool_url") && !runtime.get("zombierool_url").getAsString().isBlank()) {
            return resolveUrl(runtime.get("zombierool_url").getAsString(), cache, "1.20.1", true);
        }
        String repo = runtime.has("zombierool_github") ? runtime.get("zombierool_github").getAsString() : "Cryo60/ZombieRool";
        try {
            HttpURLConnection conn = open("https://api.github.com/repos/" + repo + "/releases/latest");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json != null && json.has("assets")) {
                    for (JsonElement el : json.getAsJsonArray("assets")) {
                        JsonObject asset = el.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.endsWith(".jar") && name.toLowerCase(Locale.ROOT).contains("zombierool")) {
                            return downloadNamed(asset.get("browser_download_url").getAsString(), new File(cache, name));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        File beside = launcherDir();
        if (beside != null) {
            File[] jars = beside.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith("zombierool") && name.endsWith(".jar"));
            if (jars != null && jars.length > 0) return jars[0];
        }
        throw new IOException(french
                ? "Jar ZombieRool introuvable. Renseigne zombierool_url dans runtime.json, ou pose le jar à côté du launcher."
                : "ZombieRool jar not found. Set zombierool_url in runtime.json, or drop the jar next to the launcher.");
    }

    private void syncGunpacks(File instance, JsonObject map, String mc) throws Exception {
        File tacz = new File(instance, "tacz");
        tacz.mkdirs();
        File cache = new File(instance.getParentFile(), "cache");
        cache.mkdirs();
        Set<String> names = new HashSet<>();
        if (map.has("tacz_gunpacks") && map.get("tacz_gunpacks").isJsonArray()) {
            for (JsonElement el : map.getAsJsonArray("tacz_gunpacks")) {
                File file = resolveUrl(el.getAsString(), cache, mc, false);
                names.add(file.getName());
                File dest = new File(tacz, file.getName());
                if (!dest.exists() || dest.length() != file.length()) {
                    Files.copy(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        File[] existing = tacz.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (file.isFile() && !names.contains(file.getName())) file.delete();
            }
        }
    }

    private File resolveUrl(String rawUrl, File cache, String mc, boolean preferJar) throws Exception {
        String url = rawUrl.trim();
        Matcher modrinth = MODRINTH.matcher(url);
        if (modrinth.find()) {
            String slug = modrinth.group(1);
            String version = modrinth.group(2);
            String api = version != null
                    ? "https://api.modrinth.com/v2/version/" + version
                    : "https://api.modrinth.com/v2/project/" + slug + "/version?game_versions=%5B%22" + mc + "%22%5D&loaders=%5B%22forge%22%5D";
            HttpURLConnection conn = open(api);
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement parsed = new Gson().fromJson(reader, JsonElement.class);
                JsonObject chosen;
                if (parsed.isJsonArray()) {
                    JsonArray arr = parsed.getAsJsonArray();
                    if (arr.isEmpty()) {
                        throw new IOException((french ? "Aucune version Forge " + mc + " pour " : "No Forge " + mc + " version for ") + slug);
                    }
                    chosen = arr.get(0).getAsJsonObject();
                } else {
                    chosen = parsed.getAsJsonObject();
                }
                JsonObject file = pickFile(chosen.getAsJsonArray("files"), preferJar);
                return downloadNamed(file.get("url").getAsString(), new File(cache, file.get("filename").getAsString()));
            }
        }

        if (url.contains("curseforge.com") && !url.toLowerCase(Locale.ROOT).endsWith(".jar") && !url.contains("forgecdn.net")) {
            throw new IOException(french
                    ? "Lien CurseForge non direct : " + url + ". Utilise un fichier .jar (edge.forgecdn.net) ou un lien Modrinth."
                    : "CurseForge page URL is not a direct file: " + url + ". Use a .jar link (edge.forgecdn.net) or a Modrinth link.");
        }

        Matcher repo = GITHUB_REPO.matcher(url);
        Matcher asset = GITHUB_ASSET.matcher(url);
        if (!asset.find() && repo.find() && !url.contains("/releases/download/")) {
            String owner = repo.group(1);
            String name = repo.group(2).replace(".git", "");
            HttpURLConnection conn = open("https://api.github.com/repos/" + owner + "/" + name + "/releases/latest");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json == null || !json.has("assets")) {
                    throw new IOException((french ? "Release GitHub vide : " : "Empty GitHub release: ") + url);
                }
                for (JsonElement el : json.getAsJsonArray("assets")) {
                    JsonObject a = el.getAsJsonObject();
                    String filename = a.get("name").getAsString().toLowerCase(Locale.ROOT);
                    if (preferJar ? filename.endsWith(".jar") : (filename.endsWith(".jar") || filename.endsWith(".zip"))) {
                        return downloadNamed(a.get("browser_download_url").getAsString(), new File(cache, a.get("name").getAsString()));
                    }
                }
            }
            throw new IOException((french ? "Aucun fichier sur la release : " : "No release file at ") + url);
        }

        String filename = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        if (filename.isBlank()) filename = "download.bin";
        filename = java.net.URLDecoder.decode(filename, StandardCharsets.UTF_8);
        return downloadNamed(url, new File(cache, filename));
    }

    private JsonObject pickFile(JsonArray files, boolean preferJar) {
        JsonObject fallback = files.get(0).getAsJsonObject();
        JsonObject primary = null;
        for (JsonElement el : files) {
            JsonObject file = el.getAsJsonObject();
            String name = file.get("filename").getAsString().toLowerCase(Locale.ROOT);
            if (file.has("primary") && file.get("primary").getAsBoolean()) primary = file;
            if (preferJar && name.endsWith(".jar")) {
                if (primary != null && primary.get("filename").getAsString().toLowerCase(Locale.ROOT).endsWith(".jar")) return primary;
                return file;
            }
        }
        return primary != null ? primary : fallback;
    }

    private void installMap(File instance, String mapName, String downloadUrl, String sha) throws Exception {
        File saves = new File(instance, "saves");
        saves.mkdirs();
        File world = new File(saves, mapName);
        File stamp = new File(world, ".zr_sha256");
        if (world.exists() && !sha.isBlank() && stamp.exists() && sha.equalsIgnoreCase(Files.readString(stamp.toPath()).trim())) {
            return;
        }
        if (world.exists()) deleteRecursively(world);
        File zip = new File(instance, "map.zip");
        download(downloadUrl, zip);
        if (!sha.isBlank()) {
            String got = sha256(zip);
            if (!sha.equalsIgnoreCase(got)) {
                zip.delete();
                throw new IOException(french ? "sha256 de la map incorrect." : "Map sha256 does not match.");
            }
        }
        installZip(zip, saves, mapName);
        zip.delete();
        if (!sha.isBlank()) Files.writeString(new File(saves, mapName).toPath().resolve(".zr_sha256"), sha);
    }

    private void launch(File instance, String mc, String forge, String worldName) throws Exception {
        File versionJson = findVersionJson(instance, mc);
        if (versionJson == null) throw new IOException(french ? "Version Forge introuvable." : "Forge version json not found.");
        JsonObject version = readJson(versionJson);
        JsonObject parent = null;
        if (version.has("inheritsFrom")) {
            String parentId = version.get("inheritsFrom").getAsString();
            File parentFile = new File(instance, "versions/" + parentId + "/" + parentId + ".json");
            if (!parentFile.exists()) parentFile = downloadVanilla(instance, parentId);
            parent = readJson(parentFile);
        }

        String id = version.get("id").getAsString();
        File libraries = new File(instance, "libraries");
        File natives = new File(instance, "natives");
        natives.mkdirs();
        List<File> classpath = new ArrayList<>();
        collectLibraries(version, libraries, natives, classpath);
        if (parent != null) collectLibraries(parent, libraries, natives, classpath);

        File clientJar = clientJar(instance, parent != null ? parent : version, mc);
        if (clientJar != null) classpath.add(clientJar);

        StringBuilder cp = new StringBuilder();
        for (File file : classpath) {
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(file.getAbsolutePath());
        }

        File assets = assetsDir(instance, parent != null ? parent : version);
        String assetIndex = "5";
        JsonObject assetOwner = parent != null && parent.has("assetIndex") ? parent : version;
        if (assetOwner.has("assetIndex")) assetIndex = assetOwner.getAsJsonObject("assetIndex").get("id").getAsString();

        String player = sanitize(System.getProperty("user.name", "Player"));
        if (player.isBlank()) player = "Player";
        if (player.length() > 16) player = player.substring(0, 16);
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("library_directory", libraries.getAbsolutePath());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("version_name", id);
        vars.put("classpath", cp.toString());
        vars.put("natives_directory", natives.getAbsolutePath());
        vars.put("launcher_name", "ZRLauncher");
        vars.put("launcher_version", "1.5");
        vars.put("auth_player_name", player);
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", "0");
        vars.put("user_type", "legacy");
        vars.put("version_type", "release");
        vars.put("assets_root", assets.getAbsolutePath());
        vars.put("assets_index_name", assetIndex);
        vars.put("game_directory", instance.getAbsolutePath());
        vars.put("clientid", "");
        vars.put("auth_xuid", "");

        List<String> jvm = new ArrayList<>();
        JsonArray childJvm = argsArray(version, "jvm");
        if (childJvm != null && childJvm.size() > 0) collectArgs(childJvm, jvm, vars);
        else if (parent != null) collectArgs(argsArray(parent, "jvm"), jvm, vars);

        List<String> game = new ArrayList<>();
        if (parent != null) collectArgs(argsArray(parent, "game"), game, vars);
        collectArgs(argsArray(version, "game"), game, vars);
        game.add("--quickPlaySingleplayer");
        game.add(worldName);

        String main = version.has("mainClass") ? version.get("mainClass").getAsString()
                : parent != null ? parent.get("mainClass").getAsString() : "net.minecraft.client.main.Main";

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin().getAbsolutePath());
        cmd.addAll(jvm);
        cmd.add(main);
        cmd.addAll(game);

        File log = new File(instance, "game.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(instance);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Process process = pb.start();
        if (process.waitFor(4, TimeUnit.SECONDS)) {
            String tail = tail(log);
            throw new IOException((french ? "Le jeu s'est fermé tout de suite. " : "The game exited immediately. ") + tail);
        }
        status.accept(french ? "Jeu lancé." : "Game launched.");
    }

    private File downloadVanilla(File instance, String versionId) throws IOException {
        HttpURLConnection conn = open("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        JsonObject manifest;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            manifest = new Gson().fromJson(reader, JsonObject.class);
        }
        String url = null;
        for (JsonElement el : manifest.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            if (versionId.equals(v.get("id").getAsString())) {
                url = v.get("url").getAsString();
                break;
            }
        }
        if (url == null) throw new IOException("Unknown Minecraft version " + versionId);
        File dest = new File(instance, "versions/" + versionId + "/" + versionId + ".json");
        dest.getParentFile().mkdirs();
        download(url, dest);
        return dest;
    }

    private File clientJar(File instance, JsonObject version, String mc) throws IOException {
        File jar = new File(instance, "versions/" + mc + "/" + mc + ".jar");
        if (jar.exists() && jar.length() > 0) return jar;
        if (!version.has("downloads")) return jar.exists() ? jar : null;
        JsonObject downloads = version.getAsJsonObject("downloads");
        if (!downloads.has("client")) return null;
        JsonObject client = downloads.getAsJsonObject("client");
        jar.getParentFile().mkdirs();
        download(client.get("url").getAsString(), jar);
        return jar;
    }

    private File assetsDir(File instance, JsonObject version) throws IOException {
        File shared = minecraftDir();
        if (shared != null) {
            File sharedAssets = new File(shared, "assets");
            if (new File(sharedAssets, "indexes").isDirectory()) return sharedAssets;
        }
        File assets = new File(instance, "assets");
        if (!version.has("assetIndex")) return assets;
        JsonObject index = version.getAsJsonObject("assetIndex");
        String id = index.get("id").getAsString();
        File indexFile = new File(assets, "indexes/" + id + ".json");
        if (!indexFile.exists()) {
            indexFile.getParentFile().mkdirs();
            download(index.get("url").getAsString(), indexFile);
        }
        JsonObject objects = readJson(indexFile).getAsJsonObject("objects");
        int n = 0;
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            File obj = new File(assets, "objects/" + hash.substring(0, 2) + "/" + hash);
            if (!obj.exists()) {
                obj.getParentFile().mkdirs();
                download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, obj);
            }
            if (++n % 400 == 0) status.accept((french ? "Assets " : "Assets ") + n + "/" + objects.size());
        }
        return assets;
    }

    private void collectLibraries(JsonObject version, File libraries, File natives, List<File> classpath) throws IOException {
        if (!version.has("libraries")) return;
        for (JsonElement el : version.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!rulesAllow(lib.get("rules"))) continue;
            if (!lib.has("downloads")) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact")) {
                File file = artifactFile(libraries, downloads.getAsJsonObject("artifact"));
                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    download(downloads.getAsJsonObject("artifact").get("url").getAsString(), file);
                }
                classpath.add(file);
            }
            if (downloads.has("classifiers") && lib.has("natives")) {
                JsonObject nativesMap = lib.getAsJsonObject("natives");
                String key = osName();
                if (nativesMap.has(key)) {
                    String classifier = nativesMap.get(key).getAsString().replace("${arch}", arch());
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(classifier)) {
                        JsonObject nat = classifiers.getAsJsonObject(classifier);
                        File file = artifactFile(libraries, nat);
                        if (!file.exists()) {
                            file.getParentFile().mkdirs();
                            download(nat.get("url").getAsString(), file);
                        }
                        unzipNatives(file, natives);
                    }
                }
            }
        }
    }

    private File artifactFile(File libraries, JsonObject artifact) {
        return new File(libraries, artifact.get("path").getAsString());
    }

    private void collectArgs(JsonArray array, List<String> out, Map<String, String> vars) {
        if (array == null) return;
        for (JsonElement el : array) {
            if (el.isJsonPrimitive()) {
                out.add(subst(el.getAsString(), vars));
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (!rulesAllow(obj.get("rules"))) continue;
                JsonElement value = obj.get("value");
                if (value == null) continue;
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) out.add(subst(v.getAsString(), vars));
                } else {
                    out.add(subst(value.getAsString(), vars));
                }
            }
        }
    }

    private JsonArray argsArray(JsonObject version, String side) {
        if (version == null || !version.has("arguments")) return null;
        JsonObject args = version.getAsJsonObject("arguments");
        return args.has(side) ? args.getAsJsonArray(side) : null;
    }

    private boolean rulesAllow(JsonElement rulesEl) {
        if (rulesEl == null || !rulesEl.isJsonArray()) return true;
        boolean allow = false;
        boolean sawAllow = false;
        for (JsonElement el : rulesEl.getAsJsonArray()) {
            JsonObject rule = el.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(osName())) matches = false;
                if (os.has("arch") && !os.get("arch").getAsString().equals(arch())) matches = false;
            }
            if (!matches) continue;
            if ("disallow".equals(action)) return false;
            if ("allow".equals(action)) {
                sawAllow = true;
                allow = true;
            }
        }
        return !sawAllow || allow;
    }

    private String subst(String value, Map<String, String> vars) {
        String out = value;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }

    private File findVersionJson(File instance, String mc) {
        File versions = new File(instance, "versions");
        File[] dirs = versions.listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (dir.getName().startsWith(mc + "-forge")) {
                File json = new File(dir, dir.getName() + ".json");
                if (json.exists()) return json;
            }
        }
        return null;
    }

    private JsonObject readJson(File file) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        }
    }

    private File downloadNamed(String url, File dest) throws IOException {
        if (dest.exists() && dest.length() > 0) return dest;
        download(url, dest);
        return dest;
    }

    private void download(String urlString, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection conn = open(urlString);
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            in.transferTo(out);
        }
        String type = conn.getContentType() == null ? "" : conn.getContentType();
        if (type.contains("text/html")) {
            tmp.delete();
            throw new IOException((french ? "Pas un fichier : " : "Not a file: ") + urlString);
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private HttpURLConnection open(String urlString) throws IOException {
        HttpURLConnection conn;
        int hops = 0;
        while (true) {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.5");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(false);
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400 && hops++ < 8) {
                urlString = conn.getHeaderField("Location");
                continue;
            }
            if (status >= 400) throw new IOException("HTTP " + status + " for " + urlString);
            return conn;
        }
    }

    private void installZip(File zipFile, File savesDir, String mapName) throws IOException {
        File finalMapDir = new File(savesDir, mapName);
        File temp = new File(savesDir, ".zr_tmp_" + System.currentTimeMillis());
        temp.mkdirs();
        try {
            unzip(zipFile, temp);
            File[] root = temp.listFiles();
            if (root == null || root.length == 0) throw new IOException(french ? "Zip de map vide." : "Map zip is empty.");
            if (root.length == 1 && root[0].isDirectory()) {
                Files.move(root[0].toPath(), finalMapDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp.toPath(), finalMapDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteRecursively(temp);
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Zip entry escapes the target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        zis.transferTo(fos);
                    }
                }
            }
        }
    }

    private void unzipNatives(File zip, File dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.contains("META-INF")) continue;
                File out = new File(dest, new File(name).getName());
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    zis.transferTo(fos);
                }
            }
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String tail(File log) {
        try {
            if (!log.exists()) return "";
            List<String> lines = Files.readAllLines(log.toPath());
            int from = Math.max(0, lines.size() - 8);
            return String.join(" ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private File javaBin() {
        String bin = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return new File(System.getProperty("java.home"), "bin" + File.separator + bin);
    }

    private File launcherDir() {
        try {
            String exe = System.getProperty("launch4j.exefile");
            if (exe != null) return new File(exe).getParentFile();
            File code = new File(InstanceManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return code.isDirectory() ? code : code.getParentFile();
        } catch (Exception e) {
            return null;
        }
    }

    private File minecraftDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new File(System.getenv("APPDATA"), ".minecraft");
        if (os.contains("mac")) return new File(System.getProperty("user.home"), "Library/Application Support/minecraft");
        return new File(System.getProperty("user.home"), ".minecraft");
    }

    private String osName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private String arch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return arch.contains("64") ? "64" : "32";
    }

    private String sanitize(String raw) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "map" : cleaned;
    }
}
