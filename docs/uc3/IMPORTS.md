# Importações do Uncharted 3 sem implementação

Saída de `gow3-probe.exe --check-imports` para `big3-ps4_Shipping.elf` 01.02
(`GOW3_EXECUTABLE=big3-ps4_Shipping.elf`, jogo base + `GOW3_PATCH_DIR`). Os nomes vieram do
hash dos nomes do SDK (o mesmo algoritmo de NID de `scripts/prepare.py`).

**69 faltando** (68 identificadas). Checklist da fase 3; marque `[x]` quando implementar.

Legenda do tipo:
- **real**: precisa funcionar de verdade, senão o jogo não roda.
- **mapear**: já existe algo equivalente no runtime; é só ligar.
- **stub**: online, diálogos ou serviços do sistema. Basta responder "ok", "não disponível" ou
  "sem rede" de forma coerente.

## Fibras — `libSceFiber` (real, prioridade máxima)
O sistema de jobs da Naughty Dog (`NdJobWorkerThread`, `NdJob Fiber (%lu KiB)`) roda tudo em
fibras. É o coração do motor e a causa dos travamentos antigos no shadPS4.

- [ ] `_sceFiberInitializeImpl` (`hVYD7Ou2pCQ`)
- [ ] `sceFiberOptParamInitialize` (`asjUJJ+aa8s`)
- [ ] `sceFiberFinalize` (`JeNX5F-NzQU`)
- [ ] `sceFiberRun` (`a0LLrZWac0M`)
- [ ] `sceFiberSwitch` (`PFT2S-tJ7Uk`)
- [ ] `sceFiberGetSelf` (`p+zLIOg27zU`)
- [ ] `sceFiberReturnToThread` (`B0ZX2hx9DMw`)

## Vídeo — `libSceVideodec2` (real)
Cutscenes. O GoW3 já decodifica H.264 com FFmpeg na API antiga (`runtime_videodec.c`); a
Videodec2 é parecida.

- [ ] `sceVideodec2QueryComputeMemoryInfo` (`RnDibcGCPKw`)
- [ ] `sceVideodec2AllocateComputeQueue` (`eD+X2SmxUt4`)
- [ ] `sceVideodec2ReleaseComputeQueue` (`UvtA3FAiF4Y`)
- [ ] `sceVideodec2QueryDecoderMemoryInfo` (`qqMCwlULR+E`)
- [ ] `sceVideodec2CreateDecoder` (`CNNRoRYd8XI`)
- [ ] `sceVideodec2DeleteDecoder` (`jwImxXRGSKA`)
- [ ] `sceVideodec2Decode` (`852F5+q6+iM`)
- [ ] `sceVideodec2Flush` (`l1hXwscLuCY`)
- [ ] `sceVideodec2Reset` (`wJXikG6QFN8`)
- [ ] `sceVideodec2GetPictureInfo` (`NtXRa3dRzU0`)

## Módulos e executáveis (real)
- [ ] `sceKernelLoadStartModule` (`wzvqT4UqKX8`): carrega `libSceFiber.prx`,
  `libSceNpManager.prx` etc. do sistema; o runtime já serve essas bibliotecas, então basta
  devolver um handle.
- [ ] `sceKernelDlsym` (`LwG8g3niqwA`): procurar o símbolo no runtime.
- [ ] `sceKernelGetModuleInfoForUnwind` (`RpQJJVKTiFM`): exceções C++ (unwind).
- [ ] `sceSystemServiceLoadExec` (`JoBqSQt1yyA`): trocar de executável (`/app0/eboot.bin` =
  voltar ao menu). Primeira versão: registrar e encerrar; depois, reiniciar o port com o outro
  executável.

## POSIX — `libScePosix` (mapear)
Mesmas funções que o runtime já tem com nome `sceKernel*`/libc, sob outra biblioteca.

