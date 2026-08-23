#!/usr/bin/env bash
# JFR profile a single scenario (or a filter substring) of the benchmark suite.
# Usage: prof.sh <filter> <iters> <out.jfr> [warmups]
# Runs the same harness as run.sh with -XX:StartFlightRecording (profile settings).
# Analyze with: jfr print --events jdk.ExecutionSample <out.jfr>
set -e
cd "$(dirname "$0")/.."
FILTER="$1"; ITERS="$2"; OUT="$3"; WARM="${4:-3}"
mkdir -p "$(dirname "$OUT")"
CP="benchmark/classes;benchmark/lib/spigot-api-1.20.1-R0.1-SNAPSHOT.jar;benchmark/lib/guava-33.6.0-jre.jar;benchmark/lib/gson-2.13.2.jar;benchmark/lib/caffeine-3.2.4.jar;benchmark/lib/concurrentlinkedhashmap-lru-1.4.2.jar;benchmark/lib/annotations-26.0.1.jar;benchmark/lib/adventure-api-4.24.0.jar;benchmark/lib/adventure-key-4.24.0.jar;benchmark/lib/examination-api-1.3.0.jar;benchmark/lib/paperlib-1.0.8.jar;benchmark/lib/fastutil-8.5.16.jar;benchmark/lib/kotlinx-coroutines-core-jvm-1.9.0.jar;benchmark/lib/kotlin-stdlib-2.0.21.jar;benchmark/lib/lz4-java-1.8.0.jar;benchmark/lib/adventure-text-minimessage-4.24.0.jar;benchmark/lib/bungeecord-chat-1.16-R0.4.jar;benchmark/lib/commons-lang-2.6.jar;benchmark/lib/commons-io-2.15.1.jar;benchmark/lib/dom4j-2.1.4.jar;benchmark/lib/commons-lang3-3.14.0.jar;benchmark/lib/zt-zip-1.16.jar;benchmark/lib/commons-math3-3.6.1.jar"
java -Xms3g -Xmx3g -XX:+AlwaysPreTouch \
  -XX:StartFlightRecording="filename=$OUT,settings=profile" \
  -cp "$CP" "-Dbench.filter=$FILTER" bench.Benchmark \
  "benchmark/results/_prof.csv" "$WARM" "$ITERS"
echo "JFR -> $OUT"
