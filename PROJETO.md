# Uncharted 3 — port nativo para PC

Port nativo para Windows de **Uncharted 3: Drake's Deception**, a partir da versão de PS4 da
*Uncharted: The Nathan Drake Collection* (Bluepoint, 2015).

O diário do projeto, com o que foi feito e por quê, está em [docs/uc3/DEVLOG.md](docs/uc3/DEVLOG.md).

## A ideia

O PS4 usa um processador x86-64, a mesma arquitetura do PC. Então o executável original do
jogo (`eboot.bin`) pode rodar **direto na CPU**, sem emulação. O que precisa ser feito:

- **Runtime:** reimplementar as funções de sistema do PS4 que o jogo chama (memória, threads,
  arquivos, áudio, vídeo, controle, saves).
- **GPU:** traduzir os comandos e shaders da GPU do PS4 (AMD GCN) para Vulkan, usando o núcleo
  de vídeo do [shadPS4](https://github.com/shadps4-emu/shadPS4).

A técnica já funcionou em outros jogos:
[bloodborne_pc](https://github.com/deadinside28/bloodborne_pc) (original, Linux),
o [port de Bloodborne para Windows](https://github.com/Mrsuss60/bloodborne_pc_windows_port) e o
[God of War III Remastered](https://github.com/FelipePrado0/gow3_pc). **Este repositório é um
fork do port do GoW3** (tag `windows-v2.1.7`); o trabalho do Uncharted fica na branch `uc3`.

A versão de PS3 foi descartada: o processador Cell (PowerPC + SPUs) não roda num PC sem
recompilação completa, e a Naughty Dog colocou boa parte do motor nas SPUs.

## Situação

**Fase 0 — preparação.** O ambiente compila o port do GoW3 sem alterações. Ainda não há dump
do jogo.

| Fase | Objetivo | Status |
|---|---|---|
| 0 | Ambiente de build, estudo do código, organização | em andamento |
| 1 | Separar o que é específico do GoW3 do que é genérico | a fazer |
| 2 | Preparar o eboot do Uncharted e listar as funções faltantes (`--check-imports`) | precisa do dump |
| 3 | Implementar o runtime até o jogo abrir (menus) | precisa do dump |
| 4 | Gameplay: gráficos, áudio, cutscenes, saves | precisa do dump |
| 5 | Launcher, patches (resolução, FPS), polimento | — |

## O que é preciso

- **Dump da sua própria cópia** da Nathan Drake Collection, feito num PS4 desbloqueado: a pasta
  com `eboot.bin`, `sce_module` e `sce_sys`, de preferência com a atualização 1.02. Nenhum
  arquivo do jogo entra neste repositório.
- Windows 10/11 64 bits, GPU com Vulkan 1.3.
- Para compilar: [MSYS2](https://www.msys2.org) com o ambiente CLANG64 (pacotes em
  [packaging/windows/README.md](packaging/windows/README.md)).

## Compilar

No ambiente desta máquina, o MSYS2 está em `D:\msys64`:

```bash
export MSYS2_ROOT=/d/msys64
bash build.sh        # gera out/gow3-probe.exe
```

Os nomes `gow3-*` e as variáveis `GOW3_*` vêm do projeto original e por enquanto ficam como
estão (veja o DEVLOG, 2026-10-12).

## Organização da pasta de trabalho

```
D:\Uncharted\
├── uc3_pc\              ← este repositório (branch uc3)
├── ref\gow3_pc\         ← port do GoW3 original, só referência
├── ref\bloodborne_pc\   ← port do Bloodborne (Windows), só referência
└── tools\shadps4\       ← shadPS4 0.19.0, para comparar o comportamento do jogo
```

## Licença e créditos

GNU GPL v2 ou posterior, como o projeto de origem ([LICENSE](LICENSE)). Os créditos do
trabalho em que este port se baseia estão no [README.md](README.md) original.
Uncharted é marca registrada da Sony Interactive Entertainment. Este projeto não tem relação
com a Sony, a Naughty Dog nem a Bluepoint Games.
