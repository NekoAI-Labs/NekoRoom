#!/usr/bin/env python3
"""Convert FireRed Kaldi CMVN stats to native little-endian means+inverse-std."""
import argparse
import math
import struct
import kaldiio

p = argparse.ArgumentParser(); p.add_argument("input"); p.add_argument("output"); a = p.parse_args()
stats = kaldiio.load_mat(a.input)
count = float(stats[0, -1]); assert stats.shape == (2, 81) and count > 0
means = [float(stats[0, i] / count) for i in range(80)]
istd = [1.0 / math.sqrt(max(1e-20, float(stats[1, i] / count - means[i] ** 2))) for i in range(80)]
with open(a.output, "wb") as f: f.write(struct.pack("<160f", *(means + istd)))
