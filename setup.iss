[Setup]
AppName=ZombieRool Launcher
AppVersion=1.3
AppPublisher=Cryo
DefaultDirName={localappdata}\Programs\ZombieRool Launcher
DefaultGroupName=ZombieRool
OutputDir=target
OutputBaseFilename=ZRLauncher-Setup
SetupIconFile=icon.ico
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "target\ZRLauncher.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "target\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\ZombieRool Launcher"; Filename: "{app}\ZRLauncher.exe"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\ZombieRool Launcher"; Filename: "{app}\ZRLauncher.exe"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
; L'installeur envoie la langue choisie (ex: "french") au launcher lors du premier lancement !
Filename: "{app}\ZRLauncher.exe"; Parameters: "{language}"; Description: "{cm:LaunchProgram,ZombieRool Launcher}"; Flags: nowait postinstall skipifsilent
