"""uc3: aggregates the 'Stall:' frames of a port log: where the time of the slow frames went."""
import re, sys
lines = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
stalls = []
for i, l in enumerate(lines):
    m = re.match(r'Stall: ([\d.]+) ms frame; waits: readback ([\d.]+) ms, GPU ticks ([\d.]+) ms, recorder ([\d.]+) ms, host copies ([\d.]+) ms; (\d+) compiles ([\d.]+) ms; GC ([\d.]+) ms', l)
    if not m:
        continue
    d = dict(frame=float(m[1]), readback=float(m[2]), ticks=float(m[3]), recorder=float(m[4]), copies=float(m[5]), compiles=int(m[6]), compile_ms=float(m[7]), gc=float(m[8]), line=i + 1)
    for j in range(i + 1, min(i + 8, len(lines))):
        f = re.search(r'fault handlers: (\d+) read faults ([\d.]+) ms, write faults ([\d.]+) ms', lines[j])
        if f:
            d.update(rf=int(f[1]), rf_ms=float(f[2]), wf_ms=float(f[3]))
        g = re.search(r'(\d+) draws, (\d+) dispatches, (\d+) submissions', lines[j])
        if g:
            d.update(draws=int(g[1]), dispatches=int(g[2]), submissions=int(g[3]))
        c = re.search(r'GPU thread on CPU ([\d.]+) ms', lines[j])
        if c:
            d['gpu_cpu'] = float(c[1])
    stalls.append(d)
n = len(stalls)
print(f'{n} stall frames (over 40 ms)')
if n:
    tot = lambda k: sum(s.get(k, 0) for s in stalls)
    print(f"frame total {tot('frame'):.0f} ms: GPU ticks {tot('ticks'):.0f}, readback {tot('readback'):.0f}, recorder {tot('recorder'):.0f}, "
          f"host copies {tot('copies'):.0f}, compiles {tot('compile_ms'):.0f}, read-fault handlers {tot('rf_ms'):.0f} ({tot('rf')} faults), "
          f"write-fault handlers {tot('wf_ms'):.0f}, GPU thread on CPU {tot('gpu_cpu'):.0f}")
    print(f"per stall frame: submissions {tot('submissions') / n:.0f}, draws {tot('draws') / n:.0f}, dispatches {tot('dispatches') / n:.0f}")
stats = [l for l in lines if l.startswith('Frame stats:')]
fps = [float(re.match(r'Frame stats: ([\d.]+) FPS, worst frame ([\d.]+)', l)[1]) for l in stats]
worst = [float(re.match(r'Frame stats: ([\d.]+) FPS, worst frame ([\d.]+)', l)[2]) for l in stats]
print(f'{len(stats)} stats windows; FPS: ' + ' '.join(f'{v:.0f}' for v in fps))
print('worst frame per window (ms): ' + ' '.join(f'{v:.0f}' for v in worst))
for l in lines:
    if 'readback-all' in l or 'readbacks: ' in l:
        print(l[:200])
