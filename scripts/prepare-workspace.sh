#!/usr/bin/env bash
# Rebuilds ./workspace from ./patches for any NekoBox+ patches.tar.gz.
#
# Each top-level patches/<name>.patch carries its own header:
#   Base: <repo url>
#   Target patch commit: <sha>
# We clone <repo url> at <sha> into workspace/<name> and `git apply` the
# patch. That covers NekoBoxForAndroid, sing-box, libneko, sing-vmess,
# MasterDnsVPN, or whatever future patch set names -- nothing here is
# hardcoded to a specific version.
#
# Some components add their OWN nested per-dependency patches once
# applied (currently: sing-box/patches/{amneziawg-go,utls}, and
# NekoBoxForAndroid/patches/byedpi), as <dep>/README or README.md with
# "N. Clone <url>" / "N. Checkout to <ref>" lines followed by numbered
# *.patch files. We scan for these dynamically after every top-level
# patch is applied and materialise whatever we find as a workspace
# sibling too. A nested README with no parseable Clone/Checkout-to line
# (byedpi's only documents a target commit) falls back to parsing
# NekoBoxForAndroid's own buildScript/lib/core/get_source.sh +
# get_source_env.sh, which is where that one is actually hardcoded --
# relying on that script to fetch it itself inside the Docker build
# isn't happening reliably in practice.
#
# One naming exception is known so far: MasterDnsVPN.patch must land in
# workspace/MasterDnsVPN-plus (that's the directory name the app code and
# core.docker.sh actually reference), not workspace/MasterDnsVPN. Add
# further overrides to DIR_OVERRIDES if a future release needs one.

set -euo pipefail

ROOT="$(pwd)"
PATCHES_DIR="$ROOT/patches"
WORKSPACE_DIR="$ROOT/workspace"

declare -A DIR_OVERRIDES=(
  [MasterDnsVPN]="MasterDnsVPN-plus"
)

echo "== resetting workspace/"
rm -rf "$WORKSPACE_DIR"
mkdir -p "$WORKSPACE_DIR"

# Fetch one exact commit/tag without a full clone. GitHub (and most other
# forges) allow fetching any reachable ref, not just branch tips.
fetch_ref() {
  local url="$1" ref="$2" dest="$3"
  mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin "$url"
  if ! git -C "$dest" fetch --depth 1 origin "$ref" -q 2>/dev/null; then
    echo "  shallow fetch of $ref failed, trying a full fetch" >&2
    git -C "$dest" fetch origin -q
  fi
  git -C "$dest" checkout -q FETCH_HEAD
}

# Strip a trailing /commits/<branch>[/] or /tree/<branch> or .git from a
# "Base:" URL so it becomes a plain clone URL.
clean_repo_url() {
  local u="${1%.git}"
  echo "$u" | sed -E 's#/(commits|tree)/[^/]+/?$##'
}

