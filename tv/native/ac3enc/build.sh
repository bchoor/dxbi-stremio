#!/usr/bin/env bash
# Builds libac3enc.so (FFmpeg ac3 encoder + JNI glue) for the ARM TVs into app/src/main/jniLibs.
# Needs the Android NDK: ANDROID_NDK_LATEST_HOME (GitHub runners) or ANDROID_NDK_HOME.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../../app"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
[ -d "$NDK" ] || { echo "no NDK"; exit 1; }
VER=6.1.2
API=24
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK="${AC3_WORK:-$HERE/.work}"
mkdir -p "$WORK"
cd "$WORK"
[ -d "ffmpeg-$VER" ] || { curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$VER.tar.xz" -o ff.tar.xz; tar xf ff.tar.xz; }
for ABI in armeabi-v7a arm64-v8a; do
  case $ABI in
    armeabi-v7a) ARCH=arm; CPU=armv7-a; TRIPLE=armv7a-linux-androideabi ;;
    arm64-v8a) ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android ;;
  esac
  CC="$TC/bin/${TRIPLE}${API}-clang"
  OUT="$WORK/out-$ABI"
  if [ ! -f "$OUT/lib/libavcodec.a" ]; then
    rm -rf "build-$ABI"; cp -r "ffmpeg-$VER" "build-$ABI"
    (cd "build-$ABI" && ./configure --prefix="$OUT" --target-os=android --arch=$ARCH --cpu=$CPU \
      --enable-cross-compile --cc="$CC" --cxx="$CC++" --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" \
      --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" --sysroot="$TC/sysroot" \
      --enable-pic --enable-static --disable-shared --disable-autodetect --disable-asm \
      --disable-everything --disable-programs --disable-doc --disable-network \
      --disable-avdevice --disable-avformat --disable-swscale --disable-swresample --disable-postproc --disable-avfilter \
      --enable-encoder=ac3 \
      && make -j"$(nproc)" && make install) > "build-$ABI.log" 2>&1 || { tail -40 "build-$ABI.log"; exit 1; }
  fi
  mkdir -p "$APP/src/main/jniLibs/$ABI"
  "$CC" -shared -fPIC -O2 -I"$OUT/include" "$HERE/ac3enc_jni.c" -o "$APP/src/main/jniLibs/$ABI/libac3enc.so" \
    "$OUT/lib/libavcodec.a" "$OUT/lib/libavutil.a" -lm -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
  "$TC/bin/llvm-strip" --strip-unneeded "$APP/src/main/jniLibs/$ABI/libac3enc.so"
  ls -l "$APP/src/main/jniLibs/$ABI/libac3enc.so"
done
