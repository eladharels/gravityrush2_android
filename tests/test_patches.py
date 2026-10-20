from paths import ROOT
import tempfile
import json
import struct
from unittest import mock
import patches
from patch_fixtures import make_game, make_xml, profiles, TEXTURE, STARTUP, FPS120, RES4K
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from patches import (compile_patches, external_patches, external_selection, compile_external,
                     game_profile, selected_patches, patch_requirements, GAMES)

GOW3 = ROOT / 'patches/God_of_War_III_Remastered_CUSA01623.xml'
GOW3_IDS = {'CUSA01623'}
SEGMENTS = [(0, 0x6000000)]


EXTERNAL = """<?xml version="1.0"?>
<Patch>
  <TitleID><ID>CUSA01623</ID></TitleID>
  <Metadata Title="God of War III" Name="On" Author="x" PatchVer="1.0" AppVer="01.02" AppElf="eboot.bin" isEnabled="true">
    <PatchList><Line Type="bytes" Address="0x00401000" Value="9090"/></PatchList>
  </Metadata>
  <Metadata Title="God of War III" Name="Off" Author="x" PatchVer="1.0" AppVer="01.02" AppElf="eboot.bin">
    <PatchList><Line Type="bytes32" Address="0x00402000" Value="0x12345678"/></PatchList>
  </Metadata>
  <Metadata Title="God of War III" Name="Mask" Author="x" PatchVer="1.0" AppVer="01.02" AppElf="eboot.bin" isEnabled="true">
    <PatchList><Line Type="mask" Value="90 ?? 90" Offset="0"/></PatchList>
  </Metadata>
  <Metadata Title="God of War III" Name="Old" Author="x" PatchVer="1.0" AppVer="01.00" AppElf="eboot.bin" isEnabled="true">
    <PatchList><Line Type="bytes" Address="0x00403000" Value="90"/></PatchList>
  </Metadata>
</Patch>"""


class ExternalPatchTests(unittest.TestCase):
    def test_selection_and_unsupported_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'extra.xml').write_text(EXTERNAL)
            (folder / 'other.xml').write_text(EXTERNAL.replace('CUSA01623', 'CUSA99999'))
            (folder / 'broken.xml').write_text('<Patch>')
            found = external_patches(folder, '01.02', GOW3, GOW3_IDS)
            self.assertEqual([key for key, _, _ in found],
                             ['extra.xml/On', 'extra.xml/Off', 'extra.xml/Mask'])
            # The file's isEnabled; the mask patch is skipped as a whole.
            writes = compile_external(external_selection(found, None), SEGMENTS)
            self.assertEqual(writes, [(0x1000, bytes.fromhex('9090'))])
            config = folder / 'patches.json'
            config.write_text('{"enabled": ["extra.xml/Off"], "disabled": ["extra.xml/On"]}')
            writes = compile_external(external_selection(found, config), SEGMENTS)
            self.assertEqual(writes, [(0x2000, (0x12345678).to_bytes(4, 'little'))])

    def test_built_in_file_is_not_external(self):
        self.assertEqual(external_patches(GOW3.parent, '01.02', GOW3, GOW3_IDS), [])

    def test_retired_shared_file_is_not_reloaded_as_external(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'God_of_War_III_Remastered.xml').write_text(EXTERNAL)
            self.assertEqual(external_patches(directory, '01.02', GOW3, GOW3_IDS), [])


