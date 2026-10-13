# uc3: sends keystrokes to a window of the named process (SendKeys syntax, e.g. "n" or "{ENTER}").
# Used to drive shadPS4 (keyboard: n = Cross, enter = Options, arrows = d-pad) for comparisons.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\uc3_sendkey.ps1 shadPS4 n
param([Parameter(Mandatory = $true)][string]$ProcessName, [Parameter(Mandatory = $true)][string]$Keys)
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Uc3Key {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
'@
$proc = Get-Process $ProcessName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { Write-Host "no window for $ProcessName"; exit 1 }
[Uc3Key]::ShowWindow($proc.MainWindowHandle, 9) | Out-Null
[Uc3Key]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 150
[System.Windows.Forms.SendKeys]::SendWait($Keys)
Write-Host "sent '$Keys' to $ProcessName"
