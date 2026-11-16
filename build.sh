#!/usr/bin/env bash
# Raphael — Codespaces için tek komutluk kurulum + APK derleme
# Kullanım:  bash build.sh        (çıktı: Raphael.apk, tam log: build.log)
cd "$(dirname "$0")" || exit 1
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GV=8.4
LOG="$PWD/build.log"
: > "$LOG"

say() { echo ">> $*" | tee -a "$LOG"; }
fail() { echo "BUILD FAILED: $*"; echo "--- son hata satırları ---"; grep -E "error:|FAILED|What went wrong|Could not|not found" -A3 "$LOG" | tail -40; exit 1; }

# 1) JDK 17 (AGP 8.1 için en uyumlu sürüm)
find_jdk17() { for d in /usr/lib/jvm/*17* /usr/local/sdkman/candidates/java/17* "$HOME"/.sdkman/candidates/java/17*; do [ -x "$d/bin/javac" ] && { echo "$d"; return; }; done; }
JH="$(find_jdk17)"
if [ -z "$JH" ]; then
  say "JDK 17 kuruluyor (1-2 dk)"
  (sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk-headless unzip curl) >> "$LOG" 2>&1 || fail "JDK 17 kurulamadi"
  JH="$(find_jdk17)"
fi
[ -n "$JH" ] || fail "JDK 17 bulunamadi"
export JAVA_HOME="$JH"; export PATH="$JAVA_HOME/bin:$PATH"
say "Java: $(java -version 2>&1 | head -1)"

# 2) Android SDK komut satırı araçları
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  say "Android komut satırı araçları indiriliyor"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip >> "$LOG" 2>&1 || fail "cmdline-tools indirilemedi"
  unzip -q -o /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" && rm -rf "$ANDROID_HOME/cmdline-tools/latest" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" || fail "cmdline-tools acilamadi"
fi
if [ ! -d "$ANDROID_HOME/platforms/android-34" ] || [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  say "SDK paketleri kuruluyor (platform 34, build-tools 34.0.0)"
  yes | "$SDKM" --licenses >> "$LOG" 2>&1
  "$SDKM" --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" >> "$LOG" 2>&1 || fail "SDK paketleri kurulamadi"
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3) Gradle
GBIN="$HOME/gradle-$GV/bin/gradle"
if [ ! -x "$GBIN" ]; then
  say "Gradle $GV indiriliyor"
  curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$GV-bin.zip" >> "$LOG" 2>&1 || fail "Gradle indirilemedi"
  unzip -q -o /tmp/gradle.zip -d "$HOME" || fail "Gradle acilamadi"
fi
[ -f gradlew ] || "$GBIN" wrapper --gradle-version "$GV" >> "$LOG" 2>&1

# 4) Derle
say "APK derleniyor (ilk seferde 3-6 dk, sonrakiler çok daha kısa)"
"$GBIN" --no-daemon -q assembleDebug >> "$LOG" 2>&1 || fail "derleme hatasi"
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || fail "APK uretilmedi"
cp "$APK" Raphael.apk
echo "BUILD OK: $(pwd)/Raphael.apk ($(du -h Raphael.apk | cut -f1))"
echo "Bir sonraki derleme: ./gradlew --no-daemon assembleDebug  (veya yine bash build.sh)"
