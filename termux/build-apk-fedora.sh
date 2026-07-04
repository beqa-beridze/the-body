#!/usr/bin/env bash
# Build + sign the Body debug APK inside the Fedora proot (aarch64).
# Zero-AAR manual pipeline:
#   aapt2 compile/link (resources + R.java) -> javac R -> kotlinc -> d8 -> inject dex -> apksigner
# No Gradle, no Android SDK, no network. Low-level builder; use termux/build-apk.sh as entrypoint.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/out"; rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex}

# --- R2 anti-sprawl gate: no self-referential scaffolding filenames -----------------
if find "$APP/kotlin" "$ROOT/termux" -type f 2>/dev/null \
     | grep -Eiq '/(doctor|scout|proof|replay|mirror|tick)[a-z0-9_-]*\.[a-z]+$'; then
  echo "R2 VIOLATION: banned filename root (doctor|scout|proof|replay|mirror|tick):" >&2
  find "$APP/kotlin" "$ROOT/termux" -type f | grep -Ei '/(doctor|scout|proof|replay|mirror|tick)[a-z0-9_-]*\.[a-z]+$' >&2
  exit 1
fi

# --- toolchain (overridable; defaults are the proven Fedora-proot paths) -------------
JH="${BODY_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
JAVA="$JH/bin/java"; JAVAC="$JH/bin/javac"; KEYTOOL="$JH/bin/keytool"

KIT="${BODY_KIT:-/root/.claude/phone/kitchen-app/apk/tools}"
AAPT2BIN="$KIT/x/aapt2_13.0.0.6-23_aarch64/data/data/com.termux/files/usr/bin/aapt2"
LIBPOOL="$KIT/libpool"
aapt2(){ LD_LIBRARY_PATH="$LIBPOOL" "$AAPT2BIN" "$@"; }
D8JAR="$KIT/r8-new.jar"
APKSIGNER="$KIT/x/apksigner_33.0.1-1_all/data/data/com.termux/files/usr/share/java/apksigner.jar"
ANDROID_JAR="$KIT/android.jar"

KC="$ROOT/tools/kotlinc/lib/kotlin-compiler.jar"
KSTDLIB="$ROOT/tools/kotlinc/lib/kotlin-stdlib.jar"
KS="$ROOT/tools/body.keystore"

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
[ -f "$ANDROID_JAR" ] || { echo "ERROR: cached kit not found at $KIT (set BODY_KIT)"; exit 1; }
[ -f "$KC" ]          || { echo "ERROR: kotlin compiler missing at $KC"; exit 1; }

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
[ -f "$KS" ] || "$KEYTOOL" -genkeypair -keystore "$KS" -alias body -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass body1234 -keypass body1234 \
  -dname "CN=Body, O=Beqa, C=IL" >/dev/null 2>&1
"$JAVA" -jar "$APKSIGNER" sign --ks "$KS" --ks-pass pass:body1234 --key-pass pass:body1234 \
  --min-sdk-version $MIN_SDK --v2-signing-enabled true --v1-signing-enabled true \
  --out "$ROOT/app-debug.apk" "$OUT/unsigned.apk"

echo "[7/7] verify"
"$JAVA" -jar "$APKSIGNER" verify --print-certs "$ROOT/app-debug.apk" | head -2
echo "BUILT: $ROOT/app-debug.apk ($(du -h "$ROOT/app-debug.apk" | cut -f1))  v$VNAME ($VCODE)"
