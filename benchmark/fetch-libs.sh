#!/usr/bin/env bash
# (Re)downloads the jars listed in lib.list into benchmark/lib/
set -e
cd "$(dirname "$0")"
mkdir -p lib
MC=https://repo.maven.apache.org/maven2
curl -sS -o lib/lombok-1.18.42.jar "$MC/org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar"
curl -sS -o lib/guava-33.6.0-jre.jar "$MC/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"
curl -sS -o lib/gson-2.13.2.jar "$MC/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
curl -sS -o lib/caffeine-3.2.4.jar "$MC/com/github/ben-manes/caffeine/caffeine/3.2.4/caffeine-3.2.4.jar"
curl -sS -o lib/concurrentlinkedhashmap-lru-1.4.2.jar "$MC/com/googlecode/concurrentlinkedhashmap/concurrentlinkedhashmap-lru/1.4.2/concurrentlinkedhashmap-lru-1.4.2.jar"
curl -sS -o lib/annotations-26.0.1.jar "$MC/org/jetbrains/annotations/26.0.1/annotations-26.0.1.jar"
curl -sS -o lib/adventure-api-4.24.0.jar "$MC/net/kyori/adventure-api/4.24.0/adventure-api-4.24.0.jar"
curl -sS -o lib/adventure-key-4.24.0.jar "$MC/net/kyori/adventure-key/4.24.0/adventure-key-4.24.0.jar"
curl -sS -o lib/examination-api-1.3.0.jar "$MC/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"
curl -sS -o lib/paperlib-1.0.8.jar "https://repo.papermc.io/repository/maven-public/io/papermc/paperlib/1.0.8/paperlib-1.0.8.jar"
echo "paper-api jar must be copied from the local gradle cache (io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT) or built locally; see lib.list"
