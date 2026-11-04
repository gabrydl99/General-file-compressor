#!/usr/bin/env bash
# Builds a self-contained `ffmpeg` executable for one Android ABI, with
# x264 (H.264), LAME (MP3), Opus, Vorbis and, when it builds, libvpx (VP9/WebM).
# The result is saved as <out>/libffmpeg.so so Android installs it in the
# app's native library folder, the only app folder it may run programs from.
#
# usage: build_ffmpeg.sh <abi> <out_dir>      abi: arm64-v8a | armeabi-v7a | x86_64
set -euo pipefail

ABI=$1
OUT=$(realpath -m "$2")
API=24
FFMPEG_TAG=n7.1.1
X264_BRANCH=stable
LAME_VER=3.100
OGG_VER=1.3.5
VORBIS_VER=1.3.7
OPUS_VER=1.5.2
VPX_TAG=v1.15.0

NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}
[ -d "$NDK" ] || { echo "Android NDK not found"; exit 1; }
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SYSROOT=$TC/sysroot
JOBS=$(nproc)
echo "NDK: $NDK  ABI: $ABI  jobs: $JOBS"

EXTRA_CFLAGS="-O3 -fPIC"
PAGE_LDFLAGS="-Wl,-z,max-page-size=16384"
case $ABI in
  arm64-v8a)
    TRIPLE=aarch64-linux-android; HOST=aarch64-linux-android
    FF_ARCH=aarch64; FF_CPU=armv8-a; FF_EXTRA="--enable-neon"
    VPX_TARGET=arm64-android-gcc ;;
  armeabi-v7a)
    TRIPLE=armv7a-linux-androideabi; HOST=arm-linux-androideabi
    FF_ARCH=arm; FF_CPU=armv7-a; FF_EXTRA="--enable-neon --enable-thumb"
    EXTRA_CFLAGS="$EXTRA_CFLAGS -march=armv7-a -mfpu=neon -mfloat-abi=softfp"
    PAGE_LDFLAGS=""
    VPX_TARGET=armv7-android-gcc ;;
  x86_64)
    TRIPLE=x86_64-linux-android; HOST=x86_64-linux-android
    FF_ARCH=x86_64; FF_CPU=x86-64; FF_EXTRA="--x86asmexe=nasm"
    VPX_TARGET=x86_64-android-gcc ;;
  *) echo "unknown ABI $ABI"; exit 1 ;;
esac

export CC=$TC/bin/${TRIPLE}${API}-clang
export CXX=$TC/bin/${TRIPLE}${API}-clang++
export AR=$TC/bin/llvm-ar
export RANLIB=$TC/bin/llvm-ranlib
export STRIP=$TC/bin/llvm-strip
export NM=$TC/bin/llvm-nm
export LD=$CC

WORK=$(realpath -m "${WORK_DIR:-$PWD/ffbuild}")/$ABI
PREFIX=$WORK/prefix
SRC=$WORK/src
mkdir -p "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$SRC" "$OUT"
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
export CFLAGS="$EXTRA_CFLAGS -I$PREFIX/include"
export CPPFLAGS="-I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib"

# Bionic has pthread and rt inside libc; empty archives keep "-lpthread -lrt" links happy.
printf 'int gfc_empty_stub;\n' > "$WORK/empty.c"
$CC -c "$WORK/empty.c" -o "$WORK/empty.o"
for l in pthread rt; do $AR rcs "$PREFIX/lib/lib$l.a" "$WORK/empty.o"; done

fetch() {  # fetch <dest.tar.gz> <url>...
  local dest=$1; shift
  for u in "$@"; do
    if curl -fsSL --retry 3 --retry-delay 3 -o "$dest" "$u"; then return 0; fi
    echo "download failed: $u"
  done
  return 1
}

newer_config() {  # refresh config.sub/config.guess so old autotools know Android
  for f in config.sub config.guess; do
    for d in /usr/share/misc /usr/share/automake-*; do
      [ -f "$d/$f" ] && cp "$d/$f" "./$f" && break
    done
  done
}

autotools_build() {  # autotools_build <dir> <configure args...>
  local dir=$1; shift
  ( cd "$dir" && newer_config && ./configure --host=$HOST --prefix="$PREFIX" \
      --disable-shared --enable-static --with-pic "$@" && make -j"$JOBS" && make install )
}

# ---------------------------------------------------------------- x264
cd "$SRC"
if [ ! -f "$PREFIX/lib/libx264.a" ]; then
  rm -rf x264
  git clone -q --depth 1 -b $X264_BRANCH https://code.videolan.org/videolan/x264.git x264 \
    || git clone -q --depth 1 https://github.com/mirror/x264.git x264
  cd x264
  X264_ARGS=(--prefix="$PREFIX" --host=$HOST --sysroot="$SYSROOT" --enable-static --enable-pic --disable-cli --disable-opencl)
  if ! ( ./configure "${X264_ARGS[@]}" && make -j"$JOBS" && make install ); then
    echo "x264 with assembly failed, building it without"
    make distclean >/dev/null 2>&1 || true
    ./configure "${X264_ARGS[@]}" --disable-asm && make -j"$JOBS" && make install
  fi
fi

