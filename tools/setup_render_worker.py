"""Create the optional local encoder environment. Run using Python 3.10 or newer."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import venv


def main():
    if sys.version_info < (3, 10):
        raise SystemExit("Use Python 3.10 or newer to set up the render worker")
    default = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local/share")) / "blender-pico-bridge/render-worker"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=default)
    args = parser.parse_args()
    venv.EnvBuilder(with_pip=True).create(args.directory)
    python = args.directory / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    requirements = Path(__file__).with_name("render-worker-requirements.txt")
    subprocess.run([str(python), "-m", "pip", "install", "-r", str(requirements)], check=True)
    print("Render worker Python:", python.resolve())
    print("Use this interpreter in Blender → Preferences → Add-ons → PICO Blender Bridge if you chose a custom directory.")


if __name__ == "__main__":
    main()
