#!/usr/bin/env bash
# Samples the smoke server heap (jcmd GC.heap_info) + world growth every 60s.
# Usage: heap-sample.sh <pid> <worldDir> <outCsv> <iterations>
set -u
PID="$1"; WORLD="$2"; OUT="$3"; N="$4"
JCMD="C:/Program Files/Java/latest/jdk-25/bin/jcmd.exe"
echo "time_s,heap_used_mb,heap_committed_mb,plates,region_files,log_lines" > "$OUT"
T0=$(date +%s)
for ((i=0; i<N; i++)); do
  sleep 60
  HI=$("$JCMD" "$PID" GC.heap_info 2>/dev/null | head -2 | tr '\n' ' ')
  USED=$(echo "$HI" | grep -o 'used [0-9]*K' | head -1 | grep -o '[0-9]*')
  COMMIT=$(echo "$HI" | grep -o 'committed [0-9]*K' | head -1 | grep -o '[0-9]*')
  PLATES=$(ls "$WORLD/mantle" 2>/dev/null | grep -c 'ttp.lz4b')
  REGIONS=$(find "$WORLD/region" -name "*.mca" 2>/dev/null | wc -l)
  LOGL=$(wc -l < "$(dirname "$WORLD")/server9.log" 2>/dev/null || echo 0)
  T=$(( $(date +%s) - T0 ))
  echo "$T,$((USED/1024)),$((COMMIT/1024)),$PLATES,$REGIONS,$LOGL" >> "$OUT"
done
echo "sampling done: $OUT"