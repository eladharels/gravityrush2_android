@echo off
rem Uncharted 2. Sem GOW3_COMPUTE_PIPE: com ele o vagao do trem (prologo) cai cedo demais.
rem Uncharted 2. Without GOW3_COMPUTE_PIPE: with it the train carriage (prologue) falls too early.
call "%~dp0config.bat"
powershell -ExecutionPolicy Bypass -File "%~dp0..\tools\run_uc3.ps1" -Set "GOW3_EXECUTABLE=big2-ps4_Shipping.elf,%UC_OTIMIZACOES%" %*
