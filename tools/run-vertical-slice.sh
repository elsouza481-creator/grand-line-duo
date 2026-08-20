#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/vertical-slice"
rm -rf "$OUT"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/core/src/main/kotlin" -name '*.kt' -type f | sort)
kotlinc "${SOURCES[@]}" -d "$OUT/demo.jar"
kotlin -classpath "$OUT/demo.jar" grandlineduo.demo.VerticalSliceDemoKt
