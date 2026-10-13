# Devlog — Uncharted 3 PC

Diário do projeto: o que foi feito, o que foi decidido e por quê. Entradas mais novas no topo.

---

## 2026-10-12 — Início do projeto

### Decisão: versão de PS4, não de PS3
O Uncharted 3 original é de PS3 (Cell: PPU PowerPC big-endian + 6 SPUs). Um port nativo
exigiria recompilar o código PPU e centenas de programas de SPU, e traduzir a GPU RSX. Isso é
um projeto de anos, e o RPCS3 já cobre esse caminho.

A *Nathan Drake Collection* (PS4) é x86-64 + GCN, a mesma situação de Bloodborne e GoW3
Remastered, que já têm ports nativos. Escolhemos ela.

### Pesquisa: estado no shadPS4
Relatos no [rastreador de compatibilidade](https://github.com/shadps4-compatibility/shadps4-game-compatibility):
- [CUSA02344](https://github.com/shadps4-compatibility/shadps4-game-compatibility/issues/441)
  (Europa, Windows): **Ingame na 0.19.0**. Em 10/10/2026, uma hora de jogo sem crash com
  *precise readback*; só um glitch de iluminação num templo subterrâneo.
- [CUSA02320](https://github.com/shadps4-compatibility/shadps4-game-compatibility/issues/147)
  (EUA, Linux): precisa de *direct memory access*. Em versões antigas o **Uncharted 3** travava
  em `NdJobWorkerThread` e `condvar`, no sistema de jobs da Naughty Dog.
  → No nosso runtime, threads e sincronização (`runtime_thread.c`, `runtime_mutex.c`) vão
  precisar de atenção.
- Outros IDs da coletânea: CUSA02343, CUSA02826, CUSA01399.

### Decisão: base no port do GoW3
Comparamos os dois ports que já tínhamos:
- **GoW3** ([FelipePrado0/gow3_pc](https://github.com/FelipePrado0/gow3_pc), fork do Bloodborne
  Windows do Supermedo): mais novo, build com MSYS2/Clang, tem decodificador de vídeo H.264
  (`runtime_videodec.c`) e MP3, hooks por assinatura. **Escolhido como base.**
- **Bloodborne** ([Mrsuss60](https://github.com/Mrsuss60/bloodborne_pc_windows_port)): outro
  fork Windows do mesmo original, com compatibilidade Win32 própria (`win32_*.c`). Fica em
  `ref/bloodborne_pc` como segunda opinião para funções que o GoW3 não implementa.

### Ambiente
- MSYS2 em `D:\msys64` (no C: só havia ~14 GB livres), CLANG64, pacotes do
  `packaging/windows/README.md`.
- O port do GoW3 compilou sem alterações (`bash build.sh`, com `MSYS2_ROOT=/d/msys64`).
- Teste Vulkan (`gow3-probe.exe --vulkan-only`): **AMD Radeon RX 6750 XT; PASS**.
- `gow3-gpu-capabilities.exe` avisa: a RX 6750 XT não faz blit de D32S8, então a escala de
  cena ao vivo fica desligada. O launcher do GoW3 já trata isso; não bloqueia nada.
- shadPS4 0.19.0 em `tools/shadps4`, para comparar o comportamento do jogo.

### Armadilha: quebras de linha
O git do Windows estava com `core.autocrlf=true`, o que transformava `build.sh` em CRLF e
quebrava o bash. Este repositório usa `core.autocrlf=false`. Repita isso em qualquer clone novo.

### Repositório
- `D:\Uncharted\uc3_pc`, branch `uc3`, a partir da tag `windows-v2.1.7` do GoW3.
- Remoto `upstream` = GoW3 no GitHub, só para *fetch* (push desativado).
- **Os nomes `gow3-*`/`GOW3_*` ficam por enquanto.** Renomear tudo agora geraria um diff enorme
  e conflitos sempre que trouxéssemos correções do upstream. Vamos renomear quando o jogo
  estiver rodando.

### Estudo do código (começo)
- `src/probe.c` (loader): quase todo genérico. Carrega a imagem do eboot, aplica as
  realocações, liga as importações ao runtime (ou a armadilhas que param com
  `STOP: first unsupported PS4 import`), aplica patches e entra no código do jogo.
  **Específico do GoW3:** `install_cheats`, `install_actor_watch`, `install_orb_multiplier` e
  `install_stat_multipliers` (+ `src/*_hook.h`), além do título padrão "God of War III".
- `scripts/prepare.py`: genérico. Lê o SELF já descriptografado, monta a imagem de memória, as
  realocações e a lista de importações (`analysis.json` com o relatório).
- `--check-imports` lista as importações sem implementação sem rodar o jogo. Esse será o
  primeiro passo com o dump.

### Próximos passos
1. Terminar o estudo: `scripts/link_*.py`, `run.py`, `gpu/shim/` (o que é GoW3 lá).
2. Fase 1: desligar ou isolar o código específico do GoW3 sem quebrar o build.
3. Conseguir o dump da Nathan Drake Collection.
