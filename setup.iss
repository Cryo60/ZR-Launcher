[Setup]
; Informations de base
AppName=ZombieRool Launcher
AppVersion=1.0
AppPublisher=Cryo
; Dossier d'installation par défaut (ex: C:\Program Files\ZombieRool Launcher)
DefaultDirName={autopf}\ZombieRool Launcher
DefaultGroupName=ZombieRool
; Où générer l'installeur final
OutputDir=target
OutputBaseFilename=ZRLauncher-Setup
; Icône de l'installeur
SetupIconFile=icon.ico
; Compression maximale pour réduire la taille du téléchargement
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64

[Languages]
; Support International : L'installeur s'adaptera à la langue de Windows
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
; Case à cocher pour créer un raccourci sur le bureau (cochée par défaut)
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; 1. On inclut le Launcher EXE généré par Launch4j
Source: "target\ZRLauncher.exe"; DestDir: "{app}"; Flags: ignoreversion
; 2. On inclut TOUT le mini-JRE généré par jlink
Source: "target\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs
; 3. On inclut l'icône pour les raccourcis
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; Raccourci dans le menu démarrer
Name: "{autoprograms}\ZombieRool Launcher"; Filename: "{app}\ZRLauncher.exe"; IconFilename: "{app}\icon.ico"
; Raccourci sur le bureau
Name: "{autodesktop}\ZombieRool Launcher"; Filename: "{app}\ZRLauncher.exe"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
; Propose de lancer l'application à la fin de l'installation
Filename: "{app}\ZRLauncher.exe"; Description: "{cm:LaunchProgram,ZombieRool Launcher}"; Flags: nowait postinstall skipifsilent
