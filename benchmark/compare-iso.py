#!/usr/bin/env python
"""Isolated A/B comparison: median ns/op + B/op of the last 5 of 9 iterations
per scenario, plus digest identity check across every iteration."""
import csv
import glob
import statistics
import sys

base = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"
rows = {}
for f in glob.glob(base + "/iso15-*.csv") + glob.glob(base + "/iso16-*.csv"):
    tag = "r15" if "iso15-" in f else "r16"
    name = f.split("iso15-")[-1].split("iso16-")[-1].replace(".csv", "")
    for r in csv.DictReader(open(f)):
        rows.setdefault((tag, name), []).append(
            (float(r["ns_per_op"]), float(r["bytes_per_op"]), r["digest"]))
print(f"{'scenario':22s} {'r15 med':>12s} {'r16 med':>12s} {'speedup':>8s} "
      f"{'r15 B/op':>10s} {'r16 B/op':>10s}  digest")
names = sorted(n for _, n in rows)
for n in names:
    a = rows.get(("r15", n))
    b = rows.get(("r16", n))
    if not a or not b:
        continue
    at = statistics.median([x[0] for x in a][-5:])
    bt = statistics.median([x[0] for x in b][-5:])
    ab = statistics.median([x[1] for x in a][-5:])
    bb = statistics.median([x[1] for x in b][-5:])
    dig = "IDENTICAL" if {x[2] for x in a} == {x[2] for x in b} and len({x[2] for x in b}) == 1 \
        else f"a={a[0][2][:8]} b={b[0][2][:8]}"
    print(f"{n:22s} {at:12.1f} {bt:12.1f} {at / bt:7.3f}x {ab:10.1f} {bb:10.1f}  {dig}")
