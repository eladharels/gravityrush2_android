@echo off
rem Compila o port e gera o zip para os jogadores em dist\. Rode com o jogo FECHADO.
rem Builds the port and the players' zip in dist\. Run with the game CLOSED.
tasklist /fi "imagename eq ucndc.exe" | find /i "ucndc.exe" >nul && (echo Feche o jogo antes. & pause & exit /b 1)
tasklist /fi "imagename eq gow3-probe.exe" | find /i "gow3-probe.exe" >nul && (echo Feche o jogo antes. & pause & exit /b 1)
if not defined MSYS2_ROOT set "MSYS2_ROOT=D:\msys64"
if not exist "%MSYS2_ROOT%\usr\bin\bash.exe" set "MSYS2_ROOT=C:\msys64"
rem Python do Windows para gerar o .exe (py launcher ou python no PATH)
if not defined WINPYTHON for /f "delims=" %%p in ('py -3 -c "import sys;print(sys.executable)" 2^>nul') do set "WINPYTHON=%%p"
if not defined WINPYTHON for /f "delims=" %%p in ('python -c "import sys;print(sys.executable)" 2^>nul') do set "WINPYTHON=%%p"
if not defined WINPYTHON if exist "%LOCALAPPDATA%\Python\bin\python.exe" for /f "delims=" %%p in ('"%LOCALAPPDATA%\Python\bin\python.exe" -c "import sys;print(sys.executable)" 2^>nul') do set "WINPYTHON=%%p"
if not defined WINPYTHON if exist "%LOCALAPPDATA%\Python\bin\python.exe" set "WINPYTHON=%LOCALAPPDATA%\Python\bin\python.exe"
if defined WINPYTHON (echo Python: %WINPYTHON%) else (echo Python do Windows nao encontrado: instale em python.org e rode de novo. & pause & exit /b 1)
cd /d "%~dp0..\.."
set "MSYSTEM=CLANG64"
set "CHERE_INVOKING=1"
if not exist out mkdir out
"%MSYS2_ROOT%\usr\bin\bash.exe" -lc "bash build.sh && bash packaging/windows/package_uc.sh" > out\empacotar.log 2>&1
type out\empacotar.log
echo.
echo Log completo em out\empacotar.log
pause
