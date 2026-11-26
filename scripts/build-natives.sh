#!/usr/bin/env bash
# Membangun komponen native untuk armeabi-v7a (32-bit) dan arm64-v8a, lalu menaruhnya di
# app/src/main/jniLibs/<abi>/ dengan nama libXXX.so (agar ikut dipaketkan & bisa dieksekusi).
#
#   libhev-socks5-tunnel.so  tun2socks (JNI)          <- wajib
#   libdnstt.so              klien SlowDNS tipe TXT    <- wajib
#   libxray.so               inti V2Ray (Xray)         <- wajib
#   libvaydns.so             klien SlowDNS tipe lain   <- opsional (gagal = peringatan)
#
# Dijalankan di GitHub Actions (ubuntu-latest: NDK + Go tersedia), bisa juga lokal.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$ROOT/.native-build"
API="${NATIVE_API:-23}"        # Android 6.0
XRAY_REF="${XRAY_REF:-}"       # kosong = rilis terbaru
JOBS="$(nproc 2>/dev/null || echo 2)"

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "::error::NDK tidak ditemukan (ANDROID_NDK_LATEST_HOME / ANDROID_NDK_HOME)"; exit 1
fi
command -v go >/dev/null || { echo "::error::Go tidak terpasang"; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "NDK: $NDK  | API: $API  | $(go version)"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT/armeabi-v7a" "$OUT/arm64-v8a"

clone() { # url dir [ref]
  local url="$1" dir="$2" ref="${3:-}"
  if [ -n "$ref" ]; then
    git clone --depth 1 --branch "$ref" "$url" "$dir"
  else
    git clone --depth 1 "$url" "$dir"
  fi
}

latest_tag() { # url pola -> tag terbaru (urut versi)
  git ls-remote --tags --refs --sort=-v:refname "$1" "$2" | sed -n '1{s#.*refs/tags/##;p;}'
}

# --- Go (cgo + NDK, GOOS=android) -> biner PIE yang memakai bionic ---------------------------
go_build() { # modul_dir pkg out_name abi
  local dir="$1" pkg="$2" name="$3" abi="$4" goarch cc extra=()
  case "$abi" in
    arm64-v8a)   goarch=arm64; cc="$TOOLCHAIN/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) goarch=arm;   cc="$TOOLCHAIN/armv7a-linux-androideabi${API}-clang"; extra=(GOARM=7) ;;
  esac
  ( cd "$dir" && env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc" "${extra[@]}" \
      go build -trimpath -buildmode=pie -ldflags "-s -w -buildid= -checklinkname=0" -o "$OUT/$abi/$name" "$pkg" ) || return 1
  echo "  ok $abi/$name ($(du -h "$OUT/$abi/$name" | cut -f1))"
}

# --- 1. tun2socks (hev-socks5-tunnel) --------------------------------------------------------
build_hev() {
  local plat="$1"
  rm -rf "$WORK/hev"; mkdir -p "$WORK/hev"
  git clone --recursive --depth 1 --shallow-submodules https://github.com/heiher/hev-socks5-tunnel "$WORK/hev/jni"

  # Pastikan tabel JNI di sumber cocok dengan kelas Java kita (app/src/main/java/hev/htproxy).
  local jni="$WORK/hev/jni/src/hev-jni.c"
  local bad=0
  for name in TProxyStartService TProxyStopService TProxyIsRunning TProxyGetStats; do
    grep -Fq "\"$name\"" "$jni" || { echo "::error::$name tidak ada di hev-jni.c"; bad=1; }
  done
  grep -Fq '(Ljava/lang/String;I)Z' "$jni" || { echo "::error::signature TProxyStartService berubah"; bad=1; }
  grep -Fq '()[J' "$jni" || { echo "::error::signature TProxyGetStats berubah"; bad=1; }
  [ "$(grep -Fc '"()Z"' "$jni")" -ge 2 ] || { echo "::error::signature Stop/IsRunning berubah"; bad=1; }
  grep -Eq 'define +PKGNAME +hev/htproxy' "$jni" || { echo "::error::PKGNAME bukan hev/htproxy"; bad=1; }
  grep -Eq 'define +CLSNAME +TProxyService' "$jni" || { echo "::error::CLSNAME bukan TProxyService"; bad=1; }
  if [ "$bad" = 1 ]; then
    echo "::error::JNI hev-socks5-tunnel tidak cocok dengan TProxyService.java; sesuaikan kelas Java."
    exit 1
  fi

  ( cd "$WORK/hev" && "$NDK/ndk-build" -j"$JOBS" \
      APP_ABI="armeabi-v7a arm64-v8a" APP_PLATFORM="android-$plat" ) || return 1
  for abi in armeabi-v7a arm64-v8a; do
    cp "$WORK/hev/libs/$abi/libhev-socks5-tunnel.so" "$OUT/$abi/"
  done
}

echo "== tun2socks"
if ! build_hev "$API"; then
  echo "::warning::hev-socks5-tunnel gagal dibuat dengan API $API; mencoba API 24 (Android 6 tidak didukung)"
  build_hev 24
  echo 24 > "$WORK/min-sdk"
fi
[ -f "$WORK/min-sdk" ] && cp "$WORK/min-sdk" "$OUT/min-sdk.txt" || true

# --- 2. dnstt (SlowDNS tipe TXT) ---------------------------------------------------------------
echo "== dnstt"
clone https://www.bamsoftware.com/git/dnstt.git "$WORK/dnstt" 2>/dev/null \
  || clone https://repo.or.cz/dnstt.git "$WORK/dnstt"
for abi in armeabi-v7a arm64-v8a; do go_build "$WORK/dnstt" ./dnstt-client libdnstt.so "$abi"; done

# --- 3. Xray (V2Ray) -----------------------------------------------------------------------------
echo "== xray"
if [ -z "$XRAY_REF" ]; then XRAY_REF="$(latest_tag https://github.com/XTLS/Xray-core 'v*')"; fi
echo "Xray-core: ${XRAY_REF:-HEAD}"
clone https://github.com/XTLS/Xray-core "$WORK/xray" "$XRAY_REF"
for abi in armeabi-v7a arm64-v8a; do go_build "$WORK/xray" ./main libxray.so "$abi"; done

# --- 4. VayDNS (opsional: tipe record selain TXT) -----------------------------------------------
echo "== vaydns (opsional)"
if clone https://github.com/net2share/vaydns "$WORK/vaydns"; then
  ok=1
  for abi in armeabi-v7a arm64-v8a; do
    go_build "$WORK/vaydns" ./vaydns-client libvaydns.so "$abi" || ok=0
  done
  [ "$ok" = 1 ] || echo "::warning::VayDNS gagal dibuild; tipe SlowDNS selain TXT tidak tersedia"
else
  echo "::warning::VayDNS tidak bisa diunduh; tipe SlowDNS selain TXT tidak tersedia"
fi

echo "== hasil"
find "$OUT" -type f -name '*.so' -printf '%P  %s bytes\n' | sort
