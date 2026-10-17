# ucndc: CPU por thread do jogo (ucndc.exe) num intervalo, com o nome de cada thread.
# So le contadores do Windows; nao mexe no jogo. Resultado em out\cpu-threads.txt.
param([int]$Seconds = 10, [string]$ProcessName = 'ucndc')
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class UcThreads {
    [DllImport("kernel32.dll")] public static extern IntPtr OpenThread(int access, bool inherit, int id);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll")] public static extern int GetThreadDescription(IntPtr h, out IntPtr text);
    [DllImport("kernel32.dll")] public static extern IntPtr LocalFree(IntPtr p);
    public static string Name(int id) {
        IntPtr h = OpenThread(0x0800, false, id);
        if (h == IntPtr.Zero) return "";
        IntPtr p; string s = "";
        if (GetThreadDescription(h, out p) >= 0 && p != IntPtr.Zero) { s = Marshal.PtrToStringUni(p); LocalFree(p); }
        CloseHandle(h);
        return s;
    }
}
'@
$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'out\cpu-threads.txt'
$proc = Get-Process $ProcessName, gow3-probe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $proc) { "Jogo nao esta aberto." | Tee-Object -Append $out; exit 1 }
$first = @{}
foreach ($t in $proc.Threads) { try { $first[$t.Id] = $t.TotalProcessorTime.TotalMilliseconds } catch {} }
$cpuStart = $proc.TotalProcessorTime.TotalMilliseconds
Start-Sleep -Seconds $Seconds
$proc.Refresh()
$cpuEnd = $proc.TotalProcessorTime.TotalMilliseconds
$rows = foreach ($t in $proc.Threads) {
    try {
        $ms = $t.TotalProcessorTime.TotalMilliseconds - ($(if ($first.ContainsKey($t.Id)) { $first[$t.Id] } else { 0 }))
        [pscustomobject]@{ Nucleos = [math]::Round($ms / ($Seconds * 1000), 2); Id = $t.Id; Nome = [UcThreads]::Name($t.Id); Estado = $t.ThreadState; Espera = $t.WaitReason }
    } catch {}
}
$total = [math]::Round(($cpuEnd - $cpuStart) / ($Seconds * 1000), 2)
& {
    "$(Get-Date)  processo $($proc.Name)  intervalo ${Seconds}s  CPUs logicas $([Environment]::ProcessorCount)"
    "Total: $total nucleos ocupados ($([math]::Round(100 * $total / [Environment]::ProcessorCount))% da CPU)"
    $rows | Where-Object { $_.Nucleos -ge 0.02 } | Sort-Object Nucleos -Descending | Format-Table -AutoSize | Out-String -Width 200
} | Tee-Object -Append $out
