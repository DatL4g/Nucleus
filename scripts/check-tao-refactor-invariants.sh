#!/usr/bin/env bash
# Verifies the FQN-coupling invariants of decorated-window-tao (see
# decorated-window-tao/REFACTOR_VERIFICATION.md, invariants I2/I3).
#
# Cross-checks the *compiled* Kotlin classes against every place that refers
# to them by hard-coded name:
#   1. JNI down-calls : Java_* symbols defined in .rs/.c/.m  <->  external funs
#   2. macOS dylibs   : exported Java_* symbols are not stale
#   3. JNI up-calls   : FindClass / signature literals in native sources
#   4. Class.forName  : reflective literals in Kotlin main sources
#   5. ServiceLoader  : META-INF/services entries + ProGuard -keep rules
#   6. GraalVM        : reachability-metadata.json (module + plugin platform)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/decorated-window-tao"
NATIVE="$MODULE/src/main/native"
CLASSES="$MODULE/build/classes/kotlin/main"
JAVAP="${JAVA_HOME:-}/bin/javap"; [[ -x "$JAVAP" ]] || JAVAP="javap"
FAIL=0

fail() { echo "  FAIL: $*"; FAIL=1; }
note() { echo "  $*"; }

# Native sources, vendored code excluded.
native_sources() {
  find "$NATIVE/src" "$NATIVE/macos" "$NATIVE/windows" "$NATIVE/linux" \
    -type f \( -name '*.rs' -o -name '*.c' -o -name '*.m' -o -name '*.h' \) 2>/dev/null
}

class_exists() { # arg: JVM binary name with dots, e.g. a.b.C$Inner
  [[ -f "$CLASSES/$(echo "$1" | tr '.' '/').class" ]]
}

if [[ ! -d "$CLASSES" ]]; then
  echo "Compiled classes not found — run: ./gradlew :decorated-window-tao:classes" >&2
  exit 1
fi

# ── 1. JNI symbols: native definitions <-> Kotlin external funs ─────────────
echo "[1/6] JNI symbol consistency (sources)"
# Expected set, derived from compiled classes ('$'->_00024, '_'->_1 per JNI spec).
EXPECTED=$(mktemp)
(cd "$CLASSES" && find . -name '*.class' | sed 's|^\./||; s|\.class$||') | while read -r cls; do
  bin="${cls//\//.}"
  "$JAVAP" -p -cp "$CLASSES" "$bin" 2>/dev/null | grep -E '\bnative\b' | \
    sed -E 's/.* ([A-Za-z0-9_$]+)\(.*/\1/' | while read -r m; do
      mangled_cls=$(echo "$cls" | sed 's/_/_1/g; s/\$/_00024/g; s|/|_|g')
      mangled_m=$(echo "$m" | sed 's/_/_1/g')
      echo "Java_${mangled_cls}_${mangled_m}"
    done
done | sort -u > "$EXPECTED"

DEFINED=$(mktemp)
native_sources | xargs grep -hoE 'Java_dev_nucleusframework[A-Za-z0-9_]+' 2>/dev/null | sort -u > "$DEFINED"

MISSING_KOTLIN=$(comm -23 "$DEFINED" "$EXPECTED")
MISSING_NATIVE=$(comm -13 "$DEFINED" "$EXPECTED" | grep '^Java_dev_nucleusframework' || true)
[[ -n "$MISSING_KOTLIN" ]] && fail "native symbols with no matching Kotlin external fun:"$'\n'"$MISSING_KOTLIN"
[[ -n "$MISSING_NATIVE" ]] && fail "Kotlin external funs with no native definition:"$'\n'"$MISSING_NATIVE"
note "$(wc -l < "$DEFINED" | tr -d ' ') native symbols / $(grep -c '^Java_dev_nucleusframework' "$EXPECTED" || true) expected"

