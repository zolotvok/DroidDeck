import os
import json
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steamvr'


class SteamVrHelperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_output = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.compiler_output.cleanup)
        cls.scheduling = Path(cls.compiler_output.name) / 'libdroiddeck_vr_scheduling.so'
        source = Path(__file__).resolve().parents[1] / 'vr/src/scheduling.c'
        subprocess.run(['cc', '-shared', '-fPIC', '-o', str(cls.scheduling), str(source), '-ldl'], check=True)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / 'home'
        self.runtime = self.root / 'runtime'
        self.commands = self.root / 'commands'
        self.driver = self.root / 'driver/droiddeck/bin/linuxarm64'
        self.steamvr = self.root / 'steamvr'
        for directory in (self.commands, self.driver, self.steamvr / 'bin/linuxarm64'):
            directory.mkdir(parents=True)
        self.helper = self.root / 'droiddeck-steamvr'
        self.assertIn('/run/droiddeck/droiddeck-xr-frames', SOURCE.read_text())
        self.helper.write_text(SOURCE.read_text().replace('/run/droiddeck/droiddeck-xr-frames',
                                                        str(self.runtime / 'droiddeck-xr-frames')), encoding='utf-8')
        self.executable(self.commands / 'pgrep', 'exit 1')
        self.executable(self.commands / 'sleep', 'exit 0')
        self.executable(self.commands / 'xset', 'exit 0')
        self.executable(self.commands / 'setsid', 'exec "$@"')
        self.executable(self.driver / 'xr-display-mode', 'echo 3360x1760@120')
        self.executable(self.steamvr / 'bin/linuxarm64/vrstartup',
                        'echo "display=${DROIDDECK_VR_DISPLAY_MODE:-unset}"')
        (self.driver / 'driver_droiddeck.so').touch()
        layer = self.root / 'driver/layer'
        layer.mkdir()
        shutil.copyfile(self.scheduling, layer / self.scheduling.name)
        self.environment = dict(os.environ)
        self.environment.pop('DROIDDECK_VR_DISPLAY_MODE', None)
        self.environment.pop('DROIDDECK_VR_PRESENT', None)
        self.environment.pop('DROIDDECK_VR_PRODUCER_LEASE', None)
        self.environment.update(HOME=str(self.home), XDG_RUNTIME_DIR=str(self.runtime),
                                STEAMVR=str(self.steamvr), DROIDDECK_VR_DIR=str(self.root / 'driver'),
                                DROIDDECK_VR_SETTINGS_HELPER=str(SOURCE.with_name('droiddeck-steamvr-settings')),
                                DROIDDECK_VR_START_CHECK='0',
                                PATH=str(self.commands) + os.pathsep + os.environ['PATH'])

    def executable(self, destination, body):
        destination.write_text('#!/bin/sh\n' + body + '\n', encoding='utf-8')
        destination.chmod(0o755)

    def start(self):
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.home / '.local/share/Steam/logs/droiddeck-vrstartup.log'
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if log.is_file() and 'display=' in log.read_text():
                return log
            time.sleep(0.01)
        self.fail('Startup log did not arrive')

    def checked_start(self, pgrep_body):
        self.environment['DROIDDECK_VR_START_CHECK'] = '6'
        self.executable(self.commands / 'pgrep', pgrep_body)
        return subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                              capture_output=True, text=True, timeout=10)

    def test_a_steamvr_that_dies_at_start_up_is_a_failure(self):
        logs = self.home / '.local/share/Steam/logs'
        self.executable(self.steamvr / 'bin/linuxarm64/vrstartup',
                        'echo "Fri Nov 20 2026 18:04:15.434406 - [Error] - Failed to create VkDevice." > "$HOME/.local/share/Steam/logs/vrcompositor.txt"')
        # vrserver answers the first check, then is gone (the 2026-11-20 18:08 report: it lived 1.3 s)
        count = self.root / 'count'
        result = self.checked_start('[ "$1" = -x ] && [ "$2" = vrserver ] || exit 1\n'
                                    f'n=$(cat {count} 2>/dev/null || echo 0); echo $((n + 1)) > {count}\n'
                                    '[ "$n" = 2 ]')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('Failed to create VkDevice', result.stdout)
        self.assertIn('Failed to create VkDevice', (self.runtime / 'droiddeck-steamvr/failed').read_text())
        self.assertFalse((self.runtime / 'droiddeck-steamvr/active').exists())
        log = (self.runtime / 'droiddeck-steamvr/start.log').read_text()
        self.assertIn('start requested', log)
        self.assertIn('start: FAILED', log)
        self.assertTrue(logs.is_dir())

    def test_a_startup_that_ends_without_a_server_is_a_failure(self):
        result = self.checked_start('exit 1')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('vrserver did not stay up', (self.runtime / 'droiddeck-steamvr/failed').read_text())

    def test_a_slow_start_with_vrstartup_still_working_is_not_killed(self):
        result = self.checked_start('[ "$1" = -x ] && [ "$2" = vrstartup ]')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_one_missed_check_after_the_server_came_up_is_no_failure(self):
        count = self.root / 'count'
        result = self.checked_start('[ "$1" = -x ] && [ "$2" = vrserver ] || exit 1\n'
                                    f'n=$(cat {count} 2>/dev/null || echo 0); echo $((n + 1)) > {count}\n'
                                    '[ "$n" != 0 ] && [ "$n" != 1 ] && [ "$n" != 3 ]')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_server_that_stays_up_is_a_success(self):
        (self.runtime / 'droiddeck-steamvr').mkdir(parents=True)
        (self.runtime / 'droiddeck-steamvr/failed').write_text('old failure\n')
        result = self.checked_start('[ "$1" = -x ] && [ "$2" = vrserver ]')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.runtime / 'droiddeck-steamvr/failed').exists())
        self.assertIn('start: running', (self.runtime / 'droiddeck-steamvr/start.log').read_text())

    def test_fresh_runtime_mode_is_selected_and_log_survives_runtime_cleanup(self):
        log = self.start()
        self.assertEqual((self.runtime / 'droiddeck-steamvr').stat().st_mode & 0o777, 0o700)
        self.assertEqual(log.read_text().strip(), 'display=3360x1760@120')
        self.assertEqual((self.runtime / 'droiddeck-steamvr/vrstartup.log').resolve(), log)
        shutil.rmtree(self.runtime)
        self.assertTrue(log.is_file())

    def test_explicit_display_override_is_preserved(self):
        self.environment['DROIDDECK_VR_DISPLAY_MODE'] = '1920x1080@90'
        self.assertEqual(self.start().read_text().strip(), 'display=1920x1080@90')

    def test_stale_or_missing_tracking_keeps_the_default(self):
        self.executable(self.driver / 'xr-display-mode', 'exit 1')
        self.assertEqual(self.start().read_text().strip(), 'display=unset')

    def test_older_runtime_without_the_reader_still_starts(self):
        (self.driver / 'xr-display-mode').unlink()
        self.assertEqual(self.start().read_text().strip(), 'display=unset')

    def lease_log(self):
        self.executable(self.steamvr / 'bin/linuxarm64/vrstartup',
                        'printf "display=%s present=%s lease=%s\\n" "${DROIDDECK_VR_DISPLAY_MODE:-unset}" '
                        '"${DROIDDECK_VR_PRESENT:-unset}" "${DROIDDECK_VR_PRODUCER_LEASE:-unset}"')
        return self.start().read_text().strip()

    def test_quest_receiver_uses_normal_compositor_and_excludes_unsupported_frame_backend(self):
        self.runtime.mkdir()
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.write_bytes(b'frame-only backend')
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as receiver:
            receiver.bind(str(self.runtime / 'droiddeck-xr-frames'))
            self.assertIn('present=questwsi lease=unset', self.lease_log())
        settings = json.loads((self.home / '.local/share/Steam/config/steamvr.vrsettings').read_text())
        self.assertEqual(settings['steamvr'], {'enableLinuxVulkanAsync': True, 'motionSmoothing': False})
        self.assertFalse(cvp.exists())
        self.assertEqual(Path(str(cvp) + '.droiddeck-disabled').read_bytes(), b'frame-only backend')

    def test_quest_settings_preserve_other_preferences_and_start_is_idempotent(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'steamvr': {'somePreference': 42}, 'dashboard': {'somePreference': True}}))
        self.start()
        self.start()
        settings = json.loads(path.read_text())
        self.assertEqual(settings['steamvr']['somePreference'], 42)
        self.assertEqual(settings['dashboard'], {'somePreference': True})

    def test_quest_refuses_to_overwrite_malformed_settings(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text('invalid json')
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(path.read_text(), 'invalid json')

    def test_quest_stop_restores_only_overridden_preferences(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({'steamvr': {'motionSmoothing': True, 'somePreference': 42}}))
        self.start()
        settings = json.loads(path.read_text())
        settings['dashboard'] = {'changedDuringSession': True}
        path.write_text(json.dumps(settings))
        subprocess.run(['sh', str(self.helper), 'stop'], env=self.environment, check=True)
        self.assertEqual(json.loads(path.read_text()), {'steamvr': {'motionSmoothing': True, 'somePreference': 42},
                                                      'dashboard': {'changedDuringSession': True}})

    def test_invalid_backup_preserves_preferences_until_repaired(self):
        settings = self.root / 'steamvr.vrsettings'
        backup = self.root / 'quest-backup.json'
        helper = SOURCE.with_name('droiddeck-steamvr-settings')
        original = {'steamvr': {'motionSmoothing': True}, 'dashboard': {'unchanged': 42}}
        settings.write_text(json.dumps(original))
        subprocess.run([sys.executable, str(helper), 'apply', str(settings), str(backup)], check=True)
        valid_backup = backup.read_bytes()
        applied = settings.read_bytes()
        invalid = ['truncated {', '{}', '{"section": true, "values": []}',
                   '{"section": false, "values": {"motionSmoothing": [false]}}']
        for content in invalid:
            for operation in ('apply', 'restore'):
                with self.subTest(content=content, operation=operation):
                    backup.write_text(content)
                    result = subprocess.run([sys.executable, str(helper), operation, str(settings), str(backup)],
                                            capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('repair the saved section/values and retry restore', result.stderr)
                    self.assertEqual(backup.read_text(), content)
                    self.assertEqual(settings.read_bytes(), applied)
        backup.write_bytes(valid_backup)
        subprocess.run([sys.executable, str(helper), 'restore', str(settings), str(backup)], check=True)
        self.assertEqual(json.loads(settings.read_text()), original)
        self.assertFalse(backup.exists())

    def test_quest_update_preserves_previous_cvp_backup(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.write_bytes(b'updated library')
        backup = Path(str(cvp) + '.droiddeck-disabled')
        backup.write_bytes(b'previous library')
        self.start()
        self.assertFalse(cvp.exists())
        self.assertEqual(backup.read_bytes(), b'previous library')
        self.assertEqual([p.read_bytes() for p in cvp.parent.glob('*.droiddeck-disabled.*')], [b'updated library'])

    def test_start_failure_after_overrides_restores_preferences(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questwsi'
        path = self.home / '.local/share/Steam/config/steamvr.vrsettings'
        path.parent.mkdir(parents=True)
        original = {'steamvr': {'enableLinuxVulkanAsync': False, 'motionSmoothing': True}}
        path.write_text(json.dumps(original))
        cvp = self.steamvr / 'drivers/cv/bin/linuxarm64/libArcturusPerception.so'
        cvp.parent.mkdir(parents=True)
        cvp.touch()
        self.executable(self.commands / 'mv', 'exit 1')
        result = subprocess.run(['sh', str(self.helper), 'start'], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads(path.read_text()), original)

    def test_explicit_quest_presenter_enables_the_lease(self):
        self.environment['DROIDDECK_VR_PRESENT'] = 'questframes'
        self.assertIn('present=questframes lease=1', self.lease_log())

    def test_explicit_diagnostic_override_is_preserved(self):
        self.environment.update(DROIDDECK_VR_PRESENT='questframes', DROIDDECK_VR_PRODUCER_LEASE='0')
        self.assertIn('present=questframes lease=0', self.lease_log())

    def test_flat_presenter_does_not_enable_the_producer_lease(self):
        self.assertIn('present=unset lease=unset', self.lease_log())

    def registration(self):
        return (self.home / '.config/openvr/openvrpaths.vrpath',
                self.home / '.config/openxr/1/active_runtime.json')

    def helper_action(self, action):
        subprocess.run(['sh', str(self.helper), action], env=self.environment, check=True,
                       capture_output=True, timeout=5)

    def test_runtime_is_registered_only_while_a_session_is_active(self):
        vrpath, xr = self.registration()
        self.start()
        self.assertTrue(vrpath.is_file())
        self.assertTrue(xr.is_symlink())
        self.helper_action('status')
        self.assertTrue(vrpath.is_file())
        self.helper_action('stop')
        self.assertFalse(vrpath.exists())
        self.assertFalse(xr.is_symlink())

    def test_idle_status_and_session_start_clear_a_leftover_registration(self):
        vrpath, xr = self.registration()
        for action in ('status', 'unregister'):
            self.start()
            (self.runtime / 'droiddeck-steamvr/active').unlink()
            self.helper_action(action)
            self.assertFalse(vrpath.exists(), action)
            self.assertFalse(xr.is_symlink(), action)

    def test_unregister_leaves_another_runtime_alone(self):
        vrpath, xr = self.registration()
        vrpath.parent.mkdir(parents=True)
        xr.parent.mkdir(parents=True)
        vrpath.write_text('{"runtime": ["/opt/other-runtime"]}')
        xr.symlink_to('/opt/monado/openxr_monado.json')
        self.helper_action('unregister')
        self.assertTrue(vrpath.is_file())
        self.assertTrue(xr.is_symlink())


if __name__ == '__main__':
    unittest.main()
