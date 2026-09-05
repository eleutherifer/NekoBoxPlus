#!/usr/bin/env bash
set -euo pipefail

REBUILD_IMAGE=0

while [[ $# -gt 0 ]]; do
	case "$1" in
	--rebuild-image)
		REBUILD_IMAGE=1
		shift
		;;
	-h | --help)
		cat <<EOF
Usage: $(basename "$0") [--rebuild-image]

Options:
  --rebuild-image   Rebuild the Docker image before building the library.
  -h, --help        Show this help message.
EOF
		exit 0
		;;
	*)
		echo "Unknown argument: $1" >&2
		echo "Run with --help for usage." >&2
		exit 1
		;;
	esac
done

# Run from the repository root, or from anywhere inside it.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if false; then
	:
else
	PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi

cd "$PROJECT_ROOT"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" ]]; then
	if [[ -d "$ANDROID_HOME/ndk" ]]; then
		ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
	else
		ANDROID_NDK_HOME="$ANDROID_HOME/ndk-bundle"
	fi
else
	ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}"
fi

export ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
: "${GOPATH:=$HOME/go}"
export GOPATH

# Keep the same env resolution as the non-Docker scripts.
# shellcheck disable=SC1091
source "buildScript/init/env.sh"
if [ -f "buildScript/lib/core/get_source_env.sh" ]; then
	# shellcheck disable=SC1091
	source "buildScript/lib/core/get_source_env.sh"
fi

GO_VERSION="${GO_VERSION:-1.27.1}"
BOOTSTRAP_GO_VERSION="${BOOTSTRAP_GO_VERSION:-1.26.6}"
GO_PATCH_DIR="${GO_PATCH_DIR:-buildScript/lib/core/go-runtime-patches}"
DOCKERFILE="${DOCKERFILE:-buildScript/lib/core/Dockerfile}"
IMAGE_NAME="${PATCHED_GO_ANDROID_IMAGE:-neko-android-aar-go:${GO_VERSION}-runtime-patches-v2}"

ANDROID_HOME_HOST="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK_HOME_HOST="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"

# Common Android SDK locations on Linux/macOS if env is not exported.
if [ -z "$ANDROID_HOME_HOST" ]; then
	for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk"; do
		if [ -d "$candidate" ]; then
			ANDROID_HOME_HOST="$candidate"
			break
		fi
	done
fi

if [ -z "$ANDROID_HOME_HOST" ] || [ ! -d "$ANDROID_HOME_HOST" ]; then
	echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an existing Android SDK directory" >&2
	exit 1
fi

# Resolve NDK from explicit env first, then from $ANDROID_HOME/ndk/<latest>.
if [ -z "$ANDROID_NDK_HOME_HOST" ] && [ -d "$ANDROID_HOME_HOST/ndk" ]; then
	ANDROID_NDK_HOME_HOST="$(find "$ANDROID_HOME_HOST/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 || true)"
fi

if [ -z "$ANDROID_NDK_HOME_HOST" ] || [ ! -d "$ANDROID_NDK_HOME_HOST" ]; then
	echo "ANDROID_NDK_HOME, ANDROID_NDK_ROOT, or NDK_HOME must point to an existing Android NDK directory" >&2
	echo "Alternatively install an NDK under: $ANDROID_HOME_HOST/ndk/<version>" >&2
	exit 1
fi

