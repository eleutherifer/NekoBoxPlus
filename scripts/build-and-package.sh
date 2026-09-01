#!/usr/bin/env bash
# Builds NekoBox+ from an already-patched ./workspace (see
# prepare-workspace.sh) and collects the release APKs into ./dist.
#
# Must run where ANDROID_HOME is set to an installed SDK with an NDK
# under $ANDROID_HOME/ndk/<version>, and Docker available -- the patch
# requires the containerised core build (buildScript/lib/core.docker.sh),
# since it bundles a Go runtime carrying this project's own runtime
# patches. That container build auto-mounts every workspace/<dep> sibling
# we already prepared (its own defaults point at exactly that layout),
# so no extra wiring is needed here beyond running it from the right cwd.

set -euo pipefail

ROOT="$(pwd)"
NBA="$ROOT/workspace/NekoBoxForAndroid"
cd "$NBA"

# --- signing ---------------------------------------------------------
# app/build.gradle.kts signs from rootProject.file("release.keystore")
# using KEYSTORE_PASS/ALIAS_NAME/ALIAS_PASS (env or local.properties).
# The upstream repo ships its own release.keystore, but we don't have
# its password, so provide your own via the KEYSTORE_BASE64 secret --
# see the setup note in the PR/README for the one-time keytool command.
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  echo "$KEYSTORE_BASE64" | base64 -d > release.keystore
  echo "using the provided KEYSTORE_BASE64 keystore"
else
  echo "WARNING: KEYSTORE_BASE64 not set; falling back to whatever" \
       "release.keystore came from the upstream checkout, which will" \
       "only work if KEYSTORE_PASS/ALIAS_NAME/ALIAS_PASS also match it." >&2
fi

# --- native core (Docker; required -- see comment above) --------------
bash buildScript/lib/assets.sh

# sing-box's adblock-rust bridge (common/adblock/adblockrust/resources/embed.go)
# go:embeds resources/files/placeholder.txt. Without adblock-resources/uBlock
# wired in as siblings for sing-box's own `make -f Makefile.plus
# adblock-resources-generate` (we don't clone those -- no source URL for them
# anywhere in patches.tar.gz), that file never gets created and the Go build
# fails outright. Touching it here keeps the build going with an empty
# ad-block ruleset; ask if you want the real uBlock filter lists wired in too.
mkdir -p "$ROOT/workspace/sing-box/common/adblock/adblockrust/resources/files"
touch "$ROOT/workspace/sing-box/common/adblock/adblockrust/resources/files/placeholder.txt"

bash buildScript/lib/core.docker.sh

[ -f app/libs/libcore.aar ] || { echo "app/libs/libcore.aar missing after core.docker.sh" >&2; exit 1; }

# --- gradle -------------------------------------------------------------
NDK_DIR="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
{
  echo "sdk.dir=${ANDROID_HOME}"
  echo "ndk.dir=${NDK_DIR}"
} > local.properties

# "plus" is the flavor this patch set adds; its own output-naming rule
# already produces NekoBoxPlus-<version>-<abi>.apk / -universal.apk, so
# nothing here needs to rename anything.
./gradlew app:assemblePlusRelease --stacktrace

# --- collect + report version ------------------------------------------
OUT_DIR="$ROOT/dist"
mkdir -p "$OUT_DIR"
find app/build/outputs/apk/plus/release -name '*.apk' -exec cp -v {} "$OUT_DIR/" \;

VERSION_NAME="$(grep -oP '^VERSION_NAME=\K.*' nb4a.properties)"
echo "Version: $VERSION_NAME"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "version_name=$VERSION_NAME" >> "$GITHUB_OUTPUT"
fi

echo
echo "Built:"
ls -la "$OUT_DIR"
