#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Uncharted: The Nathan Drake Collection launcher for Windows (Tkinter).

One window: choose the folder of your own PS4 dump (and its update), pick a game, press Play.
Each game starts with the option set tested for it (GAMES below); everything else stays at the
port's defaults. Settings live next to the executable in launcher.json; saves in user\\.

Frozen with PyInstaller (packaging/windows/package_uc.sh) the same executable also runs run.py
(`--run`), the preparation scripts (`--script`) and a game without the window
(`--play uc1|uc2|uc3`, for desktop shortcuts), so players need no Python and no terminal.
"""
import json
import os
from pathlib import Path
import queue
import runpy
import shutil
import subprocess
import sys
import threading

WINDOWS = sys.platform == 'win32'
if WINDOWS:
    import ctypes
FROZEN = getattr(sys, 'frozen', False)
PORT_DIR = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PORT_DIR / 'scripts'))
DATA_DIR = Path(os.environ.get('UC_DATA_DIR') or os.environ.get('GOW3_DATA_DIR') or PORT_DIR)
SETTINGS_FILE = DATA_DIR / 'launcher.json'
INI_FILE = DATA_DIR / 'ucndc.ini'
OLD_INI_FILE = DATA_DIR / 'gow3.ini'  # name before 0.3; copied once, left in place
USER_DIR = DATA_DIR / 'user'
LOG_FILE = USER_DIR / 'last_run.log'
APP_NAME = 'Uncharted Collection PC'
EXE_NAME = 'Uncharted Launcher.exe'
VERSION = '0.3'
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
TESTED_TITLE = 'CUSA02344'

# Options every game starts with (all of them are off by default in the code).
COMMON = {
    'GOW3_READBACKS': '2', 'GOW3_FRAME_STATS': '1', 'GOW3_READBACK_ASYNC': '1', 'GOW3_LEAF_COPY': '1',
    'GOW3_SCALAR_COPY_HLE': '1', 'GOW3_HLE_INVALIDATE': '1', 'GOW3_EAGER_SUBMIT': '128',
    'GOW3_EAGER_SUBMIT_US': '1000', 'GOW3_ASYNC_SUBMIT': '1', 'GOW3_READBACK_SIDEQ': '1',
    'GOW3_RING_COPY_THREAD': '1', 'GOW3_DEFERRED_SUBMIT': '1', 'GOW3_RING_DEDUP': '1',
    'GOW3_READBACK_FAST': '1', 'GOW3_READBACK_FAST_WAIT': '1', 'GOW3_READBACK_FAST_SIDEQ': '1',
}
# key, title, executable, (status pt, status en), extra options
GAMES = [
    ('uc1', "Uncharted: Drake's Fortune", 'eboot.bin', ('Experimental', 'Experimental'),
     {'GOW3_COMPUTE_PIPE': '1', 'GOW3_LOW_IMAGE': '1', 'GOW3_AJM_END_RULES': '1', 'GOW3_EOP_TICK_WAIT': '1'}),
    ('uc2', 'Uncharted 2: Among Thieves', 'big2-ps4_Shipping.elf', ('Jogável', 'Playable'),
     {}),  # without GOW3_COMPUTE_PIPE: with it the train carriage in the prologue falls too early
    ('uc3', "Uncharted 3: Drake's Deception", 'big3-ps4_Shipping.elf', ('Jogável · 60 FPS', 'Playable · 60 FPS'),
     {'GOW3_COMPUTE_PIPE': '1'}),
]
# ucndc.ini values the games were tested with, written when the file does not exist yet.
# Only keys the Uncharted games use (render_resolution, engine_fps, fsr1, rcas and upscaler are GoW3 patches).
INI_DEFAULTS = {'display_mode': 'windowed', 'vsync': '1', 'fps_limit': '0',
                'async_shaders': '1', 'frames_queued': '1',
                'deferred_readback': '1', 'stale_readback': '0', 'show_fps': '1', 'overlay_fps': '1'}
DISPLAY_MODES = [('windowed', ('Janela', 'Window')), ('borderless', ('Janela sem borda', 'Borderless')),
                 ('fullscreen', ('Tela cheia', 'Fullscreen'))]
DEFAULTS = {'game_dir': '', 'update_dir': '', 'language': '', 'last_game': 'uc3', 'disabled': []}
# What each optimization does (Options > Advanced); the games turn them on by default.
TOGGLE_HELP = {
    'GOW3_READBACK_ASYNC': ('Leituras da GPU sem parar a fila', 'GPU readbacks without draining the queue'),
    'GOW3_READBACK_FAST': ('Leitura rápida de buffers', 'Fast buffer readbacks'),
    'GOW3_READBACK_FAST_WAIT': ('Espera só a escrita certa da GPU', 'Wait only for the right GPU write'),
    'GOW3_READBACK_FAST_SIDEQ': ('Leituras na fila de transferência', 'Readbacks on the transfer queue'),
    'GOW3_READBACK_SIDEQ': ('Fila lateral de leituras', 'Side readback queue'),
    'GOW3_SCALAR_COPY_HLE': ('Cópia de constantes em código nativo', 'Constant copies in native code'),
    'GOW3_HLE_INVALIDATE': ('Avisa os caches da cópia nativa (evita texturas trocadas)', 'Tell caches about native copies (no swapped textures)'),
    'GOW3_COMPUTE_PIPE': ('Atalho no processamento de compute (UC1 e UC3)', 'Compute processing shortcut (UC1 and UC3)'),
    'GOW3_LEAF_COPY': ('Cópia das folhas de recursos', 'Resource leaf copies'),
    'GOW3_EAGER_SUBMIT': ('Envia comandos à GPU mais cedo', 'Submit GPU work earlier'),
    'GOW3_EAGER_SUBMIT_US': ('Intervalo do envio antecipado', 'Early submit interval'),
    'GOW3_ASYNC_SUBMIT': ('Envio em outra thread', 'Submit on another thread'),
    'GOW3_DEFERRED_SUBMIT': ('Envio adiado sem esperar a gravação', 'Deferred submit without waiting'),
    'GOW3_RING_COPY_THREAD': ('Cópias do ring buffer em outra thread', 'Ring buffer copies on another thread'),
    'GOW3_RING_DEDUP': ('Não repete cópias do ring buffer', 'Skip repeated ring buffer copies'),
    'GOW3_FRAME_STATS': ('Estatísticas de quadro no log', 'Frame statistics in the log'),
    'GOW3_EOP_TICK_WAIT': ('Evita o erro m_gfxEopTick (UC1)', 'Avoid the m_gfxEopTick assert (UC1)'),
    'GOW3_AJM_END_RULES': ('Regras de áudio do Ajm (UC1)', 'Ajm audio rules (UC1)'),
}
# The Uncharted engines run at 60 at most, so higher limits would do nothing.
FPS_LIMITS = [('0', ('Sem limite (60)', 'No limit (60)')), ('30', ('30', '30')), ('60', ('60', '60'))]
# Fixed per game, never a user switch: UC1 needs LOW_IMAGE, AJM_END_RULES and EOP_TICK_WAIT; READBACKS
# keeps the scenery; FRAME_STATS is only a log aid; HLE_INVALIDATE follows SCALAR_COPY_HLE (texture swaps).
FIXED = {'GOW3_READBACKS', 'GOW3_FRAME_STATS', 'GOW3_LOW_IMAGE', 'GOW3_AJM_END_RULES', 'GOW3_EOP_TICK_WAIT',
         'GOW3_HLE_INVALIDATE'}

BG, PANEL, CARD, TEXT, MUTED, ACCENT, OK, BAD = ('#15130f', '#1f1b16', '#2a241c', '#efe6d4', '#a89c86',
                                                 '#d9a441', '#7fbf6a', '#d9674f')
LANG = 'pt'


def _(pt, en):
    return pt if LANG == 'pt' else en


# ---------------------------------------------------------------------------------------------
# Settings, game folders, environment

def load_settings():
    try:
        return {**DEFAULTS, **json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))}
    except (OSError, ValueError):
        return dict(DEFAULTS)


def save_settings(settings):
    try:
        SETTINGS_FILE.write_text(json.dumps(settings, indent=2), encoding='utf-8')
    except OSError:
        pass


def system_language():
    if WINDOWS:
        try:
            lang_id = ctypes.windll.kernel32.GetUserDefaultUILanguage() & 0x3ff
            return 'pt' if lang_id == 0x16 else 'en'
        except (AttributeError, OSError):
            pass
    return 'pt' if os.environ.get('LANG', '').lower().startswith('pt') else 'en'


def title_id(folder):
    """The TITLE_ID of a dump folder (sce_sys/param.sfo), or None."""
    try:
        from prepare import sfo
        return sfo((Path(folder) / 'sce_sys' / 'param.sfo').read_bytes()).get('TITLE_ID')
    except (OSError, ValueError, ImportError):
        return None


def is_update(folder):
    return (Path(folder) / 'sce_discmap_patch.plt').is_file()


def guess_update(game_dir):
    """The update folder next to the game: CUSA02344-UPDATE, CUSA02344-patch, ...patch."""
    game = Path(game_dir)
    for candidate in (game.parent / f'{game.name}-UPDATE', game.parent / f'{game.name}-patch',
                      game.parent / f'{game.name}_UPDATE', game.parent / 'patch'):
        if candidate.is_dir() and is_update(candidate):
            return str(candidate)
    return ''


def guess_game(update_dir):
    """The base game next to an update folder that was picked by mistake."""
    update = Path(update_dir)
    for suffix in ('-UPDATE', '-patch', '_UPDATE'):
        if update.name.endswith(suffix):
            base = update.parent / update.name[:-len(suffix)]
            if base.is_dir() and not is_update(base):
                return str(base)
    return ''


def available(settings, executable):
    return bool(settings['game_dir']) and (Path(settings['game_dir']) / executable).is_file()


def ensure_ini(updates=None):
    """ucndc.ini with the tested values, plus what the Options window changed."""
    import graphics_options
    adopt_old_ini()
    if not INI_FILE.is_file():
        graphics_options.write_keys(INI_FILE, INI_DEFAULTS)
    if updates:
        graphics_options.write_keys(INI_FILE, updates)


def adopt_old_ini():
    """Keep the settings of an older install: copy gow3.ini to ucndc.ini once (run.py does the same)."""
    if not INI_FILE.is_file() and OLD_INI_FILE.is_file():
        try:
            shutil.copyfile(OLD_INI_FILE, INI_FILE)
        except OSError:
            pass


def read_ini():
    adopt_old_ini()
    try:
        import graphics_options
        return {**INI_DEFAULTS, **graphics_options.read_ini(INI_FILE)}
    except ImportError:
        return dict(INI_DEFAULTS)


def display_mode():
    try:
        import graphics_options
        mode = graphics_options.read_ini(INI_FILE).get('display_mode', 'windowed')
    except ImportError:
        mode = 'windowed'
    return mode if mode in {m for m, _n in DISPLAY_MODES} else 'windowed'


def public_name(name):
    """The documented name of a toggle: UC_X (the port still accepts the older GOW3_X)."""
    return 'UC_' + name[len('GOW3_'):] if name.startswith('GOW3_') else name


def game_environment(settings, key):
    _k, _title, executable, _status, extra = next(g for g in GAMES if g[0] == key)
    env = dict(os.environ)
    for name in [n for n in env if n.startswith(('GOW3_', 'UC_'))]:
        del env[name]  # only what this launcher decides
    env.update(COMMON)
    env.update(extra)
    for name in settings.get('disabled', []):
        if name not in FIXED:
            env.pop(name, None)  # turned off in Options > Advanced
    if 'GOW3_SCALAR_COPY_HLE' not in env:
        env.pop('GOW3_HLE_INVALIDATE', None)
    env['GOW3_GAME_DIR'] = settings['game_dir']
    if settings.get('update_dir'):
        env['GOW3_PATCH_DIR'] = settings['update_dir']
    env['GOW3_EXECUTABLE'] = executable
    env['GOW3_DATA_DIR'] = str(DATA_DIR)
    env['GOW3_USER_DIR'] = str(USER_DIR)
    return env


def run_command():
    return [sys.executable, '--run'] if FROZEN else [sys.executable, str(PORT_DIR / 'run.py')]


# ---------------------------------------------------------------------------------------------
# Roles of the frozen executable

def attach_stdio():
    """A windowed executable starts without sys.stdout; inherited pipes or a console still exist."""
    if not WINDOWS:
        return
    import msvcrt
    for name, std in (('stdout', -11), ('stderr', -12)):
        if getattr(sys, name) is not None:
            continue
        stream = None
        handle = ctypes.windll.kernel32.GetStdHandle(std)
        if handle and handle != ctypes.c_void_p(-1).value:
            try:
                stream = open(msvcrt.open_osfhandle(handle, os.O_WRONLY), 'w', encoding='utf-8',
                              errors='replace', buffering=1)
            except OSError:
                stream = None
        setattr(sys, name, stream or open(os.devnull, 'w'))


def run_role(argv):
    """--run [args]: run.py; --script <file> [args]: a preparation script. Returns an exit code."""
    attach_stdio()
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(line_buffering=True)
        except (AttributeError, ValueError):
            pass
    if argv[0] == '--script':
        path, sys.argv = argv[1], argv[1:]
    else:
        path = str(PORT_DIR / 'run.py')
        sys.argv = [path, *argv[1:]]
    try:
        runpy.run_path(path, run_name='__main__')
    except SystemExit as stop:
        return stop.code if isinstance(stop.code, int) else (0 if stop.code is None else 1)
    return 0


class GameJob:
    """A Windows job holding run.py and everything it starts (ucndc.exe, in-game restarts),
    so closing the launcher's Stop button ends all of it."""

    def __init__(self, process):
        self.handle = None
        if not WINDOWS:
            return
        kernel32 = ctypes.windll.kernel32
        kernel32.CreateJobObjectW.restype = ctypes.c_void_p
        self.handle = kernel32.CreateJobObjectW(None, None)
        if self.handle:
            kernel32.AssignProcessToJobObject(ctypes.c_void_p(self.handle), ctypes.c_void_p(int(process._handle)))

    def terminate(self):
        if self.handle:
            ctypes.windll.kernel32.TerminateJobObject(ctypes.c_void_p(self.handle), 1)

    def close(self):
        if self.handle:
            ctypes.windll.kernel32.CloseHandle(ctypes.c_void_p(self.handle))
            self.handle = None


