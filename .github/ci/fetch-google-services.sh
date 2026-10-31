#!/usr/bin/env bash
# Holt die google-services.json der Android-App de.greenganja.growguardian über die Firebase Management API.
# Aufruf: fetch-google-services.sh <projekt-id> <access-token> <ziel-datei>
set -euo pipefail
P="$1"; TOKEN="$2"; OUT="$3"; PKG=de.greenganja.growguardian
api() { curl -sSf -H "Authorization: Bearer $TOKEN" "$@"; }
APP=$(api "https://firebase.googleapis.com/v1beta1/projects/$P/androidApps" |
  python3 -c "import json,sys; print(next((a['appId'] for a in json.load(sys.stdin).get('apps', []) if a.get('packageName') == '$PKG'), ''))")
[ -n "$APP" ] || { echo "::warning::Keine Android-App $PKG im Projekt $P"; exit 1; }
api "https://firebase.googleapis.com/v1beta1/projects/$P/androidApps/$APP/config" |
  python3 -c "import base64,json,sys; sys.stdout.write(base64.b64decode(json.load(sys.stdin)['configFileContents']).decode())" > "$OUT"
grep -q "\"package_name\": *\"$PKG\"" "$OUT" || { echo "::warning::google-services.json passt nicht zu $PKG"; exit 1; }
