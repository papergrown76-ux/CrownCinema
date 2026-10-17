#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
for tool in python3 ffmpeg node npm; do command -v "$tool" >/dev/null; done
./mvnw -B -ntp verify
./mvnw -f backend-bridge/pom.xml -B -ntp verify
npm --prefix tools/smoke ci --cache build/npm-cache --no-audit --no-fund
python3 tools/run-test-proxy.py --prepare-only
