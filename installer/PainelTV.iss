#define MyAppName "Painel TV"
#define MyAppVersion "1.1.5"
#define MyAppPublisher "Painel TV"
#define StageDir "C:\\Users\\Adm\\Desktop\\REMODO MELHOR RDP\\PROGRAMA REMOTO PAINEL\\installer\\stage"

[Setup]
AppId={{4F3D714F-2552-4C5A-AB21-92E55E5F0C31}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\Painel TV
DefaultGroupName=Painel TV
DisableProgramGroupPage=yes
OutputDir=C:\\Users\\Adm\\Desktop\\REMODO MELHOR RDP\\PROGRAMA REMOTO PAINEL\\installer\\output
OutputBaseFilename=Painel-TV-Setup-Profissional-1.1.5
SetupIconFile={#StageDir}\\PainelTV.ico
UninstallDisplayIcon={app}\\PainelTV.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na area de trabalho"; GroupDescription: "Atalhos:"; Flags: checkedonce
Name: "autostart"; Description: "Iniciar Painel TV junto com o Windows"; GroupDescription: "Inicializacao:"; Flags: checkedonce
Name: "launchapp"; Description: "Iniciar Painel TV agora"; GroupDescription: "Finalizacao:"; Flags: checkedonce

[Files]
Source: "{#StageDir}\\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
Name: "{app}\\apps\\server\\data"

[Icons]
Name: "{autoprograms}\\Painel TV"; Filename: "{app}\\PainelTV.exe"; WorkingDir: "{app}"; IconFilename: "{app}\\PainelTV.ico"
Name: "{autodesktop}\\Painel TV"; Filename: "{app}\\PainelTV.exe"; WorkingDir: "{app}"; IconFilename: "{app}\\PainelTV.ico"; Tasks: desktopicon
Name: "{userstartup}\\Painel TV"; Filename: "{app}\\PainelTV.exe"; WorkingDir: "{app}"; IconFilename: "{app}\\PainelTV.ico"; Tasks: autostart

[Run]
Filename: "{app}\\PainelTV.exe"; Description: "Iniciar Painel TV"; Flags: nowait postinstall skipifsilent; Tasks: launchapp

[UninstallRun]
Filename: "taskkill"; Parameters: "/IM PainelTV.exe /F"; Flags: runhidden; RunOnceId: "StopPainelTV"

[Code]
function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  Exec('taskkill', '/IM PainelTV.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := True;
end;
