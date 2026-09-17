#!/usr/bin/env bash
# One-time toolchain fetch for building the Body APK on the phone, with no Gradle,
# no Android Studio and no Android SDK manager.
#
# Where each piece comes from:
#   aapt2, d8, apksigner   Termux packages (pkg install aapt2 d8 apksigner). These are
#                          installed in Termux and are reachable from inside the Fedora
#                          proot at the same /data/data/com.termux/... paths.
#   kotlinc                JetBrains' plain zip release (no IDE), unpacked to tools/kotlinc
#   android.jar            Google's platform-33 package, only android.jar is kept
#   JDK                    Fedora: dnf install java-25-openjdk-devel   Termux: pkg install openjdk-17
#
# tools/ is gitignored. Run this once from either Termux or the proot;
# ~147 MB. Re-running is a no-op for anything already present.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"; mkdir -p "$TOOLS"
TERMUX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"

KOTLIN_VER="${KOTLIN_VER:-2.4.0}"
KOTLIN_URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VER}/kotlin-compiler-${KOTLIN_VER}.zip"
PLATFORM_URL="https://dl.google.com/android/repository/platform-33_r02.zip"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1"; exit 1; }; }
need curl; need unzip

# 1) Termux-side native tools
missing=""
for p in aapt2 d8 apksigner; do
  case "$p" in
    aapt2)     [ -x "$TERMUX/bin/aapt2" ]              || missing="$missing $p" ;;
    d8)        [ -f "$TERMUX/share/java/d8.jar" ]       || missing="$missing $p" ;;
    apksigner) [ -f "$TERMUX/share/java/apksigner.jar" ]|| missing="$missing $p" ;;
  esac
done
if [ -n "$missing" ]; then
  if [ -n "${PREFIX:-}" ] && command -v pkg >/dev/null 2>&1; then
    echo "[fetch] installing Termux packages:$missing"
    pkg install -y $missing
  else
    echo "[fetch] these Termux packages are missing:$missing"
    echo "        run in the Termux app:  pkg install$missing"
    exit 1
  fi
fi

# 2) Kotlin compiler
if [ ! -f "$TOOLS/kotlinc/lib/kotlin-compiler.jar" ]; then
  echo "[fetch] kotlinc $KOTLIN_VER"
  curl -L --fail --progress-bar -o "$TOOLS/kotlinc.zip" "$KOTLIN_URL"
  rm -rf "$TOOLS/kotlinc"
  unzip -q "$TOOLS/kotlinc.zip" -d "$TOOLS"      # zip already contains a kotlinc/ dir
  rm -f "$TOOLS/kotlinc.zip"
fi

# 3) android.jar (API 33)
if [ ! -f "$TOOLS/android.jar" ]; then
  echo "[fetch] android.jar (platform-33)"
  curl -L --fail --progress-bar -o "$TOOLS/platform-33.zip" "$PLATFORM_URL"
  unzip -q -j "$TOOLS/platform-33.zip" 'android-13/android.jar' -d "$TOOLS"
  rm -f "$TOOLS/platform-33.zip"
fi

echo "[fetch] done:"
echo "  aapt2      $TERMUX/bin/aapt2"
echo "  d8         $TERMUX/share/java/d8.jar"
echo "  apksigner  $TERMUX/share/java/apksigner.jar"
echo "  kotlinc    $TOOLS/kotlinc  ($(cat "$TOOLS/kotlinc/build.txt" 2>/dev/null || echo '?'))"
echo "  android    $TOOLS/android.jar"
