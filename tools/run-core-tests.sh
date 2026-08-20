#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/core-tests"
rm -rf "$OUT"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/core/src/main/kotlin" "$ROOT/core/src/test/kotlin" -name '*.kt' -type f 2>/dev/null | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "No Kotlin sources found" >&2
  exit 2
fi
kotlinc -J-Xmx3g -language-version 2.0 "${SOURCES[@]}" -d "$OUT/tests.jar"
kotlin -classpath "$OUT/tests.jar" grandlineduo.test.TestRunnerKt
