@echo off
rem Fecha o jogo; se nao fechar em 10 s, encerra a forca. / Closes the game; forces it after 10 s.
taskkill /im gow3-probe.exe
timeout /t 10 /nobreak >nul
tasklist /fi "imagename eq gow3-probe.exe" | find /i "gow3-probe.exe" >nul && taskkill /f /im gow3-probe.exe