# Existing scripts expect sing-box/libneko as siblings of the project root.
WORKSPACE_HOST="$(cd "$PROJECT_ROOT/.." && pwd)"
SING_BOX_HOST="${SING_BOX_SRC:-$WORKSPACE_HOST/sing-box}"
SING_VMESS_HOST="${SING_VMESS_SRC:-$WORKSPACE_HOST/sing-vmess}"
X_NET_HOST="${X_NET_SRC:-$WORKSPACE_HOST/net}"
LIBNEKO_HOST="${LIBNEKO_SRC:-$WORKSPACE_HOST/libneko}"
AMNEZIAWG_GO_HOST="${AMNEZIAWG_GO_SRC:-$WORKSPACE_HOST/amneziawg-go}"
MASTERDNSVPN_HOST="${MASTERDNSVPN_SRC:-$WORKSPACE_HOST/MasterDnsVPN-plus}"
BYEDPI_HOST="${BYEDPI_SRC:-$WORKSPACE_HOST/byedpi}"
ADBLOCK_RESOURCES_HOST="${ADBLOCK_RESOURCES_SRC:-$WORKSPACE_HOST/adblock-resources}"
ADBLOCK_RUST_HOST="${ADBLOCK_RUST_SRC:-$WORKSPACE_HOST/adblock-rust}"
UBLOCK_HOST="${UBLOCK_SRC:-$WORKSPACE_HOST/uBlock}"
UTLS_HOST="${UTLS_SRC:-$WORKSPACE_HOST/utls}"
AAR_OUT_HOST="${AAR_OUT_DIR:-$PROJECT_ROOT/build/aar-docker}"
GO_CACHE_HOST="${GO_CACHE_DIR:-$PROJECT_ROOT/.cache/docker-go-build}"
GO_MOD_CACHE_HOST="${GO_MOD_CACHE_DIR:-$PROJECT_ROOT/.cache/docker-go-mod}"

mkdir -p "$AAR_OUT_HOST" "$GO_CACHE_HOST" "$GO_MOD_CACHE_HOST"

if [ ! -f "$DOCKERFILE" ]; then
	echo "Dockerfile not found: $DOCKERFILE" >&2
	exit 1
fi
if [ ! -d "$GO_PATCH_DIR" ] || ! find "$GO_PATCH_DIR" -maxdepth 1 -type f -name '*.patch' -print -quit | grep -q .; then
	echo "Go runtime patch directory is missing or empty: $GO_PATCH_DIR" >&2
	exit 1
fi

