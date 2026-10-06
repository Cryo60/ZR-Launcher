package me.cryo.zrlauncher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Microsoft account login for a purchased Minecraft: Java Edition copy.
 * Offline names are never used: Play stops if this account does not own the game.
 */
public final class MicrosoftAuth {

    /**
     * Public client id of MultiMC / Prism Launcher, already allow-listed by Minecraft
     * Services. No secret. Replace it once ZombieRool has its own approved id.
     */
    private static final String CLIENT_ID = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";
    private static final String SCOPE = "XboxLive.SignIn XboxLive.offline_access";

    private MicrosoftAuth() {}

    public static final class Session {
        public final String name;
        public final String uuid;
        public final String accessToken;
        public final String xuid;

        Session(String name, String uuid, String accessToken, String xuid) {
            this.name = name;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.xuid = xuid == null ? "" : xuid;
        }
    }

    public static String savedName() {
        try {
            JsonObject saved = readAccount();
            if (saved != null && saved.has("name")) return saved.get("name").getAsString();
        } catch (IOException ignored) {}
        return null;
    }

    public static void logout() {
        try {
            Files.deleteIfExists(accountPath());
        } catch (IOException ignored) {}
    }

    public static Session ensure(Window owner, boolean french, Consumer<String> status) throws Exception {
        JsonObject saved = readAccount();
        if (saved != null && saved.has("refreshToken")) {
            long expires = saved.has("expiresAt") ? saved.get("expiresAt").getAsLong() : 0;
            if (expires - System.currentTimeMillis() > 5 * 60_000L
                    && saved.has("accessToken") && saved.has("uuid") && saved.has("name")) {
                return new Session(
                        saved.get("name").getAsString(),
                        saved.get("uuid").getAsString(),
                        saved.get("accessToken").getAsString(),
                        saved.has("xuid") ? saved.get("xuid").getAsString() : "");
            }
            status.accept(french ? "Reconnexion au compte Minecraft..." : "Refreshing the Minecraft account...");
            try {
                return finish(saved.get("refreshToken").getAsString(), french);
            } catch (Exception refreshFailed) {
                logout();
            }
        }
        return deviceLogin(owner, french, status);
    }

    private static Session deviceLogin(Window owner, boolean french, Consumer<String> status) throws Exception {
        status.accept(french ? "Connexion Microsoft..." : "Microsoft sign-in...");
        JsonObject code = postForm(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE));
        if (code.has("error")) {
            throw new IOException(messageOf(code, french ? "Connexion Microsoft impossible." : "Microsoft sign-in failed."));
        }
        String userCode = code.get("user_code").getAsString();
        String deviceCode = code.get("device_code").getAsString();
        String verify = code.has("verification_uri") ? code.get("verification_uri").getAsString() : "https://www.microsoft.com/link";
        int interval = code.has("interval") ? code.get("interval").getAsInt() : 5;
        long deadline = System.currentTimeMillis() + (code.has("expires_in") ? code.get("expires_in").getAsLong() : 900) * 1000L;

