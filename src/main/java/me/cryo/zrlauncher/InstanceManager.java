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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.Window;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Isolated Forge instance per map: installs the map's mod list, then launches that instance.
 */
public final class InstanceManager {

    private static final String RUNTIME_URL = "https://raw.githubusercontent.com/Cryo60/ZR-Launcher/main/runtime.json";
    private static final Pattern MODRINTH = Pattern.compile("modrinth\\.com/(?:mod|plugin)/([^/?#]+)(?:/version/([^/?#]+))?");
    private static final Pattern CURSEFORGE = Pattern.compile("curseforge\\.com/minecraft/([^/]+)/([^/?#]+)(?:/files/(\\d+))?");
    private static final Pattern GITHUB_ASSET = Pattern.compile("github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^?#]+)");
    private static final Pattern GITHUB_REPO = Pattern.compile("github\\.com/([^/]+)/([^/#?]+)/?$");

    private static final long MAX_DOWNLOAD = 1024L * 1024L * 1024L;
    private static final long MAX_ZIP_ENTRY = 512L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 20000;
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.modrinth.com", "cdn.modrinth.com",
            "github.com", "api.github.com", "raw.githubusercontent.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com",
            "mediafilez.forgecdn.net", "edge.forgecdn.net", "api.cfwidget.com",
            "maven.minecraftforge.net", "libraries.minecraft.net",
            "piston-meta.mojang.com", "piston-data.mojang.com",
            "launchermeta.mojang.com", "resources.download.minecraft.net");
    private static final String[] OPTION_FILES = {"options.txt", "optionsof.txt", "optionsshaders.txt"};

    public static void assertAllowedUrl(String urlString) throws IOException {
        URL url = new URL(urlString);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("HTTPS required: " + url.getHost());
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new IOException("Host not allowed: " + host);
        }
    }

    private final boolean french;
    private final Consumer<String> status;
    private final Window owner;

    public InstanceManager(boolean french, Consumer<String> status, Window owner) {
        this.french = french;
        this.status = status;
        this.owner = owner;
    }

    public void play(JsonObject map, boolean weaponPacks) throws Exception {
        String mapId = map.get("id").getAsString();
        String mapName = map.get("name").getAsString();
        String downloadUrl = map.get("download_url").getAsString();
        String sha = map.has("sha256") ? map.get("sha256").getAsString() : "";

        MicrosoftAuth.Session session = MicrosoftAuth.ensure(owner, french, status);

        File root = new File(System.getProperty("user.home"), ".zombierool" + File.separator + "instances");
        File instance = new File(root, sanitize(mapId));
        instance.mkdirs();

        JsonObject runtime = loadRuntime();
        String mc = runtime.has("minecraft") ? runtime.get("minecraft").getAsString() : "1.20.1";
        String forge = runtime.has("forge") ? runtime.get("forge").getAsString() : "47.1.3";

        status.accept(french ? "Préparation de Forge..." : "Preparing Forge...");
        ensureForge(instance, runtime, mc, forge);

        status.accept(french ? "Installation des mods..." : "Installing mods...");
        syncMods(instance, map, runtime, mc, weaponPacks);

        if (weaponPacks) {
            status.accept(french ? "Installation des gunpacks..." : "Installing gunpacks...");
            syncGunpacks(instance, map, mc);
        } else {
            clearFiles(new File(instance, "tacz"));
        }

        status.accept(french ? "Installation de la map..." : "Installing map...");
        installMap(instance, mapName, downloadUrl, sha);

        status.accept(french ? "Lancement..." : "Launching...");
        launch(instance, mc, forge, mapName, session);
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
        ensureLauncherProfile(instance, mc);
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

    /** The Forge installer refuses to run until this file exists. */
    private void ensureLauncherProfile(File instance, String mc) throws IOException {
        File profile = new File(instance, "launcher_profiles.json");
        if (profile.exists()) return;
        String json = "{\n"
                + "  \"profiles\": {\n"
                + "    \"zombierool\": {\n"
                + "      \"name\": \"ZombieRool\",\n"
                + "      \"type\": \"custom\",\n"
                + "      \"lastVersionId\": \"" + mc + "\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"selectedProfile\": \"zombierool\"\n"
                + "}\n";
        Files.writeString(profile.toPath(), json);
    }

    private void syncMods(File instance, JsonObject map, JsonObject runtime, String mc, boolean weaponPacks) throws Exception {
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
                String raw = el.getAsString();
                if (!weaponPacks && isWeaponContent(raw)) continue;
                File file = resolveUrl(raw, cache, mc, true);
                if (!weaponPacks && isWeaponContent(file.getName())) continue;
                wanted.put(file.getName(), file);
            }
        }
        addPersonalMods(wanted, weaponPacks);
        List<String> skipped = reconcileMods(wanted, weaponPacks, mc, forgeVersion(runtime), cache);
        if (!skipped.isEmpty()) {
            status.accept((french ? "Mods ignorés : " : "Skipped mods: ") + String.join(", ", skipped));
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

    private static String forgeVersion(JsonObject runtime) {
        return runtime.has("forge") ? runtime.get("forge").getAsString() : "47.1.3";
    }

    private static final Map<String, String> LIBRARY_URLS = Map.of(
            "kotlinforforge", "https://modrinth.com/mod/kotlin-for-forge",
            "yet_another_config_lib_v3", "https://modrinth.com/mod/yacl");

    /** Drops mods that would crash Forge, and downloads libraries those mods require. */
    private List<String> reconcileMods(Map<String, File> wanted, boolean weaponPacks, String mc, String forge, File cache) throws Exception {
        Map<String, ModMeta> metas = new LinkedHashMap<>();
        for (Map.Entry<String, File> entry : wanted.entrySet()) {
            metas.put(entry.getKey(), ModMeta.read(entry.getValue()));
        }
        List<String> skipped = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> ids = new HashSet<>();
            Map<String, String> versions = new HashMap<>();
            versions.put("minecraft", mc);
            versions.put("forge", forge);
            for (ModMeta meta : metas.values()) {
                for (ModOne mod : meta.mods) {
                    ids.add(mod.id);
                    versions.put(mod.id, mod.version);
                }
            }
            String needed = missingLibrary(metas, ids);
            if (needed != null) {
                File lib = resolveUrl(LIBRARY_URLS.get(needed), cache, mc, true);
                if (!wanted.containsKey(lib.getName())) {
                    wanted.put(lib.getName(), lib);
                    metas.put(lib.getName(), ModMeta.read(lib));
                    changed = true;
                    continue;
                }
            }
            String drop = null;
            String reason = null;
            for (Map.Entry<String, ModMeta> entry : metas.entrySet()) {
                reason = rejectMod(entry.getKey(), entry.getValue(), ids, versions, weaponPacks);
                if (reason != null) {
                    drop = entry.getKey();
                    break;
                }
            }
            if (drop != null) {
                metas.remove(drop);
                wanted.remove(drop);
                skipped.add(drop + " (" + reason + ")");
                changed = true;
            }
        }
        return skipped;
    }

    private static String missingLibrary(Map<String, ModMeta> metas, Set<String> ids) {
        for (ModMeta meta : metas.values()) {
            for (Dep dep : meta.deps) {
                if (dep.mandatory && LIBRARY_URLS.containsKey(dep.modId) && !ids.contains(dep.modId)) return dep.modId;
            }
        }
        return null;
    }

    private String rejectMod(String fileName, ModMeta meta, Set<String> ids, Map<String, String> versions, boolean weaponPacks) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("zombierool") || lower.contains("playeranimator") || lower.contains("player-animation")) return null;
        if (meta.fabricOnly) return french ? "mod Fabric" : "Fabric mod";
        if (meta.brokenConstructor) return french ? "mod cassé (pas de constructeur)" : "broken mod (no constructor)";
        boolean weapon = isWeaponContent(fileName) || meta.hasId("tacz") || meta.dependsOn("tacz");
        if (!weaponPacks && weapon) return french ? "TaCZ désactivé" : "TaCZ disabled";
        for (Dep dep : meta.deps) {
            if (!dep.mandatory) continue;
            String have = versions.get(dep.modId);
            if ("forge".equals(dep.modId) || "minecraft".equals(dep.modId)) {
                if (have != null && !inRange(dep.range, have)) {
                    return (french ? "demande " : "requires ") + dep.modId + " " + dep.range;
                }
                continue;
            }
            if (!ids.contains(dep.modId)) return (french ? "il manque " : "missing ") + dep.modId;
            if (have != null && !have.startsWith("${") && !inRange(dep.range, have)) {
                return (french ? "mauvaise version de " : "wrong version of ") + dep.modId;
            }
        }
        return null;
    }

    private static boolean inRange(String range, String version) {
        if (range == null || range.isBlank() || version == null || version.isBlank()) return true;
        String text = range.trim();
        if (text.length() < 3) return true;
        boolean lowInclusive = text.startsWith("[");
        boolean highInclusive = text.endsWith("]");
        String body = text.substring(1, text.length() - 1);
        int comma = body.indexOf(',');
        String low = comma < 0 ? body.trim() : body.substring(0, comma).trim();
        String high = comma < 0 ? "" : body.substring(comma + 1).trim();
        if (!low.isEmpty()) {
            int cmp = compareVersions(version, low);
            if (lowInclusive ? cmp < 0 : cmp <= 0) return false;
        }
        if (!high.isEmpty()) {
            int cmp = compareVersions(version, high);
            if (highInclusive ? cmp > 0 : cmp >= 0) return false;
        }
        return true;
    }

    private static int compareVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] versionParts(String value) {
        String core = value.split("\\+")[0].split("-")[0];
        String[] bits = core.split("\\.");
        int[] parts = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            String digits = bits[i].replaceAll("^(\\d+).*$", "$1");
            parts[i] = digits.matches("\\d+") ? Integer.parseInt(digits) : 0;
        }
        return parts;
    }

    private static final class Dep {
        String modId = "";
        String range = "";
        boolean mandatory = true;
    }

    private static final class ModOne {
        final String id;
        final String version;
        ModOne(String id, String version) { this.id = id; this.version = version; }
    }

    private static final class ModMeta {
        final List<ModOne> mods = new ArrayList<>();
        final List<Dep> deps = new ArrayList<>();
        boolean fabricOnly;
        boolean brokenConstructor;

        boolean hasId(String id) {
            for (ModOne mod : mods) if (id.equals(mod.id)) return true;
            return false;
        }

        boolean dependsOn(String id) {
            for (Dep dep : deps) if (dep.mandatory && id.equals(dep.modId)) return true;
            return false;
        }

        static ModMeta read(File jar) {
            ModMeta meta = new ModMeta();
            try (ZipFile zip = new ZipFile(jar)) {
                boolean fabric = zip.getEntry("fabric.mod.json") != null || zip.getEntry("quilt.mod.json") != null;
                ZipEntry toml = zip.getEntry("META-INF/mods.toml");
                if (toml == null) {
                    meta.fabricOnly = fabric;
                    return meta;
                }
                String text = new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8);
                String section = "";
                Dep current = null;
                String modId = null;
                String version = null;
                for (String raw : text.split("\\R")) {
                    String line = raw.trim();
                    int comment = line.indexOf('#');
                    if (comment >= 0) line = line.substring(0, comment).trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("[[")) {
                        if ("mods".equals(section) && modId != null) meta.mods.add(new ModOne(modId, version == null ? "" : version));
                        modId = null;
                        version = null;
                        if (line.startsWith("[[mods]]")) section = "mods";
                        else if (line.startsWith("[[dependencies")) {
                            section = "dep";
                            current = new Dep();
                            meta.deps.add(current);
                        } else section = "";
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = unquote(line.substring(eq + 1).trim());
                    if ("mods".equals(section) && "modId".equals(key) && modId == null) modId = value;
                    else if ("mods".equals(section) && "version".equals(key) && version == null) version = value;
                    else if ("dep".equals(section) && current != null && "modId".equals(key)) current.modId = value;
                    else if ("dep".equals(section) && current != null && "versionRange".equals(key)) current.range = value;
                    else if ("dep".equals(section) && current != null && "mandatory".equals(key)) current.mandatory = !"false".equalsIgnoreCase(value);
                }
                if ("mods".equals(section) && modId != null) meta.mods.add(new ModOne(modId, version == null ? "" : version));
                if (jar.length() < 20_000_000L) meta.brokenConstructor = ModClassCheck.isBroken(zip);
            } catch (IOException ignored) {}
            return meta;
        }

        private static String unquote(String value) {
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }

    private File resolveZombieRool(JsonObject runtime, File cache) throws Exception {
        if (runtime.has("zombierool_url") && !runtime.get("zombierool_url").getAsString().isBlank()) {
            return resolveUrl(runtime.get("zombierool_url").getAsString(), cache, "1.20.1", true);
        }
        String repo = runtime.has("zombierool_github") ? runtime.get("zombierool_github").getAsString() : "Cryo60/ZombieRool";
        File devDir = launcherDir() == null ? null : new File(launcherDir(), "dev-mods");
        if (devDir != null && devDir.isDirectory()) {
            File[] built = devDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).contains("zombierool") && name.endsWith(".jar") && !name.contains("sources"));
            if (built != null && built.length > 0) return built[0];
        }
        Exception githubError = null;
        try {
            HttpURLConnection conn = open("https://api.github.com/repos/" + repo + "/releases/latest", "application/vnd.github+json");
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
        } catch (Exception e) {
            githubError = e;
        }

        File beside = launcherDir();
        if (beside != null) {
            File[] jars = beside.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith("zombierool") && name.endsWith(".jar"));
            if (jars != null && jars.length > 0) return jars[0];
        }
        throw new IOException((french
                ? "Jar ZombieRool introuvable. Renseigne zombierool_url dans runtime.json, ou pose le jar à côté du launcher."
                : "ZombieRool jar not found. Set zombierool_url in runtime.json, or drop the jar next to the launcher.")
                + (githubError == null || githubError.getMessage() == null ? "" : " (" + githubError.getMessage() + ")"));
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

        Matcher curse = CURSEFORGE.matcher(url);
        if (curse.find()) {
            return resolveCurseForge(curse.group(1), curse.group(2), curse.group(3), cache, mc, preferJar);
        }

        Matcher repo = GITHUB_REPO.matcher(url);
        Matcher asset = GITHUB_ASSET.matcher(url);
        if (!asset.find() && repo.find() && !url.contains("/releases/download/")) {
            String owner = repo.group(1);
            String name = repo.group(2).replace(".git", "");
            HttpURLConnection conn = open("https://api.github.com/repos/" + owner + "/" + name + "/releases/latest", "application/vnd.github+json");
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

        String filename = safeFileName(url.substring(url.lastIndexOf('/') + 1).split("\\?")[0]);
        return downloadNamed(url, new File(cache, filename));
    }

    private File resolveCurseForge(String category, String slug, String fileId, File cache, String mc, boolean preferJar) throws Exception {
        String api = "https://api.cfwidget.com/minecraft/" + category + "/" + slug;
        HttpURLConnection conn = open(api);
        JsonObject project;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            project = new Gson().fromJson(reader, JsonObject.class);
        }
        if (project == null || !project.has("files")) {
            throw new IOException((french ? "Projet CurseForge introuvable : " : "CurseForge project not found: ") + slug);
        }
        JsonObject chosen = null;
        JsonObject fallback = null;
        for (JsonElement el : project.getAsJsonArray("files")) {
            JsonObject file = el.getAsJsonObject();
            String name = file.get("name").getAsString();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean kind = preferJar ? lower.endsWith(".jar") : (lower.endsWith(".jar") || lower.endsWith(".zip"));
            if (!kind) continue;
            if (fileId != null && file.get("id").getAsLong() == Long.parseLong(fileId)) {
                chosen = file;
                break;
            }
            if (fallback == null) fallback = file;
            if (fileId != null) continue;
            boolean versionOk = file.has("version") && file.get("version").isJsonPrimitive()
                    && mc.equals(file.get("version").getAsString());
            if (!versionOk && file.has("versions") && file.get("versions").isJsonArray()) {
                for (JsonElement version : file.getAsJsonArray("versions")) {
                    if (mc.equals(version.getAsString())) versionOk = true;
                }
            }
            boolean release = file.has("type") && "release".equalsIgnoreCase(file.get("type").getAsString());
            if (versionOk && release) {
                chosen = file;
                break;
            }
            if (versionOk && chosen == null) chosen = file;
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) {
            throw new IOException((french ? "Aucun fichier " + mc + " pour " : "No " + mc + " file for ") + slug);
        }
        long id = chosen.get("id").getAsLong();
        String filename = safeFileName(chosen.get("name").getAsString());
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String cdn = "https://mediafilez.forgecdn.net/files/" + (id / 1000) + "/" + (id % 1000) + "/" + encoded;
        return downloadNamed(cdn, new File(cache, filename));
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
        assertMapName(mapName);
        assertAllowedUrl(downloadUrl);
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

    /** Jars dropped in ~/.zombierool/my-mods are copied into every map instance. */
    private int addPersonalMods(Map<String, File> wanted, boolean weaponPacks) {
        File mine = MicrosoftAuth.modsDir().toFile();
        if (!mine.isDirectory()) return 0;
        File[] own = mine.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (own == null) return 0;
        int skipped = 0;
        for (File jar : own) {
            if (!weaponPacks && isWeaponContent(jar.getName())) {
                skipped++;
                continue;
            }
            if (!wanted.containsKey(jar.getName())) wanted.put(jar.getName(), jar);
        }
        return skipped;
    }

    private static boolean isWeaponContent(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("tacz") || s.contains("timeless-and-classics")
                || s.contains("gunpack") || s.contains("gunspack") || s.contains("gun-pack");
    }

    private void clearFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    private File sharedDir() {
        File dir = new File(System.getProperty("user.home"), ".zombierool" + File.separator + "shared");
        dir.mkdirs();
        return dir;
    }

    /** First time, take options.txt from the normal Minecraft folder. Later edits are kept for every map. */
    private void applySharedOptions(File instance) throws IOException {
        File mc = minecraftDir();
        for (String name : OPTION_FILES) {
            File shared = new File(sharedDir(), name);
            File inst = new File(instance, name);
            if (!shared.exists()) {
                File fromMc = mc == null ? null : new File(mc, name);
                if (fromMc != null && fromMc.isFile()) Files.copy(fromMc.toPath(), shared.toPath());
                else if (inst.isFile()) Files.copy(inst.toPath(), shared.toPath());
            }
            if (shared.isFile()) Files.copy(shared.toPath(), inst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveSharedOptions(File instance) {
        for (String name : OPTION_FILES) {
            File inst = new File(instance, name);
            if (!inst.isFile()) continue;
            try {
                Files.copy(inst.toPath(), new File(sharedDir(), name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    private void launch(File instance, String mc, String forge, String worldName, MicrosoftAuth.Session session) throws Exception {
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

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("library_directory", libraries.getAbsolutePath());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("version_name", id);
        vars.put("classpath", cp.toString());
        vars.put("natives_directory", natives.getAbsolutePath());
        vars.put("launcher_name", "ZRLauncher");
        vars.put("launcher_version", "1.6");
        vars.put("auth_player_name", session.name);
        vars.put("auth_uuid", session.uuid);
        vars.put("auth_access_token", session.accessToken);
        vars.put("user_type", "msa");
        vars.put("version_type", "release");
        vars.put("assets_root", assets.getAbsolutePath());
        vars.put("assets_index_name", assetIndex);
        vars.put("game_directory", instance.getAbsolutePath());
        vars.put("clientid", "");
        vars.put("auth_xuid", session.xuid);
        applySharedOptions(instance);

        List<String> jvm = new ArrayList<>();
        if (parent != null) collectArgs(argsArray(parent, "jvm"), jvm, vars);
        collectArgs(argsArray(version, "jvm"), jvm, vars);
        stripOption(jvm, "-cp", "-classpath");

        File cpFile = new File(instance, "classpath.txt");
        StringBuilder listed = new StringBuilder();
        for (File file : classpath) {
            // Forge already loads Minecraft from the slim/srg libraries. The vanilla
            // versions/<mc>/<mc>.jar would also become the automatic module "_1._20._1".
            if (file.getName().equals(mc + ".jar") && file.getParentFile() != null && mc.equals(file.getParentFile().getName())) {
                continue;
            }
            if (listed.length() > 0) listed.append(System.lineSeparator());
            listed.append(file.getAbsolutePath());
        }
        Files.writeString(cpFile.toPath(), listed.toString());
        jvm.add("-DlegacyClassPath.file=" + cpFile.getAbsolutePath());
        for (File file : classpath) {
            if (file.getName().startsWith("bootstraplauncher-")) {
                jvm.add("-cp");
                jvm.add(file.getAbsolutePath());
                break;
            }
        }

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
        Thread copyBack = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            saveSharedOptions(instance);
        });
        copyBack.setDaemon(true);
        copyBack.start();
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
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            File obj = new File(assets, "objects/" + hash.substring(0, 2) + "/" + hash);
            if (!obj.exists() || obj.length() == 0) missing.add(hash);
        }
        if (!missing.isEmpty()) {
            int total = objects.size();
            AtomicInteger done = new AtomicInteger(total - missing.size());
            ExecutorService pool = Executors.newFixedThreadPool(12);
            try {
                List<Future<?>> jobs = new ArrayList<>();
                for (String hash : missing) {
                    jobs.add(pool.submit(() -> {
                        File obj = new File(assets, "objects/" + hash.substring(0, 2) + "/" + hash);
                        obj.getParentFile().mkdirs();
                        download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, obj);
                        int n = done.incrementAndGet();
                        if (n % 100 == 0 || n == total) {
                            status.accept((french ? "Assets " : "Assets ") + n + "/" + total);
                        }
                        return null;
                    }));
                }
                for (Future<?> job : jobs) job.get();
            } catch (Exception e) {
                throw new IOException(french ? "Téléchargement des assets interrompu." : "Asset download failed.", e);
            } finally {
                pool.shutdownNow();
            }
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

    private void stripOption(List<String> args, String... names) {
        Set<String> skip = new HashSet<>();
        for (String name : names) skip.add(name);
        for (int i = 0; i < args.size(); i++) {
            if (!skip.contains(args.get(i))) continue;
            args.remove(i);
            if (i < args.size()) args.remove(i);
            i--;
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
        if (rulesEl == null || !rulesEl.isJsonArray() || rulesEl.getAsJsonArray().isEmpty()) return true;
        Boolean allowed = null;
        for (JsonElement el : rulesEl.getAsJsonArray()) {
            JsonObject rule = el.getAsJsonObject();
            if (!ruleMatches(rule)) continue;
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return Boolean.TRUE.equals(allowed);
    }

    /** Last matching rule wins. A rule list that matches nothing is refused. */
    private boolean ruleMatches(JsonObject rule) {
        if (rule.has("features")) return false;
        if (!rule.has("os")) return true;
        JsonObject os = rule.getAsJsonObject("os");
        if (os.has("name") && !os.get("name").getAsString().equals(osName())) return false;
        if (os.has("arch") && !os.get("arch").getAsString().equals(arch())) return false;
        if (os.has("version") && !System.getProperty("os.version", "").matches(os.get("version").getAsString())) return false;
        return true;
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
        File safe = new File(dest.getParentFile(), safeFileName(dest.getName()));
        if (safe.exists() && safe.length() > 0) return safe;
        download(url, safe);
        return safe;
    }

    private void download(String urlString, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection conn = open(urlString);
        long total = 0;
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD) {
                    throw new IOException(french ? "Fichier trop volumineux." : "File is too large.");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        String type = conn.getContentType() == null ? "" : conn.getContentType();
        if (type.contains("text/html")) {
            tmp.delete();
            throw new IOException((french ? "Pas un fichier : " : "Not a file: ") + urlString);
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private HttpURLConnection open(String urlString) throws IOException {
        return open(urlString, null);
    }

    private HttpURLConnection open(String urlString, String accept) throws IOException {
        HttpURLConnection conn;
        int hops = 0;
        while (true) {
            assertAllowedUrl(urlString);
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.6");
            if (accept != null) conn.setRequestProperty("Accept", accept);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(false);
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400 && hops++ < 8) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isBlank()) throw new IOException("Redirect without location");
                urlString = new URL(url, location).toString();
                continue;
            }
            if (status >= 400) throw new IOException("HTTP " + status + " for " + urlString);
            return conn;
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        int entries = 0;
        long total = 0;
        String destRoot = destDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException(french ? "Archive trop grosse." : "Archive has too many files.");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.contains("..\\")) {
                    throw new IOException("Zip entry escapes the target: " + entry.getName());
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && blockedEntry(lower)) {
                    throw new IOException(french
                            ? "La map contient un fichier interdit : " + name
                            : "The map contains a forbidden file: " + name);
                }
                File out = new File(destDir, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.equals(destRoot) && !canonical.startsWith(destRoot + File.separator)) {
                    throw new IOException("Zip entry escapes the target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    long written = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            written += n;
                            total += n;
                            if (written > MAX_ZIP_ENTRY || total > MAX_DOWNLOAD) {
                                throw new IOException(french ? "Archive trop grosse." : "Archive is too large.");
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                }
            }
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

    private static boolean blockedEntry(String lowerName) {
        return lowerName.endsWith(".jar") || lowerName.endsWith(".exe") || lowerName.endsWith(".bat")
                || lowerName.endsWith(".cmd") || lowerName.endsWith(".ps1") || lowerName.endsWith(".dll")
                || lowerName.endsWith(".scr") || lowerName.endsWith(".com") || lowerName.endsWith(".msi")
                || lowerName.endsWith(".vbs") || lowerName.endsWith(".lnk") || lowerName.endsWith(".js");
    }

    private String safeFileName(String filename) throws IOException {
        filename = filename.replace("+", "%2B");
        try {
            filename = java.net.URLDecoder.decode(filename, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        filename = filename.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) filename = filename.substring(slash + 1);
        filename = filename.replaceAll("[^A-Za-z0-9._ +()\\-\\[\\]]", "_");
        String lower = filename.toLowerCase(Locale.ROOT);
        if (filename.isBlank() || filename.contains("..") || !(lower.endsWith(".jar") || lower.endsWith(".zip"))) {
            throw new IOException(french ? "Nom de fichier refusé." : "File name rejected.");
        }
        return filename;
    }

    private void assertMapName(String mapName) throws IOException {
        if (mapName == null || mapName.isBlank() || mapName.length() > 80
                || mapName.contains("..") || mapName.contains("/") || mapName.contains("\\") || mapName.contains(":")) {
            throw new IOException(french ? "Nom de map invalide." : "Invalid map name.");
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
            File dir = code.isDirectory() ? code : code.getParentFile();
            if ("classes".equals(dir.getName()) && dir.getParentFile() != null && "target".equals(dir.getParentFile().getName())) {
                return dir.getParentFile().getParentFile();
            }
            return dir;
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

    /** Forge crashes if an @Mod class has no public no-arg constructor. */
    private static final class ModClassCheck {
        private static final byte[] MARKER = "fml/common/Mod".getBytes(StandardCharsets.US_ASCII);

        static boolean isBroken(ZipFile zip) {
            var entries = zip.entries();
            int seen = 0;
            while (entries.hasMoreElements() && seen < 4000) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("$") || name.endsWith("module-info.class")) continue;
                if (entry.getSize() > 1_000_000) continue;
                seen++;
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] data = in.readAllBytes();
                    if (!contains(data, MARKER)) continue;
                    if (annotatedWithoutConstructor(data)) return true;
                } catch (Exception ignored) {}
            }
            return false;
        }

        private static boolean contains(byte[] data, byte[] needle) {
            outer:
            for (int i = 0; i <= data.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) continue outer;
                }
                return true;
            }
            return false;
        }

        private static boolean annotatedWithoutConstructor(byte[] data) {
            Reader reader = new Reader(data);
            if (reader.u4() != 0xCAFEBABE) return false;
            reader.u2();
            reader.u2();
            int count = reader.u2();
            String[] utf = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = reader.u1();
                switch (tag) {
                    case 1 -> {
                        int len = reader.u2();
                        utf[i] = new String(data, reader.pos, len, StandardCharsets.UTF_8);
                        reader.pos += len;
                    }
                    case 7, 8, 16, 19, 20 -> reader.pos += 2;
                    case 15 -> reader.pos += 3;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> reader.pos += 4;
                    case 5, 6 -> { reader.pos += 8; i++; }
                    default -> throw new IllegalStateException("tag " + tag);
                }
            }
            reader.u2();
            reader.u2();
            reader.u2();
            int interfaces = reader.u2();
            reader.pos += interfaces * 2;
            int fields = reader.u2();
            for (int i = 0; i < fields; i++) {
                reader.pos += 6;
                reader.skipAttributes();
            }
            boolean publicInit = false;
            int methods = reader.u2();
            for (int i = 0; i < methods; i++) {
                int access = reader.u2();
                int name = reader.u2();
                int desc = reader.u2();
                if ((access & 0x0001) != 0 && "<init>".equals(utf[name]) && "()V".equals(utf[desc])) publicInit = true;
                reader.skipAttributes();
            }
            int attributes = reader.u2();
            for (int i = 0; i < attributes; i++) {
                int name = reader.u2();
                int length = reader.u4();
                int start = reader.pos;
                if ("RuntimeVisibleAnnotations".equals(utf[name]) && mentionsMod(reader, start + length, utf) && !publicInit) {
                    return true;
                }
                reader.pos = start + length;
            }
            return false;
        }

        private static boolean mentionsMod(Reader reader, int end, String[] utf) {
            int annotations = reader.u2();
            for (int i = 0; i < annotations && reader.pos + 4 <= end; i++) {
                int type = reader.u2();
                String text = utf[type];
                if (text != null && text.contains("fml/common/Mod")) return true;
                int pairs = reader.u2();
                for (int p = 0; p < pairs; p++) {
                    reader.u2();
                    skipValue(reader);
                }
            }
            return false;
        }

        private static void skipValue(Reader reader) {
            int tag = reader.u1();
            switch (tag) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> reader.pos += 2;
                case 'e' -> reader.pos += 4;
                case '[' -> {
                    int n = reader.u2();
                    for (int i = 0; i < n; i++) skipValue(reader);
                }
                case '@' -> {
                    reader.u2();
                    int n = reader.u2();
                    for (int i = 0; i < n; i++) {
                        reader.u2();
                        skipValue(reader);
                    }
                }
                default -> {}
            }
        }

        private static final class Reader {
            final byte[] data;
            int pos;
            Reader(byte[] data) { this.data = data; }
            int u1() { return data[pos++] & 0xFF; }
            int u2() { int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF); pos += 2; return v; }
            int u4() { int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF); pos += 4; return v; }
            void skipAttributes() {
                int n = u2();
                for (int i = 0; i < n; i++) {
                    pos += 2;
                    int len = u4();
                    pos += len;
                }
            }
        }
    }
}