if [[ "$REBUILD_IMAGE" -eq 1 ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
	docker build \
		-f "$DOCKERFILE" \
		--build-arg "GO_VERSION=$GO_VERSION" \
		--build-arg "BOOTSTRAP_GO_VERSION=$BOOTSTRAP_GO_VERSION" \
		--build-arg "GO_PATCH_DIR=$GO_PATCH_DIR" \
		-t "$IMAGE_NAME" \
		.
fi

DOCKER_ARGS=(
	--rm
	-e "ANDROID_HOME=/opt/android-sdk"
	-e "ANDROID_SDK_ROOT=/opt/android-sdk"
	-e "ANDROID_NDK_HOME=/opt/android-ndk"
	-e "ANDROID_NDK_ROOT=/opt/android-ndk"
	-e "NDK_HOME=/opt/android-ndk"
	-e "CGO_ENABLED=${CGO_ENABLED:-1}"
	-e "GO386=${GO386:-softfloat}"
	-e "ENV_NB4A=${ENV_NB4A:-1}"
	-e "COMMIT_SING_BOX=${COMMIT_SING_BOX:-}"
	-e "COMMIT_LIBNEKO=${COMMIT_LIBNEKO:-}"
	-e "COMMIT_BYEDPI=${COMMIT_BYEDPI:-}"
	-v "$PROJECT_ROOT:/workspace/project"
	-v "$ANDROID_HOME_HOST:/opt/android-sdk"
	-v "$ANDROID_NDK_HOME_HOST:/opt/android-ndk"
	-v "$GO_CACHE_HOST:/go-cache"
	-v "$GO_MOD_CACHE_HOST:/go/pkg/mod"
	-v "$AAR_OUT_HOST:/out"
	-v "/tmp/nekoboxplus-libcore-tmp:/tmp"
	-w /workspace/project
)

# Mount source siblings when present. If absent, init.sh/get_source.sh can still clone them.
if [ -d "$SING_BOX_HOST" ]; then
	DOCKER_ARGS+=(-v "$SING_BOX_HOST:/workspace/sing-box")
fi
if [ -d "$SING_VMESS_HOST" ]; then
	DOCKER_ARGS+=(-v "$SING_VMESS_HOST:/workspace/sing-vmess")
fi
if [ -d "$X_NET_HOST" ]; then
	DOCKER_ARGS+=(-v "$X_NET_HOST:/workspace/net")
fi
if [ -d "$LIBNEKO_HOST" ]; then
	DOCKER_ARGS+=(-v "$LIBNEKO_HOST:/workspace/libneko")
fi
if [ -d "$AMNEZIAWG_GO_HOST" ]; then
	DOCKER_ARGS+=(-v "$AMNEZIAWG_GO_HOST:/workspace/amneziawg-go")
fi
if [ -d "$MASTERDNSVPN_HOST" ]; then
	DOCKER_ARGS+=(-v "$MASTERDNSVPN_HOST:/workspace/MasterDnsVPN-plus")
fi
if [ -d "$BYEDPI_HOST" ]; then
	DOCKER_ARGS+=(-v "$BYEDPI_HOST:/workspace/byedpi")
fi
if [ -d "$ADBLOCK_RESOURCES_HOST" ]; then
	DOCKER_ARGS+=(-v "$ADBLOCK_RESOURCES_HOST:/workspace/adblock-resources")
fi
if [ -d "$ADBLOCK_RUST_HOST" ]; then
	DOCKER_ARGS+=(-v "$ADBLOCK_RUST_HOST:/workspace/adblock-rust")
fi
if [ -d "$UBLOCK_HOST" ]; then
	DOCKER_ARGS+=(-v "$UBLOCK_HOST:/workspace/uBlock")
fi
if [ -d "$UTLS_HOST" ]; then
	DOCKER_ARGS+=(-v "$UTLS_HOST:/workspace/utls")
fi

# Preserve SSH agent for git@github.com clones used by get_source.sh.
if [ -n "${SSH_AUTH_SOCK:-}" ] && [ -S "$SSH_AUTH_SOCK" ]; then
	DOCKER_ARGS+=(-e SSH_AUTH_SOCK=/ssh-agent -v "$SSH_AUTH_SOCK:/ssh-agent")
fi

# Allow any extra docker flags without editing this script, e.g. DOCKER_RUN_EXTRA_ARGS='--network host'.
if [ -n "${DOCKER_RUN_EXTRA_ARGS:-}" ]; then
	# shellcheck disable=SC2206
	EXTRA_ARGS=(${DOCKER_RUN_EXTRA_ARGS})
	DOCKER_ARGS+=("${EXTRA_ARGS[@]}")
fi

docker run --network host "${DOCKER_ARGS[@]}" "$IMAGE_NAME" bash -c '
  set -euo pipefail
  go version
  git config --global http.version HTTP/1.1
  git config --global --add safe.directory /workspace/project
  git config --global --add safe.directory /workspace/sing-box
  git config --global --add safe.directory /workspace/sing-vmess
  git config --global --add safe.directory /workspace/libneko
  git config --global --add safe.directory /workspace/amneziawg-go
  git config --global --add safe.directory /workspace/MasterDnsVPN-plus
  git config --global --add safe.directory /workspace/byedpi
  git config --global --add safe.directory /workspace/adblock-resources
  git config --global --add safe.directory /workspace/adblock-rust
  git config --global --add safe.directory /workspace/uBlock
  git config --global --add safe.directory /workspace/utls
  bash buildScript/lib/core.sh
  mkdir -p /out
  find /workspace/project /workspace/sing-box /workspace/sing-vmess /workspace/libneko /workspace/byedpi /workspace/utls -type f -name "*.aar" -exec cp -v {} /out/ \; 2>/dev/null || true
  rm -rf libcore/.build || true
'

echo "AAR output directory: $AAR_OUT_HOST"
