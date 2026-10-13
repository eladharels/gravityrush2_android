# uc3: sends keystrokes to a window of the named process (SendKeys syntax, e.g. "n" or "{ENTER}").
# Used to drive shadPS4 (keyboard: n = Cross, enter = Options, arrows = d-pad) for comparisons.
# -Hold <ms> presses one key for that long instead (the port's pad polls the key state per frame,
# so an instantaneous SendKeys press is missed): key names space, enter, escape, up, down, left,
# right, or a single character. Port keyboard: Space = Cross, Left Shift = Circle, Enter = Options.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\uc3_sendkey.ps1 shadPS4 n
#        powershell -NoProfile -ExecutionPolicy Bypass -File tools\uc3_sendkey.ps1 gow3-probe space -Hold 150
param([Parameter(Mandatory = $true)][string]$ProcessName, [Parameter(Mandatory = $true)][string]$Keys,
      [int]$Hold = 0)
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Uc3Key {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
}
'@
$proc = Get-Process $ProcessName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { Write-Host "no window for $ProcessName"; exit 1 }
[Uc3Key]::ShowWindow($proc.MainWindowHandle, 9) | Out-Null
[Uc3Key]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 150
if ($Hold -gt 0) {
    $names = @{ space = 0x20; enter = 0x0D; escape = 0x1B; up = 0x26; down = 0x28; left = 0x25; right = 0x27; tab = 0x09 }
    $vk = if ($names.ContainsKey($Keys.ToLower())) { $names[$Keys.ToLower()] } else { [int][char]$Keys.ToUpper()[0] }
    $scan = [Uc3Key]::MapVirtualKey($vk, 0)
    [Uc3Key]::keybd_event([byte]$vk, [byte]$scan, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $Hold
    [Uc3Key]::keybd_event([byte]$vk, [byte]$scan, 2, [UIntPtr]::Zero)   # KEYEVENTF_KEYUP
    Write-Host "held '$Keys' for $Hold ms in $ProcessName"
} else {
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
    Write-Host "sent '$Keys' to $ProcessName"
}
