#!/usr/bin/env bash
# Compiles the benchmark harness together with the REAL production sources.
# benchmark/stubs shadows only classes that cannot be compiled standalone
# (plugin bootstrap Iris/IrisSettings, Kotlin classes, unavailable external libs).
# All files are passed EXPLICITLY to javac so that lombok annotation processing
# applies to every unit (implicit -sourcepath compilation skips processing).
set -e
cd "$(dirname "$0")/.."
rm -rf benchmark/classes
mkdir -p benchmark/classes
CP="benchmark/lib/lombok-1.18.42.jar;benchmark/lib/spigot-api-1.20.1-R0.1-SNAPSHOT.jar;benchmark/lib/guava-33.6.0-jre.jar;benchmark/lib/gson-2.13.2.jar;benchmark/lib/caffeine-3.2.4.jar;benchmark/lib/concurrentlinkedhashmap-lru-1.4.2.jar;benchmark/lib/annotations-26.0.1.jar;benchmark/lib/adventure-api-4.24.0.jar;benchmark/lib/adventure-key-4.24.0.jar;benchmark/lib/examination-api-1.3.0.jar;benchmark/lib/paperlib-1.0.8.jar;benchmark/lib/fastutil-8.5.16.jar;benchmark/lib/kotlinx-coroutines-core-jvm-1.9.0.jar;benchmark/lib/lz4-java-1.8.0.jar;benchmark/lib/adventure-text-minimessage-4.24.0.jar;benchmark/lib/bungeecord-chat-1.16-R0.4.jar;benchmark/lib/commons-lang-2.6.jar;benchmark/lib/commons-io-2.15.1.jar;benchmark/lib/dom4j-2.1.4.jar;benchmark/lib/commons-lang3-3.14.0.jar;benchmark/lib/zt-zip-1.16.jar;benchmark/lib/commons-math3-3.6.1.jar;benchmark/lib/sentry-8.14.0.jar;benchmark/lib/log4j-api-2.24.3.jar;benchmark/lib/log4j-core-2.24.3.jar;benchmark/lib/jspecify-1.0.0.jar;benchmark/lib/checker-qual-3.48.4.jar;benchmark/lib/byte-buddy-1.15.11.jar;benchmark/lib/byte-buddy-agent-1.15.11.jar;benchmark/lib/adventure-platform-bukkit-4.4.1.jar;benchmark/lib/adventure-platform-api-4.4.1.jar;benchmark/lib/bstats-bukkit-3.0.2.jar;benchmark/lib/bstats-base-3.0.2.jar;benchmark/lib/oshi-core-6.6.5.jar;benchmark/lib/jna-5.15.0.jar;benchmark/lib/jna-platform-5.15.0.jar"
# Harness + stubs first (stubs win over core for shadowed FQNs)
find benchmark/src benchmark/stubs -name "*.java" | sed 's/\r$//' > benchmark/classes/sources.txt
# All real core sources except the two shadowed by stubs (Iris.java, IrisSettings.java)
# and third-party plugin integrations whose compileOnly deps are unavailable offline.
find core/src/main/java -name "*.java" \
  ! -path "*com/volmit/iris/Iris.java" \
  ! -path "*com/volmit/iris/core/link/data/*DataProvider.java" \
  ! -path "*com/volmit/iris/core/link/IrisPapiExpansion.java" \
  ! -path "*com/volmit/iris/core/link/MultiverseCoreLink.java" \
  ! -path "*com/volmit/iris/util/misc/SlimJar.java" \
  | sed 's/\r$//' >> benchmark/classes/sources.txt
javac -proc:full -encoding UTF-8 -nowarn \
  -cp "$CP" \
  -d benchmark/classes \
  @benchmark/classes/sources.txt
echo "BUILD OK -> benchmark/classes ($(find benchmark/classes -name '*.class' | wc -l) classes)"
