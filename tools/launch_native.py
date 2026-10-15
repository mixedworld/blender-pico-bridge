"""Install and pair the native viewer without printing the pairing token."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.example.blenderpicobridge.p7f82b4c1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="emulator-5554", help="Use the serial reported by adb devices")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--pairing-file", type=Path, default=ROOT / ".pairing.json")
    parser.add_argument("--mode", choices=["SCALE", "EXPLODE"], default="SCALE")
    parser.add_argument("--explode", type=float, default=0.0, help="Debug seed 0..1; does not simulate hand input")
    parser.add_argument("--room", action="store_true", help="Experimental Full Space mode")
    parser.add_argument("--monitor", action="store_true", help="Experimental monitor mode")
    parser.add_argument("--quiet", action="store_true")
    visibility = parser.add_mutually_exclusive_group()
    visibility.add_argument("--source-hide", action="store_true")
    visibility.add_argument("--source-visible", action="store_true")
    parser.add_argument("--reveal", type=float, choices=[0.0, 0.5, 1.0], help="Debug capture seed; disables model gestures")
    args = parser.parse_args()
    if not 0 <= args.explode <= 1:
        parser.error("--explode must be between 0 and 1")
    adb_path = shutil.which("adb")
    if not adb_path:
        parser.error("adb is missing; add Android Platform-Tools to PATH")
    if not args.pairing_file.is_file():
        parser.error("Create .pairing.json from pairing.example.json and paste the current Blender token")
    try:
        pair = json.loads(args.pairing_file.read_text(encoding="utf-8"))
        token = pair["token"]
        endpoint = urlsplit(pair["endpoint"])
        port = endpoint.port or 80
        if endpoint.scheme != "http" or not endpoint.hostname or not 1 <= port <= 65535:
            raise ValueError()
        # Generated bridge tokens are URL-safe. Restrict characters because adb shell
        # forwards its argument list to a remote shell on Android.
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,256}", token) or token == "PASTE_TOKEN_FROM_BLENDER_HERE":
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        parser.error("Pairing file needs a valid http endpoint and the URL-safe token copied from Blender")
    candidates = [ROOT / "dist/release/blender-pico-bridge-debug.apk",
                  ROOT / "native-app/app/build/outputs/apk/debug/app-debug.apk",
                  ROOT / "dist/blender-pico-bridge-debug.apk"]
    apk = args.apk or next((path for path in candidates if path.is_file()), None)
    if apk is None or not apk.is_file():
        parser.error("Build/package the viewer first or supply --apk PATH")
    adb = [adb_path, "-s", args.device]

    def run(command):
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout.replace(token, "<pairing-token>"), end="")
        if result.returncode:
            raise SystemExit(result.returncode)

    run(adb + ["install", "-r", str(apk)])
    run(adb + ["reverse", f"tcp:{port}", f"tcp:{port}"])
    run(adb + ["shell", "am", "force-stop", PACKAGE])
    seed = ["--ez", "inspection_room", str(args.room).lower(), "--ez", "inspection_monitor", str(args.monitor).lower(),
            "--ez", "inspection_quiet", str(args.quiet).lower()]
    if args.source_hide:
        seed += ["--ez", "inspection_hide_source", "true"]
    if args.source_visible:
        seed += ["--ez", "inspection_hide_source", "false"]
    if args.reveal is not None:
        seed += ["--ef", "inspection_reveal", str(args.reveal)]
    local_endpoint = f"http://127.0.0.1:{port}"
    run(adb + ["shell", "am", "start", "-n", PACKAGE + "/.platform.LaunchActivity", "--es", "bridge_endpoint", local_endpoint,
               "--es", "bridge_token", token, "--es", "inspection_mode", args.mode, "--ef", "inspection_explosion", str(args.explode)] + seed)
    print(f"Paired {args.device} with {local_endpoint}; keep Blender's bridge running.")


if __name__ == "__main__":
    main()