def start_game(settings, key):
    USER_DIR.mkdir(parents=True, exist_ok=True)
    ensure_ini()
    return subprocess.Popen(run_command(), cwd=PORT_DIR, env=game_environment(settings, key),
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            creationflags=NO_WINDOW)


def play_without_window(settings, key):
    """--play uc3: a desktop shortcut starts the game directly; the log goes to user\\last_run.log."""
    attach_stdio()
    process = start_game(settings, key)
    with open(LOG_FILE, 'w', encoding='utf-8', buffering=1) as log:
        for raw in iter(process.stdout.readline, b''):
            log.write(raw.decode('utf-8', errors='replace'))
    return process.wait()


def make_shortcut(key, title):
    """A desktop shortcut that runs `<launcher> --play <key>` (through Windows Script Host)."""
    target = sys.executable if FROZEN else str(Path(sys.executable).with_name('pythonw.exe'))
    arguments = f'--play {key}' if FROZEN else f'"{Path(__file__).resolve()}" --play {key}'
    icon = str(PORT_DIR / 'launcher' / 'uc.ico') if (PORT_DIR / 'launcher' / 'uc.ico').is_file() else target
    script = ('$s=(New-Object -ComObject WScript.Shell);'
              '$d=[Environment]::GetFolderPath("Desktop");'
              f'$l=$s.CreateShortcut((Join-Path $d "{title}.lnk"));'
              f'$l.TargetPath="{target}";$l.Arguments="{arguments}";'
              f'$l.WorkingDirectory="{PORT_DIR}";$l.IconLocation="{icon}";$l.Save()')
    result = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', script],
                            stdin=subprocess.DEVNULL, capture_output=True, creationflags=NO_WINDOW)
    return result.returncode == 0


