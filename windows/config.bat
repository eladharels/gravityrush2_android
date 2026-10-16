@echo off
rem ==========================================================================
rem  Edite os caminhos abaixo / Edit the paths below
rem ==========================================================================
rem  MSYS2 (https://www.msys2.org)
if not defined MSYS2_ROOT set "MSYS2_ROOT=C:\msys64"
rem  Dump do seu PS4 / Your own PS4 dump: CUSA02344 (Uncharted: The Nathan Drake Collection)
if not defined UC_GAME_DIR set "UC_GAME_DIR=C:\Jogos\CUSA02344"
rem  Atualizacao 01.02 / Update 01.02
if not defined UC_PATCH_DIR set "UC_PATCH_DIR=C:\Jogos\CUSA02344-UPDATE"

rem  Otimizacoes recomendadas (todas desligadas por padrao no codigo). Os nomes antigos GOW3_* tambem valem.
rem  Recommended optimizations (all off by default in the code). The older GOW3_* names still work.
set "UC_OTIMIZACOES=UC_DEFERRED_READBACK=1,UC_FRAME_STATS=1,UC_READBACK_ASYNC=1,UC_LEAF_COPY=1,UC_SCALAR_COPY_HLE=1,UC_HLE_INVALIDATE=1,UC_EAGER_SUBMIT=128,UC_EAGER_SUBMIT_US=1000,UC_ASYNC_SUBMIT=1,UC_READBACK_SIDEQ=1,UC_RING_COPY_THREAD=1,UC_DEFERRED_SUBMIT=1,UC_RING_DEDUP=1,UC_READBACK_FAST=1,UC_READBACK_FAST_WAIT=1,UC_READBACK_FAST_SIDEQ=1"
