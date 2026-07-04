#!/usr/bin/env bash
# Body APK builder — environment-adaptive entrypoint.
#
# The real build is a zero-AAR manual pipeline (aapt2 -> javac -> kotlinc -> d8 ->
# apksigner) needing a JVM + the cached aarch64 kit. Native Termux ships none of that,
# so this script builds directly when the toolchain is reachable (e.g. inside the
# Fedora proot) and otherwise delegates into the proot. Low-level pipeline lives in
# termux/build-apk-fedora.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FEDORA_BUILD="$ROOT/termux/build-apk-fedora.sh"

KIT="${BODY_KIT:-/root/.claude/phone/kitchen-app/apk/tools}"
ALT_JAVA="${BODY_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}/bin/java"

have_java() { command -v java >/dev/null 2>&1 || [ -x "$ALT_JAVA" ]; }
have_kit()  { [ -f "$KIT/android.jar" ]; }

# 1) toolchain reachable here: build directly.
if have_java && have_kit; then
  echo "[build-apk] toolchain reachable (JVM + cached kit) — building directly."
  exec bash "$FEDORA_BUILD" "$@"
fi

# 2) native Termux: delegate into the Fedora proot.
if [ -n "${PREFIX:-}" ] && command -v proot-distro >/dev/null 2>&1; then
  if proot-distro list 2>&1 | grep -qiE '(^|[[:space:]*])fedora([[:space:]]|$)' \
     || [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/fedora" ]; then
    echo "[build-apk] no native Android toolchain in Termux — delegating to Fedora proot…"
    exec proot-distro login fedora -- bash -lc "cd '$ROOT' && bash termux/build-apk-fedora.sh"
  fi
fi

# 3) nothing usable: explain clearly.
cat >&2 <<EOF
[build-apk] Cannot build: no usable Android toolchain found here.
Needs a JVM + the cached aarch64 kit (aapt2/r8/apksigner/android.jar). Native Termux
has none, so this normally delegates into the Fedora proot — not found either.
Fix: pkg install proot-distro && proot-distro install fedora, then re-run;
or inside the proot: bash termux/build-apk-fedora.sh
EOF
have_java || echo "  - java (no JVM on PATH and none at $ALT_JAVA)" >&2
have_kit  || echo "  - cached kit ($KIT/android.jar) — set \$BODY_KIT to override" >&2
exit 1