def open_path(path):
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    if WINDOWS:
        os.startfile(str(path))  # noqa: S606 (opens Explorer / the default text editor)
    else:
        subprocess.Popen(['xdg-open', str(path)])


# ---------------------------------------------------------------------------------------------
# Window

class Launcher:
    def __init__(self, root, tk, ttk, filedialog, messagebox):
        self.root, self.tk, self.ttk = root, tk, ttk
        self.filedialog, self.messagebox = filedialog, messagebox
        self.settings = load_settings()
        self.process = self.job = None
        self.output = queue.Queue()
        self.log_visible = False
        root.title(f'{APP_NAME} {VERSION}')
        root.configure(bg=BG)
        root.minsize(760, 560)
        icon = PORT_DIR / 'launcher' / 'uc.ico'
        if WINDOWS and icon.is_file():
            try:
                root.iconbitmap(str(icon))
            except tk.TclError:
                pass
        root.protocol('WM_DELETE_WINDOW', self.close)
        self.build()
        self.refresh()
        root.after(100, self.drain_output)

    # -- layout -----------------------------------------------------------------------------
    def label(self, parent, text, size=10, color=TEXT, bold=False, **kw):
        return self.tk.Label(parent, text=text, bg=parent['bg'], fg=color,
                             font=('Segoe UI', size, 'bold' if bold else 'normal'), **kw)

    def button(self, parent, text, command, primary=False, **kw):
        return self.tk.Button(parent, text=text, command=command, relief='flat', cursor='hand2',
                              bg=ACCENT if primary else CARD, fg='#1a1408' if primary else TEXT,
                              activebackground='#f0bf5a' if primary else '#3a3226',
                              activeforeground='#1a1408' if primary else TEXT,
                              font=('Segoe UI', 11 if primary else 10, 'bold' if primary else 'normal'),
                              padx=14, pady=6 if primary else 4, bd=0, **kw)

    def build(self):
        tk = self.tk
        for child in self.root.winfo_children():
            child.destroy()
        top = tk.Frame(self.root, bg=BG)
        top.pack(fill='x', padx=24, pady=(20, 6))
        self.label(top, 'UNCHARTED', 22, ACCENT, True).pack(side='left')
        self.label(top, '  The Nathan Drake Collection · PC', 13, MUTED).pack(side='left', pady=(8, 0))
        lang = self.button(top, 'English' if LANG == 'pt' else 'Português', self.toggle_language)
        lang.pack(side='right')

        # Folders
        box = tk.Frame(self.root, bg=PANEL)
        box.pack(fill='x', padx=24, pady=8)
        inner = tk.Frame(box, bg=PANEL)
        inner.pack(fill='x', padx=16, pady=12)
        self.label(inner, _('1. Seu dump do PS4', '1. Your PS4 dump'), 11, TEXT, True).grid(row=0, column=0, sticky='w', columnspan=3)
        self.game_var = tk.StringVar(value=self.settings['game_dir'])
        self.update_var = tk.StringVar(value=self.settings['update_dir'])
        for row, (title, var, command) in enumerate((
                (_('Pasta do jogo', 'Game folder'), self.game_var, self.pick_game),
                (_('Atualização (01.02)', 'Update (01.02)'), self.update_var, self.pick_update)), start=1):
            self.label(inner, title, 10, MUTED).grid(row=row, column=0, sticky='w', pady=3)
            entry = tk.Entry(inner, textvariable=var, bg=CARD, fg=TEXT, insertbackground=TEXT, relief='flat',
                             font=('Segoe UI', 10), state='readonly', readonlybackground=CARD)
            entry.grid(row=row, column=1, sticky='we', padx=10, ipady=4)
            self.button(inner, _('Escolher…', 'Browse…'), command).grid(row=row, column=2)
        inner.columnconfigure(1, weight=1)
        self.dump_status = self.label(inner, '', 9, MUTED, justify='left', wraplength=680)
        self.dump_status.grid(row=3, column=0, columnspan=3, sticky='w', pady=(6, 0))

        # Games
        self.label(self.root, _('2. Escolha o jogo', '2. Pick a game'), 11, TEXT, True).pack(anchor='w', padx=24, pady=(10, 4))
        cards = tk.Frame(self.root, bg=BG)
        cards.pack(fill='x', padx=24)
        self.play_buttons = {}
        for column, (key, title, executable, status, _extra) in enumerate(GAMES):
            card = tk.Frame(cards, bg=CARD)
            card.grid(row=0, column=column, sticky='nsew', padx=(0 if column == 0 else 8, 0))
            cards.columnconfigure(column, weight=1, uniform='games')
            self.label(card, title, 11, TEXT, True, wraplength=220, justify='left').pack(anchor='w', padx=12, pady=(12, 2))
            self.label(card, status[0] if LANG == 'pt' else status[1], 9,
                       OK if key != 'uc1' else ACCENT).pack(anchor='w', padx=12)
            button = self.button(card, _('JOGAR', 'PLAY'), lambda k=key: self.play(k), primary=True)
            button.pack(fill='x', padx=12, pady=12)
            self.play_buttons[key] = button

        # Options and tools
        bar = tk.Frame(self.root, bg=BG)
        bar.pack(fill='x', padx=24, pady=(12, 4))
        for text, command in ((_('Opções', 'Options'), self.options),
                              (_('Pasta de saves', 'Saves folder'), lambda: open_path(USER_DIR)),
                              (_('Log', 'Log'), self.toggle_log),
                              (_('Atalho na área de trabalho', 'Desktop shortcut'), self.shortcut)):
            self.button(bar, text, command).pack(side='left', padx=(0, 6))
        self.stop_button = self.button(bar, _('Parar o jogo', 'Stop the game'), self.stop)
        self.stop_button.pack(side='right')

        self.status = self.label(self.root, '', 10, MUTED, justify='left', wraplength=700)
        self.status.pack(anchor='w', padx=24, pady=(8, 4))
        self.log_frame = tk.Frame(self.root, bg=BG)
        self.log = tk.Text(self.log_frame, bg='#0d0c0a', fg='#cfc6b4', font=('Consolas', 9), relief='flat',
                           height=12, state='disabled', wrap='none')
        self.log.pack(fill='both', expand=True)
        if self.log_visible:
            self.log_frame.pack(fill='both', expand=True, padx=24, pady=(0, 16))
        footer = self.label(self.root, _('Port não oficial, sem relação com a Sony ou a Naughty Dog. Nenhum arquivo do jogo vem neste pacote: use o dump do seu próprio PS4.',
                                         'Unofficial port, not affiliated with Sony or Naughty Dog. No game files are included: use a dump of your own PS4.'), 8, MUTED)
        footer.pack(side='bottom', anchor='w', padx=24, pady=(0, 10))

    # -- state ------------------------------------------------------------------------------
    def refresh(self):
        s = self.settings
        lines, ok = [], bool(s['game_dir'])
        if not s['game_dir']:
            lines.append(_('Escolha a pasta do jogo (CUSA02344): a que tem eboot.bin, big2-ps4_Shipping.elf e big3-ps4_Shipping.elf.',
                           'Choose the game folder (CUSA02344): the one with eboot.bin, big2-ps4_Shipping.elf and big3-ps4_Shipping.elf.'))
        else:
            found = [title for key, title, exe, _s, _e in GAMES if available(s, exe)]
            if not found:
                ok = False
                lines.append(_('Essa pasta não tem os executáveis da coletânea.', 'This folder has none of the collection executables.'))
            tid = title_id(s['game_dir'])
            if tid and tid != TESTED_TITLE:
                lines.append(_(f'Versão {tid}: só a {TESTED_TITLE} foi testada; pode não funcionar.',
                               f'Version {tid}: only {TESTED_TITLE} was tested; it may not work.'))
            if not s['update_dir']:
                lines.append(_('Sem a atualização 01.02: o jogo roda, mas os testes foram feitos com ela.',
                               'Without the 01.02 update: the game runs, but it was tested with it.'))
        if ok and not lines:
            lines.append(_('Tudo certo. Escolha um jogo.', 'All set. Pick a game.'))
        self.dump_status.configure(text='\n'.join(lines), fg=OK if ok and len(lines) == 1 and s['update_dir'] else MUTED)
        running = self.process is not None
        for key, _t, exe, _s, _e in GAMES:
            self.play_buttons[key].configure(state='normal' if available(s, exe) and not running else 'disabled')
        self.stop_button.configure(state='normal' if running else 'disabled')

    def toggle_language(self):
        global LANG
        LANG = 'en' if LANG == 'pt' else 'pt'
        self.settings['language'] = LANG
        save_settings(self.settings)
        self.build()
        self.refresh()

    def pick_game(self):
        folder = self.filedialog.askdirectory(title=_('Pasta do jogo (CUSA02344)', 'Game folder (CUSA02344)'))
        if not folder:
            return
        if is_update(folder):  # the update was picked: use it as such and look for the game
            self.settings['update_dir'] = folder
            folder = guess_game(folder) or ''
            if not folder:
                self.messagebox.showinfo(APP_NAME, _('Essa é a pasta da atualização. Agora escolha a pasta do jogo base.',
                                                     'That is the update folder. Now choose the base game folder.'))
        if folder:
            self.settings['game_dir'] = folder
            if not self.settings['update_dir']:
                self.settings['update_dir'] = guess_update(folder)
        self.game_var.set(self.settings['game_dir'])
        self.update_var.set(self.settings['update_dir'])
        save_settings(self.settings)
        self.refresh()

    def pick_update(self):
        folder = self.filedialog.askdirectory(title=_('Pasta da atualização 01.02', 'Update 01.02 folder'))
        if not folder:
            return
        if not is_update(folder):
            self.messagebox.showwarning(APP_NAME, _('Essa pasta não parece uma atualização (falta sce_discmap_patch.plt).',
                                                    'This folder does not look like an update (no sce_discmap_patch.plt).'))
            return
        self.settings['update_dir'] = folder
        self.update_var.set(folder)
        save_settings(self.settings)
        self.refresh()

    def options(self):
        """Options window: display, VSync, FPS limit and overlay (ucndc.ini); Advanced: the
        optimizations each game starts with (launcher.json 'disabled')."""
        tk, ttk = self.tk, self.ttk
        win = tk.Toplevel(self.root, bg=BG)
        win.title(_('Opções', 'Options'))
        win.transient(self.root)
        win.resizable(False, True)
        ini = read_ini()
        body = tk.Frame(win, bg=PANEL)
        body.pack(fill='both', expand=True, padx=16, pady=16)
        pick = lambda pairs: [n[0] if LANG == 'pt' else n[1] for _v, n in pairs]
        self.label(body, _('Imagem e desempenho', 'Display and performance'), 11, TEXT, True).grid(row=0, column=0, columnspan=2, sticky='w', pady=(0, 6))
        mode = tk.StringVar(value=pick(DISPLAY_MODES)[[m for m, _n in DISPLAY_MODES].index(display_mode())])
        limit_values = [v for v, _n in FPS_LIMITS]
        limit = tk.StringVar(value=pick(FPS_LIMITS)[limit_values.index(ini.get('fps_limit', '0'))
                                                    if ini.get('fps_limit', '0') in limit_values else 0])
        vsync = tk.BooleanVar(value=ini.get('vsync', '1') != '0')
        fps = tk.BooleanVar(value=ini.get('overlay_fps', '1') != '0' and ini.get('show_fps', '1') != '0')
        rows = ((_('Tela', 'Display'), ttk.Combobox(body, values=pick(DISPLAY_MODES), state='readonly', width=18, textvariable=mode)),
                (_('Limite de FPS', 'FPS limit'), ttk.Combobox(body, values=pick(FPS_LIMITS), state='readonly', width=18, textvariable=limit)))
        for row, (title, widget) in enumerate(rows, start=1):
            self.label(body, title, 10, MUTED).grid(row=row, column=0, sticky='w', pady=3)
            widget.grid(row=row, column=1, sticky='w', padx=10)
        check = dict(bg=PANEL, fg=TEXT, selectcolor=CARD, activebackground=PANEL, activeforeground=TEXT,
                     font=('Segoe UI', 10), anchor='w')
        tk.Checkbutton(body, text=_('VSync (sem tearing; recomendado)', 'VSync (no tearing; recommended)'),
                       variable=vsync, **check).grid(row=3, column=0, columnspan=2, sticky='w')
        tk.Checkbutton(body, text=_('Mostrar FPS na tela', 'Show FPS on screen'), variable=fps, **check).grid(row=4, column=0, columnspan=2, sticky='w')
        self.label(body, _('Os jogos rodam a no máximo 60 FPS. No jogo, Insert (ou R3+L2) abre o menu do port.',
                           'The games run at 60 FPS at most. In game, Insert (or R3+L2) opens the port menu.'),
                   9, MUTED, wraplength=440, justify='left').grid(row=5, column=0, columnspan=2, sticky='w', pady=(4, 10))

        advanced = tk.Frame(body, bg=PANEL)
        disabled = set(self.settings.get('disabled', []))
        toggles = {}
        names = list(COMMON) + [k for g in GAMES for k in g[4] if k not in COMMON]
        self.label(advanced, _('Otimizações (todas ligadas = configuração testada)',
                               'Optimizations (all on = tested setup)'), 10, ACCENT, True).pack(anchor='w', pady=(0, 4))
        for name in dict.fromkeys(names):
            if name in FIXED:
                continue
            toggles[name] = tk.BooleanVar(value=name not in disabled)
            help_text = TOGGLE_HELP.get(name, ('', ''))[0 if LANG == 'pt' else 1]
            tk.Checkbutton(advanced, text=f'{help_text}  ({public_name(name)})', variable=toggles[name], **check).pack(anchor='w')
        shown = tk.BooleanVar(value=bool(disabled))

        def show_advanced():
            if shown.get():
                advanced.grid(row=7, column=0, columnspan=2, sticky='w', pady=(6, 0))
            else:
                advanced.grid_forget()
        tk.Checkbutton(body, text=_('Avançado: escolher otimizações', 'Advanced: choose optimizations'),
                       variable=shown, command=show_advanced, **check).grid(row=6, column=0, columnspan=2, sticky='w')
        show_advanced()

        def reset():
            for var in toggles.values():
                var.set(True)
            vsync.set(True)
            fps.set(True)
            limit.set(pick(FPS_LIMITS)[0])

        def save():
            ensure_ini({'display_mode': DISPLAY_MODES[pick(DISPLAY_MODES).index(mode.get())][0],
                        'vsync': '1' if vsync.get() else '0',
                        'fps_limit': FPS_LIMITS[pick(FPS_LIMITS).index(limit.get())][0],
                        'show_fps': '1' if fps.get() else '0', 'overlay_fps': '1' if fps.get() else '0'})
            self.settings['disabled'] = sorted(k for k, v in toggles.items() if not v.get())
            save_settings(self.settings)
            win.destroy()
        buttons = tk.Frame(win, bg=BG)
        buttons.pack(fill='x', padx=16, pady=(0, 16))
        self.button(buttons, _('Salvar', 'Save'), save, primary=True).pack(side='right')
        self.button(buttons, _('Cancelar', 'Cancel'), win.destroy).pack(side='right', padx=6)
        self.button(buttons, _('Restaurar padrão', 'Restore defaults'), reset).pack(side='left')
        win.grab_set()

    def toggle_log(self):
        self.log_visible = not self.log_visible
        if self.log_visible:
            self.log_frame.pack(fill='both', expand=True, padx=24, pady=(0, 16))
        else:
            self.log_frame.pack_forget()

    def shortcut(self):
        key = self.settings.get('last_game', 'uc3')
        title = next(t for k, t, *_r in GAMES if k == key)
        if make_shortcut(key, title.split(':')[0] if key != 'uc1' else 'Uncharted'):
            self.messagebox.showinfo(APP_NAME, _(f'Atalho criado na área de trabalho para {title}.',
                                                 f'Desktop shortcut created for {title}.'))
        else:
            self.messagebox.showerror(APP_NAME, _('Não foi possível criar o atalho.', 'Could not create the shortcut.'))

    # -- game -------------------------------------------------------------------------------
    def play(self, key):
        if self.process:
            return
        self.settings['last_game'] = key
        save_settings(self.settings)
        self.set_log('')
        try:
            self.process = start_game(self.settings, key)
        except OSError as error:
            self.status.configure(text=_(f'Não foi possível iniciar: {error}', f'Could not start: {error}'), fg=BAD)
            return
        self.job = GameJob(self.process)
        self.log_file = open(LOG_FILE, 'w', encoding='utf-8', buffering=1)
        threading.Thread(target=self.read_output, args=(self.process,), daemon=True).start()
        self.status.configure(text=_('Preparando o jogo… ele abre na própria janela. Na primeira vez demora mais '
                                     'e pode engasgar enquanto os shaders são criados.',
                                     'Preparing the game… it opens in its own window. The first start takes longer '
                                     'and may stutter while shaders are built.'), fg=TEXT)
        self.refresh()

    def read_output(self, process):
        for raw in iter(process.stdout.readline, b''):
            self.output.put(raw.decode('utf-8', errors='replace'))
        self.output.put((process.wait(),))

    def drain_output(self):
        try:
            for _i in range(500):
                item = self.output.get_nowait()
                if isinstance(item, tuple):
                    self.finished(item[0])
                    continue
                self.log_file.write(item)
                if 'Entering original x86-64 code' in item:
                    self.status.configure(text=_('Jogo rodando. Insert (ou R3+L2) abre o menu do port.',
                                                 'Game running. Insert (or R3+L2) opens the port menu.'), fg=OK)
                self.append(item)
        except queue.Empty:
            pass
        self.root.after(100, self.drain_output)

    def finished(self, code):
        self.process = None
        if self.job:
            self.job.close()
            self.job = None
        self.log_file.close()
        if code:
            self.status.configure(text=_(f'O jogo fechou com erro (código {code}). Veja o Log; '
                                         'ao relatar, envie user\\last_run.log.',
                                         f'The game closed with an error (code {code}). See the Log; '
                                         'when reporting, send user\\last_run.log.'), fg=BAD)
            if not self.log_visible:
                self.toggle_log()
        else:
            self.status.configure(text=_('O jogo fechou.', 'The game closed.'), fg=MUTED)
        self.refresh()

    def stop(self):
        if self.job:
            self.job.terminate()

    def append(self, text):
        self.log.configure(state='normal')
        self.log.insert('end', text)
        lines = int(self.log.index('end-1c').split('.')[0])
        if lines > 4000:
            self.log.delete('1.0', f'{lines - 4000}.0')
        self.log.see('end')
        self.log.configure(state='disabled')

    def set_log(self, text):
        self.log.configure(state='normal')
        self.log.delete('1.0', 'end')
        self.log.insert('end', text)
        self.log.configure(state='disabled')

    def close(self):
        if self.process and not self.messagebox.askyesno(
                APP_NAME, _('O jogo ainda está aberto. Fechar o launcher também fecha o jogo. Continuar?',
                            'The game is still running. Closing the launcher also closes the game. Continue?')):
            return
        self.stop()
        self.root.destroy()


def main():
    global LANG
    args = sys.argv[1:]
    if args and args[0] in ('--run', '--script'):
        sys.exit(run_role(args))
    settings = load_settings()
    LANG = settings.get('language') or system_language()
    if args[:1] == ['--play'] and len(args) == 2 and settings['game_dir'] and args[1] in {g[0] for g in GAMES}:
        sys.exit(play_without_window(settings, args[1]))
    if WINDOWS:
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (AttributeError, OSError):
            pass
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    root = tk.Tk()
    Launcher(root, tk, ttk, filedialog, messagebox)
    root.mainloop()


if __name__ == '__main__':
    main()
