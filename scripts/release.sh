#!/usr/bin/env bash
# Push main to GitHub and publish a release with every build. Run from Git Bash on the Windows host:
#   scripts/release.sh
# Tests and builds on Windows (Android APK, Chrome extension zip, helper binaries for 4 platforms), pushes
# through WSL (where the SSH key lives), creates the release with the Windows `gh` CLI.
# Tag = v<version>, version = 0.1.<commit count> (Android versionName and the extension version alike).
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "Not on main." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes. Commit first." >&2; exit 1; }

version="0.1.$(git rev-list --count HEAD)"
tag="v$version"
sha="$(git rev-parse HEAD)"
out="$root/dist/release-$tag"
rm -rf "$out" && mkdir -p "$out"

go_dir="$(sed -n 's/^go\.dir=//p' android/local.properties 2>/dev/null || true)"
[ -n "$go_dir" ] && export PATH="$(cygpath -u "$go_dir")/bin:$PATH"
export GOPATH="${GOPATH:-/d/android/gopath}" GOMODCACHE="${GOMODCACHE:-/d/android/gopath/pkg/mod}"

# Android
(cd android && ./gradlew.bat --console=plain testDebugUnitTest assembleDebug)
cp android/app/build/outputs/apk/debug/app-debug.apk "$out/ResProxy-$tag.apk"

# Chrome extension
(cd chrome && npm ci --no-audit --no-fund && npx tsc --noEmit && npm test && node build.mjs --zip)
cp "chrome/dist/ResProxy-chrome-$tag.zip" "$out/"

# Native helper for the extension
(cd native/wgbridge && CGO_ENABLED=0 go test ./cmd/resproxy-helper/)
for target in windows/amd64 linux/amd64 darwin/arm64 darwin/amd64; do
  os="${target%/*}"; arch="${target#*/}"
  ext=""; [ "$os" = windows ] && ext=".exe"
  (cd native/wgbridge && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags "-s -w -X main.Version=$version" \
    -o "$out/resproxy-helper-$os-$arch$ext" ./cmd/resproxy-helper)
done

wsl.exe -e bash -lc 'export SSH_AUTH_SOCK=$HOME/.ssh/agent.sock; cd /mnt/c/Users/Alex/work/res_proxy && git push origin main && git fetch --tags -q origin'

if gh release view "$tag" >/dev/null 2>&1; then
    echo "Release $tag already exists; nothing to publish."
    exit 0
fi

prev="$(git describe --tags --abbrev=0 --match 'v*' HEAD 2>/dev/null || true)"
notes="$(git log --no-merges --format='- %s' "${prev:+$prev..}HEAD")"
notes="$notes

**Assets:** \`ResProxy-$tag.apk\` (Android) · \`ResProxy-chrome-$tag.zip\` (Chrome: unzip, then chrome://extensions → Developer mode → Load unpacked) · \`resproxy-helper-*\` (optional helper for the extension: run \`resproxy-helper install\` once)"

gh release create "$tag" "$out"/* --target "$sha" --title "ResProxy $version" --notes "$notes"
wsl.exe -e bash -lc 'export SSH_AUTH_SOCK=$HOME/.ssh/agent.sock; cd /mnt/c/Users/Alex/work/res_proxy && git fetch --tags -q origin'
echo "Published $tag"
