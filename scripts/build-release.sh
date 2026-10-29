#!/usr/bin/env bash
# Baut das signierte App Bundle (.aab) für Google Play.
# Voraussetzungen: Android Studio / SDK, local.properties (Vorlage: local.properties.example),
# app/google-services.json aus der Firebase-Konsole, app/libs/security-algorithm.aar aus der Tuya-Plattform.
set -euo pipefail
cd "$(dirname "$0")/.."
for f in local.properties app/google-services.json; do
  [ -f "$f" ] || { echo "Fehlt: $f"; exit 1; }
done
ls app/libs/*.aar >/dev/null 2>&1 || { echo "Fehlt: app/libs/security-algorithm.aar (Tuya)"; exit 1; }
grep -q '^revenuecat.key.release=goog_' local.properties || { echo "revenuecat.key.release muss ein goog_-Key sein"; exit 1; }
grep -q '^admob.appId=ca-app-pub-' local.properties || echo "Warnung: admob.appId fehlt, der Release nutzt sonst Googles Test-Anzeigen"
grep -q 'example.org' app/src/main/java/de/greenganja/growguardian/app/Legal.kt && echo "Warnung: Legal.kt enthält noch Platzhalter-Links"

./gradlew clean testPlayDebugUnitTest bundlePlayRelease assembleDirectRelease
echo
echo "Fertig:"
echo " - Google Play: app/build/outputs/bundle/playRelease/app-play-release.aab"
echo " - Direkt (ohne Play): app/build/outputs/apk/direct/release/app-direct-release.apk"
echo "Hochladen in der Play Console unter Testen → Interner Test → Neuer Release."
