#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""The patch XML in the flat text format gow3_prepare.cpp reads (see gow3_prepare.h).
Both the Python baseline and the C++ port are fed from the same XML through this, so the golden
test compares the compilers and not the parsers."""
import sys
import xml.etree.ElementTree as ET

def field(value):
    """A field, or '-' when empty. Newlines would break the line-based format."""
    text = (value or '').strip()
    if not text:
        return '-'
    return text.replace('\\', '\\\\').replace('\n', '\\n')

def tail(value):
    """A field that takes the rest of the line: names and patterns contain spaces."""
    return field(value)

def emit(root, out, only_version=None):
    wrote = 0
    for meta in root.iter('Metadata'):
        version = meta.get('AppVer', '')
        if only_version and version != only_version:
            continue
        out.write('P %s\n' % field(meta.get('Name')))
        out.write('N %s\n' % field(meta.get('Note')))
        out.write('V %s\n' % field(version))
        out.write('E %s\n' % field(meta.get('AppElf', 'eboot.bin')))
        for target in meta.iter('Target'):
            # Pattern last here too: a target's signature contains spaces just like a line's.
            out.write('T %s %s %s %s %s %s\n' % (
                tail(target.get('Name')),
                field(target.get('Line')),
                field(target.get('At')),
                '1' if target.get('Ref') else '0',
                field(target.get('Tail')),
                field(target.get('Pattern'))))
        for line in meta.iter('Line'):
            # The pattern is the last field and takes the rest of the line: it contains spaces
            # ("48 8d 75 a0"), so it cannot be one whitespace-separated token.
            out.write('L %s %s %s %s %s %s\n' % (
                field(line.get('Type')),
                field(line.get('Address')),
                field(line.get('Value')),
                field(line.get('At')),
                field(line.get('Target')),
                field(line.get('Pattern'))))
        wrote += 1
    return wrote

def main(argv):
    if len(argv) < 2:
        print('usage: make_patch_input.py <patches.xml> <out.txt> [app_version]', file=sys.stderr)
        return 2
    root = ET.parse(argv[0]).getroot()
    with open(argv[1], 'w', encoding='utf-8') as out:
        wrote = emit(root, out, argv[2] if len(argv) > 2 else None)
    print('%d patches -> %s' % (wrote, argv[1]))
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