# ---------------------------------------------------------------- LAME (MP3)
cd "$SRC"
if [ ! -f "$PREFIX/lib/libmp3lame.a" ]; then
  fetch lame.tar.gz \
    "https://deb.debian.org/debian/pool/main/l/lame/lame_${LAME_VER}.orig.tar.gz" \
    "https://downloads.sourceforge.net/project/lame/lame/${LAME_VER}/lame-${LAME_VER}.tar.gz"
  rm -rf lame-$LAME_VER && tar xzf lame.tar.gz
  autotools_build lame-$LAME_VER --disable-frontend --disable-gtktest --disable-decoder
fi

# ---------------------------------------------------------------- Ogg + Vorbis
cd "$SRC"
if [ ! -f "$PREFIX/lib/libvorbisenc.a" ]; then
  fetch ogg.tar.gz \
    "https://downloads.xiph.org/releases/ogg/libogg-${OGG_VER}.tar.gz" \
    "https://deb.debian.org/debian/pool/main/libo/libogg/libogg_${OGG_VER}.orig.tar.gz"
  rm -rf libogg-$OGG_VER && tar xzf ogg.tar.gz
  autotools_build libogg-$OGG_VER
  fetch vorbis.tar.gz \
    "https://downloads.xiph.org/releases/vorbis/libvorbis-${VORBIS_VER}.tar.gz" \
    "https://deb.debian.org/debian/pool/main/libv/libvorbis/libvorbis_${VORBIS_VER}.orig.tar.gz"
  rm -rf libvorbis-$VORBIS_VER && tar xzf vorbis.tar.gz
  autotools_build libvorbis-$VORBIS_VER --disable-docs --disable-examples --disable-oggtest --with-ogg="$PREFIX"
fi

# ---------------------------------------------------------------- Opus
cd "$SRC"
if [ ! -f "$PREFIX/lib/libopus.a" ]; then
  fetch opus.tar.gz \
    "https://downloads.xiph.org/releases/opus/opus-${OPUS_VER}.tar.gz" \
    "https://github.com/xiph/opus/releases/download/v${OPUS_VER}/opus-${OPUS_VER}.tar.gz"
  rm -rf opus-$OPUS_VER && tar xzf opus.tar.gz
  OPUS_EXTRA=""
  [ "$ABI" = armeabi-v7a ] && OPUS_EXTRA="--disable-asm"
  autotools_build opus-$OPUS_VER --disable-doc --disable-extra-programs $OPUS_EXTRA
fi

# ---------------------------------------------------------------- libvpx (optional: WebM/VP9)
cd "$SRC"
VPX_OK=no
if [ -f "$PREFIX/lib/libvpx.a" ]; then
  VPX_OK=yes
else
  rm -rf libvpx
  if git clone -q --depth 1 -b $VPX_TAG https://github.com/webmproject/libvpx.git libvpx; then
    cd libvpx
    if [ "$ABI" = x86_64 ]; then VPX_ENV=(env -u AS); VPX_AS="--as=nasm"; else VPX_ENV=(env AS="$CC" ASFLAGS="-c"); VPX_AS=""; fi
    if "${VPX_ENV[@]}" CROSS=$TC/bin/llvm- \
       ./configure --prefix="$PREFIX" --target=$VPX_TARGET $VPX_AS \
         --disable-examples --disable-tools --disable-docs --disable-unit-tests \
         --disable-debug --enable-pic --enable-vp8 --enable-vp9 --disable-shared --enable-static \
         --extra-cflags="$EXTRA_CFLAGS" \
       && make -j"$JOBS" && make install; then
      VPX_OK=yes
    else
      echo "::warning::libvpx did not build for $ABI, WebM output will be off"
    fi
  fi
fi

# ---------------------------------------------------------------- FFmpeg
cd "$SRC"
rm -rf FFmpeg
git clone -q --depth 1 -b $FFMPEG_TAG https://github.com/FFmpeg/FFmpeg.git FFmpeg
cd FFmpeg
VPX_FLAG=""
[ $VPX_OK = yes ] && VPX_FLAG="--enable-libvpx"
./configure \
  --prefix="$PREFIX" --target-os=android --arch=$FF_ARCH --cpu=$FF_CPU --enable-cross-compile \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
  --sysroot="$SYSROOT" --pkg-config=pkg-config --pkg-config-flags=--static \
  --extra-cflags="$EXTRA_CFLAGS -I$PREFIX/include" \
  --extra-ldflags="-L$PREFIX/lib $PAGE_LDFLAGS" --extra-libs="-lm" \
  --enable-gpl --enable-version3 \
  --enable-libx264 --enable-libmp3lame --enable-libopus --enable-libvorbis $VPX_FLAG --enable-zlib \
  --disable-autodetect --disable-shared --enable-static --enable-pic \
  --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
  --disable-ffplay --disable-ffprobe --disable-debug --disable-network \
  --disable-indevs --disable-outdevs \
  $FF_EXTRA \
  || { tail -60 ffbuild/config.log; exit 1; }
make -j"$JOBS"
$STRIP --strip-all ffmpeg -o "$OUT/libffmpeg.so"
chmod 755 "$OUT/libffmpeg.so"
{
  echo "abi=$ABI"; echo "ffmpeg=$FFMPEG_TAG"; echo "vpx=$VPX_OK"
  echo "size=$(stat -c %s "$OUT/libffmpeg.so")"
} > "$OUT/build-info.txt"
cat "$OUT/build-info.txt"
file "$OUT/libffmpeg.so" || true
