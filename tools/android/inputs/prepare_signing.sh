#!/usr/bin/env bash
# Restore and validate a GitHub Actions Android signing keystore.
# Secret values are read from the environment and are never printed.
set -euo pipefail

output=${1:?usage: prepare_signing.sh <temporary-keystore-path>}
: "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD is required}"
: "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS is required}"
: "${ANDROID_KEY_PASSWORD:?ANDROID_KEY_PASSWORD is required}"
: "${ANDROID_KEYSTORE_BASE64:?ANDROID_KEYSTORE_BASE64 is required}"

umask 077
mkdir -p "$(dirname -- "$output")"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$output"
[[ -s $output ]] || { echo 'Decoded Android keystore is empty' >&2; exit 1; }

keytool -list -keystore "$output" \
    -storepass "$ANDROID_KEYSTORE_PASSWORD" \
    -alias "$ANDROID_KEY_ALIAS" >/dev/null 2>&1 || {
    rm -f -- "$output"
    echo 'Android keystore validation failed' >&2
    exit 1
}
