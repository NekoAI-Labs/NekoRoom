#!/usr/bin/env bash
set -euo pipefail
SRC=${1:?pass verified fireredvad_vad_fp16.mnn path}
DST=${2:?pass Android working-copy assets directory}
CMVN=${3:?pass FireRed cmvn.ark path}
EXPECTED=92bfcc966bd71b754c4f608cee536da308e9793874099434c2be026a94741651
ACTUAL=$(sha256sum "$SRC" | awk '{print $1}')
test "$ACTUAL" = "$EXPECTED" || { echo "unexpected FireRed model checksum: $ACTUAL" >&2; exit 1; }
mkdir -p "$DST/vad"
cp "$SRC" "$DST/vad/fireredvad_vad_fp16.mnn"
python3 "$(dirname "$0")/convert_firered_cmvn.py" "$CMVN" "$DST/vad/fireredvad_cmvn.bin"
