#!/bin/bash
# Builds the native libraries the packet tunnel links, into ios/build/native/<sdk>/:
#   libhev.a       hev-socks5-tunnel (vendored in android/app/src/main/jni/hev-socks5-tunnel): TUN → SOCKS5
#   libresproxy.a  native/wgbridge/cmd/resproxy-helper as a C archive: relay, gate, upstream, WireGuard (Go)
# and the headers in ios/build/native/include/. Xcode runs this before compiling the Tunnel target; it rebuilds only
# when sources changed. Needs Go (version per native/wgbridge/go.mod) on PATH or in GO=…, and Xcode.
#
#   ios/scripts/build-native.sh [iphoneos|iphonesimulator|all]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/ios/build/native"
HEV_SRC="$ROOT/android/app/src/main/jni/hev-socks5-tunnel"
GO_SRC="$ROOT/native/wgbridge"
MIN_IOS=16.0
GO="${GO:-$(command -v go || true)}"
for cand in "$HOME/sdk/go/bin/go" /usr/local/go/bin/go /opt/homebrew/bin/go /usr/local/bin/go; do
  [ -n "$GO" ] && break
  [ -x "$cand" ] && GO="$cand"
done
if [ "${RP_SKIP_NATIVE:-}" = 1 ]; then
  echo "RP_SKIP_NATIVE=1: not building native libs (type-check only; linking the tunnel will fail)"
  exit 0
fi
if [ -z "$GO" ]; then
  echo "error: Go not found. Install Go $(sed -n 's/^go //p' "$GO_SRC/go.mod") (https://go.dev/dl) or set GO=/path/to/go" >&2
  exit 1
fi

which_sdks="${1:-${PLATFORM_NAME:-all}}"
case "$which_sdks" in
  all) sdks="iphoneos iphonesimulator" ;;
  *) sdks="$which_sdks" ;;
esac

stamp_of() { # newest mtime under the sources this lib depends on
  find "$@" -type f \( -name '*.c' -o -name '*.h' -o -name '*.go' -o -name 'go.mod' -o -name 'go.sum' -o -name 'Makefile' -o -name '*.mk' \) \
    -newer "$OUT/.stamp-$sdk" 2>/dev/null | head -1
}

mkdir -p "$OUT/include"
cp "$HEV_SRC/include/hev-socks5-tunnel.h" "$OUT/include/"

for sdk in $sdks; do
  if [ -f "$OUT/$sdk/libhev.a" ] && [ -f "$OUT/$sdk/libresproxy.a" ] && [ -f "$OUT/.stamp-$sdk" ] \
     && [ -z "$(stamp_of "$HEV_SRC" "$GO_SRC")" ] && [ -z "$(find "$0" -newer "$OUT/.stamp-$sdk")" ]; then
    echo "native libs for $sdk are up to date"
    continue
  fi
  if [ "$sdk" = iphoneos ]; then
    archs="arm64"
  else
    archs="$(uname -m)"   # simulator on this Mac's architecture
    [ "$archs" = x86_64 ] || archs=arm64
  fi
  mkdir -p "$OUT/$sdk"
  sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
  clang="$(xcrun --sdk "$sdk" -f clang)"
  hev_libs=(); go_libs=()
  for arch in $archs; do
    if [ "$sdk" = iphoneos ]; then target="$arch-apple-ios$MIN_IOS"; else target="$arch-apple-ios$MIN_IOS-simulator"; fi
    work="$ROOT/ios/build/work/$sdk-$arch"

    echo "== hev-socks5-tunnel ($sdk $arch)"
    rm -rf "$work/hev"; mkdir -p "$work"
    cp -R "$HEV_SRC" "$work/hev"
    (cd "$work/hev" && make -s PP="$clang" CC="$clang" \
        CFLAGS="-target $target -isysroot $sysroot -O2" \
        LFLAGS="-target $target -isysroot $sysroot" static >/dev/null)
    libtool -static -no_warning_for_no_symbols -o "$work/libhev.a" \
      "$work/hev/bin/libhev-socks5-tunnel.a" \
      "$work/hev/third-part/lwip/bin/liblwip.a" \
      "$work/hev/third-part/yaml/bin/libyaml.a" \
      "$work/hev/third-part/hev-task-system/bin/libhev-task-system.a"
    hev_libs+=("$work/libhev.a")

    echo "== resproxy bridge (Go, $sdk $arch)"
    goarch=$arch; [ "$arch" = x86_64 ] && goarch=amd64
    (cd "$GO_SRC" && CGO_ENABLED=1 GOOS=ios GOARCH=$goarch \
        CC="$clang -target $target -isysroot $sysroot" CGO_CFLAGS="-target $target -isysroot $sysroot -O2" \
        CGO_LDFLAGS="-target $target -isysroot $sysroot" \
        "$GO" build -buildmode=c-archive -trimpath \
          -ldflags "-s -w -X main.Version=ios-$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo dev)" \
          -o "$work/libresproxy.a" ./cmd/resproxy-helper)
    go_libs+=("$work/libresproxy.a")
    cp "$work/libresproxy.h" "$OUT/include/libresproxy.h"
  done
  lipo -create "${hev_libs[@]}" -output "$OUT/$sdk/libhev.a"
  lipo -create "${go_libs[@]}" -output "$OUT/$sdk/libresproxy.a"
  touch "$OUT/.stamp-$sdk"
done
echo "native libs in $OUT"
