#!/usr/bin/env bash
# Verifies behavioral identity between two benchmark result CSVs.
# Compares the digest column of iteration 0 (fixed seed 900000) for every
# scenario. Any digest mismatch means terrain-affecting behavior changed.
# Usage: verify.sh <baselineCsv> <candidateCsv>
set -e
cd "$(dirname "$0")/.."
python benchmark/src/bench/verify.py "$@"
