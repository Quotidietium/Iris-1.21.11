#!/usr/bin/env python3
"""Compares two benchmark CSVs: iteration-0 digests (fixed seed) must match.

Exit code 0 = behaviorally identical, 1 = digests differ (prints details).

A baseline digest may list several accepted values separated by '|'
(e.g. 'fc83d9..|784ea6..'): the scenario then passes if the candidate digest
matches ANY of them, and samples comparison is skipped. This is ONLY for the
two trig/noise-heavy scenarios (decorator-decorate, layers-gen) whose digests
flip between two JDK/Math-environment states (see benchmark/README.md
"digest drift"). Single-value baselines stay strict: digest AND samples.
"""
import csv
import sys


def digests_of(path):
    """Reads digests. Accepts either a full benchmark CSV (filters iteration 0)
    or a golden CSV with columns scenario,digest[,samples]."""
    out = {}
    with open(path, newline='', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader)
        if 'digest' in header and 'iteration' in header:
            idx = {name: i for i, name in enumerate(header)}
            for row in reader:
                if row[idx['iteration']] == '0':
                    out[row[idx['scenario']]] = (row[idx['digest']], row[idx['samples']])
        elif 'digest' in header:
            idx = {name: i for i, name in enumerate(header)}
            for row in reader:
                out[row[idx['scenario']]] = (row[idx['digest']], row[idx['samples']] if 'samples' in idx else '?')
        else:
            raise SystemExit(f"unrecognized CSV format: {header}")
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
        accepted = a[k][0].split('|')
        if b[k][0] not in accepted:
            print(f"MISMATCH {k}: {a[k]} != {b[k]}")
            bad += 1
        elif len(accepted) == 1:
            if a[k][1] != b[k][1]:
                print(f"MISMATCH {k}: samples {a[k][1]} != {b[k][1]}")
                bad += 1
            else:
                print(f"OK       {k}: digest={a[k][0]} samples={a[k][1]}")
        else:
            print(f"OK       {k}: digest={b[k][0]} samples={b[k][1]} "
                  f"(accepted set of {len(accepted)})")
    if bad:
        print(f"{bad} scenario(s) CHANGED behavior")
        sys.exit(1)
    print("ALL SCENARIOS BIT-IDENTICAL")


if __name__ == '__main__':
    main()
