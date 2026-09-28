#!/usr/bin/env bash
# Build the Windows hotkey helper with the .NET Framework compiler that ships with Windows,
# install it under %LOCALAPPDATA%\stt_button, start it at login, and launch it now.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="${STT_MODEL_DIR:-$HOME/models/stt_button}"
WIN=/mnt/c/Windows
CSC="$WIN/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
POWERSHELL="$WIN/System32/WindowsPowerShell/v1.0/powershell.exe"

LOCALAPPDATA_WIN="$("$POWERSHELL" -NoProfile -Command '$env:LOCALAPPDATA' | tr -d '\r')"
DEST_WIN="$LOCALAPPDATA_WIN\\stt_button"
DEST="$(wslpath "$DEST_WIN")"

"$WIN/System32/taskkill.exe" /IM SttHotkey.exe /F >/dev/null 2>&1 || true
mkdir -p "$DEST"
cp "$REPO/desktop/SttHotkey.cs" "$DEST/"
# csc needs Windows paths, so it runs from the destination directory.
(cd "$DEST" && "$CSC" /nologo /target:winexe /out:SttHotkey.exe \
    /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll SttHotkey.cs)

"$POWERSHELL" -NoProfile -Command "
\$link = (New-Object -ComObject WScript.Shell).CreateShortcut(\"\$env:APPDATA\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\STT Hotkey.lnk\")
\$link.TargetPath = '$DEST_WIN\\SttHotkey.exe'
\$link.Arguments = '$REPO $MODELS'
\$link.Save()
Start-Process -FilePath '$DEST_WIN\\SttHotkey.exe' -ArgumentList '$REPO','$MODELS'
"
echo "installed $DEST_WIN\\SttHotkey.exe; Ctrl+Alt+Space toggles, Ctrl+Alt+Shift+Space cancels"
