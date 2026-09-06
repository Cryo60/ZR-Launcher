package me.cryo.zrlauncher;

import com.formdev.flatlaf.FlatDarkLaf;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZRLauncher extends JFrame {

    private static final String CURRENT_VERSION = "1.1";
    
    // URLs
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/Cryo60/zombierool-maps/main/launcher_version.json";
    private static final String OFFICIAL_JSON_URL = "https://raw.githubusercontent.com/Cryo60/zombierool-maps/main/maps.json";
    private static final String COMMUNITY_JSON_URL = "https://raw.githubusercontent.com/Cryo60/zombierool-community-hub/main/maps.json";
    private static final String FEATURED_JSON_URL = "https://raw.githubusercontent.com/Cryo60/zombierool-maps/main/featured.json";

    private boolean isFrench = false;
    private final Map<String, String> langEN = new HashMap<>();
    private final Map<String, String> langFR = new HashMap<>();

    private JPanel mainContentPanel;
    private JComboBox<String> langSelector;
    private JButton btnOfficial, btnCommunity;
    private JLabel lblStatus;
    private JProgressBar globalProgressBar;
    
    private JTextField txtInstallPath;
    private JLabel lblPath;
    private JButton btnBrowse;

    private boolean showingOfficial = true;
    private JsonObject featuredData = null;
    private final Map<String, Image> imageCache = new HashMap<>();

    // Couleurs du thème
    private final Color COLOR_BG = new Color(30, 33, 36);
    private final Color COLOR_CARD = new Color(43, 47, 51);
    private final Color COLOR_ACCENT = new Color(234, 179, 8); // Doré
    private final Color COLOR_GREEN = new Color(46, 160, 67);
    private final Color COLOR_BLUE = new Color(88, 166, 255);

    public ZRLauncher() {
        initTranslations();
        setupUI();
        checkForUpdates();
    }

    private void initTranslations() {
        langEN.put("title", "ZombieRool Launcher v" + CURRENT_VERSION);
        langEN.put("official", "Official Maps");
        langEN.put("community", "Community Maps");
        langEN.put("install", "Install");
        langEN.put("installed", "Installed");
        langEN.put("downloading", "Downloading...");
        langEN.put("extracting", "Extracting...");
        langEN.put("done", "Ready.");
        langEN.put("error", "Error: ");
        langEN.put("loading", "Loading maps...");
        langEN.put("featured", "⭐ FEATURED MAP");
        langEN.put("downloads", "Downloads: ");
        langEN.put("update_avail", "A new version of the launcher is available!");
        langEN.put("update_btn", "Update Now");
        langEN.put("path", "Install Path:");
        langEN.put("browse", "Browse...");

        langFR.put("title", "ZombieRool Launcher v" + CURRENT_VERSION);
        langFR.put("official", "Maps Officielles");
        langFR.put("community", "Maps Communautaires");
        langFR.put("install", "Installer");
        langFR.put("installed", "Installé");
        langFR.put("downloading", "Téléchargement...");
        langFR.put("extracting", "Extraction...");
        langFR.put("done", "Prêt.");
        langFR.put("error", "Erreur : ");
        langFR.put("loading", "Chargement des maps...");
        langFR.put("featured", "⭐ MAP À LA UNE");
        langFR.put("downloads", "Téléchargements : ");
        langFR.put("update_avail", "Une nouvelle version du launcher est disponible !");
        langFR.put("update_btn", "Mettre à jour");
        langFR.put("path", "Dossier d'installation :");
        langFR.put("browse", "Parcourir...");
    }

    private String t(String key) {
        return isFrench ? langFR.get(key) : langEN.get(key);
    }

    private void setupUI() {
        setTitle(t("title"));
        setSize(1000, 750);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(COLOR_BG);

        // --- HEADER (Titre + Langue) ---
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(COLOR_BG);
        headerPanel.setBorder(new EmptyBorder(20, 25, 10, 25));

        JLabel lblMainTitle = new JLabel("ZOMBIEROOL");
        lblMainTitle.setFont(new Font("SansSerif", Font.BOLD, 32));
        lblMainTitle.setForeground(COLOR_ACCENT);
        
        langSelector = new JComboBox<>(new String[]{"English", "Français"});
        langSelector.setPreferredSize(new Dimension(100, 30));
        langSelector.addActionListener(e -> {
            isFrench = langSelector.getSelectedIndex() == 1;
            updateTexts();
        });

        headerPanel.add(lblMainTitle, BorderLayout.WEST);
        headerPanel.add(langSelector, BorderLayout.EAST);

        // --- TABS (Onglets) ---
        JPanel tabsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        tabsPanel.setBackground(COLOR_BG);
        tabsPanel.setBorder(new EmptyBorder(0, 20, 15, 20));

        btnOfficial = createTabButton(t("official"));
        btnCommunity = createTabButton(t("community"));
        
        btnOfficial.addActionListener(e -> {
            showingOfficial = true;
            updateTabStyles();
            loadMaps(OFFICIAL_JSON_URL);
        });
        
        btnCommunity.addActionListener(e -> {
            showingOfficial = false;
            updateTabStyles();
            loadMaps(COMMUNITY_JSON_URL);
        });

        tabsPanel.add(btnOfficial);
        tabsPanel.add(btnCommunity);
        updateTabStyles();

        // Conteneur Haut
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setBackground(COLOR_BG);
        topContainer.add(headerPanel, BorderLayout.NORTH);
        topContainer.add(tabsPanel, BorderLayout.SOUTH);
        add(topContainer, BorderLayout.NORTH);

        // --- MAPS LIST ---
        mainContentPanel = new JPanel();
        mainContentPanel.setLayout(new BoxLayout(mainContentPanel, BoxLayout.Y_AXIS));
        mainContentPanel.setBackground(COLOR_BG);
        // FIX SCROLL : On ajoute un gros padding en bas (40px) pour ne pas couper la dernière map
        mainContentPanel.setBorder(new EmptyBorder(10, 25, 40, 25));
        
        JScrollPane scrollPane = new JScrollPane(mainContentPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setBorder(null);
        scrollPane.setBackground(COLOR_BG);
        scrollPane.getViewport().setBackground(COLOR_BG);
        add(scrollPane, BorderLayout.CENTER);

        // --- BOTTOM BAR (Path + Status) ---
        JPanel bottomContainer = new JPanel(new BorderLayout(0, 15));
        bottomContainer.setBackground(new Color(25, 27, 30));
        bottomContainer.setBorder(new EmptyBorder(15, 25, 15, 25));

        // Path Selector
        JPanel pathPanel = new JPanel(new BorderLayout(10, 0));
        pathPanel.setOpaque(false);
        lblPath = new JLabel(t("path"));
        lblPath.setForeground(new Color(180, 180, 180));
        
        txtInstallPath = new JTextField(getMinecraftSavesDir().getAbsolutePath());
        txtInstallPath.setEditable(false);
        txtInstallPath.setBackground(new Color(40, 44, 48));
        txtInstallPath.setForeground(Color.WHITE);
        txtInstallPath.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 60)),
                new EmptyBorder(5, 10, 5, 10)
        ));
        
        btnBrowse = new JButton(t("browse"));
        btnBrowse.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnBrowse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(txtInstallPath.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                txtInstallPath.setText(chooser.getSelectedFile().getAbsolutePath());
                loadMaps(showingOfficial ? OFFICIAL_JSON_URL : COMMUNITY_JSON_URL);
            }
        });

        pathPanel.add(lblPath, BorderLayout.WEST);
        pathPanel.add(txtInstallPath, BorderLayout.CENTER);
        pathPanel.add(btnBrowse, BorderLayout.EAST);
        bottomContainer.add(pathPanel, BorderLayout.NORTH);

        // Status & Progress
        JPanel statusPanel = new JPanel(new BorderLayout(15, 0));
        statusPanel.setOpaque(false);
        lblStatus = new JLabel(t("done"));
        lblStatus.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblStatus.setForeground(new Color(200, 200, 200));
        
        globalProgressBar = new JProgressBar(0, 100);
        globalProgressBar.setStringPainted(true);
        globalProgressBar.setVisible(false);
        globalProgressBar.setPreferredSize(new Dimension(100, 22));

        statusPanel.add(lblStatus, BorderLayout.WEST);
        statusPanel.add(globalProgressBar, BorderLayout.CENTER);
        bottomContainer.add(statusPanel, BorderLayout.SOUTH);

        add(bottomContainer, BorderLayout.SOUTH);
    }

    private JButton createTabButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setPreferredSize(new Dimension(180, 40));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        return btn;
    }

    private void updateTabStyles() {
        btnOfficial.setBackground(showingOfficial ? COLOR_BLUE : COLOR_CARD);
        btnOfficial.setForeground(showingOfficial ? Color.WHITE : new Color(180, 180, 180));
        
        btnCommunity.setBackground(!showingOfficial ? COLOR_BLUE : COLOR_CARD);
        btnCommunity.setForeground(!showingOfficial ? Color.WHITE : new Color(180, 180, 180));
    }

    private void updateTexts() {
        setTitle(t("title"));
        btnOfficial.setText(t("official"));
        btnCommunity.setText(t("community"));
        lblPath.setText(t("path"));
        btnBrowse.setText(t("browse"));
        loadMaps(showingOfficial ? OFFICIAL_JSON_URL : COMMUNITY_JSON_URL);
    }

    // ==========================================
    // AUTO-UPDATE SYSTEM
    // ==========================================
    private void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_JSON_URL + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                reader.close();

                String latestVersion = json.get("version").getAsString();
                String downloadUrl = json.get("download_url").getAsString();

                if (!latestVersion.equals(CURRENT_VERSION)) {
                    SwingUtilities.invokeLater(() -> showUpdateDialog(latestVersion, downloadUrl));
                } else {
                    fetchFeaturedAndLoad();
                }
            } catch (Exception e) {
                fetchFeaturedAndLoad();
            }
        }).start();
    }

    private void showUpdateDialog(String newVersion, String downloadUrl) {
        int response = JOptionPane.showConfirmDialog(this,
                t("update_avail") + "\nVersion: " + newVersion,
                isFrench ? "Mise à jour" : "Update",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);

        if (response == JOptionPane.YES_OPTION) {
            performUpdate(downloadUrl);
        } else {
            fetchFeaturedAndLoad();
        }
    }

    private void performUpdate(String downloadUrl) {
        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText("Downloading Update...");
                    globalProgressBar.setVisible(true);
                    globalProgressBar.setValue(0);
                });

                File currentExe = new File(ZRLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                String exeName = currentExe.getName();
                if (!exeName.endsWith(".exe")) exeName = "ZRLauncher.exe";

                File newExe = new File(currentExe.getParentFile(), "ZRLauncher_new.exe");

                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                int fileSize = conn.getContentLength();
                
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(newExe)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalRead = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        int percent = (int) ((totalRead * 100L) / fileSize);
                        SwingUtilities.invokeLater(() -> globalProgressBar.setValue(percent));
                    }
                }

                File batFile = new File(currentExe.getParentFile(), "update.bat");
                try (PrintWriter writer = new PrintWriter(batFile)) {
                    writer.println("@echo off");
                    writer.println("timeout /t 2 /nobreak > NUL");
                    writer.println("del /f /q \"" + exeName + "\"");
                    writer.println("ren \"ZRLauncher_new.exe\" \"" + exeName + "\"");
                    writer.println("start \"\" \"" + exeName + "\"");
                    writer.println("del \"%~f0\"");
                }

                Runtime.getRuntime().exec("cmd /c start \"\" \"" + batFile.getAbsolutePath() + "\"");
                System.exit(0);

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(t("error") + " Update failed.");
                    globalProgressBar.setVisible(false);
                    fetchFeaturedAndLoad();
                });
            }
        }).start();
    }

    // ==========================================
    // MAPS LOADING SYSTEM
    // ==========================================
    private void fetchFeaturedAndLoad() {
        new Thread(() -> {
            try {
                URL url = new URL(FEATURED_JSON_URL + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
                featuredData = new Gson().fromJson(reader, JsonObject.class);
                reader.close();
            } catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> loadMaps(OFFICIAL_JSON_URL));
        }).start();
    }

    private void loadMaps(String jsonUrl) {
        mainContentPanel.removeAll();
        JLabel loadingLabel = new JLabel(t("loading"));
        loadingLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        loadingLabel.setForeground(Color.WHITE);
        mainContentPanel.add(loadingLabel);
        mainContentPanel.revalidate();
        mainContentPanel.repaint();

        new Thread(() -> {
            try {
                URL url = new URL(jsonUrl + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                reader.close();

                JsonArray mapsArray = json.getAsJsonArray("maps");
                String featuredId = "";
                if (featuredData != null) {
                    String key = showingOfficial ? "official" : "community";
                    if (featuredData.has(key)) featuredId = featuredData.get(key).getAsString();
                }

                final String finalFeaturedId = featuredId;

                SwingUtilities.invokeLater(() -> {
                    mainContentPanel.removeAll();
                    
                    for (JsonElement elem : mapsArray) {
                        JsonObject mapObj = elem.getAsJsonObject();
                        if (mapObj.get("id").getAsString().equals(finalFeaturedId)) {
                            mainContentPanel.add(createMapCard(mapObj, true));
                            mainContentPanel.add(Box.createVerticalStrut(15));
                            break;
                        }
                    }

                    for (JsonElement elem : mapsArray) {
                        JsonObject mapObj = elem.getAsJsonObject();
                        if (!mapObj.get("id").getAsString().equals(finalFeaturedId)) {
                            mainContentPanel.add(createMapCard(mapObj, false));
                            mainContentPanel.add(Box.createVerticalStrut(15));
                        }
                    }
                    
                    // FIX SCROLL : Ajout d'un espace vide à la toute fin pour être sûr que rien n'est coupé
                    mainContentPanel.add(Box.createVerticalStrut(20));
                    
                    mainContentPanel.revalidate();
                    mainContentPanel.repaint();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    mainContentPanel.removeAll();
                    JLabel errLabel = new JLabel(t("error") + e.getMessage());
                    errLabel.setForeground(new Color(255, 85, 85));
                    mainContentPanel.add(errLabel);
                    mainContentPanel.revalidate();
                    mainContentPanel.repaint();
                });
            }
        }).start();
    }

    private JPanel createMapCard(JsonObject mapData, boolean isFeatured) {
        String id = mapData.get("id").getAsString();
        String name = mapData.get("name").getAsString();
        String desc = mapData.has("description") ? mapData.get("description").getAsString() : "";
        String author = mapData.has("author") ? mapData.get("author").getAsString() : "Cryyoons";
        String downloadUrl = mapData.get("download_url").getAsString();
        String imageUrl = mapData.has("image_url") ? mapData.get("image_url").getAsString() : "";
        int downloads = mapData.has("downloads") ? mapData.get("downloads").getAsInt() : 0;

        JPanel card = new JPanel(new BorderLayout(20, 0));
        card.setBackground(COLOR_CARD);
        
        // Bordure d'accentuation sur la gauche (Dorée ou Grise)
        Color leftBorderColor = isFeatured ? COLOR_ACCENT : new Color(80, 85, 90);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 5, 0, 0, leftBorderColor),
                new EmptyBorder(15, 15, 15, 20)
        ));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));

        // --- IMAGE ---
        JLabel lblImage = new JLabel();
        lblImage.setPreferredSize(new Dimension(220, 124));
        lblImage.setOpaque(true);
        lblImage.setBackground(new Color(20, 22, 25));
        lblImage.setHorizontalAlignment(SwingConstants.CENTER);
        lblImage.setText("No Image");
        lblImage.setForeground(new Color(100, 100, 100));
        card.add(lblImage, BorderLayout.WEST);

        if (!imageUrl.isEmpty()) {
            if (imageCache.containsKey(id)) {
                lblImage.setText("");
                lblImage.setIcon(new ImageIcon(imageCache.get(id)));
            } else {
                new Thread(() -> {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
                        conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                        BufferedImage img = ImageIO.read(conn.getInputStream());
                        Image scaledImg = img.getScaledInstance(220, 124, Image.SCALE_SMOOTH);
                        imageCache.put(id, scaledImg);
                        SwingUtilities.invokeLater(() -> {
                            lblImage.setText("");
                            lblImage.setIcon(new ImageIcon(scaledImg));
                        });
                    } catch (Exception ignored) {}
                }).start();
            }
        }

        // --- INFOS ---
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);
        
        if (isFeatured) {
            JLabel lblFeatured = new JLabel(t("featured"));
            lblFeatured.setForeground(COLOR_ACCENT);
            lblFeatured.setFont(new Font("SansSerif", Font.BOLD, 12));
            infoPanel.add(lblFeatured);
            infoPanel.add(Box.createVerticalStrut(3));
        }

        JLabel lblName = new JLabel("<html><span style='font-size:18px; font-weight:bold; color:white;'>" + name + "</span> <span style='font-size:13px; color:#aaaaaa'>by " + author + "</span></html>");
        JLabel lblDesc = new JLabel("<html><p style='width:400px; font-size:13px; color:#cccccc; margin-top:5px;'>" + desc.replace("\n", "<br>") + "</p></html>");
        JLabel lblStats = new JLabel("<html><span style='font-size:12px; color:#888888'>" + t("downloads") + downloads + "</span></html>");
        
        infoPanel.add(lblName);
        infoPanel.add(lblDesc);
        infoPanel.add(Box.createVerticalGlue());
        infoPanel.add(lblStats);

        card.add(infoPanel, BorderLayout.CENTER);

        // --- BOUTON INSTALL ---
        JPanel actionPanel = new JPanel(new GridBagLayout());
        actionPanel.setOpaque(false);
        
        JButton btnInstall = new JButton(t("install"));
        btnInstall.setPreferredSize(new Dimension(140, 45));
        btnInstall.setFont(new Font("SansSerif", Font.BOLD, 15));
        btnInstall.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnInstall.setFocusPainted(false);
        
        File savesDir = new File(txtInstallPath.getText());
        File mapDir = new File(savesDir, name); 
        
        if (mapDir.exists()) {
            btnInstall.setText(t("installed"));
            btnInstall.setBackground(new Color(70, 75, 80));
            btnInstall.setForeground(new Color(150, 150, 150));
            btnInstall.setEnabled(false);
        } else {
            btnInstall.setBackground(COLOR_GREEN);
            btnInstall.setForeground(Color.WHITE);
        }

        btnInstall.addActionListener(e -> {
            btnInstall.setEnabled(false);
            btnInstall.setBackground(new Color(70, 75, 80));
            btnInstall.setForeground(new Color(150, 150, 150));
            downloadAndInstallMap(name, downloadUrl, btnInstall);
        });

        actionPanel.add(btnInstall);
        card.add(actionPanel, BorderLayout.EAST);

        return card;
    }

    private void downloadAndInstallMap(String mapName, String downloadUrl, JButton btn) {
        new Thread(() -> {
            try {
                File savesDir = new File(txtInstallPath.getText());
                if (!savesDir.exists()) savesDir.mkdirs();

                File tempZip = new File(savesDir, "temp_zr_map.zip");

                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(t("downloading") + " " + mapName);
                    lblStatus.setForeground(COLOR_BLUE);
                    globalProgressBar.setVisible(true);
                    globalProgressBar.setValue(0);
                });
                
                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setRequestProperty("User-Agent", "ZombieRool-Launcher/1.0");
                int fileSize = conn.getContentLength();
                
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempZip)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalRead = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        if (fileSize > 0) {
                            int percent = (int) ((totalRead * 100L) / fileSize);
                            SwingUtilities.invokeLater(() -> globalProgressBar.setValue(percent));
                        }
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(t("extracting") + " " + mapName);
                    globalProgressBar.setIndeterminate(true);
                });
                
                unzip(tempZip, savesDir);
                tempZip.delete();

                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(t("done"));
                    lblStatus.setForeground(COLOR_GREEN);
                    globalProgressBar.setIndeterminate(false);
                    globalProgressBar.setVisible(false);
                    btn.setText(t("installed"));
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(t("error") + e.getMessage());
                    lblStatus.setForeground(new Color(255, 85, 85));
                    globalProgressBar.setVisible(false);
                    btn.setEnabled(true);
                    btn.setBackground(COLOR_GREEN);
                    btn.setForeground(Color.WHITE);
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(destDir, zipEntry.getName());
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
                }
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    private File getMinecraftSavesDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");
        File mcDir;
        if (os.contains("win")) {
            mcDir = new File(System.getenv("APPDATA"), ".minecraft");
        } else if (os.contains("mac")) {
            mcDir = new File(home, "Library/Application Support/minecraft");
        } else {
            mcDir = new File(home, ".minecraft");
        }
        return new File(mcDir, "saves");
    }

    public static void main(String[] args) {
        try {
            // Configuration de FlatLaf pour un look très moderne
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("TabbedPane.showTabSeparators", true);
            
            FlatDarkLaf.setup();
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF");
        }

        SwingUtilities.invokeLater(() -> {
            new ZRLauncher().setVisible(true);
        });
    }
}
