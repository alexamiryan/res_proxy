#!/usr/bin/env bash
# Push main to GitHub and publish a release with the APK. Run from Git Bash on the Windows host:
#   scripts/release.sh
# Builds and tests on Windows, pushes through WSL (where the SSH key lives), creates the release with
# the Windows `gh` CLI. Tag = v<versionName>, versionName = 0.1.<commit count>.
set -euo pipefail
cd "$(dirname "$0")/.."

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "Not on main." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes. Commit first." >&2; exit 1; }

version="0.1.$(git rev-list --count HEAD)"
tag="v$version"
sha="$(git rev-parse HEAD)"

./gradlew.bat --console=plain testDebugUnitTest assembleDebug
apk_src=app/build/outputs/apk/debug/app-debug.apk
apk="app/build/outputs/apk/debug/ResProxy-$tag.apk"
cp "$apk_src" "$apk"

wsl.exe -e bash -lc 'export SSH_AUTH_SOCK=$HOME/.ssh/agent.sock; cd /mnt/c/Users/Alex/work/res_proxy && git push origin main && git fetch --tags -q origin'

if gh release view "$tag" >/dev/null 2>&1; then
    echo "Release $tag already exists; nothing to publish."
    exit 0
fi

prev="$(git describe --tags --abbrev=0 --match 'v*' HEAD 2>/dev/null || true)"
notes="$(git log --no-merges --format='- %s' "${prev:+$prev..}HEAD")"

gh release create "$tag" "$apk" --target "$sha" --title "ResProxy $version" --notes "$notes"
wsl.exe -e bash -lc 'export SSH_AUTH_SOCK=$HOME/.ssh/agent.sock; cd /mnt/c/Users/Alex/work/res_proxy && git fetch --tags -q origin'
echo "Published $tag"
