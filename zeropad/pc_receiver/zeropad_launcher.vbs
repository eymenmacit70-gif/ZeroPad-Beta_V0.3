Option Explicit

Dim fso, shell, baseDir, rootDir, scriptPath, exePath, command

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

baseDir = fso.GetParentFolderName(WScript.ScriptFullName)
rootDir = fso.GetParentFolderName(baseDir)
scriptPath = fso.BuildPath(baseDir, "zeropad_gui.py")

exePath = fso.BuildPath(shell.ExpandEnvironmentStrings("%USERPROFILE%"), ".zeropad-venv311\Scripts\pythonw.exe")
If Not fso.FileExists(exePath) Then
    exePath = fso.BuildPath(shell.ExpandEnvironmentStrings("%USERPROFILE%"), ".zeropad-venv311\Scripts\python.exe")
End If
If Not fso.FileExists(exePath) Then
    exePath = fso.BuildPath(rootDir, ".venv311\Scripts\pythonw.exe")
End If
If Not fso.FileExists(exePath) Then
    exePath = fso.BuildPath(rootDir, ".venv311\Scripts\python.exe")
End If
If Not fso.FileExists(exePath) Then
    exePath = fso.BuildPath(rootDir, ".venv\Scripts\pythonw.exe")
End If
If Not fso.FileExists(exePath) Then
    exePath = fso.BuildPath(rootDir, ".venv\Scripts\python.exe")
End If
If Not fso.FileExists(exePath) Then
    exePath = "pythonw.exe"
End If

shell.CurrentDirectory = rootDir
command = """" & exePath & """ """ & scriptPath & """"
shell.Run command, 0, False
