#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="$ROOT_DIR/app/src/main/assets/sing-box"
PARENT_DIR="$(dirname "$DIR")"
STAGING_DIR="$(mktemp -d "$PARENT_DIR/.sing-box-assets.XXXXXX")"
BACKUP_DIR="$PARENT_DIR/.sing-box-assets.backup"

cleanup() {
  rm -rf "$STAGING_DIR"
  if [ ! -d "$DIR" ] && [ -d "$BACKUP_DIR" ]; then
    mv "$BACKUP_DIR" "$DIR"
  fi
}
trap cleanup EXIT

if [ -d "$BACKUP_DIR" ]; then
  if [ -d "$DIR" ]; then
    rm -rf "$BACKUP_DIR"
  else
    mv "$BACKUP_DIR" "$DIR"
  fi
fi

cd "$STAGING_DIR"

get_latest_release() {
  curl --fail --location --silent --show-error \
    "https://api.github.com/repos/$1/releases/latest" |
    jq -er '.tag_name'
}

get_latest_release_json() {
  curl --fail --location --silent --show-error \
    "https://api.github.com/repos/$1/releases/latest"
}

VERSION_GEOIP="$(get_latest_release "soffchen/sing-geoip")"
echo "VERSION_GEOIP=$VERSION_GEOIP"
printf '%s' "$VERSION_GEOIP" > geoip.version.txt
curl -fLSsO "https://github.com/soffchen/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db"
xz -9 --lzma2=dict=4MiB geoip.db

VERSION_GEOSITE="$(get_latest_release "soffchen/sing-geosite")"
echo "VERSION_GEOSITE=$VERSION_GEOSITE"
printf '%s' "$VERSION_GEOSITE" > geosite.version.txt
curl -fLSsO "https://github.com/soffchen/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db"
xz -9 --lzma2=dict=4MiB geosite.db

THRONE_RULESET_SHA="$(
  curl --fail --location --silent --show-error \
    "https://api.github.com/repos/throneproj/routeprofiles/commits/rule-set" |
    jq -er '.sha'
)"
THRONE_RULESET_SHA_SHORT="$(printf '%s' "$THRONE_RULESET_SHA" | cut -c1-7)"
echo "THRONE_RULESET_SHA=$THRONE_RULESET_SHA_SHORT"
printf '%s' "$THRONE_RULESET_SHA_SHORT" > throne-ruleset.version.txt
curl -fLSso throne-ruleset-srslist.h \
  https://raw.githubusercontent.com/throneproj/routeprofiles/refs/heads/rule-set/srslist.h

ITDOG_RELEASE_JSON="$(get_latest_release_json "itdoginfo/allow-domains")"
ITDOG_RULESET_VERSION="$(printf '%s' "$ITDOG_RELEASE_JSON" | jq -er '.tag_name')"
echo "ITDOG_RULESET_VERSION=$ITDOG_RULESET_VERSION"
printf '%s' "$ITDOG_RULESET_VERSION" > itdog-ruleset.version.txt
printf '%s' "$ITDOG_RELEASE_JSON" | jq -c '
  reduce (
    .assets[]
      | if (.name | endswith(".srs") and (endswith("_domain.srs") | not)) then
        {
          alias: ("itdog-" + (.name | sub("\\.srs$"; ""))),
          key: "rsip",
          url: .browser_download_url
        }
      else empty end
  ) as $item
  ({};
    .[$item.alias] = ((.[$item.alias] // {}) + {($item.key): $item.url})
  )
' > itdog-ruleset.json

jq -e 'type == "object" and length > 0' itdog-ruleset.json >/dev/null
for required_asset in \
  geoip.db.xz geoip.version.txt \
  geosite.db.xz geosite.version.txt \
  throne-ruleset-srslist.h throne-ruleset.version.txt \
  itdog-ruleset.json itdog-ruleset.version.txt
do
  test -s "$required_asset"
done

rm -rf "$BACKUP_DIR"
if [ -d "$DIR" ]; then
  mv "$DIR" "$BACKUP_DIR"
fi
if mv "$STAGING_DIR" "$DIR"; then
  rm -rf "$BACKUP_DIR"
  trap - EXIT
else
  if [ -d "$BACKUP_DIR" ]; then
    mv "$BACKUP_DIR" "$DIR"
  fi
  exit 1
fi
