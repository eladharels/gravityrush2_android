@echo off
rem Uncharted 1 (experimental). GOW3_LOW_IMAGE=1 carrega o executavel abaixo de 2 GB (exigido pelo jogo).
rem Uncharted 1 (experimental). GOW3_LOW_IMAGE=1 loads the executable below 2 GB (required by the game).
call "%~dp0config.bat"
powershell -ExecutionPolicy Bypass -File "%~dp0..\tools\run_uc3.ps1" -Set "GOW3_EXECUTABLE=eboot.bin,GOW3_LOW_IMAGE=1,GOW3_AJM_END_RULES=1,GOW3_EOP_TICK_WAIT=1,GOW3_COMPUTE_PIPE=1,%UC_OTIMIZACOES%" %*
