@echo off
rem ==========================================================================
rem  Edite os caminhos abaixo / Edit the paths below
rem ==========================================================================
rem  MSYS2 (https://www.msys2.org)
if not defined MSYS2_ROOT set "MSYS2_ROOT=C:\msys64"
rem  Dump do seu PS4 / Your own PS4 dump: CUSA02344 (Uncharted: The Nathan Drake Collection)
if not defined GOW3_GAME_DIR set "GOW3_GAME_DIR=C:\Jogos\CUSA02344"
rem  Atualizacao 01.02 / Update 01.02
if not defined GOW3_PATCH_DIR set "GOW3_PATCH_DIR=C:\Jogos\CUSA02344-UPDATE"

rem  Otimizacoes recomendadas (todas desligadas por padrao no codigo).
rem  Recommended optimizations (all off by default in the code).
set "UC_OTIMIZACOES=GOW3_DEFERRED_READBACK=1,GOW3_FRAME_STATS=1,GOW3_READBACK_ASYNC=1,GOW3_LEAF_COPY=1,GOW3_SCALAR_COPY_HLE=1,GOW3_HLE_INVALIDATE=1,GOW3_EAGER_SUBMIT=128,GOW3_EAGER_SUBMIT_US=1000,GOW3_ASYNC_SUBMIT=1,GOW3_READBACK_SIDEQ=1,GOW3_RING_COPY_THREAD=1,GOW3_DEFERRED_SUBMIT=1,GOW3_RING_DEDUP=1,GOW3_READBACK_FAST=1,GOW3_READBACK_FAST_WAIT=1,GOW3_READBACK_FAST_SIDEQ=1"
