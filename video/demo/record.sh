#!/bin/bash
# Records the demo: a virtual screen (only the 1920x1080 page area below the browser's toolbar is captured), a real browser driven by record.mjs,
# and ffmpeg capturing the screen at 30 fps. Output: out/continuum-demo.mp4
# Needs: Xvfb, ffmpeg, node + playwright, and the stack running (see README.md).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
DISPLAY_NUM=${DISPLAY_NUM:-99}
Xvfb ":$DISPLAY_NUM" -screen 0 1920x1200x24 -nolisten tcp >/dev/null 2>&1 &
XVFB=$!
trap 'kill $XVFB 2>/dev/null || true' EXIT
sleep 1.5
date +%s.%N > out/capture-start.txt
ffmpeg -loglevel error -y -f x11grab -draw_mouse 0 -framerate 30 -video_size 1920x1080 -i ":$DISPLAY_NUM+0,${CHROME_TOP:-87}" \
  -c:v libx264 -preset veryfast -crf 16 -pix_fmt yuv420p out/raw.mp4 &
FF=$!
sleep 1
DISPLAY=":$DISPLAY_NUM" MARKS_FILE="$PWD/out/chapters.json" node record.mjs
sleep 1
kill -INT $FF; wait $FF || true
# The capture starts a moment before the browser window appears: trim the first second,
# and write the chapter list into the file so players can jump between features.
python3 - <<'PY'
import json
marks = json.load(open("out/chapters.json"))
t0 = float(open("out/capture-start.txt").read()) + 1.0  # the first second is trimmed below
lines = [";FFMETADATA1", "title=Continuum feature demo"]
for i, m in enumerate(marks):
    start = max(0, m["at"] - t0)
    end = (marks[i + 1]["at"] - t0) if i + 1 < len(marks) else None
    lines += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={int(start*1000)}"]
    lines += [f"END={int(end*1000)}" if end else "END=999999999", f"title={m['title']}"]
open("out/chapters.txt", "w").write("\n".join(lines) + "\n")
PY
ffmpeg -loglevel error -y -ss 1 -i out/raw.mp4 -i out/chapters.txt -map_metadata 1 -map_chapters 1 \
  -c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p -movflags +faststart out/continuum-demo.mp4
echo "wrote $(pwd)/out/continuum-demo.mp4"
