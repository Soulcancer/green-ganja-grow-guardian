#!/usr/bin/env bash
# Backend testen und nach Firebase ausrollen.
# Aufruf: ./deploy.sh <firebase-projekt-id>
# Voraussetzung: firebase-tools (npm i -g firebase-tools), firebase login, Blaze-Tarif, Secrets gesetzt (README Abschnitt 8).
set -euo pipefail
PROJECT="${1:?Bitte die Firebase-Projekt-ID angeben, z. B. ./deploy.sh growguardian-prod}"
cd "$(dirname "$0")/functions"

[ -f .env ] || { echo "functions/.env fehlt (Vorlage: .env.example)"; exit 1; }
npm ci
npm test
npm run build
cd ..
firebase deploy --project "$PROJECT" --only functions,firestore:rules,firestore:indexes
echo
echo "Fertig. Jetzt prüfen:"
echo " - RevenueCat-Webhook-URL (revenuecatWebhook) in RevenueCat eingetragen?"
echo " - AdMob: SSV-URL (admobRewardCallback) beim Belohnungsvideo-Anzeigenblock eingetragen?"
echo " - Tuya-Secrets gesetzt (TUYA_CLIENT_ID, TUYA_CLIENT_SECRET) und Cloud-Projekt mit der App verknüpft?"
echo " - Firestore → TTL-Richtlinien für expireAt aktiv (README Abschnitt 8)?"