class GameProfileTests(unittest.TestCase):
    def test_each_title_has_its_own_catalog_and_runtime_manifest_entry(self):
        files = set()
        for ids, xml, version, _ in patches.GAMES:
            self.assertEqual(len(ids), 1)
            title = next(iter(ids))
            self.assertEqual(xml.name, f'God_of_War_III_Remastered_{title}.xml')
            root = ET.parse(xml).getroot()
            self.assertEqual({e.text for e in root.iter('ID')}, ids)
            self.assertEqual({m.get('AppVer') for m in root.iter('Metadata')},
                             {'01.00', '01.02'} if title == 'CUSA01740' else {version})
            names = [m.get('Name') for m in root.iter('Metadata') if m.get('AppVer') == version]
            self.assertEqual(len(names), len(set(names)))
            # 16:10 档位（1280x800/1440x900）已实施后因真机显示异常回退隐藏，见
            # change add-16x10-resolution-tiers；重新上架时恢复 16/22 与 checked 29。
            expected = ((14 if version == '01.00' else 14) if title == 'CUSA01740' else 20)
            self.assertEqual(len(names), expected)
            for m in root.iter('Metadata'):
                self.assertIsNotNone(m.get('Validation'))
                if m.get('Name').startswith('Disable ') or m.get('Name') in {
                        'Resolution Patch - 540p', 'Resolution Patch - 900p', 'Resolution Patch - 1080p'}:
                    self.assertEqual(m.get('isEnabled'), 'false')
                for line in m.iter('Line'):
                    self.assertTrue(line.get('Pattern') or line.get('Target'))
            # The launcher compiles these on the device, so they must reach the APK as assets. The
            # runtime payload deliberately does not carry them (libgow3prepare needs the XML, and
            # libgow3present the socket), which is what test_android_1740_patches.py now asserts.
            self.assertTrue(xml.is_file(), '%s is missing' % xml)
            files.add(xml)
        self.assertEqual(len(files), 4)

    def test_profiles_by_title_id(self):
        self.assertEqual(game_profile('CUSA01623')[1:3], (GOW3, '01.02'))
        self.assertIsNone(game_profile('CUSA99999'))
        self.assertIsNone(game_profile(None))

    def test_unavailable_frame_rate_patch_falls_back_to_the_highest_lower_one(self):
        from graphics_options import restrict_to_available
        available = {'Bug Fix - Texture Corruption Fix', 'Frame Rate Patch - 120 FPS'}
        self.assertEqual(restrict_to_available(['Bug Fix - Texture Corruption Fix', 'Frame Rate Patch - 240 FPS'],
                                               available),
                         ['Bug Fix - Texture Corruption Fix', 'Frame Rate Patch - 120 FPS'])
        self.assertEqual(restrict_to_available(['Resolution Patch - 8K'], available), [])

    def test_gow3_defaults_and_extras(self):
        self.assertEqual(selected_patches(GOW3, '01.02', ''),
                         ['Bug Fix - Texture Corruption Fix', 'Skip Any Video With X Button', STARTUP])
        self.assertEqual(selected_patches(GOW3, '01.02', 'Resolution Patch - 720p; Skip Any Video With X Button'),
                         ['Skip Any Video With X Button', STARTUP, 'Resolution Patch - 720p'])
        self.assertEqual(selected_patches(GOW3, '01.00', ''), [])
        # The launcher's selection is exact: unchecked defaults stay off.
        self.assertEqual(selected_patches(GOW3, '01.02', 'Skip Any Video With X Button', only=True), ['Skip Any Video With X Button', STARTUP])
        self.assertEqual(selected_patches(GOW3, '01.02', '', only=True), [STARTUP])

    def test_gow3_resolution_patch_replaces_texture_fix(self):
        self.assertEqual(selected_patches(GOW3, '01.02', 'Resolution Patch - 4K'),
                         ['Skip Any Video With X Button', STARTUP, 'Resolution Patch - 4K'])
        with self.assertRaises(ValueError):
            selected_patches(GOW3, '01.02', 'Resolution Patch - 4K;Resolution Patch - 720p')

    def test_profile_by_title_for_any_region(self):
        from unittest import mock
        import patches
        self.assertEqual(game_profile('CUSA01715')[1], GOW3)
        self.assertEqual(game_profile('CUSA99999', 'God of War® III Remastered')[1], GOW3)
        self.assertIsNone(game_profile('CUSA99999', 'Another Game'))
        with mock.patch.object(patches, 'game_sfo', return_value={'TITLE_ID': 'CUSA77777', 'TITLE': 'God of War III Remastered'}):
            self.assertTrue(patches.game_patchable('folder'))
        self.assertFalse(patches.game_patchable(None))
        with tempfile.TemporaryDirectory() as folder:
            self.assertFalse(patches.game_patchable(folder))  # no param.sfo: unknown game


