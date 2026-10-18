#!/usr/bin/env python3
"""Opt-in physical-device OpenXR discovery using a small JNI helper and the public Khronos loader.

Install both debug and androidTest APKs first. Requires a local Android NDK. No camera capture.
The helper is built under native-app/.scratch, loaded from app code_cache, then removed.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

PACKAGE = 'com.example.blenderpicobridge.p7f82b4c1'
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, help='Exact adb serial; never guesses a device')
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    adb = shutil.which('adb')
    if not adb:
        parser.error('adb must be on PATH')
    base = [adb, '-s', args.device]

    def run(command, **kwargs):
        return subprocess.run(command, check=True, **kwargs)

    abi = run(base + ['shell', 'getprop', 'ro.product.cpu.abi'], capture_output=True, text=True).stdout.strip()
    triples = {'arm64-v8a': 'aarch64-linux-android35', 'x86_64': 'x86_64-linux-android35'}
    if abi not in triples:
        parser.error(f'Unsupported diagnostic ABI: {abi}')
    hosts = list((args.ndk / 'toolchains/llvm/prebuilt').glob('*'))
    compiler = next((h / 'bin' / ('clang++.exe' if sys.platform == 'win32' else 'clang++')
                     for h in hosts if (h / 'bin' / ('clang++.exe' if sys.platform == 'win32' else 'clang++')).exists()), None)
    if compiler is None:
        parser.error('No NDK clang++ found')
    gradle = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    artifacts = list((gradle / 'caches/modules-2/files-2.1/org.khronos.openxr/openxr_loader_for_android/1.1.63').glob('*/*.aar'))
    if not artifacts:
        parser.error('Build the androidTest APK first to resolve the pinned Khronos loader AAR')
    scratch = ROOT / 'native-app/.scratch/openxr-native-probe'
    include = scratch / 'include/openxr'
    include.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifacts[0]) as artifact:
        for name in ('openxr.h', 'openxr_platform.h', 'openxr_platform_defines.h'):
            (include / name).write_bytes(artifact.read('prefab/modules/headers/include/openxr/' + name))
    output = scratch / 'libopenxr_probe.so'
    source = ROOT / 'native-app/app/src/androidTest/cpp/openxr_probe.cpp'
    run([str(compiler), '--target=' + triples[abi], '-std=c++17', '-shared', '-fPIC', '-static-libstdc++',
         '-Wl,-z,max-page-size=16384', '-I' + str(include.parent), str(source), '-o', str(output), '-ldl'])
    run(base + ['shell', 'run-as', PACKAGE, 'mkdir', '-p', 'code_cache'])
    run(base + ['shell', 'run-as', PACKAGE, 'tee', 'code_cache/libopenxr_probe.so'],
        input=output.read_bytes(), stdout=subprocess.DEVNULL)
    run(base + ['shell', 'run-as', PACKAGE, 'chmod', '400', 'code_cache/libopenxr_probe.so'])
    try:
        result = run(base + ['shell', 'am', 'instrument', '-w', '-e', 'class', PACKAGE + '.OpenXrCameraCapabilityTest',
                            '-e', 'physical_camera', 'true', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
                     capture_output=True, text=True, timeout=30)
        print(result.stdout)
        if 'OK (' not in result.stdout:
            raise RuntimeError('Native capability probe did not pass')
        log = run(base + ['logcat', '-d', '-s', 'System.out:I'], capture_output=True, text=True).stdout
        reports = []
        for line in log.splitlines():
            if 'OPENXR_CAMERA {' in line:
                reports.append(json.loads(line.split('OPENXR_CAMERA ', 1)[1]))
        if not reports:
            raise RuntimeError('No native OpenXR capability result found')
        print(json.dumps(reports[-1], indent=2))
    finally:
        run(base + ['shell', 'run-as', PACKAGE, 'rm', '-f', 'code_cache/libopenxr_probe.so'])


if __name__ == '__main__':
    main()
