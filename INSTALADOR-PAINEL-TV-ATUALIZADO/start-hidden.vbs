Set shell = CreateObject("WScript.Shell")
base = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
cmd = "cmd /c cd /d """ & base & """ && npm.cmd start >> ""apps\server\data\server-runtime.log"" 2>>&1"
shell.Run cmd, 0, False
