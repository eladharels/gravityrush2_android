# uc3: starts Uncharted 3 (Nathan Drake Collection 01.02) with the port and keeps the log.
# Usage (PowerShell, from anywhere):  powershell -ExecutionPolicy Bypass -File D:\Uncharted\uc3_pc\tools\run_uc3.ps1
# The log goes to out\last-run.log (the previous one is kept as out\previous-run.log).
param([string[]]$Extra = @())
$port = Split-Path -Parent $PSScriptRoot
$env:MSYSTEM = 'CLANG64'; $env:CHERE_INVOKING = '1'; $env:MSYS2_ROOT = 'D:/msys64'
$env:GOW3_GAME_DIR = 'D:/Uncharted/CUSA02344/app0'
$env:GOW3_PATCH_DIR = 'D:/Uncharted/CUSA02344/patch'
$env:GOW3_EXECUTABLE = 'big3-ps4_Shipping.elf'
if (Get-Process gow3-probe -ErrorAction SilentlyContinue) {
    Write-Host 'O jogo (gow3-probe.exe) ja esta rodando; feche-o antes.' -ForegroundColor Yellow
    exit 1
}
Set-Location $port
if (Test-Path out\last-run.log) { Move-Item -Force out\last-run.log out\previous-run.log }
Write-Host 'Iniciando o Uncharted 3... (log em out\last-run.log; feche a janela do jogo para terminar)'
$args_line = ($Extra | ForEach-Object { "'$_'" }) -join ' '
& D:\msys64\usr\bin\bash.exe -lc "python run.py $args_line 2>&1 | tee out/last-run.log | grep -E --line-buffered 'STOP|Fault|ErrorDialog|LoadExec|first picture|first fiber'"
Write-Host "Fim. Log completo: $port\out\last-run.log"
