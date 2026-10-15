"""Inspect the exact Git index for private files, credentials and local paths."""
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_DIRS = {"evidence", "recordings", "research", "demo", ".tools", ".scratch", ".gradle", ".idea", "__pycache__", ".CacheData", "UserSettings"}
FORBIDDEN_NAMES = {".pairing.json", "local.properties", ".DS_Store", ".pico-env.json"}
FORBIDDEN_SUFFIXES = {".apk", ".aab", ".blend", ".glb", ".mp4", ".keystore", ".jks", ".pyc", ".log"}
PATH_PREFIXES = (b"/" + b"Users/", b"/" + b"Volumes/", b"C:\\Users\\")


def main():
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    names = [n for n in names if n]
    if not names:
        raise SystemExit("No files in Git index; stage the intended public sources first")
    private = ROOT / ".pairing.json"
    token = json.loads(private.read_text()).get("token", "").encode() if private.exists() else b""
    failures = []

    def inspect(data, name):
        if token and token in data:
            failures.append(f"{name}: private pairing token")
        if any(p in data for p in PATH_PREFIXES):
            failures.append(f"{name}: local authoring path")
        if re.search(rb"(?:gh[pousr]_)[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", data):
            failures.append(f"{name}: possible credential/private key")

    for name in names:
        path = Path(name)
        if any(part in FORBIDDEN_DIRS for part in path.parts) or path.name in FORBIDDEN_NAMES or path.suffix in FORBIDDEN_SUFFIXES:
            failures.append(f"{name}: excluded file category")
        data = subprocess.check_output(["git", "show", f":{name}"], cwd=ROOT)
        inspect(data, name)
        if path.suffix == ".bundle":
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for entry in archive.namelist():
                    inspect(archive.read(entry), f"{name}!{entry}")
        if len(data) > 10 * 1024 * 1024:
            failures.append(f"{name}: unexpectedly large source file")
    if failures:
        for failure in failures:
            print(failure)
        raise SystemExit(1)
    print(f"PASS: {len(names)} staged files; no forbidden files, known private token, local paths or obvious private keys found")
    print("This is a scoped publication check, not a substitute for reviewing the staged diff.")


if __name__ == "__main__":
    main()
