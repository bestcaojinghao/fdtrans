#!/data/user/0/app.kxxy/files/engine/bin/bash
# Build F-Droid realtime translator LSPosed module (no Gradle).
set -e
ROOT="/data/user/0/app.kxxy/files/kxxy-home/开发一个新app"
BIN="$ROOT/../engine/bin"
BIN="/data/user/0/app.kxxy/files/engine/bin"
JAR="$ROOT/fake-sdk/platforms/android-35/android.jar"
JAVAC="/data/user/0/app.kxxy/files/engine/lib/jvm/java-21-openjdk/bin/javac"
PY="/data/user/0/app.kxxy/files/kxxy-home/tools/bin/python3"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-FDTrans-stage1.apk}"

cd "$HERE"
rm -rf classes dex base.apk aligned.apk "$OUT"
mkdir -p classes dex

echo "== javac =="
find src stubs -name '*.java' > /tmp/srcs.txt 2>/dev/null || find src stubs -name '*.java' > sources.txt
if [ -f /tmp/srcs.txt ]; then S=$(cat /tmp/srcs.txt); else S=$(cat sources.txt); fi
$JAVAC -source 17 -target 17 -encoding UTF-8 -nowarn -cp "$JAR" -d classes $S

echo "== d8 (Xposed API stubs excluded) =="
find classes -name '*.class' | grep -v '/de/robv/' > classlist.txt
wc -l < classlist.txt
$BIN/d8 --lib "$JAR" --min-api 26 --output dex $(cat classlist.txt)

echo "== aapt2 link =="
$BIN/aapt2 link -o base.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version 35

echo "== add classes.dex =="
$PY - <<'EOF'
import zipfile, shutil, os
shutil.copy('base.apk','withdex.apk')
with zipfile.ZipFile('withdex.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('dex/classes.dex','classes.dex')
print('dex added', os.path.getsize('withdex.apk'))
EOF

echo "== zipalign =="
$BIN/zipalign -f 4 withdex.apk aligned.apk

echo "== sign =="
$BIN/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --out "$OUT" aligned.apk
$BIN/apksigner verify --print-certs "$OUT" | head -5
ls -la "$OUT"
echo "APK=$HERE/$OUT"
