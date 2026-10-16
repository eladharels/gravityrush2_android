@echo off
rem Fecha o jogo; se nao fechar em 10 s, encerra a forca. / Closes the game; forces it after 10 s.
rem ucndc.exe (desde a 0.3) e gow3-probe.exe (builds antigas). / ucndc.exe (since 0.3) and gow3-probe.exe (older builds).
taskkill /im ucndc.exe >nul 2>&1
taskkill /im gow3-probe.exe >nul 2>&1
timeout /t 10 /nobreak >nul
tasklist /fi "imagename eq ucndc.exe" | find /i "ucndc.exe" >nul && taskkill /f /im ucndc.exe
tasklist /fi "imagename eq gow3-probe.exe" | find /i "gow3-probe.exe" >nul && taskkill /f /im gow3-probe.exe
