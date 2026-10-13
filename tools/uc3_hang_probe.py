#!/usr/bin/env python3
"""uc3: drives Uncharted 3 to "New Game" with scripted input (GOW3_PAD_FILE) and, when the game
stops making progress, saves the backtraces of every thread with lldb.

Run in the MSYS2 CLANG64 environment with GOW3_GAME_DIR, GOW3_PATCH_DIR and GOW3_EXECUTABLE set:
    python tools/uc3_hang_probe.py [--seconds 240] [--tap 2.5]
Output: out/hang-run.log (game log), out/hang-backtrace-full.txt (when a hang is seen).
A hang: no new log line (besides the pad echo) for --quiet seconds while the process uses almost no CPU.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

PORT = Path(__file__).resolve().parent.parent


def process_cpu_seconds(pid):
    """Total CPU time of a Windows process, through PowerShell."""
    out = subprocess.run(['powershell', '-NoProfile', '-Command', f'(Get-Process -Id {pid}).CPU'],
                         capture_output=True, text=True).stdout.strip().replace(',', '.')
    try:
        return float(out)
    except ValueError:
        return None


def progress_lines(log):
    with log.open(encoding='utf-8', errors='replace') as f:
        return sum(1 for line in f if not line.startswith('Runtime: pad file:'))


def probe_pid():
    out = subprocess.run(['powershell', '-NoProfile', '-Command',
                          '(Get-Process gow3-probe -ErrorAction SilentlyContinue | Select-Object -First 1).Id'],
                         capture_output=True, text=True).stdout.strip()
    return int(out) if out.isdigit() else None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--seconds', type=float, default=240, help='give up after this long')
    p.add_argument('--tap', type=float, default=2.5, help='press Cross every this many seconds')
    p.add_argument('--quiet', type=float, default=25, help='log silent this long = possible hang')
    a = p.parse_args()
    out = PORT / 'out'
    pad = out / 'pad-script.txt'
    pad.write_text('')
    log = out / 'hang-run.log'
    env = dict(os.environ, GOW3_PAD_FILE=str(pad))
    with log.open('w', encoding='utf-8', errors='replace') as f:
        game = subprocess.Popen([sys.executable, str(PORT / 'run.py')], cwd=PORT, env=env,
                                stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    start = time.monotonic()
    last_size, last_growth, next_tap, pressed = 0, start, start + 20, False
    try:
        while game.poll() is None and time.monotonic() - start < a.seconds:
            now = time.monotonic()
            # Tap Cross: held 0.3 s, then released.
            if now >= next_tap and not pressed:
                pad.write_text('cross\n'); pressed = True
            elif pressed and now >= next_tap + 0.3:
                pad.write_text(''); pressed = False; next_tap = now + a.tap
            # Progress = new log lines other than the pad file's own echo of each tap.
            size = progress_lines(log)
            if size != last_size:
                last_size, last_growth = size, now
            elif now - last_growth > a.quiet:
                pid = probe_pid()
                if pid:
                    c1 = process_cpu_seconds(pid); time.sleep(5); c2 = process_cpu_seconds(pid)
                    if c1 is not None and c2 is not None and c2 - c1 < 0.5:
                        print(f'hang: log silent {now - last_growth:.0f} s, CPU {c2 - c1:.2f} s in 5 s; dumping threads')
                        trace = out / 'hang-backtrace-full.txt'
                        with trace.open('w', encoding='utf-8', errors='replace') as t:
                            subprocess.run(['lldb', '-p', str(pid), '-b', '-o', 'thread backtrace all -c 60',
                                            '-o', 'process detach'], stdout=t, stderr=subprocess.STDOUT)
                        print(f'backtraces: {trace}')
                        break
                last_growth = now  # busy, or no process yet: keep watching
            time.sleep(0.1)
        else:
            print('no hang seen' if game.poll() is None else f'game ended (exit {game.returncode})')
    finally:
        pad.write_text('')
        subprocess.run(['taskkill', '/F', '/IM', 'gow3-probe.exe'], capture_output=True)
        game.wait(timeout=30)


if __name__ == '__main__':
    main()
