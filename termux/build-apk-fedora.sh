#!/usr/bin/env bash
# Build + sign the Body debug APK inside the Fedora proot (aarch64).
# Zero-AAR manual pipeline:
#   aapt2 compile/link (resources + R.java) -> javac R -> kotlinc -> d8 -> inject dex -> apksigner
# No Gradle, no Android Studio. Toolchain comes from termux/fetch-toolchain.sh (run once).
# Low-level builder; use termux/build-apk.sh as the entrypoint.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/out"; rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex}

# --- toolchain -----------------------------------------------------------------------
# Everything below is fetched once by termux/fetch-toolchain.sh (tools/ is gitignored).
# aapt2/d8/apksigner are Termux packages; from inside the Fedora proot they are reachable
# at the same absolute paths. The JDK is whichever one is on PATH (Fedora: java-25-openjdk,
# Termux: openjdk-17), overridable with BODY_JAVA_HOME.
TERMUX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
if [ -n "${BODY_JAVA_HOME:-}" ]; then JH="$BODY_JAVA_HOME"
elif command -v javac >/dev/null 2>&1; then JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else JH="/usr/lib/jvm/java-25-openjdk"; fi
JAVA="$JH/bin/java"; JAVAC="$JH/bin/javac"; KEYTOOL="$JH/bin/keytool"

AAPT2BIN="$TERMUX/bin/aapt2"
aapt2(){ "$AAPT2BIN" "$@"; }
D8JAR="$TERMUX/share/java/d8.jar"
APKSIGNER="$TERMUX/share/java/apksigner.jar"
ANDROID_JAR="$ROOT/tools/android.jar"

KC="$ROOT/tools/kotlinc/lib/kotlin-compiler.jar"
KSTDLIB="$ROOT/tools/kotlinc/lib/kotlin-stdlib.jar"

# Signing key. Generated on first build, never committed (tools/ is gitignored).
# The password is random and stored next to the keystore; keep both if you want
# later builds to install over the earlier ones.
KS="$ROOT/tools/body.keystore"; KSPASS_FILE="$ROOT/tools/body.keystore.pass"

# libs/*.jar are auto-included (empty in M0; nanohttpd etc. added by later increments).
LIBS="$(find "$ROOT/libs" -name '*.jar' 2>/dev/null | tr '\n' ':')"; LIBS="${LIBS%:}"
CP="$ANDROID_JAR:$KSTDLIB${LIBS:+:$LIBS}"

MIN_SDK=26; TARGET_SDK=33
BUILDCONFIG="$APP/kotlin/com/beqa/body/BuildConfig.kt"
VCODE="$(python3 - "$BUILDCONFIG" <<'PY'
import re, sys
m = re.search(r'VERSION_CODE\s*=\s*(\d+)', open(sys.argv[1], encoding='utf-8').read())
if not m: raise SystemExit('missing VERSION_CODE in BuildConfig.kt')
print(m.group(1))
PY
)"
VNAME="$(python3 - "$BUILDCONFIG" <<'PY'
import re, sys
m = re.search(r'VERSION_NAME\s*=\s*"([^"]+)"', open(sys.argv[1], encoding='utf-8').read())
if not m: raise SystemExit('missing VERSION_NAME in BuildConfig.kt')
print(m.group(1))
PY
)"

[ -x "$JAVA" ]        || { echo "ERROR: no JVM at $JAVA (set BODY_JAVA_HOME)"; exit 1; }
for f in "$AAPT2BIN" "$D8JAR" "$APKSIGNER" "$ANDROID_JAR" "$KC" "$KSTDLIB"; do
  [ -e "$f" ] || { echo "ERROR: missing $f  ->  run termux/fetch-toolchain.sh first"; exit 1; }
done

echo "[1/7] aapt2: compile + link resources -> base.apk + R.java"
aapt2 compile --dir "$APP/res" -o "$OUT/compiled.zip"
aapt2 link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VCODE --version-name $VNAME \
  "$OUT/compiled.zip"

echo "[2/7] javac generated R.java"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$CP" \
  -d "$OUT/classes" $(find "$OUT/gen" -name R.java)

echo "[3/7] kotlinc app sources"
"$JAVA" -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-reflect -nowarn \
  -classpath "$CP:$OUT/classes" \
  -d "$OUT/classes" $(find "$APP/kotlin" -name '*.kt')

echo "[4/7] d8 -> classes.dex (app + kotlin-stdlib + libs)"
"$JAVA" -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
  --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') "$KSTDLIB" $(find "$ROOT/libs" -name '*.jar' 2>/dev/null)
ls "$OUT/dex/classes.dex" >/dev/null

echo "[5/7] inject classes.dex into base.apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import zipfile, sys
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("   dex added")
PY

echo "[6/7] keystore + sign"
if [ ! -f "$KS" ]; then
  [ -f "$KSPASS_FILE" ] || head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$KSPASS_FILE"
  chmod 600 "$KSPASS_FILE"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias body -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$(cat "$KSPASS_FILE")" -keypass "$(cat "$KSPASS_FILE")" \
    -dname "CN=Body, O=Beqa" >/dev/null 2>&1
  echo "   new signing key generated at $KS"
fi
KSPASS="$(cat "$KSPASS_FILE")"
"$JAVA" -jar "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --min-sdk-version $MIN_SDK --v2-signing-enabled true --v1-signing-enabled true \
  --out "$ROOT/app-debug.apk" "$OUT/unsigned.apk"

echo "[7/7] verify"
"$JAVA" -jar "$APKSIGNER" verify --print-certs "$ROOT/app-debug.apk" | head -2
echo "BUILT: $ROOT/app-debug.apk ($(du -h "$ROOT/app-debug.apk" | cut -f1))  v$VNAME ($VCODE)"
