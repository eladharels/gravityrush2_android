"""执行生产启动段，替代平台准备和 probe 进程，检查失败传播与真实补丁输出。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from paths import ROOT
import patches
from patch_fixtures import make_game, make_xml, profiles, FPS120


class PatchLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game, self.out = make_game(self.root)
        self.xml = make_xml(self.root)
        self.config = self.root / 'gow3.ini'
        self.config.write_text('engine_fps=120\n')
        self.capture = self.root / 'probe.json'
        self.probe = self.root / 'probe'
        self.probe.write_text(f'#!{sys.executable}\n' + '''import json, os, pathlib, struct, sys
path = pathlib.Path(sys.argv[sys.argv.index('--patches') + 1])
blob = path.read_bytes()
report = json.loads((path.parent / 'patch_status.json').read_text())
data = {key: os.environ.get(key) for key in ('GOW3_PATCHES', 'GOW3_ENGINE_FPS', 'GOW3_DMEM_MB', 'GOW3_VBLANK_HZ')}
data.update(count=struct.unpack_from('<Q', blob, 16)[0], report=report)
pathlib.Path(os.environ['PATCH_TEST_CAPTURE']).write_text(json.dumps(data))
''')
        self.probe.chmod(0o755)
        self.python = self.root / 'python-wrapper'
        self.python.write_text(f'#!{sys.executable}\n' + '''import os, pathlib, runpy, sys
script = pathlib.Path(sys.argv[1])
if script.name == os.environ.get('PATCH_TEST_FAIL'):
    raise SystemExit(23)
if script.name == 'mods.py':
    print(pathlib.Path(os.environ['GOW3_GAME_DIR']).resolve())
    raise SystemExit(0)
if script.name in ('prepare.py', 'link_libc.py', 'link_modules.py', 'content_profile.py'):
    raise SystemExit(0)
sys.path.insert(0, str(script.parent))
import patches
patches.GAMES = [({'CUSA01715'}, pathlib.Path(os.environ['PATCH_TEST_XML']), '01.02', 'God of War III')]
sys.argv = sys.argv[1:]
runpy.run_path(str(script), run_name='__main__')
''')
        self.python.chmod(0o755)
        self.loader = self.root / 'loader'
        self.loader.write_text('#!/bin/sh\nunset PYTHONHOME\nexec "$@"\n')
        self.loader.chmod(0o755)
        self.env = {key: value for key, value in os.environ.items() if not key.startswith('GOW3_')}
        self.env.update(GOW3_PREBUILT='1', GOW3_GAME_DIR=str(self.game),
                        GOW3_DATA_DIR=str(self.root), GOW3_CONFIG=str(self.config),
                        GOW3_PROBE=str(self.probe), GOW3_USER_DIR=str(self.root / 'user'),
                        GOW3_DEVICE_MEM_MB='16384', PATCH_TEST_CAPTURE=str(self.capture),
                        PATCH_TEST_XML=str(self.xml), PYTHON=str(self.python))

    def run_shell(self):
        # Linux shell and Windows entry points only. Android used to run the same Python here, under
        # a glibc rootfs; libgow3prepare now compiles the patches on the device, and that port is
        # checked byte for byte by tools/migration/run_prepare_golden.sh.
        return subprocess.run(['bash', str(ROOT / 'run.sh')], env=self.env, capture_output=True,
                              text=True, timeout=15)

    def run_windows(self):
        spec = importlib.util.spec_from_file_location('patch_test_run', ROOT / 'run.py')
        run = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(run)

        def prepared(name, *args, **kwargs):
            if name == 'mods.py':
                return str(self.game)
            if name == self.env.get('PATCH_TEST_FAIL'):
                raise SystemExit(23)

        with mock.patch.dict(os.environ, self.env, clear=True), \
             mock.patch.object(sys, 'argv', ['run.py']), \
             mock.patch.object(run, 'run_script', side_effect=prepared), \
             mock.patch.object(run, 'no_console', return_value=0), \
             mock.patch.object(patches, 'GAMES', profiles(self.xml)):
            return run.main()

    def test_all_launchers_consume_same_compiled_result(self):
        captures = []
        result = self.run_shell()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        captures.append(json.loads(self.capture.read_text()))
        self.assertEqual(self.run_windows(), 0)
        captures.append(json.loads(self.capture.read_text()))
        for capture in captures:
            self.assertEqual(capture['GOW3_ENGINE_FPS'], '120')
            self.assertEqual(capture['GOW3_VBLANK_HZ'], '120')
            self.assertEqual(capture['GOW3_DMEM_MB'], '7104')
            self.assertEqual(capture['count'], capture['report']['write_count'])
            self.assertIn(FPS120, capture['report']['applied'])
        self.assertEqual(captures[0], captures[1])

    def test_shell_rejected_selection_reaches_probe_with_zero_writes(self):
        self.env.update(GOW3_DEVICE_MEM_MB='4096', GOW3_PATCHES=FPS120)
        result = self.run_shell()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        capture = json.loads(self.capture.read_text())
        self.assertEqual(capture['count'], 0)
        self.assertEqual(capture['GOW3_PATCHES'], '')
        self.assertEqual(capture['GOW3_ENGINE_FPS'], '60')
        self.assertEqual(capture['report']['reason'], 'insufficient-memory-budget')

    def test_shell_preparation_failure_never_launches_stale_patch(self):
        for failure in ('prepare.py', 'launch_env.py'):
            self.env['PATCH_TEST_FAIL'] = failure
            result = self.run_shell()
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.capture.exists())

    def test_windows_publish_failure_never_launches_probe(self):
        with mock.patch.object(patches, 'atomic_write', side_effect=PermissionError('read-only')):
            with self.assertRaises(PermissionError):
                self.run_windows()
        self.assertFalse(self.capture.exists())


if __name__ == '__main__':
    unittest.main()
