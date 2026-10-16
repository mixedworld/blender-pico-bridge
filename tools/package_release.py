"""Package only public add-on sources and a verified debug APK. Python 3.9+."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADDON = ROOT / "blender-addon/pico_blender_bridge"


def version():
    for node in ast.parse((ADDON / "__init__.py").read_text()).body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "bl_info" for t in node.targets):
            return ".".join(map(str, ast.literal_eval(node.value)["version"]))
    raise ValueError("Add-on version missing")


def validate_payload(data, name, private_token):
    for prefix in (b"/" + b"Users/", b"/" + b"Volumes/", b"C:\\Users\\"):
        if prefix in data:
            raise ValueError(f"Local authoring path in {name}")
    if private_token and private_token in data:
        raise ValueError(f"Private pairing token in {name}")


def validate_apk(apk, private_token):
    source_bundle = ROOT / "native-app/app/src/main/assets/monitor_occlusion.bundle"
    with zipfile.ZipFile(apk) as archive:
        if "assets/box.usdz" in archive.namelist():
            raise ValueError("APK includes the unused legacy scaffold model; rebuild from the public sources")
        entry = archive.getinfo("assets/monitor_occlusion.bundle")
        if entry.compress_type != zipfile.ZIP_STORED:
            raise ValueError("APK bundle is compressed; keep androidResources.noCompress")
        if archive.read(entry) != source_bundle.read_bytes():
            raise ValueError("APK contains a different occlusion bundle; rebuild first")
        for abi in ("arm64-v8a", "x86_64"):
            if not any(n.startswith(f"lib/{abi}/") and n.endswith(".so") for n in archive.namelist()):
                raise ValueError(f"APK is missing native libraries for {abi}")
        for entry in archive.infolist():
            if not entry.is_dir():
                validate_payload(archive.read(entry), entry.filename, private_token)
    with zipfile.ZipFile(source_bundle) as archive:
        for name in archive.namelist():
            validate_payload(archive.read(name), name, private_token)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--addon-only", action="store_true")
    parser.add_argument("--apk", type=Path, default=ROOT / "native-app/app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/release")
    args = parser.parse_args()
    token = None
    pairing = ROOT / ".pairing.json"
    if pairing.exists():
        token = json.loads(pairing.read_text()).get("token", "").encode() or None
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.addon_only:
        if not args.apk.is_file():
            parser.error("Build the debug APK first, or use --addon-only")
        validate_apk(args.apk, token)
    addon_zip = args.output / f"pico_blender_bridge-{version()}.zip"
    sources = [(ADDON / name, f"pico_blender_bridge/{name}") for name in
               ("__init__.py", "glb.py", "server.py", "pairing.py", "qrcodegen.py", "calibration.py", "display.py", "tracked_camera.py", "source_visibility.py")]
    sources.append((ROOT / "LICENSE", "pico_blender_bridge/LICENSE"))
    with zipfile.ZipFile(addon_zip, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, name in sources:
            data = path.read_bytes()
            validate_payload(data, name, token)
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    outputs = [addon_zip]
    if not args.addon_only:
        target = args.output / "blender-pico-bridge-debug.apk"
        if args.apk.resolve() != target.resolve():
            shutil.copy2(args.apk, target)
        outputs.append(target)
    checksum = args.output / "SHA256SUMS"
    checksum.write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in outputs), encoding="utf-8")
    for path in outputs + [checksum]:
        print(path)


if __name__ == "__main__":
    main()
