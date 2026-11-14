; FileSync 桌面端安装包（Inno Setup 6）
; 编译：ISCC.exe /DMyAppVersion=1.0.9 FileSync.iss
; 依赖：先用 jpackage --type app-image 生成 desktop\build\jpackage\FileSync

#define MyAppName "FileSync"
#ifndef MyAppVersion
  #define MyAppVersion "1.0.9"
#endif
#define MyAppPublisher "FileSync"
#define MyAppExeName "FileSync.exe"

[Setup]
AppId={{7B1D4C8E-2A6F-4E3B-9C5D-8F0A1B2C3D4E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=E:\Program Files (x86)\FileSync
DisableDirPage=no
DefaultGroupName=FileSync
AllowNoIcons=yes
OutputDir=..\build\inno
OutputBaseFilename=FileSync-{#MyAppVersion}-Setup
SetupIconFile=fileSync.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Files]
Source: "..\build\jpackage\FileSync\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\FileSync"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\FileSync"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "启动 FileSync"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; 卸载时移除开机自启项，避免留下指向已删除 exe 的注册表项
Filename: "{sys}\reg.exe"; Parameters: "delete ""HKCU\Software\Microsoft\Windows\CurrentVersion\Run"" /v FileSync /f"; Flags: runhidden; RunOnceId: "RemoveFileSyncAutoStart"