apply_patches_in() {
  local dep_dir="$1" patch_src_dir="$2" any=0
  shopt -s nullglob
  for p in "$patch_src_dir"/*.patch; do
    any=1
    echo "  applying $(basename "$p")"
    git -C "$dep_dir" apply --whitespace=nowarn "$p"
  done
  shopt -u nullglob
  [ "$any" = 1 ] || echo "  (no .patch files in $patch_src_dir)"
}

# Fallback for a nested README with no Clone/Checkout-to line (byedpi's
# only states a target commit). NekoBoxForAndroid's own get_source.sh
# hardcodes "git clone --no-checkout <url> <dep>" directly for a few
# deps, with the ref in get_source_env.sh as COMMIT_<DEP>; recover both
# from there instead of leaving it to that script to fetch on its own.
get_source_fallback() {
  local dep_name="$1"
  local gs="$WORKSPACE_DIR/NekoBoxForAndroid/buildScript/lib/core/get_source.sh"
  local gse="$WORKSPACE_DIR/NekoBoxForAndroid/buildScript/lib/core/get_source_env.sh"
  [ -f "$gs" ] && [ -f "$gse" ] || return 1

  local url var ref
  url="$(grep "git clone --no-checkout" "$gs" | grep -E "[[:space:]]${dep_name}\$" | grep -oP 'https?://\S+' | head -1 || true)"
  var="COMMIT_$(echo "$dep_name" | tr '[:lower:]-' '[:upper:]_')"
  ref="$(grep -oP "^export ${var}=\"\K[^\"]+" "$gse" | head -1 || true)"

  [ -n "$url" ] && [ -n "$ref" ] || return 1
  echo "$url $ref"
}

# Handle one nested <dep>/README(.md) discovered under an already-applied
# component: a parseable Clone + Checkout-to line, or the get_source.sh
# fallback above.
process_nested_readme() {
  local readme="$1"
  local dep_patch_dir dep_name clone_url ref dest fallback
  dep_patch_dir="$(dirname "$readme")"
  dep_name="$(basename "$dep_patch_dir")"

  clone_url="$(grep -ioP '\bClone\s+\K\S+' "$readme" | head -1 || true)"
  ref="$(grep -ioP '\bCheckout\s+to\s+\K\S+' "$readme" | head -1 || true)"

  if [ -z "$clone_url" ] || [ -z "$ref" ]; then
    fallback="$(get_source_fallback "$dep_name" || true)"
    if [ -n "$fallback" ]; then
      clone_url="${fallback% *}"
      ref="${fallback#* }"
      echo "  $dep_name: no Clone/Checkout-to line in $(basename "$readme"), recovered from get_source.sh instead"
    else
      echo "  $dep_name: no Clone/Checkout-to line in $(basename "$readme") and nothing recoverable from get_source.sh -- leaving it to NekoBoxForAndroid's own tooling"
      return
    fi
  fi

  dest="$WORKSPACE_DIR/${DIR_OVERRIDES[$dep_name]:-$dep_name}"
  if [ -d "$dest" ]; then
    echo "  $dep_name already in workspace, skipping"
    return
  fi

  echo "  $dep_name -> $clone_url @ $ref"
  fetch_ref "$clone_url" "$ref" "$dest"
  apply_patches_in "$dest" "$dep_patch_dir"
}

shopt -s nullglob
patch_files=("$PATCHES_DIR"/*.patch)
shopt -u nullglob
[ "${#patch_files[@]}" -gt 0 ] || { echo "no *.patch files found in $PATCHES_DIR" >&2; exit 1; }

for patch_file in "${patch_files[@]}"; do
  name="$(basename "$patch_file" .patch)"
  header="$(head -12 "$patch_file")"

  base_url="$(echo "$header" | grep -ioP '^Base:\s*\K.*' | tr -d '\r' | head -1)"
  target="$(echo "$header" | grep -ioP '^Target patch commit:\s*\K\S+' | tr -d '\r' | head -1)"

  if [ -z "$base_url" ] || [ -z "$target" ]; then
    echo "SKIP $name.patch: no Base:/Target patch commit: header found" >&2
    continue
  fi
  base_url="$(clean_repo_url "$base_url")"
  dest="$WORKSPACE_DIR/${DIR_OVERRIDES[$name]:-$name}"

  echo "== $name : $base_url @ $target -> ${dest#"$ROOT"/}"
  fetch_ref "$base_url" "$target" "$dest"
  git -C "$dest" apply --whitespace=nowarn "$patch_file"

  for readme in "$dest"/patches/*/README "$dest"/patches/*/README.md; do
    [ -f "$readme" ] || continue
    process_nested_readme "$readme"
  done
done

# NekoBoxForAndroid's buildScript/lib/core/get_source.sh re-checks-out
# sing-box/libneko/sing-vmess to whatever get_source_env.sh names -- after
# patching that's normally an upstream maintainer's own fork commit,
# meaningless against the plain-upstream checkouts we just made
# ourselves. Point those vars at the commits we actually used so that
# step is a no-op. byedpi is handled above instead (its source has no
# public placeholder to reconcile against).
GET_SOURCE_ENV="$WORKSPACE_DIR/NekoBoxForAndroid/buildScript/lib/core/get_source_env.sh"
if [ -f "$GET_SOURCE_ENV" ]; then
  for pair in COMMIT_SING_BOX:sing-box COMMIT_LIBNEKO:libneko COMMIT_SING_VMESS:sing-vmess; do
    var="${pair%%:*}"; dir="${pair##*:}"
    if [ -d "$WORKSPACE_DIR/$dir" ] && grep -q "^export $var=" "$GET_SOURCE_ENV"; then
      actual="$(git -C "$WORKSPACE_DIR/$dir" rev-parse HEAD)"
      sed -i "s|^export $var=.*|export $var=\"$actual\"|" "$GET_SOURCE_ENV"
      echo "pinned $var=$actual in get_source_env.sh"
    fi
  done
fi

echo
echo "workspace ready:"
find "$WORKSPACE_DIR" -maxdepth 1 -mindepth 1 -type d -printf '  %f\n'
