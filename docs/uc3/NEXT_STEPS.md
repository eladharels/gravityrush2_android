# Uncharted 3 PC port: next steps

State on 2026-10-14: the port builds and runs on the new machine (`D:\Uncharted\uc3_pc`, RX 6750 XT).
It is playable from the start up to "The Chateau" (27%), with sound, controller, saves and cutscenes.
Imports: 0 missing. Old saves were copied over, including SAVEDATA038 (The Chateau).

## 1. Housekeeping (small, do first)

- Commit today's uncommitted changes: `tools/run_uc3.ps1` (falls back to `CUSA02344` / `CUSA02344-UPDATE`
  when the old `app0`/`patch` junctions are missing), `tools/testar_ambiente.sh`, and the new DEVLOG entry.
  Do not commit the ~70 SPIRV-Headers files under `gpu/third_party/sirit` (CRLF-only changes).
- Fix the test list in `testar_ambiente.sh`: `sema-test`, `runtime-test` and `pad-test` are not real targets.

## 2. Performance

- **Fullscreen vs. windowed:** the player measured 21–22 FPS in fullscreen and 24–27 FPS windowed, in the
  same scene. The monitor is 1920x1080, the same as the game image, so resolution is not the cause.
  Next: measure with `GOW3_FRAME_STATS=1` in both modes and compare exclusive fullscreen/FIFO
  presentation against DWM-composed windowed mode. If windowed stays faster, make Borderless or
  Windowed the default.
- **Precise readback cost** (the biggest open item): in the museum and Cartagena streets, read faults
  cost 20–30 ms per slow frame, because each one drains the whole GPU queue (`scheduler.Finish()`).
  Options, in order:
  1. Wait only for the tick of the last GPU write to that range instead of `Finish()`.
  2. At end of pipe, batch-download the ranges the CPU read in the previous frame.
  3. Track and download per frame.
  Always measure in both modes (`GOW3_READBACKS=2` precise, `=1` relaxed) before working on a scene.
  In The Chateau the readback costs only 2–4 FPS. There the limit is GPU sync (about 145 submissions
  per frame), and shadPS4 is just as slow.

## 3. Visual bugs

- Triage the 16 F11 captures from the 2-hour session (`user/captures/frame_20261013-19*`).
  They have no description yet.
- One-frame black flashes: not reproduced since tracing was added. Keep `GOW3_FLASH_TRACE=1` handy.
- Grey screen when launching through `eboot.bin` (collection mode).
- Core bugs shared with shadPS4 (white hair after loading a checkpoint, yellow dust dots): not port
  work. Pick up the upstream fixes when shadPS4 has them.

## 4. Progress through the game

- Keep playing past The Chateau (27%). Note crashes with `GOW3_FAULT_HOLD` and take lldb backtraces.

## 5. Phase 5 (later)

- Launcher, resolution/FPS patches and polish.
- Renaming `gow3-*` / `GOW3_*` to UC3 names is still deferred.

## Rules

- Never build or run tests while `gow3-probe.exe` is open.
- Don't touch the game while the player is playing.
- Update the DEVLOG and commit at each step.
- Only use the player's own game dump.
