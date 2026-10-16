@echo off
rem Uncharted 3 a 60 FPS. Log em out\last-run.log. / Uncharted 3 at 60 FPS. Log in out\last-run.log.
call "%~dp0config.bat"
powershell -ExecutionPolicy Bypass -File "%~dp0..\tools\run_uc3.ps1" -Set "GOW3_EXECUTABLE=big3-ps4_Shipping.elf,GOW3_COMPUTE_PIPE=1,%UC_OTIMIZACOES%" %*
