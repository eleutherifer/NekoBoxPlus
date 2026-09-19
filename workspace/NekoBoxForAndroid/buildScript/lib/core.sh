#!/bin/bash

if [[ -z "${VERSION_SING_BOX:-}" ]]; then
	VERSION_SING_BOX="$(sed -nE 's/^VERSION_NAME=.*-([0-9]+)[[:space:]]*$/\1/p' nb4a.properties | tail -n1)"
	if [[ -z "$VERSION_SING_BOX" ]]; then
		echo "Unable to derive VERSION_SING_BOX from nb4a.properties" >&2
		exit 1
	fi
fi
export VERSION_SING_BOX

buildScript/lib/core/init.sh
buildScript/lib/core/build.sh
