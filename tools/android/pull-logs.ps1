# Pulls the game's logs from the phone over adb: android-game.log, android-game.prev.log and
# profile.txt, which the launcher copies to its external folder (GameActivity.java).
#   tools/android/pull-logs.ps1            # into .\logs-<time>\
#   tools/android/pull-logs.ps1 -Debug_    # the .debug package
param([string]$Out = "logs-$(Get-Date -Format yyyyMMdd-HHmmss)", [switch]$Debug_)

$package = if ($Debug_) { "com.gravityrush2.android.debug" } else { "com.gravityrush2.android" }
$remote = "/sdcard/Android/data/$package/files/logs"

New-Item -ItemType Directory -Force $Out | Out-Null
adb pull "$remote/." $Out
if ($LASTEXITCODE -ne 0) { Write-Error "adb pull $remote failed"; exit 1 }
Get-ChildItem $Out | Format-Table Name, Length, LastWriteTime -AutoSize
