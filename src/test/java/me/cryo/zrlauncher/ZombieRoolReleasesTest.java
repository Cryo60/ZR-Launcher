package me.cryo.zrlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ZombieRoolReleasesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File jar(String filename, String version) throws Exception {
        File file = new File(temporary.getRoot(), filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zip.write(("[[mods]]\nmodId=\"zombierool\"\nversion=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
    private JsonObject release(String tag, boolean beta, String... names) {
        JsonObject result = new JsonObject();
        result.addProperty("tag_name", tag); result.addProperty("prerelease", beta);
        JsonArray assets = new JsonArray();
        for (String name : names) {
            JsonObject asset = new JsonObject(); asset.addProperty("name", name);
            asset.addProperty("browser_download_url", "https://github.com/Cryo60/ZombieRool/releases/download/" + tag + "/" + name);
            assets.add(asset);
        }
        result.add("assets", assets); return result;
    }

    private JsonArray catalogue() {
        JsonArray releases = new JsonArray();
        releases.add(release("26.7-beta.1", true, "zombierool-26.7-beta.1.jar"));
        releases.add(release("ZombieRool-1.20.1-1.6.6-hotfix1", false, "ZombieRool-1.20.1-1.6.6-hotfix1.jar"));
        releases.add(release("ZombieRool-1.20.1-1.6.6", false, "ZombieRool-1.20.1-1.6.6.jar"));
        releases.add(release("ZombieRool-1.20.1-1.6.1", false, "ZombieRool-1.20.1-1.6.1.jar"));
        releases.add(release("ZombieRool-1.20.1-1.6.0-stable", false, "ZombieRool-1.20.1-1.6.0-stable.jar"));
        return releases;
    }

    @Test public void oldMapsKeepTheirExactRelease() {
        for (String version : new String[]{"1.6.1", "1.6.6", "1.6.6-hotfix1"})
            assertEquals(version, ZombieRoolReleases.normalize(ZombieRoolReleases.select(catalogue(), version).get("tag_name").getAsString()));
    }
    @Test public void missingVersionUsesStableAndNeverBeta() {
        assertEquals("1.6.6-hotfix1", ZombieRoolReleases.normalize(ZombieRoolReleases.select(catalogue(), "").get("tag_name").getAsString()));
        JsonObject map = new JsonObject(); assertEquals("", ZombieRoolReleases.requirement(map));
        map.addProperty("zr_version", "Unknown"); assertEquals("", ZombieRoolReleases.requirement(map));
    }
    @Test public void newVersionSelectsItsPreviewOnlyWhenNoFinalExists() {
        assertEquals("26.7-beta.1", ZombieRoolReleases.select(catalogue(), "26.7").get("tag_name").getAsString());
        JsonArray releases = catalogue(); releases.add(release("26.7", false, "zombierool-26.7.jar"));
        assertEquals("26.7", ZombieRoolReleases.select(releases, "26.7").get("tag_name").getAsString());
        assertEquals("26.7-beta.1", ZombieRoolReleases.select(releases, "26.7-beta.1").get("tag_name").getAsString());
    }
    @Test public void handlesHistoricalLabelsAndFullTags() {
        assertEquals("1.6.0-stable", ZombieRoolReleases.normalize("1.6.0 stable"));
        assertNotNull(ZombieRoolReleases.select(catalogue(), "1.6.0 stable"));
        assertEquals("1.6.1", ZombieRoolReleases.normalize("ZombieRool-1.20.1-1.6.1.jar"));
        assertEquals("26.7-beta.1", ZombieRoolReleases.normalize("v26.7-beta.1"));
    }
    @Test public void missingReleaseDoesNotSilentlyUseAnotherVersion() {
        assertNull(ZombieRoolReleases.select(catalogue(), "1.6.2"));
        assertNull(ZombieRoolReleases.select(catalogue(), "26.7-beta.2"));
    }
    @Test public void ignoresDraftsAndDevelopmentJars() {
        JsonObject draft = release("1.6.2", false, "zombierool-1.6.2.jar"); draft.addProperty("draft", true);
        JsonArray releases = catalogue(); releases.add(draft);
        assertNull(ZombieRoolReleases.select(releases, "1.6.2"));
        JsonObject release = release("26.7", false, "zombierool-26.7-sources.jar", "zombierool-26.7-slim.jar", "zombierool-26.7.jar");
        assertEquals("zombierool-26.7.jar", ZombieRoolReleases.asset(release).get("name").getAsString());
    }

    @Test public void validatesJarMetadataInsteadOfTrustingTheFilename() throws Exception {
        File misleading = jar("zombierool-1.6.1.jar", "26.7-beta.1");
        assertFalse(InstanceManager.matchesZombieRoolVersion(misleading, "1.6.1"));
        assertFalse(InstanceManager.matchesZombieRoolVersion(misleading, ""));
        assertTrue(InstanceManager.matchesZombieRoolVersion(misleading, "26.7-beta.1"));
        File old = jar("renamed.jar", "1.6.1");
        assertTrue(InstanceManager.matchesZombieRoolVersion(old, "1.6.1"));
        assertFalse(InstanceManager.matchesZombieRoolVersion(old, "1.6.6"));
    }

    @Test public void acceptsTheHistoricalStableSuffixInJarMetadata() throws Exception {
        assertTrue(InstanceManager.matchesZombieRoolVersion(jar("old.jar", "1.6.0"), "1.6.0 stable"));
        assertTrue(InstanceManager.matchesZombieRoolVersion(jar("legacy.jar", "1.20.11.6.0"), "1.6.0 stable"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsUnsupportedRequirementSyntax() {
        JsonObject map = new JsonObject(); map.addProperty("zr_version", ">=1.6.1");
        ZombieRoolReleases.requirement(map);
    }
}
