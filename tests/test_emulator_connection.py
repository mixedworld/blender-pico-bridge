import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

path = Path(__file__).parents[1] / 'blender-addon/pico_blender_bridge/emulator_connection.py'
spec = importlib.util.spec_from_file_location('emulator_connection', path)
connection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connection)
TOKEN = 'fixture_pairing_token_1234'


class EmulatorConnectionTest(unittest.TestCase):
    def connect(self, devices, launch_output='Starting: Intent', failure=None):
        def run(args, **kwargs):
            if failure:
                raise failure
            if args[1:] == ['devices']:
                output = devices
            elif 'path' in args:
                output = 'package:/data/app/viewer/base.apk'
            elif 'start' in args:
                output = launch_output
            else:
                output = ''
            return subprocess.CompletedProcess(args, 0, output, '')
        return patch.object(connection.subprocess, 'run', side_effect=run)

    @patch.object(connection, 'find_adb', return_value='/sdk/adb')
    def test_repair_pairs_only_emulator_with_current_port_without_install_or_clear(self, _):
        with self.connect('real-headset\tdevice\nemulator-5556\tdevice\n') as run:
            result = connection.connect_emulator(8877, TOKEN)
        calls = [c.args[0] for c in run.call_args_list]
        self.assertIn('emulator-5556', result)
        self.assertTrue(all(c[1:3] == ['-s', 'emulator-5556'] for c in calls[1:]))
        self.assertIn(['reverse', 'tcp:8877', 'tcp:8877'], [c[3:] for c in calls])
        self.assertIn(TOKEN, calls[-1])
        self.assertIn('http://127.0.0.1:8877', calls[-1])
        self.assertFalse(any('install' in c or 'clear' in c for c in calls))
        self.assertNotIn(TOKEN, result)

    @patch.object(connection, 'find_adb', return_value='/sdk/adb')
    def test_missing_offline_or_multiple_emulators_make_no_mutations(self, _):
        for devices in ['real-headset\tdevice', 'emulator-5554\toffline',
                        'emulator-5554\tdevice\nemulator-5556\tdevice']:
            with self.subTest(devices=devices), self.connect(devices) as run:
                with self.assertRaises(RuntimeError):
                    connection.connect_emulator(8766, TOKEN)
                self.assertEqual(run.call_count, 1)

    @patch.object(connection, 'find_adb', return_value='/sdk/adb')
    def test_activity_error_with_zero_exit_is_sanitized(self, _):
        with self.connect('emulator-5554\tdevice', 'Error: Intent ' + TOKEN):
            with self.assertRaises(RuntimeError) as error:
                connection.connect_emulator(8766, TOKEN)
        self.assertNotIn(TOKEN, str(error.exception))

    @patch.object(connection, 'find_adb', return_value='/sdk/adb')
    def test_timeout_does_not_expose_command(self, _):
        with self.connect('', failure=subprocess.TimeoutExpired(['adb', TOKEN], 10)):
            with self.assertRaises(RuntimeError) as error:
                connection.connect_emulator(8766, TOKEN)
        self.assertNotIn(TOKEN, str(error.exception))

    def test_rejects_remote_shell_metacharacters_before_adb(self):
        with patch.object(connection, 'find_adb') as find:
            with self.assertRaises(RuntimeError):
                connection.connect_emulator(8766, TOKEN + ';id')
            find.assert_not_called()
