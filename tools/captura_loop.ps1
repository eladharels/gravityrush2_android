# uc3: saves a picture of the whole primary screen every N seconds into out\capturas (keeps the
# last 30). It never brings a window forward or touches input, so it can run while playing.
param([int]$Seconds = 20)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$dir = Join-Path $PSScriptRoot '..\out\capturas'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
while ($true) {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $small = New-Object System.Drawing.Bitmap $bmp, ([int]($bounds.Width / 2)), ([int]($bounds.Height / 2))
    $small.Save((Join-Path $dir ("tela_" + (Get-Date -Format 'HHmmss') + ".jpg")), [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $small.Dispose(); $g.Dispose(); $bmp.Dispose()
    Get-ChildItem $dir -Filter 'tela_*.jpg' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 30 | Remove-Item -Force
    Start-Sleep -Seconds $Seconds
}
