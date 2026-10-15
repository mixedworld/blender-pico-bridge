"""Record the PICO HTTP-FLV cast directly, retaining video and audio quality.

Start casting on the headset and connect the browser once. Then navigate that
browser tab to about:blank (do not press End Cast). The stream allows one reader.
Run this script; q or Ctrl-C finalizes the MP4. --seconds 0 records until stopped.
"""
import argparse
import datetime
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--seconds", type=float, default=30)
parser.add_argument("--url", default="http://127.0.0.1:13342/test.flv?abr_pts=-800")
parser.add_argument("--output", type=Path)
args = parser.parse_args()
if args.seconds < 0:
    parser.error("--seconds must be 0 or positive")
ffmpeg = shutil.which("ffmpeg")
if not ffmpeg:
    candidates = list((ROOT / ".tools/ffmpeg").glob("lib/python*/site-packages/imageio_ffmpeg/binaries/ffmpeg-*"))
    if not candidates:
        parser.error("ffmpeg is missing")
    ffmpeg = str(candidates[0])
output = args.output or ROOT / "recordings" / ("pico-cast-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + ".mp4")
output.parent.mkdir(parents=True, exist_ok=True)
command = [ffmpeg, "-hide_banner", "-n", "-rw_timeout", "10000000", "-i", args.url]
if args.seconds:
    command += ["-t", str(args.seconds)]
command += ["-map", "0:v:0", "-map", "0:a?", "-c", "copy", "-movflags", "+faststart", str(output)]
print("Recording to", output, flush=True)
try:
    result = subprocess.run(command)
except KeyboardInterrupt:
    # FFmpeg also receives Ctrl-C and finalizes the output container.
    raise SystemExit(130)
if result.returncode:
    print("If the server reports '404 not allow', disconnect browser playback first.")
raise SystemExit(result.returncode)
