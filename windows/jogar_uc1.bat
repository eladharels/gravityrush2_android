@echo off
rem Uncharted 1 (experimental). UC_LOW_IMAGE=1 carrega o executavel abaixo de 2 GB (exigido pelo jogo).
rem Uncharted 1 (experimental). UC_LOW_IMAGE=1 loads the executable below 2 GB (required by the game).
call "%~dp0config.bat"
powershell -ExecutionPolicy Bypass -File "%~dp0..\tools\run_uc3.ps1" -Set "UC_EXECUTABLE=eboot.bin,UC_LOW_IMAGE=1,UC_AJM_END_RULES=1,UC_EOP_TICK_WAIT=1,UC_COMPUTE_PIPE=1,%UC_OTIMIZACOES%" %*
