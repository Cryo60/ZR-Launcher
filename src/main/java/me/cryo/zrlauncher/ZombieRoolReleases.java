package me.cryo.zrlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/** Resolves map requirements without replacing an older release with the latest one. */
final class ZombieRoolReleases {
    private ZombieRoolReleases() {}

    static String normalize(String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.equals("unknown") || result.equals("?") || result.equals("latest")) return "";
        return result.replaceFirst("^zombierool-\\d+\\.\\d+(?:\\.\\d+)?-", "")
                .replaceFirst("^v(?=\\d)", "").replaceFirst("\\.jar$", "").replaceAll("\\s+", "-");
    }

    static String requirement(JsonObject map) {
        if (!map.has("zr_version") || map.get("zr_version").isJsonNull()) return "";
        String version = normalize(map.get("zr_version").getAsString());
        if (!version.isEmpty() && !version.matches("\\d+(?:\\.\\d+){1,3}(?:-[a-z0-9]+(?:[.-][a-z0-9]+)*)?"))
            throw new IllegalArgumentException("Invalid ZombieRool version: " + map.get("zr_version").getAsString());
        return version;
    }

    static JsonObject select(JsonArray releases, String requirement) {
        String wanted = normalize(requirement);
        JsonObject beta = null;
        for (JsonElement entry : releases) {
            JsonObject release = entry.getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) continue;
            if (asset(release) == null) continue;
            boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
            String version = normalize(release.get("tag_name").getAsString());
            if (wanted.isEmpty()) {
                if (!prerelease) return release;
            } else if (version.equals(wanted)) {
                return release;
            } else if (beta == null && prerelease && !wanted.contains("-")
                    && version.matches(java.util.regex.Pattern.quote(wanted) + "-(?:beta|alpha|rc)[.-]?\\d*")) {
                // A map explicitly targeting a new version can use its preview while no final exists.
                beta = release;
            }
        }
        return beta;
    }

    static JsonObject asset(JsonObject release) {
        if (!release.has("assets")) return null;
        for (JsonElement entry : release.getAsJsonArray("assets")) {
            JsonObject asset = entry.getAsJsonObject();
            String name = asset.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (name.startsWith("zombierool") && name.endsWith(".jar")
                    && !name.matches(".*-(?:sources|javadoc|slim|dev)\\.jar$")) return asset;
        }
        return null;
    }
}