class SignatureTests(unittest.TestCase):
    XML = """<?xml version="1.0"?>
<Patch>
  <Metadata Name="Found" AppVer="01.02">
    <PatchList><Line Type="bytes" Address="0x401004" Value="9090" Pattern="11 22 ?? 44 55 66" At="1"/></PatchList>
  </Metadata>
  <Metadata Name="Call" AppVer="01.02">
    <PatchList><Line Type="bytes" Address="0x401010" Value="e8{rel:there:0}" Pattern="aa bb cc dd ee" At="0"/></PatchList>
    <Target Name="there" Address="0x401100" Pattern="e9 ?? ?? ?? ?? 77 88" At="1" Ref="1"/>
  </Metadata>
  <Metadata Name="Missing" AppVer="01.02">
    <PatchList>
      <Line Type="bytes" Address="0x401004" Value="9090" Pattern="11 22 ?? 44 55 66" At="1"/>
      <Line Type="bytes" Address="0x401020" Value="90" Pattern="de ad be ef" At="0"/>
    </PatchList>
  </Metadata>
</Patch>"""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.xml = Path(directory.name) / 'test.xml'
        self.xml.write_text(self.XML)
        image = bytearray(0x400)
        image[0x40:0x46] = bytes.fromhex('112233445566')
        image[0x80:0x85] = bytes.fromhex('aabbccddee')
        image[0x200:0x207] = bytes.fromhex('e9') + (0x300 - 0x205).to_bytes(4, 'little', signed=True) + bytes.fromhex('7788')
        self.image = bytes(image)

    def test_sites_found_anywhere_and_rel32_recomputed(self):
        skipped = {}
        writes = compile_patches(self.xml, ['Found', 'Call', 'Missing'], None, [(0, 0x400)], self.image, skipped)
        call = (0x300 - (0x80 + 5)).to_bytes(4, 'little', signed=True)
        self.assertEqual(writes, [(0x41, b'\x90\x90'), (0x80, b'\xe8' + call)])
        # One site of the patch is missing: the whole patch is left out, with the reason.
        self.assertEqual(skipped, {'Missing': 'signature not found'})

    def test_ambiguous_signature_is_skipped(self):
        image = bytearray(self.image)
        image[0x100:0x106] = image[0x40:0x46]
        skipped = {}
        self.assertEqual(compile_patches(self.xml, ['Found'], None, [(0, 0x400)], bytes(image), skipped), [])
        self.assertEqual(skipped, {'Found': 'signature found more than once'})

    def test_without_the_image_the_reference_addresses_are_used(self):
        writes = compile_patches(self.xml, ['Found', 'Call'], '01.02', [(0, 0x10000)])
        self.assertEqual(writes[0], (0x1004, b'\x90\x90'))
        self.assertEqual(writes[1][0], 0x1010)

    def test_signatures_match_the_full_update_eboot(self):
        # Without that eboot.bin here: the original bytes others published for it must be the
        # fixed bytes of each signature at its site, with one shift per code region.
        import json
        import re
        known = json.loads((ROOT / 'tests/data/eboot_6e72_sites.json').read_text())
        fixed = lambda pattern, at, n: [None if b == '??' else int(b, 16) for b in pattern.split()][at:at + n]
        shifts = {}
        metas = {m.get('Name'): list(m.iter('Line')) for m in ET.parse(GOW3).getroot().iter('Metadata')}
        for name, sites in known['patches'].items():
            for index, address, original in sites:
                line = metas[name][index]
                ours = fixed(line.get('Pattern'), int(line.get('At')), len(original) // 2)
                self.assertTrue(all(b is None or b == o for b, o in zip(ours, bytes.fromhex(original))),
                                f'{name} line {index}')
                reference = int(line.get('Address'), 0)
                shifts.setdefault(reference >> 16, set()).add(int(address, 0) - reference)
        self.assertTrue(all(len(s) == 1 for s in shifts.values()), shifts)
        header = (ROOT / 'src/cheat_hook.h').read_text()
        hooks = re.findall(r'\{(0x[0-9a-f]+),GOW3_CHEAT_\w+,\d+,\d+,\s*\{([^}]*)\},.*?\{"([^"]+)",(\d+)\}\}',
                           header, re.S)
        for name, (address, original) in known['cheats'].items():
            # The cheats' region moved -0x10 (Rage of Sparta has two sites with the same bytes).
            match = [h for h in hooks if bytes(int(b, 0) for b in h[1].split(',')).hex() == original
                     and int(h[0], 0) - 0x10 == int(address, 0)]
            self.assertEqual(len(match), 1, name)
            _, _, pattern, at = match[0]
            self.assertEqual(fixed(pattern, int(at), len(original) // 2), list(bytes.fromhex(original)), name)

    @unittest.skipUnless(os.environ.get('GOW3_GAME'), 'GOW3_GAME (game folder) not set')
    def test_gow3_signatures_in_the_reference_and_a_moved_build(self):
        from prepare import parse_self
        from patches import eboot_image
        elf, *_ = parse_self((Path(os.environ['GOW3_GAME']) / 'eboot.bin').read_bytes())
        image = eboot_image(bytes(elf))
        names = [m.get('Name') for m in ET.parse(GOW3).getroot().iter('Metadata')]
        segments = [(0, len(image))]
        # The reference build: the signatures give exactly the Address writes.
        for name in names:
            self.assertEqual(compile_patches(GOW3, [name], None, segments, image, {}),
                             compile_patches(GOW3, [name], '01.02', segments), name)
        # A build whose code moved like the full 01.02 update's: +0x120 from 0x1f0000 to 0x210000,
        # data unchanged (the bytes taken out keep the data at its address).
        moved = image[:0x1f0000] + bytes(0x120) + image[0x1f0000:0x210000] + image[0x210120:]
        self.assertEqual(len(moved), len(image))
        shift = lambda at: at + 0x120 if 0x1f0000 <= at < 0x210000 else at
        for name in ('Resolution Patch - 4K', 'Skip Intro'):
            reference = compile_patches(GOW3, [name], None, segments, image, {})
            found = compile_patches(GOW3, [name], None, segments, moved, {})
            self.assertEqual([at for at, _ in found], [shift(at) for at, _ in reference], name)
        # Skip Intro's cave moved: the boot call into it is recomputed, the flag (data) is not.
        writes = dict(compile_patches(GOW3, ['Skip Intro'], None, segments, moved, {}))
        rel = lambda data, at, end: end + int.from_bytes(data[at:at + 4], 'little', signed=True)
        self.assertEqual(rel(writes[0x01b36a], 1, 0x01b36f), shift(0x212170))
        flag = rel(writes[0x0c8440], 2, 0x0c844a)  # the getter (not moved) and the cave share it
        self.assertEqual(rel(writes[shift(0x212170)], 2, shift(0x212170) + 10), flag)

    def test_gow3_patches_compile(self):
        names = [m.get('Name') for m in ET.parse(GOW3).getroot().iter('Metadata')]
        self.assertTrue(compile_patches(GOW3, names, '01.02', SEGMENTS))

    def test_requirements_from_patch_notes(self):
        # Notes ask for extra direct memory (shadPS4 "DMEM") and a VBlank rate.
        self.assertEqual(patch_requirements(GOW3, ['Resolution Patch - 4K', 'Skip Any Video With X Button'], '01.02'),
                         (5056 + 6144, None))
        self.assertEqual(patch_requirements(GOW3, ['Frame Rate Patch - 120 FPS'], '01.02'), (None, 120))
        self.assertEqual(patch_requirements(GOW3, [], '01.02'), (None, None))


class PatchPlanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game, self.out = make_game(self.root)
        self.xml = make_xml(self.root)
        override = mock.patch.object(patches, 'GAMES', profiles(self.xml))
        override.start()
        self.addCleanup(override.stop)

    def test_plan_compiles_and_publishes_current_identity(self):
        report, writes = compile_plan(self.game, self.out, [FPS120])
        publish_plan(self.out, report, writes)
        self.assertEqual(writes, [(4, b'\x90')])
        self.assertEqual(report['effective']['engine_fps'], 120)
        self.assertEqual(report['effective']['vblank_hz'], 120)
        self.assertEqual(json.loads((self.out / 'patch_status.json').read_text()), report)
        self.assertEqual(struct.unpack_from('<Q', (self.out / 'patches.bin').read_bytes(), 16)[0], 1)

    def test_same_eboot_with_unsupported_identity_replaces_old_success(self):
        report, writes = compile_plan(self.game, self.out)
        publish_plan(self.out, report, writes)
        for title, version, reason in [('CUSA01740', '01.02', 'unsupported-title'),
                                       ('CUSA99999', '01.02', 'unsupported-title'),
                                       ('CUSA01715', '01.00', 'unsupported-app-version')]:
            make_game(self.root, title, version)
            report, writes = compile_plan(self.game, self.out, extra=FPS120)
            publish_plan(self.out, report, writes)
            self.assertEqual(writes, [])
            self.assertEqual(report['reason'], reason)
            self.assertEqual(report['applied'], [])
            self.assertEqual(len((self.out / 'patches.bin').read_bytes()), 24)
            self.assertEqual(json.loads((self.out / 'patch_status.json').read_text())['reason'], reason)

    def test_global_rejection_does_not_readd_always_on(self):
        for kwargs, reason in [({'extra': RES4K, 'env': {'GOW3_DEVICE_MEM_MB': '4096'}},
                                'insufficient-memory-budget'),
                               ({'extra': FPS120 + ';Frame Rate Patch - 240 FPS'}, 'patch-conflict'),
                               ({'disabled_reason': 'patch-conflict'}, 'patch-conflict')]:
            report, writes = compile_plan(self.game, self.out, **kwargs)
            self.assertEqual(writes, [])
            self.assertEqual(report['disabled_reason'], reason)
            self.assertEqual(report['applied'], [])

    def test_stale_or_missing_prepared_elf_is_rejected(self):
        (self.out / 'eboot.elf').write_bytes(b'old image')
        with self.assertRaisesRegex(ValueError, 'does not match'):
            compile_plan(self.game, self.out)
        (self.game / 'eboot.bin').unlink()
        with self.assertRaisesRegex(ValueError, 'missing-or-unreadable-eboot'):
            compile_plan(self.game, self.out)

    def test_non_reference_external_patches_are_not_written(self):
        report, writes = compile_plan(self.game, self.out, [], patches_dir=self.root)
        self.assertEqual(writes, [])
        self.assertEqual(report['external']['reason'], 'non-reference-eboot')

    def test_output_in_game_or_failed_publish_is_not_silently_accepted(self):
        with self.assertRaisesRegex(ValueError, 'outside the game'):
            compile_plan(self.game, self.game / 'out')
        report, writes = compile_plan(self.game, self.out)
        with mock.patch.object(patches.os, 'replace', side_effect=PermissionError('read-only')):
            with self.assertRaises(PermissionError):
                publish_plan(self.out, report, writes)
        self.assertFalse(list(self.out.glob('.patch*')))

    def test_whole_patch_is_dropped_for_bad_later_write_or_target(self):
        cases = [
            ('<Line Type="bytes" Pattern="ee ff" Value="90"/>', 'signature not found'),
            ('<Line Type="bytes" Pattern="00" Value="90"/>', 'signature found more than once'),
            ('<Line Type="bytes" Pattern="07 08" At="64" Value="90"/>', 'outside'),
            ('<Line Type="bytes" Address="0x400001" Value="90"/>', 'missing signature'),
            ('<Target Name="bad" Pattern="07 08" At="64"/>', 'target outside'),
            ('<Target Name="bad" Pattern="07 08" At="-64" Ref="1"/>', 'reference operand'),
            ('<Target Name="bad" Line="-1"/>', 'invalid target line'),
        ]
        image = bytes(range(16)) + bytes(16)
        for tail, reason in cases:
            with self.subTest(reason=reason):
                self.xml.write_text('<Patch><Metadata Name="Bad" AppVer="01.02"><PatchList>'
                                    '<Line Type="bytes" Pattern="01 02" Value="90"/>' + tail +
                                    '</PatchList></Metadata><Metadata Name="Good" AppVer="01.02">'
                                    '<Line Type="bytes" Pattern="04 05" Value="ff"/></Metadata></Patch>')
                skipped = {}
                writes = compile_patches(self.xml, ['Bad', 'Good'], '01.02', [(0, 32)], image, skipped)
                self.assertEqual(writes, [(4, b'\xff')])
                self.assertIn(reason, skipped['Bad'])

    def test_bss_write_uses_verified_target_without_absolute_address_fallback(self):
        self.xml.write_text('<Patch><Metadata Name="BSS" AppVer="01.02">'
                            '<Line Type="bytes32" Target="index" Value="2" Address="0xdeadbeef"/>'
                            '<Target Name="index" Pattern="01 02 03" At="8"/>'
                            '</Metadata></Patch>')
        skipped = {}
        writes = compile_patches(self.xml, ['BSS'], '01.02', [(0, 32)], bytes(range(16)), skipped)
        self.assertEqual(skipped, {})
        self.assertEqual(writes, [(9, struct.pack('<I', 2))])


class HalfResolutionTests(unittest.TestCase):
    """分辨率补丁必须把 0x7f2a3c/0x7f2a40 半分辨率后处理表写成输出的一半（移植上游 PR #613）：
    低分辨率下该表若不随输出缩放，480p 的表比映像还大，亮部过曝。按写入位点自身的特征码
    定位以兼容各文件的地址布局；1080p/540p 豁免（输出一半分别等于表默认值与输出本身），
    CUSA01740 01.00 缺少地区样本，半分辨率行缓交。"""

    WIDTH_HEIGHT = 'bf 00 01 00 00 b9 80 07 00 00 41 b8 38 04 00 00'  # At=6 宽、At=12 高
    HALF_WIDTH = '00 00 00 00 0a e0 f2 00 c0 03 00 00 1c 02 00 00 01 00 00 00'  # At=8
    HALF_HEIGHT = '0a e0 f2 00 c0 03 00 00 1c 02 00 00 01 00 00 00 00 00 00 00'  # At=8
    EXEMPT = {'Resolution Patch - 1080p', 'Resolution Patch - 540p'}

    def test_every_resolution_patch_halves_the_output(self):
        size = lambda value: int.from_bytes(bytes.fromhex(value), 'little')
        checked = 0
        for ids, xml, version, _ in patches.GAMES:
            for meta in ET.parse(xml).getroot().iter('Metadata'):
                name = meta.get('Name')
                if not name.startswith('Resolution Patch'):
                    continue
                if name in self.EXEMPT or meta.get('AppVer') != version:
                    continue
                if next(iter(ids)) == 'CUSA01740' and version == '01.00':
                    continue
                lines = list(meta.iter('Line'))
                dims = {line.get('At'): line.get('Value') for line in lines
                        if line.get('Pattern') == self.WIDTH_HEIGHT and line.get('At') in ('6', '12')}
                self.assertEqual(sorted(dims), ['12', '6'], name)
                half = [(line.get('Pattern'), line.get('Value')) for line in lines
                        if line.get('Pattern') in (self.HALF_WIDTH, self.HALF_HEIGHT)]
                self.assertEqual(sorted(pattern for pattern, _ in half),
                                 sorted([self.HALF_WIDTH, self.HALF_HEIGHT]), name)
                values = dict(half)
                width, height = size(dims['6']), size(dims['12'])
                self.assertEqual((size(values[self.HALF_WIDTH]), size(values[self.HALF_HEIGHT])),
                                 (width // 2, height // 2), name)
                checked += 1
        self.assertEqual(checked, 21)


if __name__ == '__main__':
    unittest.main()
