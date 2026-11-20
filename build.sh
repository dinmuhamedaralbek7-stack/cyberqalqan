#!/usr/bin/env bash
# CyberQalqan: веб-сайт + Android APK-ны Vercel серверінде жинау
set -uo pipefail
ROOT="$(pwd)"; OUT="$ROOT/public"; T="$ROOT/.tools"
rm -rf "$OUT"; mkdir -p "$OUT" "$T"; cp -r web/. "$OUT/"
echo '{"ok":false}' > "$OUT/apk.json"
log(){ echo "[CyberQalqan] $*"; }

build_apk() {
  set -e
  cd "$T"
  log "1/5 JDK 17 жүктелуде"
  curl -fsSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar -xzf jdk.tgz -C jdk --strip-components=1 && rm jdk.tgz
  export JAVA_HOME="$T/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
  java -version 2>&1 | head -1
  java -Djava.awt.headless=true "$ROOT/tools/Icon.java" "$OUT" && log "Иконкалар дайын" || log "Иконкалар жасалмады"

  log "2/5 Android SDK жүктелуде"
  mkdir -p sdk/cmdline-tools && cd sdk/cmdline-tools
  curl -fsSL -o ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  jar xf ct.zip && rm ct.zip && mv cmdline-tools latest && chmod +x latest/bin/*
  cd "$T"
  export ANDROID_HOME="$T/sdk" ANDROID_SDK_ROOT="$T/sdk"
  log "3/5 SDK пакеттері орнатылуда"
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" > /dev/null

  log "4/5 Gradle жүктелуде"
  curl -fsSL -o g.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
  jar xf g.zip && rm g.zip && chmod +x gradle-8.9/bin/gradle

  log "5/5 APK жиналуда"
  cd "$ROOT/android"
  mkdir -p app/src/main/assets && cp "$ROOT/web/index.html" app/src/main/assets/report.html
  # Қосымша сенім деңгейін осы сайттың /api/trust арқылы сұрайды
  API_HOST="${VERCEL_PROJECT_PRODUCTION_URL:-${VERCEL_URL:-}}"
  printf "%s" "$API_HOST" > app/src/main/assets/api_base.txt
  log "API хосты: ${API_HOST:-жоқ}"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  "$T/gradle-8.9/bin/gradle" assembleRelease --no-daemon --console=plain --warning-mode=none -Dorg.gradle.jvmargs=-Xmx3g
  # Екі нұсқа: full (SMS-ті автоматты оқиды) және lite (оқымайды — Play Protect бұғаттамайды)
  cp app/build/outputs/apk/full/release/app-full-release.apk "$OUT/CyberQalqan.apk"
  cp app/build/outputs/apk/lite/release/app-lite-release.apk "$OUT/CyberQalqan-Lite.apk"
  SIZE=$(stat -c %s "$OUT/CyberQalqan.apk");      SHA=$(sha256sum "$OUT/CyberQalqan.apk" | cut -d" " -f1)
  LSIZE=$(stat -c %s "$OUT/CyberQalqan-Lite.apk"); LSHA=$(sha256sum "$OUT/CyberQalqan-Lite.apk" | cut -d" " -f1)
  echo "{\"ok\":true,\"version\":\"2.1\",\"size\":$SIZE,\"sha256\":\"$SHA\",\"lite\":{\"ok\":true,\"size\":$LSIZE,\"sha256\":\"$LSHA\"},\"built\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > "$OUT/apk.json"
  log "✅ APK дайын: full $SIZE байт, lite $LSIZE байт"
}

( build_apk ) || log "❌ APK жиналмады — сайт APK-сыз жарияланады (жоғарыдағы логты қараңыз)"
cd "$ROOT"; ls -la "$OUT"
exit 0
