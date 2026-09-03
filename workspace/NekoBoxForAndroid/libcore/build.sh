#!/bin/bash

[ -f ./env_java.sh ] && source ./env_java.sh
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
	$BUILD/java \
	$BUILD/javac-output \
	$BUILD/src

if [ -z "$GOPATH" ]; then
	GOPATH=$(go env GOPATH)
fi

# Verify gVisor workaround.
if ! go run -tags=with_gvisor ../../sing-box/cmd/internal/gvisor_workaround_verify/main.go; then
	echo "ERROR: gVisor workaround verification failed"
	exit 1
fi

export GOBIND=gobind

build_adblock_assets() {
	local sing_box_dir="../../sing-box"
	local bridge_dir="$sing_box_dir/common/adblock/bridge"
	local target_dir="$bridge_dir/target"
	local llvm_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

	if [ ! -f "$sing_box_dir/Makefile.plus" ]; then
		echo "ERROR: sing-box Makefile.plus not found"
		exit 1
	fi

	make -C "$sing_box_dir" -f Makefile.plus adblock-rust-sync adblock-resources-generate || exit 1

	export CARGO_TARGET_DIR="$target_dir"
	export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$llvm_bin/armv7a-linux-androideabi21-clang"
	export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$llvm_bin/aarch64-linux-android21-clang"
	export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$llvm_bin/i686-linux-android21-clang"
	export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$llvm_bin/x86_64-linux-android21-clang"
	export AR_armv7_linux_androideabi="$llvm_bin/llvm-ar"
	export AR_aarch64_linux_android="$llvm_bin/llvm-ar"
	export AR_i686_linux_android="$llvm_bin/llvm-ar"
	export AR_x86_64_linux_android="$llvm_bin/llvm-ar"

	cargo build --manifest-path "$bridge_dir/Cargo.toml" --release --target armv7-linux-androideabi || exit 1
	cargo build --manifest-path "$bridge_dir/Cargo.toml" --release --target aarch64-linux-android || exit 1
	cargo build --manifest-path "$bridge_dir/Cargo.toml" --release --target i686-linux-android || exit 1
	cargo build --manifest-path "$bridge_dir/Cargo.toml" --release --target x86_64-linux-android || exit 1
}

build_adblock_assets

# Resolve bundled-module versions from their sibling git repos and inject them
# via linker flags. Sibling repos live two levels up from libcore/ (the same
# relative path under both the local and the Docker build layouts).
#
#   tag at HEAD  -> use the tag name
#   else         -> use the short commit hash
#   neither      -> leave the default (no -X flag)
# MasterDnsVPN always uses the latest local tag, never a commit.
resolve_version() {
	local dir="$1" mode="${2:-}" version=""
	[ -d "$dir" ] || return 0
	if [ "$mode" = "tag" ]; then
		version="$(git -C "$dir" describe --tags --abbrev=0 2>/dev/null || true)"
	else
		version="$(git -C "$dir" describe --tags --exact-match 2>/dev/null || true)"
		if [ -z "$version" ]; then
			version="$(git -C "$dir" rev-parse --short HEAD 2>/dev/null || true)"
		fi
	fi
	printf '%s' "$version"
}

VERSION_AMNEZIA="v3.1.20260814"
VERSION_BYEDPI="ba53229"
VERSION_MASTERDNSVPN="v2026.06.13.234407-7de2476"
VERSION_ADBLOCK_RUST="$(resolve_version ../../adblock-rust)"
VERSION_ADBLOCK_RESOURCES="$(resolve_version ../../adblock-resources)"
VERSION_UBLOCK="$(resolve_version ../../uBlock)"
VERSION_SING_BOX="1.14.0"

VERSION_LDFLAGS=""
append_version_ldflag() {
	local symbol="$1" value="$2"
	[ -n "$value" ] && VERSION_LDFLAGS="${VERSION_LDFLAGS} -X libcore.${symbol}=${value}"
}
append_version_ldflag VersionAmnezia "$VERSION_AMNEZIA"
append_version_ldflag VersionByeDPI "$VERSION_BYEDPI"
append_version_ldflag VersionMasterDnsVPN "$VERSION_MASTERDNSVPN"
append_version_ldflag VersionAdblockRust "$VERSION_ADBLOCK_RUST"
append_version_ldflag VersionAdblockResources "$VERSION_ADBLOCK_RESOURCES"
append_version_ldflag VersionUBlock "$VERSION_UBLOCK"
[ -n "$VERSION_SING_BOX" ] && VERSION_LDFLAGS="${VERSION_LDFLAGS} -X github.com/sagernet/sing-box/constant.Version=${VERSION_SING_BOX}"

echo ">> core version: sing-box-plus=${VERSION_SING_BOX:-?}"
echo ">> module versions: amneziawg-go=${VERSION_AMNEZIA:-?} byedpi=${VERSION_BYEDPI:-?} masterdnsvpn=${VERSION_MASTERDNSVPN:-?} adblock-rust=${VERSION_ADBLOCK_RUST:-?} adblock-resources=${VERSION_ADBLOCK_RESOURCES:-?} uBlock=${VERSION_UBLOCK:-?}"

gomobile bind -v -androidapi 23 -trimpath -ldflags="-s -w -checklinkname=0${VERSION_LDFLAGS}" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_awg,with_tailscale,with_openvpn,with_openconnect,with_utls,with_clash_api,with_naive_outbound,with_trusttunnel_cronet,with_adblock,with_adblock_cronet,with_grpc,badlinkname,tfogo_checklinkname0' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
