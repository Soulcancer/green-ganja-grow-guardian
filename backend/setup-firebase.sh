#!/usr/bin/env bash
# Richtet das Firebase-Projekt für Green Ganja Grow Guardian ein: einmal in der Google Cloud Shell ausführen.
#
#   bash setup-firebase.sh <projekt-id>
#
# Vorher: Projekt in der Firebase-Konsole anlegen (console.firebase.google.com → Projekt erstellen).
# Für die Cloud Functions zusätzlich den Blaze-Tarif aktivieren (Firebase-Konsole → Tarif ändern).
#
# Das Skript kann mehrfach laufen; was es schon gibt, bleibt unverändert. Es legt an:
#   - Firestore-Datenbank in europe-west3 (Frankfurt). Der Ort lässt sich später nicht mehr ändern.
#   - E-Mail/Passwort-Anmeldung
#   - Android-App de.greenganja.growguardian
#   - Dienstkonto github-deploy und eine schlüssellose Anmeldung (Workload Identity Federation),
#     über die NUR GitHub Actions aus dem Repository Soulcancer/green-ganja-grow-guardian ausrollen darf.
#     Es entstehen keine Schlüsseldateien.
set -uo pipefail

P="${1:?Projekt-ID angeben, z. B.: bash setup-firebase.sh green-ganja-grow-guardian}"
REGION=europe-west3
REPO_ID=1440933051 # GitHub-ID von Soulcancer/green-ganja-grow-guardian (bleibt auch bei Umbenennung gleich)
PKG=de.greenganja.growguardian
SA_NAME=github-deploy
SA="$SA_NAME@$P.iam.gserviceaccount.com"

ok() { echo "✔ $*"; }
warn() { echo "⚠ $*"; }
die() { echo "✘ $*"; exit 1; }

gcloud config set project "$P" >/dev/null 2>&1
NUM=$(gcloud projects describe "$P" --format='value(projectNumber)' 2>/dev/null) ||
  die "Projekt $P nicht gefunden. Zuerst in der Firebase-Konsole anlegen und die Projekt-ID genau so angeben."
ok "Projekt $P ($NUM)"
TOKEN=$(gcloud auth print-access-token)
api() { curl -sS -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $P" -H "Content-Type: application/json" "$@"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# 1. APIs
gcloud services enable firebase.googleapis.com firestore.googleapis.com firebaserules.googleapis.com \
  identitytoolkit.googleapis.com fcm.googleapis.com iam.googleapis.com iamcredentials.googleapis.com \
  sts.googleapis.com cloudresourcemanager.googleapis.com serviceusage.googleapis.com ||
  die "APIs ließen sich nicht einschalten"
ok "Grund-APIs aktiv"
BLAZE=0
if gcloud services enable cloudfunctions.googleapis.com run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com eventarc.googleapis.com pubsub.googleapis.com cloudscheduler.googleapis.com \
  secretmanager.googleapis.com >/dev/null 2>&1; then
  BLAZE=1
  ok "Functions-APIs aktiv (Blaze-Tarif)"
else
  warn "Functions-APIs nicht aktiv: Blaze-Tarif fehlt. Anmelden und Datenbank gehen trotzdem; Functions später."
fi

# 2. Firestore
if gcloud firestore databases describe --database='(default)' >/dev/null 2>&1; then
  ok "Firestore-Datenbank gibt es schon"
else
  gcloud firestore databases create --database='(default)' --location="$REGION" --type=firestore-native >/dev/null &&
    ok "Firestore-Datenbank angelegt ($REGION)" || warn "Firestore-Datenbank ließ sich nicht anlegen"
fi

# 3. E-Mail/Passwort-Anmeldung
r=$(api -X PATCH "https://identitytoolkit.googleapis.com/admin/v2/projects/$P/config?updateMask=signIn.email.enabled,signIn.email.passwordRequired" \
  -d '{"signIn":{"email":{"enabled":true,"passwordRequired":true}}}')
if echo "$r" | grep -q '"error"'; then
  warn "E-Mail-Anmeldung bitte von Hand einschalten: Firebase-Konsole → Authentication → Jetzt starten → E-Mail/Passwort"
else
  ok "E-Mail/Passwort-Anmeldung aktiv"
fi

# 4. Android-App
find_app() {
  api "https://firebase.googleapis.com/v1beta1/projects/$P/androidApps" |
    json "next((a['appId'] for a in d.get('apps', []) if a.get('packageName') == '$PKG'), '')"
}
APP=$(find_app)
if [ -z "$APP" ]; then
  api -X POST "https://firebase.googleapis.com/v1beta1/projects/$P/androidApps" \
    -d "{\"packageName\":\"$PKG\",\"displayName\":\"Grow Guardian\"}" >/dev/null
  for _ in $(seq 1 12); do
    sleep 5
    APP=$(find_app)
    [ -n "$APP" ] && break
  done
fi
[ -n "$APP" ] && ok "Android-App $APP" || warn "Android-App ließ sich nicht anlegen"

# 5. Dienstkonto für GitHub Actions
gcloud iam service-accounts describe "$SA" >/dev/null 2>&1 ||
  gcloud iam service-accounts create "$SA_NAME" --display-name="GitHub Deploy (Grow Guardian)" >/dev/null
for role in roles/firebase.admin roles/datastore.indexAdmin roles/firebaserules.admin roles/serviceusage.serviceUsageAdmin \
  roles/cloudfunctions.admin roles/run.admin roles/iam.serviceAccountUser roles/secretmanager.admin \
  roles/cloudscheduler.admin roles/artifactregistry.admin roles/eventarc.admin roles/pubsub.admin \
  roles/cloudbuild.builds.editor; do
  gcloud projects add-iam-policy-binding "$P" --member="serviceAccount:$SA" --role="$role" --condition=None >/dev/null ||
    warn "Rolle $role fehlt"
done
ok "Dienstkonto $SA"

# 6. Schlüssellose Anmeldung für genau dieses GitHub-Repository
gcloud iam workload-identity-pools describe github --location=global >/dev/null 2>&1 ||
  gcloud iam workload-identity-pools create github --location=global --display-name="GitHub" >/dev/null
gcloud iam workload-identity-pools providers describe github --location=global --workload-identity-pool=github >/dev/null 2>&1 ||
  gcloud iam workload-identity-pools providers create-oidc github --location=global --workload-identity-pool=github \
    --display-name="GitHub Actions" --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id" \
    --attribute-condition="assertion.repository_id=='$REPO_ID'" >/dev/null
gcloud iam service-accounts add-iam-policy-binding "$SA" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$NUM/locations/global/workloadIdentityPools/github/attribute.repository_id/$REPO_ID" \
  >/dev/null && ok "GitHub Actions darf ausrollen (nur dieses Repository)"

echo
echo "Fertig. Diese Zeile an Claude schicken:"
echo
echo "GGG-FIREBASE projekt=$P nummer=$NUM blaze=$BLAZE app=$APP"
