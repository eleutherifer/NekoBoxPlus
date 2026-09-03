#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
BYEDPI_PATCH=$(realpath "patches/byedpi/0001-byedpi-jni-api.patch")
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout {sing-box-repo-here} sing-box
fi
pushd sing-box
if [ "$(git rev-parse HEAD)" != "$COMMIT_SING_BOX" ]; then
  git checkout "$COMMIT_SING_BOX"
fi
popd

####

####

if [ ! -d "sing-vmess" ]; then
  git clone --no-checkout {sing-vmess-repo-here} sing-vmess
fi
pushd sing-vmess
if [ "$(git rev-parse HEAD)" != "$COMMIT_SING_VMESS" ]; then
  git checkout "$COMMIT_SING_VMESS"
fi
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout {libneko-repo-here} libneko
fi
pushd libneko
if [ "$(git rev-parse HEAD)" != "$COMMIT_LIBNEKO" ]; then
  git checkout "$COMMIT_LIBNEKO"
fi
popd

####

if [ ! -d "byedpi" ]; then
  git clone --no-checkout https://github.com/hufrea/byedpi.git byedpi
fi
pushd byedpi
if [ "$(git rev-parse HEAD)" != "$COMMIT_BYEDPI" ]; then
  git checkout "$COMMIT_BYEDPI"
  git apply "$BYEDPI_PATCH"
fi
popd

####

popd
