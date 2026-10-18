"""Explicit, bounded ADB pairing for an already running emulator (no bpy)."""
import os
from pathlib import Path
import re
import shutil
import subprocess

PACKAGE = 'com.example.blenderpicobridge.p7f82b4c1'


def find_adb(configured=''):
    if configured:
        path = Path(configured).expanduser()
        if path.is_file():
            return str(path)
        raise RuntimeError('ADB executable not found; check the add-on preferences.')
    found = shutil.which('adb')
    if found:
        return found
    executable = 'adb.exe' if os.name == 'nt' else 'adb'
    roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
             str(Path.home() / 'Library/Android/sdk'),
             str(Path.home() / 'Android/Sdk')]
    if os.environ.get('LOCALAPPDATA'):
        roots.append(str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'))
    for root in roots:
        if root and (Path(root) / 'platform-tools' / executable).is_file():
            return str(Path(root) / 'platform-tools' / executable)
    raise RuntimeError('Set ADB executable in Edit > Preferences > Add-ons > PICO Blender Bridge.')


def connect_emulator(port, token, configured_adb=''):
    if not isinstance(port, int) or not 1 <= port <= 65535:
        raise RuntimeError('Invalid bridge port.')
    # ADB shell joins arguments remotely. Never send arbitrary shell characters.
    if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,256}', token):
        raise RuntimeError('Start the bridge with a URL-safe pairing token first.')
    adb = find_adb(configured_adb)

    def run(args, label):
        try:
            result = subprocess.run([adb] + args, capture_output=True, text=True,
                                    timeout=10, **({'creationflags': subprocess.CREATE_NO_WINDOW}
                                                   if os.name == 'nt' else {}))
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError(f'{label} failed; check ADB and the running emulator.') from None
        if result.returncode or 'Error:' in result.stdout or 'Exception' in result.stdout:
            # am start can exit 0 on failure. Do not echo intent extras or credentials.
            raise RuntimeError(f'{label} failed; check that Blender Bridge is installed in the emulator.')
        return result.stdout

    output = run(['devices'], 'Emulator discovery')
    emulators = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and re.fullmatch(r'emulator-\d+', fields[0]) and fields[1] == 'device':
            emulators.append(fields[0])
    if not emulators:
        raise RuntimeError('No online emulator. Start PICO Emulator and wait for it to finish booting.')
    if len(emulators) != 1:
        raise RuntimeError('Multiple emulators are online; leave only the intended PICO Emulator running.')
    serial = emulators[0]
    prefix = ['-s', serial]
    if not run(prefix + ['shell', 'pm', 'path', PACKAGE], 'Viewer check').strip().startswith('package:'):
        raise RuntimeError('Install Blender Bridge in the PICO Emulator first.')
    run(prefix + ['reverse', f'tcp:{port}', f'tcp:{port}'], 'Port forwarding')
    # A fresh activity consumes the new pairing extras. Persistent app data/anchors stay intact.
    run(prefix + ['shell', 'am', 'force-stop', PACKAGE], 'Viewer restart')
    run(prefix + ['shell', 'am', 'start', '-n', PACKAGE + '/.platform.LaunchActivity',
                  '--es', 'bridge_endpoint', f'http://127.0.0.1:{port}',
                  '--es', 'bridge_token', token,
                  '--ez', 'inspection_room', 'true'], 'Viewer launch')
    return f'Pairing sent to {serial}'