# ── 2. macOS dylibs must not export stale symbols ───────────────────────────
echo "[2/6] macOS dylib exported symbols"
for dylib in "$MODULE"/src/main/resources/nucleus/native/darwin-*/*.dylib; do
  [[ -f "$dylib" ]] || continue
  STALE=$(nm -gU "$dylib" 2>/dev/null | grep -oE 'Java_dev_nucleusframework[A-Za-z0-9_]+' | sort -u | comm -23 - "$EXPECTED")
  [[ -n "$STALE" ]] && fail "stale JNI exports in ${dylib#"$MODULE"/} (rebuild natives + clear ~/.cache/nucleus/native):"$'\n'"$STALE"
done
note "checked $(ls "$MODULE"/src/main/resources/nucleus/native/darwin-*/*.dylib 2>/dev/null | wc -l | tr -d ' ') dylibs"

# ── 3. FindClass / signature literals in native sources ─────────────────────
echo "[3/6] Native FQN string literals (FindClass, method signatures)"
while read -r lit; do
  [[ -n "$lit" ]] || continue
  class_exists "${lit//\//.}" || fail "native literal '$lit' resolves to no compiled class"
done < <(native_sources | xargs grep -hoE 'dev/nucleusframework/[A-Za-z0-9/$_]+' 2>/dev/null | sort -u)

# ── 4. Class.forName literals in Kotlin main sources ────────────────────────
echo "[4/6] Class.forName literals (main sources, repo-wide)"
while read -r fqn; do
  [[ -n "$fqn" ]] || continue
  class_exists "$fqn" || fail "Class.forName(\"$fqn\") resolves to no compiled class"
done < <(grep -rhoE 'forName\(\s*"dev\.nucleusframework\.window\.tao[A-Za-z0-9.$]*"' \
    --include='*.kt' "$ROOT" 2>/dev/null | grep -v '/build/' | \
    sed -E 's/.*"([^"]+)".*/\1/' | sort -u)

# ── 5. ServiceLoader + ProGuard rules ───────────────────────────────────────
echo "[5/6] META-INF/services + ProGuard -keep rules"
for svc in "$MODULE"/src/main/resources/META-INF/services/*; do
  [[ -f "$svc" ]] || continue
  while read -r fqn; do
    [[ -n "$fqn" ]] || continue
    class_exists "$fqn" || fail "service entry '$fqn' ($(basename "$svc")) resolves to no compiled class"
  done < <(grep -vE '^\s*(#|$)' "$svc")
done
for pro in "$MODULE"/src/main/resources/META-INF/proguard/*.pro; do
  [[ -f "$pro" ]] || continue
  while read -r fqn; do
    [[ -n "$fqn" ]] || continue
    class_exists "$fqn" || fail "ProGuard rule references '$fqn' which resolves to no compiled class"
  done < <(grep -hoE 'dev\.nucleusframework\.window\.tao[A-Za-z0-9.$]*' "$pro" | sort -u)
done

# ── 6. GraalVM reachability metadata ────────────────────────────────────────
echo "[6/6] GraalVM reachability metadata (module + plugin platform metadata)"
for json in \
    "$MODULE/src/main/resources/META-INF/native-image/dev.nucleusframework/nucleus.decorated-window-tao/reachability-metadata.json" \
    "$ROOT"/plugin-build/plugin/src/main/resources/nucleus/graalvm/platform-metadata/*.json; do
  [[ -f "$json" ]] || continue
  while read -r fqn; do
    [[ -n "$fqn" ]] || continue
    class_exists "$fqn" || fail "$(basename "$json") references '$fqn' which resolves to no compiled class"
  done < <(grep -oE '"dev\.nucleusframework\.window\.tao[A-Za-z0-9.$]*"' "$json" | tr -d '"' | sort -u)
done

echo
if [[ $FAIL -ne 0 ]]; then echo "RESULT: FAILED"; exit 1; fi
echo "RESULT: OK — all FQN-coupling invariants hold"