- [ ] Arquivos: `open` (`wuCroIGjt2g`), `close` (`bY-PO6JhzhQ`), `read` (`AqBioC2vF3I`),
  `write` (`FN4gaPmuFV8`), `lseek` (`Oy6IpwgtYOk`), `fsync` (`juWbTNM+8hw`), `fcntl` (`8nY19bKoiZk`)
- [ ] Threads: `pthread_condattr_init` (`mKoTx03HRWA`)
- [ ] Rede (stub "sem rede"): `socket` (`TU-d9PfIHPM`), `bind` (`KuOmgKoqCdY`),
  `listen` (`pxnCmagrtao`), `accept` (`3e+4Iv7IJ8U`), `connect` (`XVL8So3QJUk`),
  `send` (`fZOeZIOEmLw`), `recv` (`Ez8xjo9UF4E`), `select` (`T8fER+tIGgk`),
  `setsockopt` (`fFxGkxF2bVo`)

## Kernel — `libkernel`
- [ ] `scePthreadGetthreadid` (`EI-5-jlq2dE`): mapear
- [ ] `sceKernelConvertUtcToLocaltime` (`-o5uEDpN+oY`): mapear (fuso do Windows)
- [ ] `sceKernelConvertLocaltimeToUtc` (`0NTHN1NKONI`): mapear
- [ ] `sceKernelSetVirtualRangeName` (`DGMG3JshrZU`): stub (só nomeia memória)
- [ ] **não identificada** (`0Cq8ipKr9n0`): ver quem chama quando o jogo rodar

## Controle, relógio, instalação
- [ ] `scePadRead` (`q1cHNfGycLI`): mapear (o runtime tem `scePadReadState`; `Read` devolve
  uma fila de estados)
- [ ] `sceRtcGetTime_t` (`BtqmpTRXHgk`): mapear
- [ ] PlayGo (stub "tudo instalado"): `scePlayGoGetEta` (`v6EZ-YWRdMs`),
  `scePlayGoGetLanguageMask` (`3OMbYZBaa50`), `scePlayGoSetLanguageMask` (`LosLlHOpNqQ`),
  `scePlayGoGetToDoList` (`Nn7zKwnA5q0`), `scePlayGoPrefetch` (`-Q1-u1a7p0g`)
- [ ] AppContent: `sceAppContentAddcontMount` (`VANhIWcqYak`, sem DLC),
  `sceAppContentAddcontEnqueueDownload` (`7gxh+5QubhY`),
  `sceAppContentDownloadDataGetAvailableSpaceKb` (`Gl6w5i0JokY`)

## Online e diálogos (stub)
- [ ] ErrorDialog: `sceErrorDialogInitialize` (`I88KChlynSs`), `sceErrorDialogTerminate`
  (`9XAxK2PMwk8`), `sceErrorDialogOpen` (`M2ZF-ClLhgY`), `sceErrorDialogUpdateStatus`
  (`WWiGuh9XfgQ`). Registrar no log o erro que o jogo quis mostrar: é diagnóstico útil.
- [ ] `sceInvitationDialogTerminate` (`B6HVJtDYxEE`)
- [ ] `sceRemoteplayInitialize` (`k1SwgkMSOM8`)
- [ ] NpSignaling: `sceNpSignalingGetConnectionInfo` (`AN3h0EBSX7A`),
  `sceNpSignalingTerminateConnection` (`b4qaXPzMJxo`)
- [ ] NpTus (dados online): `sceNpTusCreateNpTitleCtx` (`BIkMmUfNKWM`),
  `sceNpTusDeleteNpTitleCtx` (`H3uq7x0sZOI`), `sceNpTusCreateRequest` (`3bh2aBvvmvM`),
  `sceNpTusDeleteRequest` (`CcIH40dYS88`), `sceNpTusGetDataAsync` (`uHtKS5V1T5k`),
  `sceNpTusSetDataAsync` (`G68xdfQuiyU`), `sceNpTusPollAsync` (`t7b6dmpQNiI`),
  `sceNpTusGetFriendsDataStatusAsync` (`LUwvy0MOSqw`)
