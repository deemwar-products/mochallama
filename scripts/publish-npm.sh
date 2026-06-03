#!/usr/bin/env bash
# publish-npm.sh — publish ALL @deemwario/mochallama* npm packages:
#   5 platform packages + the main launcher.
#
# Unlike a Go CLI (one cross-compiled binary per platform), mochallama ships a
# per-platform jlink runtime IMAGE, which cannot be cross-built locally. So:
#   - darwin-x64  -> built + packed LOCALLY (this dev Intel mac; no CI Intel runner)
#   - linux-x64 / linux-arm64 / darwin-arm64 / win32-x64 -> from a release.yml run's
#     npm-* artifacts (each is a ready-to-publish .tgz)
#
# Platform sub-packages are published FIRST (the launcher's optionalDependencies
# resolve against them), the launcher LAST. Modeled on windowctl/scripts/publish-npm-local.sh.
#
# Prereqs:
#   1. npm login  (org 'deemwario' has 2FA -> you'll be prompted for an OTP per
#      package, OR use an automation token to skip: npm Access Tokens -> Automation).
#   2. A SUCCESSFUL release.yml run on the CURRENT code (so the CI npm tarballs
#      carry the @deemwario scope + current version). Trigger:
#        gh workflow run release.yml --ref main
#   3. JDK 22 on PATH/JAVA_HOME (for the local darwin-x64 jlink image).
#
# Usage:  bash scripts/publish-npm.sh            # latest successful release run
#         RUN_ID=<id> bash scripts/publish-npm.sh
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="deemwar-products/mochallama"

# Non-interactive auth: if an automation token is set (NPM_DEEMWAR_IO_PUBLISH_TOKEN),
# write a project .npmrc that REFERENCES the env var (no literal secret on disk;
# npm expands it). An automation token also bypasses the org's 2FA OTP prompt.
# Otherwise fall back to an interactive `npm login` session (OTP per package).
if [ -n "${NPM_DEEMWAR_IO_PUBLISH_TOKEN:-}" ]; then
  echo '//registry.npmjs.org/:_authToken=${NPM_DEEMWAR_IO_PUBLISH_TOKEN}' > .npmrc
  echo "→ using NPM_DEEMWAR_IO_PUBLISH_TOKEN (non-interactive)"
fi

WHOAMI=$(npm whoami 2>/dev/null || true)
[ -n "$WHOAMI" ] || { echo "✗ not authenticated — set NPM_DEEMWAR_IO_PUBLISH_TOKEN or run: npm login" >&2; exit 1; }
echo "→ npm user: $WHOAMI"

# 1. Build + pack the host (darwin-x64) platform package + the launcher locally.
echo "→ building + packing darwin-x64 (local jlink image) + launcher ..."
task cli:npm:pack >/dev/null
HOST_TGZ=$(ls -t cli/npm/platforms/darwin-x64/*.tgz | head -1)
LAUNCHER_TGZ=$(ls -t cli/npm/*.tgz | head -1)
[ -s "$HOST_TGZ" ] && [ -s "$LAUNCHER_TGZ" ] || { echo "✗ local pack failed" >&2; exit 1; }

# 2. Download the other platforms' npm tarballs from the release run.
RID="${RUN_ID:-$(gh run list -R "$REPO" --workflow release.yml --json databaseId,conclusion -q 'map(select(.conclusion=="success"))[0].databaseId')}"
[ -n "$RID" ] || { echo "✗ no successful release run — trigger: gh workflow run release.yml --ref main" >&2; exit 1; }
echo "→ downloading CI npm tarballs from release run $RID ..."
rm -rf dist/npm && mkdir -p dist/npm
gh run download "$RID" -R "$REPO" -D dist/npm -p 'npm-*'
# Collect tarballs portably (macOS /bin/bash 3.2 has no `mapfile`).
CI_TGZS=()
while IFS= read -r t; do CI_TGZS+=("$t"); done < <(find dist/npm -name '*.tgz')
echo "→ found ${#CI_TGZS[@]} CI platform tarballs"

# HARD STOP if any CI tarball isn't @deemwario (a stale, pre-rename release run).
# Publishing those would create wrong-scoped @deemwarhq packages the launcher
# can't resolve — exactly the bug we must not repeat.
BAD=0
for t in "${CI_TGZS[@]}"; do
  tar -xzO -f "$t" package/package.json 2>/dev/null | grep -q '"@deemwario/' || {
    echo "  ✗ $(basename "$t") is not @deemwario-scoped" >&2; BAD=1; }
done
if [ "$BAD" = 1 ]; then
  echo "✗ CI tarballs predate the @deemwario rename. Re-run release.yml on current main:" >&2
  echo "    gh workflow run release.yml --ref main && gh run watch -R $REPO" >&2
  echo "  then re-run this. (Nothing more was published.)" >&2
  exit 1
fi

# publish one tarball, skipping if that name@version is already on npm (idempotent re-runs).
publish_tgz() {
  local tgz="$1" nv
  nv=$(tar -xzO -f "$tgz" package/package.json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["name"]+"@"+d["version"])')
  if npm view "$nv" version >/dev/null 2>&1; then
    echo "  = $nv already published — skip"
  else
    echo "  + $nv"
    npm publish "$tgz" --access public
  fi
}

# 3. Publish platform packages FIRST (host + the 4 CI ones).
echo "=== publishing platform packages ==="
publish_tgz "$HOST_TGZ"
for t in "${CI_TGZS[@]}"; do publish_tgz "$t"; done

# 4. Publish the launcher LAST.
echo "=== publishing launcher ==="
publish_tgz "$LAUNCHER_TGZ"

echo ""
echo "✓ published. Verify:"
echo "    npm view @deemwario/mochallama"
echo "    npx @deemwario/mochallama chat"
