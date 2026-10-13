# Devlog — Uncharted 3 PC

Diário do projeto: o que foi feito, o que foi decidido e por quê. Entradas mais novas no topo.

---

## 2026-10-13 — Dump do jogo extraído

### O pacote
- `D:\Uncharted\CUSA02344\CUSA02344.pkg` (40,55 GB), backup da cópia do usuário.
- `EP9000-CUSA02344_00-UNCHARTEDTRILOGY`: *Nathan Drake Collection* europeia, **versão 01.00**
  (jogo base, sem a atualização 1.02), SDK 2.508. É o mesmo ID do relato *Ingame* no shadPS4 0.19.0.

### Extração
- Ferramenta: **PkgTool 0.2.231** do [LibOrbisPkg](https://github.com/maxton/LibOrbisPkg)
  (LGPL, código aberto), em `tools/pkgtool`.
- `pkg_extract --passcode 00000000000000000000000000000000` → 58,6 GB, 15.146 arquivos.
- O `pkg_extract` **não** grava os metadados do `sce_sys` (`param.sfo`, ícones, playgo,
  troféus): eles são entradas do pacote. Extraídos um a um com `pkg_extractentry` (IDs 11–29 do
  `pkg_listentries`).
- Armadilha: em PowerShell, um `[ordered]@{}` com chaves inteiras é indexado **por posição**;
  os nomes saíram trocados na primeira tentativa. Use uma lista de pares.
- Pasta final: **`D:\Uncharted\CUSA02344\app0`** (nome do PS4 para a pasta do jogo).

### Estrutura do jogo — descoberta importante
```
app0\
├── eboot.bin                 10,6 MB  menu da coletânea (e talvez o Uncharted 1)
├── big2-ps4_Shipping.elf     16,4 MB  Uncharted 2
├── big3-ps4_Shipping.elf     22,9 MB  Uncharted 3   ← o nosso alvo
├── sce_module\ libc.prx, libSceFios2.prx
├── u1data\ u2data\ u3data\ shareddata\
└── sce_sys\
```
"big" é o codinome da série na Naughty Dog. Cada jogo da coletânea tem **o seu próprio
executável**, e o `eboot.bin` provavelmente lança os outros (algo como
`sceSystemServiceLoadExec`). Isso explica o relato antigo do shadPS4 em que "o Uncharted 3 não
abria", mesmo com o 1 e o 2 funcionando. Para o port, a ideia é **carregar o `big3` direto**.

Os três executáveis são *fake SELF* sem criptografia nem compressão (flags dos segmentos com
`0x800` e sem `0x2`/`0x8`), o formato que o `prepare.py` aceita.

### Validação
`prepare.py` no `eboot.bin`: 429 importações, 19.243 realocações, 52 módulos requisitados,
`_init_env` da libc verificado. O dump está bom.

### Próximos passos (fase 2)
1. Ensinar o `prepare.py`/`link_modules.py` a usar outro executável além do `eboot.bin`
   (o `big3-ps4_Shipping.elf`).
2. `--check-imports` no `big3` para listar o que falta no runtime.
3. Descobrir como o `eboot.bin` lança o `big3` (argumentos, parâmetros) para reproduzir isso.

## 2026-10-13 — WSL (Ubuntu) como ambiente de apoio

### Por quê
O port vai rodar no **Windows**, e o build principal continua sendo o MSYS2. O WSL é apoio:
- roda os testes que dependem do Linux (os 11 que falham no Windows);
- é o **plano B para travamentos de threads**. As ferramentas de diagnóstico Linux do loader (o
  *watchdog* `--timeout`, que mostra a pilha de cada thread, e o `gdb`) não existem na versão
  Windows, e o Uncharted 3 tem histórico de travar no sistema de jobs da Naughty Dog
  (`NdJobWorkerThread`, `condvar`). A ideia é rodar o loader em `--cpu-only` no WSL quando isso
  aparecer.
- **Não** serve para jogar: no WSL2 o Vulkan passa por tradução para D3D12 (Dozen), lento e
  diferente do driver real da AMD.

### Instalação
- A virtualização (AMD SVM) estava desligada na BIOS; ligada pelo usuário.
- `wsl --install -d Ubuntu` (admin) ligou os recursos do Windows, mas a distribuição não ficou
  instalada depois do reinício. Resolvido com `wsl --install -d Ubuntu --no-launch`, sem admin.
- **Ubuntu 26.04.1 LTS** no WSL2 (WSL 3.0.3, kernel 6.18), usuário `matheus`, 16 threads,
  15 GB de RAM.
- O disco virtual foi movido do C: (pouco espaço) para **`D:\WSL\Ubuntu`**
  (`wsl --manage Ubuntu --move D:\WSL\Ubuntu`).
- Pacotes: `build-essential cmake ninja-build pkg-config python3-pil git gdb`.

### Repositório no Linux
- `~/uncharted/uc3_pc`, branch `uc3`, clonado do repositório do Windows. Fica **dentro** do
  sistema de arquivos do Linux, porque o acesso a `/mnt/d` é lento.
- Remotos: `windows` (`/mnt/d/Uncharted/uc3_pc`, para trazer os commits) e `upstream` (GoW3,
  só *fetch*). Para atualizar: `git pull windows uc3`.

### Resultado: testes
**Linux: 159 testes, todos OK (34 pulados).** As 11 falhas no Windows são limitações da
plataforma (links simbólicos, Nix), não problemas do código.

### Plano B: build completo no Linux (pacotes já conferidos no 26.04)
```bash
sudo apt install -y libsdl3-dev libavcodec-dev libavutil-dev libavformat-dev libswscale-dev \
  libboost-dev libfmt-dev robin-map-dev libxxhash-dev libvulkan-memory-allocator-dev \
  glslang-dev glslang-tools spirv-cross libspirv-cross-c-shared-dev libxbyak-dev libzydis-dev \
  spirv-headers libvulkan-dev libx11-dev libxcb1-dev libwayland-dev
```
`magic_enum` e `miniz` vêm dos submódulos (não há pacote no Ubuntu).

## 2026-10-12 — Fase 1: separar o que é do GoW3

### Resultado do estudo
O caminho principal (preparar → linkar → carregar → rodar) já é quase todo genérico:

| Parte | Específico do GoW3? |
|---|---|
| `scripts/prepare.py`, `link_libc.py`, `content_profile.py` | Não |
| `scripts/link_modules.py` | Exigia `libSceFios2.prx` → **corrigido** |
| `scripts/patches.py` | Tabela `GAMES` com os IDs do GoW3; sem perfil, não aplica patch nenhum. Ok por enquanto; o perfil do Uncharted entra aqui na fase 5 |
| `run.py` | Só o padrão `GOW3_GAME_DIR=../CUSA01623`; basta definir a variável |
| `src/probe.c` | Hooks de trapaça do GoW3 rodavam em qualquer jogo → **corrigido** |
| `src/runtime_*.c` | Genérico (é o "PS4" que o jogo enxerga) |
| `gpu/shim/gow3_overlay.cpp` | Abas de trapaça do GoW3, mas já se desativam sozinhas quando os hooks não existem |
| `launcher/`, `packaging/` | Totalmente GoW3 (nome, cores, ícones). Fica para a fase 5 |

### Núcleo gráfico (shadPS4 embutido)
- Base: shadPS4 commit `b4e7ae7` (2026-10-02), praticamente a mesma época da **0.19.0**, a
  versão em que a Nathan Drake Collection chegou a *Ingame*. Bom sinal.
- 286 alterações marcadas com `gow3:`. Quase todas são de **arquitetura e desempenho**, não
  truques do GoW3: pipeline de desenho em duas threads, leituras adiadas, compilação de shaders
  em segundo plano, estatísticas e suporte a Windows. Poucas são calibradas para o GoW3 (vetores
  de movimento e upscaler temporal, que já ficam desligados por padrão).
- Interruptores para testar se o Uncharted renderizar errado (todos variáveis de ambiente):
  - `GOW3_DIRECT_MEMORY_ACCESS=1`: **desligado por padrão, e o shadPS4 diz que a NDC precisa
    dele.** É o primeiro a testar.
  - `GOW3_DRAW_PIPE`, `GOW3_VK_RECORD_THREAD`: voltam ao desenho numa thread só, como no shadPS4.
  - `GOW3_DEFERRED_READBACK`, `GOW3_STALE_READBACK`, `GOW3_READBACK_LINEAR`: leituras da GPU
    (equivalem ao *precise readback* do shadPS4).
  - `GOW3_ASYNC_SHADERS`, `GOW3_DMEM_MB` (memória direta informada ao jogo, padrão 5056 MiB).
- Plano B: se algo renderizar errado só no port, comparar com o shadPS4 0.19.0 puro
  (`tools/shadps4`) para saber se o problema é do núcleo ou das mudanças do GoW3.

### Mudanças
1. **`src/probe.c`:** os hooks do GoW3 (trapaças, multiplicadores, barra de vida) só são
   procurados quando o `TITLE_ID` é CUSA01623/01715/01720. Eles acham o código por padrões de
   bytes, e num jogo diferente uma coincidência alteraria código do Uncharted sem ninguém
   perceber. Em outro jogo o log mostra `Game hooks: none for title ...`. O título padrão da
   janela (sem `param.sfo`) virou "PS4 game".
2. **`scripts/link_modules.py`:** só o `libc.prx` é obrigatório. Se o jogo não trouxer o
   `libSceFios2.prx`, o linker avisa e as importações dele vão para o runtime (o
   `--check-imports` vai mostrar o que faltar). Com `--modules` explícito continua estrito.

### Verificação
- Build completo OK.
- Testes Python: 159 rodados, mesmas 11 falhas do GoW3 original (testes que dependem do Linux:
  links simbólicos, Nix). Nenhuma falha nova.
- Ainda não deu para testar as mudanças com um jogo de verdade (falta o dump).

### Armadilha (de novo): CRLF nos submódulos
Os submódulos são clonados com o `core.autocrlf=true` global do Windows, e o patch do
FSR-Vulkan falha (`patch não se aplica`). Correção: `core.autocrlf=false` em cada submódulo e
checkout de novo (`git submodule foreach`).

### Próximos passos
- Fase 1 concluída no que dá para fazer sem o jogo.
- **Dump da Nathan Drake Collection** → fase 2: `prepare.py` + `link_modules.py` +
  `gow3-probe.exe --check-imports`.
- Antes disso, se quiser: testar a coletânea no shadPS4 0.19.0 para ter uma referência.

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
