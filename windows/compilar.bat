@echo off
rem Compila o port (out\gow3-probe.exe). / Builds the port (out\gow3-probe.exe).
call "%~dp0config.bat"
cd /d "%~dp0.."
set "MSYSTEM=CLANG64"
set "CHERE_INVOKING=1"
"%MSYS2_ROOT%\usr\bin\bash.exe" -lc "git submodule update --init --recursive && bash build.sh"
echo.
pause
