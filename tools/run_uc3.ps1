# uc3: starts Uncharted 3 (Nathan Drake Collection 01.02) with the port and keeps the log.
# Usage (PowerShell, from anywhere):
#   powershell -ExecutionPolicy Bypass -File D:\Uncharted\uc3_pc\tools\run_uc3.ps1
#   powershell -ExecutionPolicy Bypass -File D:\Uncharted\uc3_pc\tools\run_uc3.ps1 -Set GOW3_READBACKS=1,GOW3_DRAW_PIPE=0
# -Set takes KEY=VALUE pairs for this run only (GPU options to compare; see docs/uc3/DEVLOG.md).
# The log goes to out\last-run.log (the previous one is kept as out\previous-run.log).
param([string[]]$Set = @(), [string[]]$Extra = @())
$port = Split-Path -Parent $PSScriptRoot
$env:MSYSTEM = 'CLANG64'; $env:CHERE_INVOKING = '1'
# Caminhos: windows\config.bat (ou variaveis de ambiente) tem prioridade; senao, o layout da maquina de desenvolvimento.
if (-not $env:MSYS2_ROOT) { $env:MSYS2_ROOT = 'D:/msys64' }
# Layout antigo: junctions CUSA02344\app0 e \patch. Layout novo: jogo em CUSA02344, atualizacao em CUSA02344-UPDATE.
if ($env:GOW3_GAME_DIR) {
    if (-not $env:GOW3_PATCH_DIR) { $env:GOW3_PATCH_DIR = "$($env:GOW3_GAME_DIR)-UPDATE" }
} elseif (Test-Path 'D:/Uncharted/CUSA02344/app0') {
    $env:GOW3_GAME_DIR = 'D:/Uncharted/CUSA02344/app0'
    $env:GOW3_PATCH_DIR = 'D:/Uncharted/CUSA02344/patch'
} else {
    $env:GOW3_GAME_DIR = 'D:/Uncharted/CUSA02344'
    $env:GOW3_PATCH_DIR = 'D:/Uncharted/CUSA02344-UPDATE'
}
$env:GOW3_EXECUTABLE = 'big3-ps4_Shipping.elf'
# Leituras precisas da GPU (readbacks_mode 2 do shadPS4): o modo em que o cenario aparece.
# -Set GOW3_READBACKS=1 volta ao modo relaxado (padrao do port do GoW3).
if (-not $env:GOW3_READBACKS) { $env:GOW3_READBACKS = '2' }
# With -File, "-Set A=1,B=2" arrives as one string: split it here.
foreach ($pair in ($Set | ForEach-Object { $_ -split ',' } | Where-Object { $_ })) {
    $key, $value = $pair -split '=', 2
    if (-not $key -or $null -eq $value) { Write-Host "Ignorado (use CHAVE=VALOR): $pair" -ForegroundColor Yellow; continue }
    Set-Item -Path "env:$key" -Value $value
    Write-Host "Opcao: $key=$value"
}
if (Get-Process gow3-probe -ErrorAction SilentlyContinue) {
    Write-Host 'O jogo (gow3-probe.exe) ja esta rodando; feche-o antes.' -ForegroundColor Yellow
    exit 1
}
Set-Location $port
if (Test-Path out\last-run.log) { Move-Item -Force out\last-run.log out\previous-run.log }
Write-Host 'Iniciando o Uncharted 3... (log em out\last-run.log; feche a janela do jogo para terminar)'
$args_line = ($Extra | ForEach-Object { "'$_'" }) -join ' '
& (Join-Path $env:MSYS2_ROOT 'usr\bin\bash.exe') -lc "python run.py $args_line 2>&1 | tee out/last-run.log | grep -E --line-buffered 'STOP|Fault|ASSERTION|ErrorDialog|LoadExec|first picture|first fiber'"
Write-Host "Fim. Log completo: $port\out\last-run.log"
