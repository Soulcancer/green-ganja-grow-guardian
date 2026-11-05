# Release-Checkliste: Green Ganja Grow Guardian

Was ihr in euren Konten erledigen müsst, in dieser Reihenfolge. Alles, was ohne Konto geht, liegt im Projekt bereit.
Geschätzter Aufwand bis zum internen Test: 1–2 Tage; bis zur Freigabe in Google Play zusätzlich die Prüfzeit
(neue Entwickler-Konten: mindestens 14 Tage geschlossener Test mit 12 Testern, siehe Schritt 7).

## 0. Wichtig vorab: zwei App-Fassungen

Google Play verbietet Apps, die „bei der Abholung oder Lieferung von Marihuana helfen“, auch wo es legal ist
([Richtlinie](https://support.google.com/googleplay/android-developer/answer/9878810)). Deshalb baut das Projekt zwei Fassungen:

| Fassung | Inhalt | Verteilung |
|---|---|---|
| `play` | Grow-Monitoring, Automationen, Alarme, Club-Mitglieder, Club-Boxen, Zubehör-Marktplatz, Coins, Werbung | Google Play |
| `direct` | zusätzlich Reservierungen, Abgabe-Doku (§ 26 KCanG), Samen/Stecklinge als Club-Angebot | direkt an Clubs (APK, eigene Website) |

Auch die `play`-Fassung kann wegen des Themas Cannabis genauer geprüft werden. Beschreibt sie im Store klar als
Werkzeug für legalen Eigenanbau ohne Verkauf.

## 1. Firebase

1. Projekt anlegen (Standort Firestore: **europe-west3**), **Blaze-Tarif** aktivieren.
2. Authentication → Anmeldemethode **E-Mail/Passwort** aktivieren. Cloud Messaging ist automatisch aktiv (für Push-Alarme). Unter *Vorlagen* die Mails (Passwort zurücksetzen) auf Deutsch stellen.
3. Android-App hinzufügen: Paket `de.greenganja.growguardian` (und für Debug `de.greenganja.growguardian.debug`), SHA-256 des Upload- und des Play-App-Signing-Schlüssels eintragen. `google-services.json` nach `app/` legen.
4. Backend: `backend/functions/.env` aus `.env.example`, Secrets setzen (README Abschnitt 8), dann `backend/deploy.sh <projekt-id>`.
5. Firestore → TTL-Richtlinien: `expireAt` für `dispensations`, `reservations`, `history`, `stats`, `listings`, `propagationRequests`, `propagationLog`, `ledger`, `adRewards`, `modLog`, `announcements`.
6. Empfohlen vor dem Start: **App Check** mit Play Integrity aktivieren und in den Functions `enforceAppCheck: true` setzen.

## 2. RevenueCat und Google-Play-Abos

README Abschnitt 9: Abos `premium_farmer` und `social_club_farmer` mit Basisplänen `monthly`/`yearly`, Entitlements,
Offerings, Webhook mit HMAC. Den `goog_…`-Key in `local.properties` als `revenuecat.key.release`.

## 3. Tuya

App in der Tuya IoT Platform: Paketname und SHA-256 eintragen, AppKey/AppSecret in `local.properties`,
`security-algorithm.aar` nach `app/libs/`. Für Server-Überwachung, Push-Alarme und Automationen: Cloud-Projekt anlegen, mit der App verknüpfen,
`TUYA_CLIENT_ID`/`TUYA_CLIENT_SECRET` als Secrets setzen (README „Server-Überwachung“). Tarif mit ausreichendem API-Kontingent wählen.

## 4. AdMob

1. App anlegen (noch nicht veröffentlicht → später mit dem Play-Eintrag verknüpfen).
2. Anzeigenblöcke: **Banner** (adaptiv), **Belohnung** (Prämie: 10 Coins), **App-Start** (App Open). IDs in `local.properties`.
3. Beim Belohnungs-Block **Serverseitige Bestätigung** aktivieren, URL: die von `admobRewardCallback` (Ausgabe von `deploy.sh`).
4. **Datenschutz & Mitteilungen → DSGVO**: Einwilligungsmitteilung erstellen und veröffentlichen (sonst zeigt UMP nichts und in der EU kommen keine personalisierten Anzeigen).
5. **Blockierungseinstellungen**: Kategorien prüfen. Hinweis: Google-Ads-Werbekunden liefern bei Cannabis-Inhalten nicht aus („Publisher Restrictions: Recreational drugs“); erwartet geringe Füllraten.
6. `app-ads.txt` auf eurer Website (Entwickler-Website aus dem Play-Eintrag) hinterlegen.

## 5. Rechtstexte

1. `docs/datenschutz-entwurf.md` anpassen (Verantwortlicher, Kontakt) und **von einer Fachperson prüfen lassen**, dazu Impressum und Nutzungsbedingungen.
2. Öffentlich erreichbar hochladen und die URLs in `app/src/main/java/de/greenganja/growguardian/app/Legal.kt` eintragen, auch die Seite zum Konto-Löschen (Play verlangt eine Web-Möglichkeit).

## 5a. Alpha-APK über GitHub (ohne Android Studio)

1. Repository: `Soulcancer/green-ganja-grow-guardian` (privat).
2. Unter *Actions* läuft **Android Alpha** bei jedem Push auf `main` oder per „Run workflow“.
3. Nach jedem erfolgreichen Lauf auf `main` ersetzt der Workflow das Pre-Release **alpha**
   (*Releases → Alpha 0.1.0 (Build N)*) mit den APKs `…-direct.apk` und `…-play.apk`. Auf dem Handy herunterladen und
   installieren (Installation aus unbekannten Quellen erlauben). Zusätzlich liegen sie als Artefakt am Lauf.
   Scheitert ein Lauf, stehen die Gradle-Fehler als Annotationen am Lauf (lesbar auch über die API).
4. Ohne Secrets baut der Workflow trotzdem, aber mit Platzhaltern: Anmelden und Tuya-Geräte funktionieren dann nicht. Für eine nutzbare Alpha die Secrets `GOOGLE_SERVICES_JSON`, `TUYA_SECURITY_AAR_BASE64`, `TUYA_APPKEY`, `TUYA_APPSECRET` (und optional `REVENUECAT_KEY_DEBUG`) setzen.
5. Die Alpha ist Debug-signiert und zeigt Googles Test-Anzeigen; sie ist nicht für den Play Store gedacht.

## 5b. Firebase einrichten (Cloud Shell, ohne lokale Werkzeuge)

1. [Firebase-Konsole](https://console.firebase.google.com) → *Projekt erstellen*, Google Analytics aus. Die Projekt-ID merken.
2. Für Cloud Functions (Club, Coins, Server-Alarme): *Tarif ändern* → **Blaze**. Ohne Blaze gehen Anmelden und Datenbank, aber keine Functions.
3. [Cloud Shell](https://shell.cloud.google.com) öffnen, `backend/setup-firebase.sh` hochladen (⋮ → Hochladen) und starten:
   `bash setup-firebase.sh <projekt-id>`
   Das Skript legt Firestore (europe-west3), E-Mail-Anmeldung, die Android-App und eine schlüssellose Anmeldung für
   GitHub Actions an (nur für dieses Repository, keine Schlüsseldateien).
4. Die letzte Zeile (`GGG-FIREBASE projekt=… nummer=…`) an Claude schicken oder selbst in `backend/firebase-project.json`
   eintragen (`projectId`, `projectNumber`) und pushen. Alternativ als Repository-Variablen `FIREBASE_PROJECT_ID` und
   `GCP_PROJECT_NUMBER`; die haben Vorrang vor der Datei.
5. Danach holt **Android Alpha** die `google-services.json` selbst aus dem Projekt, und **Firebase Deploy**
   (*Actions → Firebase Deploy → Run workflow*) rollt Regeln, Indizes und Functions aus.
   Fehlende Secrets (RevenueCat, Tuya) legt der Workflow mit dem Platzhalter „nicht-gesetzt“ an; echte Werte als
   GitHub-Secrets mit gleichem Namen eintragen und den Workflow neu starten.

## 6. Bauen

1. `local.properties.example` → `local.properties` ausfüllen; Upload-Keystore erzeugen:
   `keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`
2. In Android Studio öffnen, Gradle-Sync. Versionen in `gradle/libs.versions.toml` auf den aktuellen Stand bringen.
3. Auf einem echten Gerät testen (Debug nutzt Test-Anzeigen und den RevenueCat-Test-Store).
4. `scripts/build-release.sh` → `app-play-release.aab` und `app-direct-release.apk`.

## 7. Google Play Console

1. Entwicklerkonto (Organisation empfohlen, D-U-N-S-Nummer nötig). **Neue private Konten** müssen vor der Produktion einen
   geschlossenen Test mit mindestens 12 Testern über 14 Tage durchführen.
2. App erstellen: Name, Standardsprache Deutsch, App, kostenlos (mit In-App-Käufen).
3. **App-Inhalte**:
   - Datenschutzerklärung (URL)
   - Werbung: „Enthält Werbung“ = Ja
   - App-Zugriff: Test-Zugangsdaten für die Prüfer angeben (E-Mail/Passwort eines Testkontos)
   - Einstufung (IARC-Fragebogen): Drogen-Bezug wahrheitsgemäß angeben → Einstufung ab 18 erwartet
   - Zielgruppe: **18 und älter**
   - Datensicherheit: siehe `docs/data-safety.md`
   - Werbe-ID: Ja (Werbung, Analysen durch AdMob)
   - Konto löschen: In-App-Weg (Konto → Konto löschen) und Web-URL angeben
   - Finanzfunktionen / Gesundheit: Nein
4. Store-Eintrag: Texte aus `docs/store-eintrag.md`, Icon 512×512, Feature-Grafik 1024×500, mindestens 2 Screenshots (Handy).
5. Testen → Interner Test: `.aab` hochladen, Tester eintragen, Kauf mit Lizenztestern prüfen.
6. Geschlossener Test (falls nötig) → Produktion: zuerst gestaffelt (z. B. 10 %).

## 8. Nach dem Start

- Crash-Berichte (Play Console → Android Vitals). Firebase Crashlytics ist nicht eingebaut; bei Bedarf ergänzen.
- RevenueCat-Dashboard: Käufe und Webhook-Zustellungen.
- AdMob: Richtlinienzentrum regelmäßig prüfen.
- Firestore-Kosten im Blick behalten (Verlauf schreibt alle 15 Minuten je Box).
