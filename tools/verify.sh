#!/usr/bin/env bash
#
# Compile and run the pure-Kotlin tests on this machine, before pushing.
# Gradle cannot build Android here (Google's Maven is not reachable from the
# Claude Code sandbox), but the Kotlin compiler is on Maven Central, so the
# parts with no Android import — Core.kt — can be compiled and tested.
# Exits non-zero on any compile error or failing test.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="${KVERIFY_LIB:-/tmp/lu-klib}"
OUT="${KVERIFY_OUT:-/tmp/lu-kverify}"
K=2.0.21
mkdir -p "$LIB"; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"

fetch() {
  [ -s "$LIB/$2" ] && return 0
  for t in 1 2 3 4 5; do
    curl -sSfL -o "$LIB/$2" "https://repo1.maven.org/maven2/$1" && return 0
    sleep $((t * 3))
  done
  echo "could not fetch $1"; exit 1
}
fetch "org/jetbrains/kotlin/kotlin-compiler-embeddable/$K/kotlin-compiler-embeddable-$K.jar" compiler.jar
fetch "org/jetbrains/kotlin/kotlin-stdlib/$K/kotlin-stdlib-$K.jar" stdlib.jar
fetch "org/jetbrains/kotlin/kotlin-reflect/$K/kotlin-reflect-$K.jar" reflect.jar
fetch "org/jetbrains/kotlin/kotlin-script-runtime/$K/kotlin-script-runtime-$K.jar" script.jar
fetch "org/jetbrains/kotlin/kotlin-daemon-embeddable/$K/kotlin-daemon-embeddable-$K.jar" daemon.jar
fetch "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" trove4j.jar
fetch "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar" coroutines.jar
# Without this on the compiler's own classpath IR lowering dies with no error line.
fetch "org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" annotations.jar
fetch "junit/junit/4.13.2/junit-4.13.2.jar" junit.jar
fetch "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" hamcrest.jar

unset JAVA_TOOL_OPTIONS  # only the downloads needed the proxy settings it carries
C="$LIB/compiler.jar:$LIB/stdlib.jar:$LIB/reflect.jar:$LIB/script.jar:$LIB/daemon.jar:$LIB/trove4j.jar:$LIB/coroutines.jar:$LIB/annotations.jar"
kc() { java -cp "$C" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn -jvm-target 17 "$@"; }

PURE=$(grep -L -E '^import (android|androidx|kotlinx|org\.json)' "$ROOT"/app/src/main/java/com/jollydoddger/liningup/*.kt || true)
echo "== compiling: $(echo $PURE | xargs -n1 basename | tr '\n' ' ')"
kc -d "$OUT/main" -cp "$LIB/stdlib.jar" $PURE
kc -d "$OUT/test" -cp "$LIB/stdlib.jar:$LIB/junit.jar:$OUT/main" -Xfriend-paths="$OUT/main" \
  "$ROOT"/app/src/test/java/com/jollydoddger/liningup/*.kt
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')
echo "== testing"
java -cp "$OUT/main:$OUT/test:$LIB/stdlib.jar:$LIB/junit.jar:$LIB/hamcrest.jar" org.junit.runner.JUnitCore $CLASSES
