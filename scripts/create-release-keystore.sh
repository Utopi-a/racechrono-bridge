#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${ANDROID_RELEASE_KEYSTORE:-"$HOME/.android/racechrono-bridge-release.jks"}"
PROPERTIES_FILE="$ROOT_DIR/release-keystore.properties"
KEY_ALIAS="${ANDROID_RELEASE_KEY_ALIAS:-racechrono-bridge}"

if [[ -f "$PROPERTIES_FILE" ]]; then
  echo "release-keystore.properties already exists. Leaving existing release signing config unchanged."
  exit 0
fi

if [[ -f "$KEYSTORE" ]]; then
  echo "Keystore already exists at $KEYSTORE, but release-keystore.properties is missing." >&2
  echo "Refusing to create unknown credentials for an existing keystore." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool is required. Run with JAVA_HOME pointing at a JDK." >&2
  exit 1
fi

if command -v openssl >/dev/null 2>&1; then
  STORE_PASSWORD="$(openssl rand -base64 36 | tr -d '\n')"
else
  STORE_PASSWORD="$(uuidgen | tr -d '-')$(uuidgen | tr -d '-')"
fi
KEY_PASSWORD="$STORE_PASSWORD"

mkdir -p "$(dirname "$KEYSTORE")"

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=RaceChrono Bridge, OU=RaceChrono Bridge, O=Utopi-a, L=Tokyo, ST=Tokyo, C=JP"

cat > "$PROPERTIES_FILE" <<EOF
storeFile=$KEYSTORE
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF

chmod 600 "$PROPERTIES_FILE"

echo "Created release keystore: $KEYSTORE"
echo "Created local signing config: $PROPERTIES_FILE"
echo "Back up both files. Losing them prevents installing updates over this signed build."