        AtomicBoolean cancelled = new AtomicBoolean();
        JDialog dialog = showCode(owner, french, userCode, verify, cancelled);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(userCode), null);
        } catch (Exception ignored) {}

        try {
            while (System.currentTimeMillis() < deadline) {
                if (cancelled.get()) {
                    throw new IOException(french ? "Connexion annulée." : "Sign-in cancelled.");
                }
                Thread.sleep(interval * 1000L);
                HttpResult polled = request(
                        "POST",
                        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                        "application/x-www-form-urlencoded",
                        "client_id=" + enc(CLIENT_ID)
                                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                                + "&device_code=" + enc(deviceCode),
                        null);
                JsonObject token = parse(polled.body);
                if (token.has("refresh_token")) return finish(token.get("refresh_token").getAsString(), french);
                String err = token.has("error") ? token.get("error").getAsString() : "";
                if ("authorization_pending".equals(err)) continue;
                if ("slow_down".equals(err)) {
                    interval += 5;
                    continue;
                }
                if ("expired_token".equals(err)) break;
                if ("access_denied".equals(err)) {
                    throw new IOException(french ? "Connexion refusée." : "Sign-in was denied.");
                }
                if (!err.isBlank()) throw new IOException(messageOf(token, err));
            }
            throw new IOException(french ? "Le code a expiré. Réessaie." : "The code expired. Try again.");
        } finally {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    private static Session finish(String refreshToken, boolean french) throws Exception {
        JsonObject ms = postForm(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "client_id=" + enc(CLIENT_ID)
                        + "&grant_type=refresh_token&refresh_token=" + enc(refreshToken)
                        + "&scope=" + enc(SCOPE));
        if (!ms.has("access_token")) {
            throw new IOException(messageOf(ms, french ? "Jeton Microsoft refusé." : "Microsoft rejected the token."));
        }
        if (ms.has("refresh_token")) refreshToken = ms.get("refresh_token").getAsString();

        JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate",
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d="
                        + jsonEscape(ms.get("access_token").getAsString())
                        + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
        if (!xbl.has("Token")) throw xboxError(xbl, french);

        JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\""
                        + jsonEscape(xbl.get("Token").getAsString())
                        + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}");
        if (!xsts.has("Token")) throw xboxError(xsts, french);

        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        String xuid = claim(xsts.get("Token").getAsString(), "xid");
        JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + jsonEscape(xsts.get("Token").getAsString()) + "\"}");
        if (!mc.has("access_token")) {
            throw new IOException(french
                    ? "Minecraft a refusé ce compte Microsoft."
                    : "Minecraft rejected this Microsoft account.");
        }
        String access = mc.get("access_token").getAsString();
        long expiresAt = System.currentTimeMillis() + (mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400) * 1000L;

        JsonObject store = getBearer("https://api.minecraftservices.com/entitlements/mcstore", access);
        if (!ownsJava(store)) {
            throw new IOException(french
                    ? "Ce compte Microsoft ne possède pas Minecraft Java. Le jeu ne se lance qu'avec une licence."
                    : "This Microsoft account does not own Minecraft Java. The game only starts with a licence.");
        }
        JsonObject profile = getBearer("https://api.minecraftservices.com/minecraft/profile", access);
        if (!profile.has("name") || !profile.has("id")) {
            throw new IOException(french
                    ? "Ce compte n'a pas encore de pseudo Minecraft. Ouvre le launcher officiel une fois pour le choisir."
                    : "This account has no Minecraft name yet. Open the official launcher once to choose it.");
        }

        JsonObject stored = new JsonObject();
        stored.addProperty("name", profile.get("name").getAsString());
        stored.addProperty("uuid", profile.get("id").getAsString().replace("-", ""));
        stored.addProperty("xuid", xuid);
        stored.addProperty("accessToken", access);
        stored.addProperty("refreshToken", refreshToken);
        stored.addProperty("expiresAt", expiresAt);
        Files.createDirectories(accountPath().getParent());
        Files.writeString(accountPath(), new Gson().toJson(stored));
        return new Session(stored.get("name").getAsString(), stored.get("uuid").getAsString(), access, xuid);
    }

    private static boolean ownsJava(JsonObject store) {
        if (store == null || !store.has("items") || !store.get("items").isJsonArray()) return false;
        for (JsonElement el : store.getAsJsonArray("items")) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("name")) continue;
            String name = el.getAsJsonObject().get("name").getAsString();
            if (name.equals("product_minecraft") || name.equals("game_minecraft")
                    || name.equals("product_game_pass_pc") || name.equals("product_game_pass_ultimate")) {
                return true;
            }
        }
        return false;
    }

    private static IOException xboxError(JsonObject body, boolean french) {
        long code = body.has("XErr") ? body.get("XErr").getAsLong() : 0;
        String msg;
        if (code == 2148916233L) {
            msg = french
                    ? "Ce compte n'a pas de profil Xbox. Connecte-toi une fois sur minecraft.net, puis réessaie."
                    : "This account has no Xbox profile. Sign in once at minecraft.net, then try again.";
        } else if (code == 2148916238L) {
            msg = french
                    ? "Compte enfant : un adulte doit l'ajouter à une famille Xbox."
                    : "Child account: an adult must add it to an Xbox family.";
        } else if (code == 2148916235L) {
            msg = french ? "Xbox Live n'est pas disponible pour ce pays." : "Xbox Live is not available in this country.";
        } else if (code == 2148916227L) {
            msg = french ? "Ce compte Xbox est suspendu." : "This Xbox account is suspended.";
        } else {
            msg = french ? "Connexion Xbox refusée (" + code + ")." : "Xbox sign-in was refused (" + code + ").";
        }
        return new IOException(msg);
    }

    private static JDialog showCode(Window owner, boolean french, String userCode, String verify, AtomicBoolean cancelled) throws Exception {
        JDialog[] box = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog(owner, french ? "Connexion Microsoft" : "Microsoft sign-in");
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout(0, 12));
            root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            JLabel help = new JLabel("<html>" + (french
                    ? "Va sur <b>" + verify + "</b> et entre ce code avec le compte qui possède Minecraft."
                    : "Open <b>" + verify + "</b> and enter this code with the account that owns Minecraft.") + "</html>");
            JTextField codeField = new JTextField(userCode);
            codeField.setEditable(false);
            codeField.setFont(new Font("SansSerif", Font.BOLD, 28));
            codeField.setHorizontalAlignment(JTextField.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton open = new JButton(french ? "Ouvrir la page" : "Open the page");
            open.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(URI.create(verify));
                } catch (Exception ignored) {}
            });
            JButton cancel = new JButton(french ? "Annuler" : "Cancel");
            cancel.addActionListener(e -> {
                cancelled.set(true);
                dialog.dispose();
            });
            buttons.add(open);
            buttons.add(cancel);
            root.add(help, BorderLayout.NORTH);
            root.add(codeField, BorderLayout.CENTER);
            root.add(buttons, BorderLayout.SOUTH);
            dialog.setContentPane(root);
            dialog.pack();
            dialog.setMinimumSize(dialog.getSize());
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
            box[0] = dialog;
        });
        return box[0];
    }

    private static String claim(String jwt, String key) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "";
            byte[] json = Base64.getUrlDecoder().decode(pad(parts[1]));
            JsonObject obj = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has(key)) return obj.get(key).getAsString();
            if (obj.has("xui") && obj.get("xui").isJsonArray()) {
                JsonArray xui = obj.getAsJsonArray("xui");
                if (!xui.isEmpty() && xui.get(0).getAsJsonObject().has(key)) {
                    return xui.get(0).getAsJsonObject().get(key).getAsString();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String pad(String b64) {
        int mod = b64.length() % 4;
        if (mod == 0) return b64;
        return b64 + "====".substring(mod);
    }

    private static JsonObject readAccount() throws IOException {
        Path path = accountPath();
        if (!Files.isRegularFile(path)) return null;
        JsonElement el = JsonParser.parseString(Files.readString(path));
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static Path accountPath() {
        return Path.of(System.getProperty("user.home"), ".zombierool", "account.json");
    }

    public static Path modsDir() {
        return Path.of(System.getProperty("user.home"), ".zombierool", "my-mods");
    }

    private static JsonObject postForm(String url, String body) throws IOException {
        HttpResult result = request("POST", url, "application/x-www-form-urlencoded", body, null);
        JsonObject json = parse(result.body);
        if (result.code >= 400 && !json.has("error") && !json.has("XErr")) {
            throw new IOException("HTTP " + result.code);
        }
        return json;
    }

    private static JsonObject postJson(String url, String body) throws IOException {
        HttpResult result = request("POST", url, "application/json", body, "application/json");
        JsonObject json = parse(result.body);
        if (result.code >= 400 && !json.has("error") && !json.has("XErr") && !json.has("path")) {
            throw new IOException("HTTP " + result.code);
        }
        return json;
    }

    private static JsonObject getBearer(String url, String token) throws IOException {
        HttpResult result = request("GET", url, null, null, "application/json", "Bearer " + token);
        return parse(result.body);
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String messageOf(JsonObject json, String fallback) {
        if (json.has("error_description")) return json.get("error_description").getAsString();
        if (json.has("errorMessage")) return json.get("errorMessage").getAsString();
        if (json.has("error") && json.get("error").isJsonPrimitive()) return json.get("error").getAsString();
        return fallback;
    }

    private static HttpResult request(String method, String url, String contentType, String body, String accept) throws IOException {
        return request(method, url, contentType, body, accept, null);
    }

    private static HttpResult request(String method, String url, String contentType, String body, String accept, String authorization) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.6");
        conn.setRequestProperty("Accept", accept == null ? "application/json" : accept);
        if (authorization != null) conn.setRequestProperty("Authorization", authorization);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = "";
        if (in != null) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
        }
        HttpResult result = new HttpResult();
        result.code = code;
        result.body = text;
        return result;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class HttpResult {
        int code;
        String body;
    }
}
