#!/usr/bin/env bash
# Body APK builder — environment-adaptive entrypoint.
#
# The real build is a manual pipeline (aapt2 -> javac -> kotlinc -> d8 -> apksigner)
# needing a JDK plus the pieces termux/fetch-toolchain.sh downloads into tools/.
# This script builds directly when a JDK is reachable (Termux with openjdk-17, or the
# Fedora proot) and otherwise delegates into the proot. Low-level pipeline lives in
# termux/build-apk-fedora.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FEDORA_BUILD="$ROOT/termux/build-apk-fedora.sh"

ALT_JAVA="${BODY_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}/bin/java"

have_java() { command -v javac >/dev/null 2>&1 || [ -x "$ALT_JAVA" ]; }
have_kit()  { [ -f "$ROOT/tools/android.jar" ] && [ -f "$ROOT/tools/kotlinc/lib/kotlin-compiler.jar" ]; }

# 1) toolchain reachable here: build directly.
if have_java && have_kit; then
  echo "[build-apk] toolchain reachable — building directly."
  exec bash "$FEDORA_BUILD" "$@"
fi

# 2) native Termux: delegate into the Fedora proot.
if [ -n "${PREFIX:-}" ] && command -v proot-distro >/dev/null 2>&1; then
  if proot-distro list 2>&1 | grep -qiE '(^|[[:space:]*])fedora([[:space:]]|$)' \
     || [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/fedora" ]; then
    echo "[build-apk] no JDK here — delegating to Fedora proot…"
    exec proot-distro login fedora -- bash -lc "cd '$ROOT' && bash termux/build-apk-fedora.sh"
  fi
fi

# 3) nothing usable: explain clearly.
cat >&2 <<EOF
[build-apk] Cannot build: no usable toolchain found here.
Needs a JDK plus tools/ from termux/fetch-toolchain.sh. No JDK here and no Fedora
proot to delegate to.
Fix: bash termux/fetch-toolchain.sh, then either pkg install openjdk-17 (Termux)
or build inside the proot: bash termux/build-apk-fedora.sh
EOF
have_java || echo "  - java (no JVM on PATH and none at $ALT_JAVA)" >&2
have_kit  || echo "  - tools/android.jar + tools/kotlinc (run termux/fetch-toolchain.sh)" >&2
exit 1
