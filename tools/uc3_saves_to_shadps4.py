#!/usr/bin/env python3
"""uc3: copies the port's Uncharted 3 saves into shadPS4's save folder, so the same checkpoint can
be compared in the plain emulator ("Continuar" there loads it).

The game's files (ICN-ID, USR-DATA) are the same on both sides. The port keeps the save
parameters as the raw OrbisSaveDataParam (<dir>.sce_sys/param.bin: title 128, subtitle 128,
detail 1024, user param, mtime); shadPS4 wants <dir>/sce_sys/param.sfo, generated here from
one of its own param.sfo files with the directory name and the texts replaced.
shadPS4's existing saves are backed up first (savedata_backup-<time> next to the folder).

    python tools/uc3_saves_to_shadps4.py [--port-user user] [--shadps4 D:/Uncharted/tools/shadps4]
"""
import argparse
import datetime
import shutil
import struct
from pathlib import Path

TITLE_ID = 'CUSA02344'


def read_sfo(data):
    magic, version, key_table, data_table, count = struct.unpack_from('<4sIIII', data, 0)
    if magic != b'\0PSF':
        raise ValueError('not an SFO file')
    entries = []
    for i in range(count):
        key_offset, fmt, length, max_length, data_offset = struct.unpack_from('<HHIII', data, 20 + 16 * i)
        key = data[key_table + key_offset:data.index(b'\0', key_table + key_offset)].decode()
        entries.append(dict(key=key, fmt=fmt, length=length, max_length=max_length, data_offset=data_offset))
    return dict(version=version, key_table=key_table, data_table=data_table, entries=entries), data


def write_sfo(sfo, data, values):
    """Returns the SFO bytes with the string entries in `values` (key -> str) replaced in place:
    every string has a fixed max_length slot in the data table, so the layout stays."""
    out = bytearray(data)
    for i, e in enumerate(sfo['entries']):
        if e['key'] not in values:
            continue
        encoded = values[e['key']].encode('utf-8')[:e['max_length'] - 1] + b'\0'
        start = sfo['data_table'] + e['data_offset']
        out[start:start + e['max_length']] = encoded.ljust(e['max_length'], b'\0')
        struct.pack_into('<HHIII', out, 20 + 16 * i, *struct.unpack_from('<HHIII', data, 20 + 16 * i)[:2],
                         len(encoded), e['max_length'], e['data_offset'])
    return bytes(out)


def c_string(raw):
    return raw.split(b'\0', 1)[0].decode('utf-8', 'replace')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port-user', default=str(Path(__file__).resolve().parent.parent / 'user'))
    p.add_argument('--shadps4', default='D:/Uncharted/tools/shadps4')
    p.add_argument('--home', default='1000', help="shadPS4 user folder under user/home")
    a = p.parse_args()
    source = Path(a.port_user) / 'savedata' / '1' / TITLE_ID
    dest = Path(a.shadps4) / 'user' / 'home' / a.home / 'savedata' / TITLE_ID
    templates = sorted(dest.glob('SAVEDATA*/sce_sys/param.sfo'))
    if not templates:
        raise SystemExit(f'no param.sfo to use as a template under {dest}')
    sfo, template = read_sfo(templates[0].read_bytes())
    backup = dest.parent / f'{TITLE_ID}_backup-{datetime.datetime.now():%Y%m%d-%H%M%S}'
    shutil.copytree(dest, backup)
    print(f'shadPS4 saves backed up to {backup}')
    copied = 0
    for save in sorted(d for d in source.iterdir() if d.is_dir() and not d.name.endswith('.sce_sys')):
        meta = source / f'{save.name}.sce_sys'
        target = dest / save.name
        target.mkdir(exist_ok=True)
        for f in save.iterdir():
            if f.is_file():
                shutil.copy2(f, target / f.name)
        (target / 'sce_sys').mkdir(exist_ok=True)
        if (meta / 'icon0.png').is_file():
            shutil.copy2(meta / 'icon0.png', target / 'sce_sys' / 'icon0.png')
        values = {'SAVEDATA_DIRECTORY': save.name}
        if (meta / 'param.bin').is_file():
            raw = (meta / 'param.bin').read_bytes()
            values.update(MAINTITLE=c_string(raw[0:128]), SUBTITLE=c_string(raw[128:256]), DETAIL=c_string(raw[256:1280]))
        (target / 'sce_sys' / 'param.sfo').write_bytes(write_sfo(sfo, template, values))
        print(f'{save.name}: {len(list(save.iterdir()))} files, "{values.get("SUBTITLE", "")}"')
        copied += 1
    print(f'{copied} saves copied to {dest}')


if __name__ == '__main__':
    main()
