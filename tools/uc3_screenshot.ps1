# uc3: saves a screenshot of the game window (gow3-probe, or -ProcessName shadPS4) to the given PNG path. The window is
# brought to the front first, so another window on top (the chat, a browser) is not what gets
# captured. Falls back to the whole primary screen when the game is not running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\uc3_screenshot.ps1 out\shot.png
param([Parameter(Mandatory = $true)][string]$Path, [string]$ProcessName = "gow3-probe")
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Uc3Win {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
}
'@
$game = Get-Process $ProcessName -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if ($game) {
    [Uc3Win]::ShowWindow($game.MainWindowHandle, 9) | Out-Null   # SW_RESTORE
    [Uc3Win]::SetForegroundWindow($game.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 400
    $rect = New-Object Uc3Win+RECT
    [Uc3Win]::GetWindowRect($game.MainWindowHandle, [ref]$rect) | Out-Null
    $bounds = New-Object System.Drawing.Rectangle $rect.Left, $rect.Top, ($rect.Right - $rect.Left), ($rect.Bottom - $rect.Top)
} else {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
}
$bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose(); $bitmap.Dispose()
Write-Host "saved $Path ($($bounds.Width)x$($bounds.Height), game window: $([bool]$game))"
