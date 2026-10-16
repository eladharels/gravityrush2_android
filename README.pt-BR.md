# Uncharted: The Nathan Drake Collection para PC (port nativo)

[English](README.md) | **Português (Brasil)**

Port nativo para Windows 10 e 11 dos jogos da **Uncharted: The Nathan Drake Collection** (PS4,
Bluepoint, 2015). O foco é o **Uncharted 3: Drake's Deception**; o Uncharted 2 já é jogável e o
Uncharted 1 já abre e roda.

> **Nenhum arquivo do jogo está incluído.** Você precisa do dump do seu próprio PS4
> (testado: **CUSA02344**, versão 01.00 com a atualização **01.02**).
> Este projeto não tem relação com a Sony Interactive Entertainment, a Naughty Dog ou a Bluepoint
> Games. Uncharted é marca registrada da Sony Interactive Entertainment.

## Como funciona

O PS4 usa um processador x86-64, a mesma arquitetura do PC. Por isso o executável original do jogo
roda **direto na CPU**, sem emulação: um runtime escrito para o port substitui as bibliotecas de
sistema do PS4 (memória, threads, arquivos, áudio, vídeo, controle, saves). A parte gráfica é
traduzida: os comandos e shaders da GPU do PS4 (AMD GCN) viram Vulkan pelo núcleo de vídeo do
[shadPS4](https://github.com/shadps4-emu/shadPS4).

Este repositório é um fork do [port do God of War III](https://github.com/FelipePrado0/gow3_pc), que
vem do [port do Bloodborne para Windows](https://github.com/Supermedo/bloodborne_pc) do Supermedo e
do [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc) original do deadinside28. Por
isso muitos nomes ainda começam com `gow3` / `GOW3_` (o executável é `gow3-probe.exe`).

## Situação

| Jogo | Executável | Situação |
|---|---|---|
| Uncharted 3 | `big3-ps4_Shipping.elf` | Jogável; **60 FPS** no Castelo, nas ruas e no museu de Cartagena com as otimizações de `windows\jogar_uc3.bat` |
| Uncharted 2 | `big2-ps4_Shipping.elf` | Jogável (51–60 FPS), com saves próprios. Use `windows\jogar_uc2.bat` |
| Uncharted 1 | `eboot.bin` | Abre e roda a 60 FPS (`windows\jogar_uc1.bat`); pouco testado |

Problemas conhecidos: alguns sons do motor (Ajm) faltam; no prólogo do trem do UC2 o vagão cai
cedo demais se `GOW3_COMPUTE_PIPE=1`; defeitos do núcleo que o shadPS4 também tem (cabelo branco
depois de carregar checkpoint, pontos de poeira). Escala de resolução interna (FSR/DLSS) não
funciona no Uncharted: os ganchos herdados são específicos do God of War III.

O diário do projeto, com o que foi feito e por quê, está em [docs/uc3/DEVLOG.md](docs/uc3/DEVLOG.md).

## Requisitos

- Windows 10 (1903+) ou 11, 64 bits; GPU com Vulkan 1.3 (desenvolvido numa AMD RX 6750 XT).
- [MSYS2](https://www.msys2.org) para compilar.
- O dump do seu PS4: a pasta do jogo (`CUSA02344`) e a da atualização 01.02.

## Compilar

1. Instale o MSYS2 (`winget install MSYS2.MSYS2`), abra o terminal **MSYS2 CLANG64** e instale as
   dependências:

   ```bash
   pacman -S --needed git patch \
     mingw-w64-clang-x86_64-{toolchain,cmake,ninja,pkgconf,python} \
     mingw-w64-clang-x86_64-{vulkan-headers,vulkan-loader,vulkan-memory-allocator,glslang} \
     mingw-w64-clang-x86_64-{sdl3,fmt,boost,robin-map,xxhash,ffmpeg,zydis} \
     mingw-w64-clang-x86_64-{spirv-tools,spirv-headers,spirv-cross}
   ```

2. Clone com os submódulos:

   ```bash
   git clone --recursive <url deste repositório> uc3_pc
   ```

3. Edite `windows\config.bat` com os caminhos do MSYS2, do jogo e da atualização.
4. Rode `windows\compilar.bat` (ou `bash build.sh` no terminal CLANG64). O resultado é
   `out\gow3-probe.exe`.

## Jogar

Dê dois cliques no `.bat` do jogo, dentro de `windows\`:

| Arquivo | O que faz |
|---|---|
| `config.bat` | Caminhos (MSYS2, jogo, atualização) e a lista de otimizações recomendadas |
| `compilar.bat` | Compila o port |
| `jogar_uc3.bat` | Uncharted 3 com as otimizações (60 FPS) |
| `jogar_uc2.bat` | Uncharted 2 (sem `GOW3_COMPUTE_PIPE`, por causa do trem) |
| `jogar_uc1.bat` | Uncharted 1 (experimental) |
| `fechar_jogo.bat` | Fecha o jogo se ele travar |

O log de cada sessão fica em `out\last-run.log` (o anterior em `out\previous-run.log`). Os saves
ficam em `user\`. No jogo, **Insert** (ou R3+L2 no controle) abre o menu do port.

Também dá para chamar o script direto, passando opções só para aquela execução:

```powershell
powershell -ExecutionPolicy Bypass -File tools\run_uc3.ps1 -Set GOW3_READBACKS=1,GOW3_FRAME_STATS=1
```

### Opções (toggles)

Toda otimização nova entra **desligada por padrão** no código; os `.bat` de `windows\` ligam o
conjunto testado. As principais:

| Variável | Efeito |
|---|---|
| `GOW3_READBACKS` | `2` (padrão do `run_uc3.ps1`): leituras precisas da GPU, necessárias para o cenário aparecer; `1`: modo relaxado |
| `GOW3_READBACK_ASYNC`, `GOW3_READBACK_FAST*`, `GOW3_READBACK_SIDEQ`, `GOW3_DEFERRED_READBACK` | Leituras da GPU sem parar a fila inteira |
| `GOW3_SCALAR_COPY_HLE`, `GOW3_HLE_INVALIDATE` | Substitui um compute shader de cópia por código nativo (e invalida os caches que ele escreve) |
| `GOW3_COMPUTE_PIPE`, `GOW3_LEAF_COPY` | Atalhos no processamento de compute |
| `GOW3_EAGER_SUBMIT*`, `GOW3_ASYNC_SUBMIT`, `GOW3_DEFERRED_SUBMIT` | Envio de comandos à GPU mais cedo / em outra thread |
| `GOW3_RING_COPY_THREAD`, `GOW3_RING_DEDUP` | Cópias do ring buffer numa thread separada, sem repetição |
| `GOW3_FRAME_STATS` | Estatísticas de quadro no log |
| `GOW3_EXECUTABLE` | Qual jogo abrir (`big3-ps4_Shipping.elf`, `big2-ps4_Shipping.elf`, `eboot.bin`) |
| `GOW3_LOW_IMAGE` | Carrega o executável abaixo de 2 GB (necessário no UC1) |

A lista completa, com medições, está no [DEVLOG](docs/uc3/DEVLOG.md).

## Créditos

- **deadinside28**: [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), o port nativo
  original (a base de tudo, também chamada de bbport).
- **Supermedo** (Mohammed Albarghouthi): o [port do Bloodborne para Windows](https://github.com/Supermedo/bloodborne_pc)
  e o [uncharted_pc](https://github.com/Supermedo/uncharted_pc), de onde vieram ideias e correções
  (HLE da cópia escalar de constantes, `BUFFER_LOAD` com addr64, regras de fim de job do Ajm, limite
  de quadros à frente).
- **Felipe Prado** e colaboradores: [gow3_pc](https://github.com/FelipePrado0/gow3_pc), o port do
  God of War III do qual este repositório é fork, com as correções de renderização do
  [cuesta4](https://github.com/cuesta4/shadPS4/tree/eltutz).
- **Equipe do shadPS4**: o núcleo de vídeo [shadPS4](https://github.com/shadps4-emu/shadPS4), que
  faz toda a tradução de GPU e shaders para Vulkan.

Também usados: [LibAtrac9](https://github.com/Thealexbarney/LibAtrac9), [FFmpeg](https://ffmpeg.org),
[SDL3](https://github.com/libsdl-org/SDL), [Dear ImGui](https://github.com/ocornut/imgui),
[sirit](https://github.com/shadps4-emu/sirit), [magic_enum](https://github.com/Neargye/magic_enum),
[miniz](https://github.com/richgel999/miniz), [xbyak](https://github.com/herumi/xbyak),
[Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator),
[FSR-Vulkan](https://github.com/FireBurn/FSR-Vulkan), [MSYS2](https://www.msys2.org) e
[LLVM](https://llvm.org).

O README original do port do God of War III está em [docs/gow3/README.gow3.pt-BR.md](docs/gow3/README.gow3.pt-BR.md).

## Licença

GNU GPL v2 ou posterior ([LICENSE](LICENSE)), a mesma do shadPS4 e dos ports dos quais este deriva.
Componentes de terceiros mantêm suas próprias licenças.
