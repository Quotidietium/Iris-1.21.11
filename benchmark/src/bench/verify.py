#!/usr/bin/env python3
"""Compares two benchmark CSVs: iteration-0 digests (fixed seed) must match.

Exit code 0 = behaviorally identical, 1 = digests differ (prints details).
"""
import csv
import sys


def digests_of(path):
    out = {}
    with open(path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            if row['iteration'] == '0':
                out[row['scenario']] = (row['digest'], row['samples'])
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    a, b = digests_of(sys.argv[1]), digests_of(sys.argv[2])
    if set(a) != set(b):
        print(f"scenario sets differ: {set(a) ^ set(b)}")
        sys.exit(1)
    bad = 0
    for k in sorted(a):
        if a[k] != b[k]:
            print(f"MISMATCH {k}: {a[k]} != {b[k]}")
            bad += 1
        else:
            print(f"OK       {k}: digest={a[k][0]} samples={a[k][1]}")
    if bad:
        print(f"{bad} scenario(s) CHANGED behavior")
        sys.exit(1)
    print("ALL SCENARIOS BIT-IDENTICAL")


if __name__ == '__main__':
    main()
