# Green Ganja Grow Guardian: Grow-Boxen, Abos, Social Clubs

Stand Oktober 2026: Tuya Smart App SDK (thingsmart 7.x), RevenueCat Android SDK 11.0.0, Firebase BoM 35.1.0, firebase-functions 7.4.0, firebase-admin 14.5.0.

## Überblick

| Gruppe | Kosten | Wer |
|---|---|---|
| **Freemium Farmer** | kostenlos | alle ohne Abo |
| **Premium Farmer** | Abo | eine Person |
| **Social Club Farmer** | Abo | **5 feste Personen**: der Käufer (Inhaber) plus 4 Team-Plätze, die er per Einladung vergibt |

| Funktion | Freemium | Premium | Social Club |
|---|---|---|---|
| Grow-Boxen | 1 | 3 | unbegrenzt |
| Sensoren | 5 | 30 | unbegrenzt |
| Verlauf | 7 Tage | 1 Jahr | unbegrenzt |
| Alarme per Push-Nachricht | – | ja | ja |

Die Werte sind Platzhalter (`FarmerTier.kt`, im Backend `rules/grow.ts` und `rules/history.ts`).

Ein Social Club hat außerdem **Mitglieder**: beliebig viele, kostenlos. Sie beantragen die Aufnahme mit dem Mitglieder-Code des Clubs, das Team gibt sie nach der Ausweiskontrolle frei. Danach können sie Abgabemengen zur Abholung im Club reservieren.

**Wer sieht was** (durchgesetzt in `firestore.rules`, geschrieben wird nur über die Cloud Functions):

| Daten | Inhaber und Team | Mitglied |
|---|---|---|
| Club, Mitglieder-Code, Team | ja | nein |
| Mitgliederliste mit Namen und Geburtsdatum | ja | nur der eigene Eintrag |
| Reservierungen | alle | nur die eigenen |
| Abgabe-Dokumentation (§ 26 KCanG) | ja | nein |

## Inhalt

| Pfad | Zweck |
|---|---|
| `app/…/app/` | App-Rahmen: `GrowGuardianApp`, `MainActivity` (Einwilligung, Start-Anzeige), Navigation, Anmeldung, Kaufseite, Konto (inkl. Löschen), Rechtstext-Links, Fassungen |
| `app/…/ads/` | Werbung: Einwilligung (UMP), AdMob-Start, Banner (fest unten + 2 im Inhalt), Start-Anzeige, Belohnungsvideos |
| `app/…/rewards/` | Coins: Wallet, tägliche Serie, Erfolge, Shop, Bildschirm |
| `docs/` | Release-Checkliste, Store-Texte, Datenschutz-Entwurf, Datensicherheit |
| `scripts/build-release.sh`, `backend/deploy.sh` | Release bauen, Backend ausrollen |
| `app/…/grow/` | Grow-Box-Logik: Messgrößen, Tuya-Datenpunkte, VPD, Zielwerte je Phase, Bewertung, Limits, Speicher |
| `app/…/grow/ui/` | Grow-Bildschirme in Compose: `GrowScreen` als Einstieg, Übersicht, Box-Details, Analyse, Assistent, Dialoge |
| `app/…/grow/history/` | Verlauf: Speicherformat, Laden, Tageswerte, Trend |
| `app/…/grow/alarm/` | Alarm-Regeln (entprellt, Erinnerung nach 6 Std.) und Benachrichtigungen |
| `app/…/grow/automation/` | Automationen: Regeln (Messwert, Zeitplan), Plan für Tuya-Szenen, Speichern, Abgleich nach Stufenwechsel |
| `app/…/grow/background/` | Hintergrund-Job alle 15 Minuten: Verlauf speichern, Alarme prüfen, Automationen abgleichen |
| `app/…/tuya/` | Tuya-Anmeldung über das Firebase-Konto, Live-Werte aller Geräte, Geräte koppeln |
| `app/…/ui/theme/` | `GrowGuardianTheme`: das dunkelgrüne App-Design für alle Bildschirme |
| `app/…/billing/` | RevenueCat: drei Stufen, Kauf, Upgrade, Restore, Feature-Limits |
| `app/…/club/` | Club-Verwaltung: Team-Plätze, Mitglieder, Reservierungen, Abgabe-Doku |
| `app/…/club/ui/` | Bildschirme in Jetpack Compose: `ClubScreen` als Einstieg, ViewModel, Dialoge, Club-Boxen, Mein Bereich, Marktplatz |
| `app/…/backend/Backend.kt` | Aufruf der Cloud Functions mit deutschen Fehlermeldungen |
| `app/…/account/TuyaAccountLink.kt` | Tuya-Login über das Firebase-Konto |
| `backend/functions/` | Cloud Functions (TypeScript) inkl. Tests |
| `backend/firestore.rules`, `firestore.indexes.json` | Leserechte, Indizes, automatische Löschfristen |

**Geprüft:** Der Kotlin-Code ist gegen die API von RevenueCat 11.0.0 (aus dem Quellcode), die Compose-, Material-3-, Activity- und Lifecycle-Signaturen aus dem aktuellen AndroidX-Quellcode, WorkManager- und NotificationCompat-Signaturen aus dem AndroidX-Quellcode, die Tuya-Schnittstellen aus Tuyas offiziellem Beispielprojekt und nachgebildete Firebase-Schnittstellen kompiliert, 103 Unit-Tests laufen grün (u. a. VPD, Datenpunkt-Umrechnung, Limits, Kopplungs-Berechtigungen, Alarm-Regeln und Push-Szenen, Verlauf, Automationen, Club-Boxen, Marktplatz, Werbe-Regeln, Coins, Anmeldung). Das Backend ist mit TypeScript 6 und 7 gegen die Signaturen von firebase-functions 7.4.0 und firebase-admin 14.5.0 typgeprüft, 74 Tests (Server-Überwachung, KCanG-Grenzen, Codes, Webhook-Signatur, Grow-Box-Limits, Aufbewahrung des Verlaufs, Automationen, Club-Boxen, Mitglieder-Einstellungen, Marktplatz, Coins inkl. AdMob-Signaturprüfung) laufen grün. **Noch offen:** ein Gradle-Build mit echten Bibliotheken (hier nicht möglich, weil die Android-Repositories nicht erreichbar sind), ein Test mit echten Tuya-Geräten und gegen den Firebase-Emulator. Die Icon-Namen stammen aus `material-icons-extended` 1.7.8 und sind nicht gegen dessen Quellcode geprüft.

## 1. Anmeldung: Firebase, RevenueCat und Tuya koppeln

Firebase Auth ist der Login der App. RevenueCat und Tuya hängen an derselben User-ID:

```kotlin
// nach erfolgreichem Firebase-Login
val uid = FirebaseAuth.getInstance().currentUser!!.uid
app.subscriptions.logIn(uid)
// Tuya meldet TuyaSensorSource beim ersten Laden selbst an (UID-Login, siehe Abschnitt 4)

GrowMonitorWorker.start(context)   // Verlauf und Alarme im Hintergrund

// beim Logout
GrowMonitorWorker.stop(context)
app.subscriptions.logOut()
app.tuya.logOut()
```

Das Tuya-Passwort erzeugt das Backend einmal pro Nutzer zufällig; es ist nicht das Passwort des Nutzers. Wechselt auf einem Gerät der Firebase-Nutzer, meldet `TuyaSession` das alte Tuya-Konto vorher ab.

## 2. Abhängigkeiten der App

`gradle/libs.versions.toml`:

```toml
[versions]
revenuecat = "11.0.0"
firebaseBom = "35.1.0"
coroutines = "1.10.2"

[libraries]
revenuecat = { module = "com.revenuecat.purchases:purchases", version.ref = "revenuecat" }
firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebaseBom" }
firebase-auth = { module = "com.google.firebase:firebase-auth" }
firebase-firestore = { module = "com.google.firebase:firebase-firestore" }
firebase-functions = { module = "com.google.firebase:firebase-functions" }
coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
# Icons der Grow-Bildschirme; wird nicht mehr weiterentwickelt, 1.7.8 ist die letzte Version
compose-icons-extended = { module = "androidx.compose.material:material-icons-extended", version = "1.7.8" }
tuya-sdk = { module = "com.thingclips.smart:thingsmart", version = "7.11.0" }
# Geräte koppeln (Activator-Kit)
tuya-expansion = { module = "com.thingclips.smart:thingsmart-expansion-sdk", version = "7.11.1" }
# Hintergrund-Job für Verlauf und Alarme; 2.12 verlangt minSdk 24, 2.11.2 läuft ab minSdk 23
work-runtime = { module = "androidx.work:work-runtime", version = "2.11.2" }
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.revenuecat)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.coroutines.play.services)
    implementation(libs.compose.icons.extended)
    implementation(libs.tuya.sdk)
    implementation(libs.tuya.expansion)
    implementation(libs.work.runtime)       // enthält seit 2.9 auch CoroutineWorker
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))   // security-algorithm.aar von Tuya
}
```

Für Tuya zusätzlich: die Maven-Repos `https://maven-other.tuya.com/repository/maven-releases/` und `…/maven-commercial-releases/` in `settings.gradle.kts`, AppKey und AppSecret als Meta-Daten `THING_SMART_APPKEY` und `THING_SMART_SECRET` im Manifest, die `security-algorithm.aar` aus der Tuya-Plattform in `app/libs`, die ProGuard-Regeln aus der Tuya-Doku und `configurations.all { exclude(group = "com.thingclips.smart", module = "thingsmart-modularCampAnno") }`. Initialisieren im `Application.onCreate()` mit `ThingHomeSdk.init(this)`. Paketname und SHA256-Fingerprints müssen auf der Tuya-Plattform eingetragen sein.

Dazu das Google-Services-Plugin und die `google-services.json`; beides nennt euch die Firebase-Konsole beim Hinzufügen der Android-App. Das komplette Gradle-Projekt (Wrapper, `settings.gradle.kts`, `app/build.gradle.kts`, Versionskatalog, Manifest, Ressourcen) liegt bei. Voraussetzungen: **compileSdk/targetSdk 36**, **Kotlin 2.2**, **minSdk 26**. Manifest und ProGuard-Regeln liegen bei.

## 3. RevenueCat in der App

API-Keys in `local.properties` (nicht ins Git):

```properties
revenuecat.key.debug=test_XXXXXXXX
revenuecat.key.release=goog_XXXXXXXX
```

`app/build.gradle.kts`:

```kotlin
import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun rcKey(name: String) = "\"${localProps.getProperty(name, "")}\""

android {
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { buildConfigField("String", "REVENUECAT_API_KEY", rcKey("revenuecat.key.debug")) }
        release { buildConfigField("String", "REVENUECAT_API_KEY", rcKey("revenuecat.key.release")) }
    }
}
```

Mit einem **Test-Store-Key** (`test_…`) funktionieren Käufe im Debug-Build ohne Play-Console-Setup. In einem Release-Build zeigt das SDK bei diesem Key eine Fehlermeldung und beendet die App absichtlich, dort muss der `goog_…`-Key stehen.

```kotlin
class GrowGuardianApp : Application() {
    val subscriptions = SubscriptionManager()
    val clubs by lazy { ClubRepository(subscriptions) }

    override fun onCreate() {
        super.onCreate()
        subscriptions.configure(
            context = this,
            apiKey = BuildConfig.REVENUECAT_API_KEY,
            appUserId = FirebaseAuth.getInstance().currentUser?.uid,
            debugLogs = BuildConfig.DEBUG,
        )
    }
}
```

Feature-Gates und Kauf:

```kotlin
val sub by app.subscriptions.state.collectAsStateWithLifecycle()

if (sub.features.automations) AutomationsScreen() else UpgradeHint(FarmerTier.PREMIUM)
if (sub.isClubSeat) Text("Zugang über deinen Club") // kein Kauf- oder Verwalten-Button

scope.launch {
    val plans = app.subscriptions.loadPlans().getOrElse { showError(); return@launch }
    val clubYearly = plans.firstOrNull { it.tier == FarmerTier.SOCIAL_CLUB && it.billingPeriod?.unit == Period.Unit.YEAR }
        ?: return@launch
    when (val result = app.subscriptions.purchase(activity, clubYearly)) {
        is PurchaseOutcome.Success -> showWelcome(result.tier)
        PurchaseOutcome.Cancelled -> Unit
        PurchaseOutcome.Pending -> showInfo("Zahlung ausstehend, der Zugang folgt automatisch.")
        PurchaseOutcome.AlreadyOwned -> showInfo("Dieses Abo ist schon aktiv.")
        PurchaseOutcome.NotAllowed -> showInfo("Käufe sind auf diesem Gerät gesperrt.")
        PurchaseOutcome.NetworkError -> showInfo("Keine Verbindung.")
        is PurchaseOutcome.Failed -> showError(result.message)
    }
}
```

Wechselt jemand von Premium zu Social Club (oder von monatlich zu jährlich), ersetzt `purchase()` das laufende Google-Play-Abo und rechnet die Restlaufzeit an.

## 4. Grow-Box-Überwachung

```kotlin
class GrowGuardianApp : Application(), GrowMonitorHost {
    val subscriptions = SubscriptionManager()
    val tuya by lazy { TuyaSession(this) }
    val sensors by lazy { TuyaSensorSource(tuya) }
    val growBoxes by lazy { GrowBoxRepository() }
    val growHistory by lazy { GrowHistoryRepository() }
    val pairing by lazy { TuyaPairing(tuya) }
    val growAutomations by lazy { GrowAutomations(this, AutomationRepository(), TuyaAutomationSync(tuya)) }
    val clubBoxes by lazy { ClubBoxRepository() }
    val market by lazy { MarketRepository() }
    override val growMonitor by lazy {
        GrowMonitor(
            this, growBoxes, sensors, growHistory, subscriptions,
            automations = growAutomations,
            clubBoxes = clubBoxes,
            alarmPush = TuyaAlarmPush(tuya), // ohne eigene PushTaskFactory bleibt es beim Handy-Alarm
        )
    }
    // onCreate: ThingHomeSdk.init(this) und subscriptions.configure(...) wie oben
}

// Activity
setContent { GrowGuardianTheme { AppNavigation() } }

// Navigation, erst nach dem Login
composable("grow") {
    GrowScreen(
        repository = app.growBoxes,
        sensors = app.sensors,
        subscriptions = app.subscriptions,
        pairing = app.pairing,
        onUpgradeClick = { navController.navigate("paywall") },
        history = app.growHistory,
        automations = app.growAutomations,
    )
}
```

**Was die Bildschirme zeigen** (im Design eures Entwurfs: dunkles Grün, grüne Buttons, Status-Chips):

| Bildschirm | Inhalt |
|---|---|
| Übersicht | Boxen x / Limit mit Fortschrittsbalken und Stufe, Upgrade-Zeile mit Krone, Box-Karten mit Status-Chip (Im Ziel, Prüfen, Offline) und Temperatur · Luftfeuchte, gesperrte Boxen nach einem Downgrade |
| Box-Details | Kacheln für Temperatur, Luftfeuchte und VPD, alle Messwerte mit Zielbereich und Bewertung, Sensoren mit Online-Status, Zielwerte, Box löschen |
| Neue Box | Assistent in 3 Schritten: Name und Medium, Wachstumsphase mit Vorschau der Startwerte, Blatttemperatur für den VPD |
| Analyse | Reiter je Messgröße, Zeitraum 24 Std. / 7 Tage / 30 Tage / 1 Jahr (über dem Limit der Stufe mit Schloss), Liniendiagramm mit Zielband, Min./Ø/Max., Trend aller Messgrößen |
| Alarme | Schalter in jeder Box; ohne Premium führt er zum Upgrade, ab Android 13 fragt er nach der Benachrichtigungs-Berechtigung |
| Automationen | Regelliste je Box mit Schalter und Status (Fehler, Pausiert), Editor für Zeitplan (Licht 18/6, 12/12, 20/4, 16/8, Uhrzeiten, Wochentage) und Messwert (Sensor, Messgröße, Ein- und Ausschaltpunkt), Auswahl der Steckdose oder Dose; ohne Premium ein Upgrade-Hinweis |
| Gerät koppeln | Bluetooth-Suche (reine Bluetooth- und Kombi-Geräte), WLAN-Gerät (blinkt schnell = EZ, blinkt langsam = Hotspot/AP) oder Zigbee-Sensor über ein Gateway; danach erscheint das Gerät von selbst in der Übersicht |
| Dialoge | Sensoren zuordnen (ein Sensor gehört zu genau einer Box), Zielwerte anpassen, Löschen |

**Messwerte:** `TuyaSensorSource` lädt alle Geräte aller Tuya-Familien des Kontos, liest Skalierung und Einheit aus dem Datenpunkt-Schema und hört per MQTT auf Updates und Online-Status. `DpMapping` erkennt die Standard-Codes für Klima-, CO₂-, Boden- und Wassersensoren sowie Steckdosen mit Strommessung, rechnet °F in °C und µS/cm in mS/cm um und verwirft Fehlwerte (z. B. pH 15 bei abgezogener Sonde). Geräte mit eigenen Codes ergänzt ihr über `DpMapping.overrides["<productId>"] = mapOf("<dp-code>" to Metric.AIR_TEMPERATURE)`. Mehrere Sensoren einer Box werden gemittelt, der VPD wird aus Temperatur und Luftfeuchte mit dem Blatt-Offset der Box berechnet.

**Zielwerte:** Startwerte je Phase und Medium in `DefaultTargets` (z. B. Blüte 20–26 °C, 40–55 %, VPD 1,0–1,5 kPa; pH Erde 6,0–7,0, Kokos 5,8–6,3, Hydro 5,5–6,2). Jede Box kann sie überschreiben. Die Werte sind übliche Richtwerte, keine Garantie.

**Limits:** Freemium 1 Box und 5 Sensoren, Premium 3 Boxen und 30 Sensoren, Social Club unbegrenzt (`FarmerTier.kt`, im Backend `rules/grow.ts`). Gespeichert wird nur über die Cloud Functions `saveGrowBox` und `deleteGrowBox`; sie fragen RevenueCat nach der Stufe, sobald eine Änderung über Freemium hinausgeht. Nach einem Downgrade bleiben alle Boxen gespeichert, überwacht werden aber nur die ältesten bis zum Limit und darin die ersten Sensoren bis zum Sensor-Limit; der Rest wird gesperrt angezeigt.

Die Firestore-Daten liegen unter `users/{uid}/growBoxes/{boxId}` und sind nur für den Nutzer selbst lesbar. Live-Werte speichert die App nicht, sie kommen direkt von Tuya.

**Geräte koppeln:** `TuyaPairing` nutzt Tuyas Activator-Kit. Gekoppelt wird in die erste Tuya-Familie des Kontos; das Gerätelimit der Stufe zählt erst beim Zuordnen zu einer Box. Die App fragt die nötigen Berechtigungen selbst an: ab Android 12 `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` und `ACCESS_FINE_LOCATION`, darunter und für WLAN nur `ACCESS_FINE_LOCATION`. Im Manifest müssen sie stehen (das Tuya-SDK bringt die meisten mit, prüft das zusammengeführte Manifest):

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

Tuya-Geräte koppeln nur in 2,4-GHz-Netzen. Das WLAN-Passwort geht nur an das Gerät und wird nirgends gespeichert.

### Automationen

Premium und Social Club können Steckdosen und Schaltleisten automatisch schalten, zum Beispiel „Entfeuchter an über 60 %, aus unter 55 %“ oder „Licht 18/6 ab 06:00“. Die Regeln laufen **in der Tuya-Cloud** und schalten damit auch bei ausgeschaltetem Handy.

- **Wie es funktioniert:** Jede Regel wird zu zwei Tuya-Automationen (einschalten, ausschalten), benannt `[GG] <Name> · an/aus`. Messwert-Regeln vergleichen den Rohwert des Sensor-Datenpunkts (z. B. 23,5 °C bei Skalierung 1 = 235). Der Abstand zwischen Ein- und Ausschaltpunkt ist die Hysterese; ohne ihn würde das Gerät ständig takten. Zeitpläne über Mitternacht (06:00 bis 00:00) schalten am Folgetag aus; das berücksichtigt die Wochentagsauswahl.
- **Messgrößen:** Temperatur, Luftfeuchte, CO₂, Bodenfeuchte, Boden- und Wassertemperatur. Berechnete Werte wie der VPD sind in der Tuya-Cloud nicht prüfbar. Den VPD steuert man über Temperatur und Luftfeuchte.
- **Limits:** Freemium 0, Premium 20 Regeln, Social Club unbegrenzt (200 als Obergrenze gegen Missbrauch; `FarmerTier.maxAutomations`, im Backend `rules/automation.ts`). Gespeichert wird nur über die Cloud Functions `saveAutomation` und `deleteAutomation`, die die Stufe bei RevenueCat prüfen und die Werte validieren. Die App trägt danach nur die IDs der Tuya-Automationen ein (die Firestore-Regeln erlauben nur `sceneIds`, `syncError`, `syncedAt`).
- **Stufenwechsel:** Läuft Premium aus, pausiert der Hintergrund-Job alle Regeln (die Tuya-Automationen werden deaktiviert, die Regeln bleiben gespeichert). Mit dem nächsten Upgrade laufen sie wieder, ausgeschaltete bleiben aus.
- **Wenn Tuya nicht erreichbar ist:** Die Regel wird trotzdem gespeichert und zeigt „Fehler“ bzw. „Wird übertragen“. Der Hintergrund-Job versucht es erneut. Einmal am Tag entfernt er Automationen mit dem Präfix `[GG]`, die zu keiner Regel mehr gehören (z. B. nach einem Abbruch beim Löschen).
- **Box löschen:** Die App entfernt zuerst die Tuya-Automationen der Box, dann löscht das Backend Box und Regeln.
- **Voraussetzung:** Der Sensor muss online sein und seine Werte an die Tuya-Cloud melden. Geräte, die nur lokal (Bluetooth) verbunden sind, lösen keine Cloud-Automation aus.
- **Steckdosen:** Als schaltbar gelten Bool-Datenpunkte mit dem Code `switch` oder `switch_<n>` (Steckdosenleisten zeigen „Dose n“). Eigene Codes erkennt `DpMapping.isSwitchCode` nicht.

### Mehrere Pflanzen je Box

Jede Box (jedes Zelt) hat beliebig viele Pflanzen, höchstens 50 (`grow/plants/Plant.kt`, `users/{uid}/growBoxes/{boxId}/plants`): Name, Sorte, Samen oder Steckling, Automatik ja/nein, Starttag, Blütebeginn, Notiz; „Ernten“ setzt Erntetag und Trockengewicht, die Pflanze wandert zu „Geerntet“. Box, Sensoren, Zielwerte und Phase teilen sich alle Pflanzen der Box. Tagebuch-Einträge lassen sich einer Pflanze oder der ganzen Box zuordnen (`plantId`). Ohne Social Club zeigt die App ab der vierten wachsenden Pflanze einen Hinweis auf § 9 KCanG (privat 3 Pflanzen je volljähriger Person). Alte Boxen mit Sorte und Starttag direkt an der Box werden beim ersten Öffnen als „Pflanze 1“ übernommen.

### Startseite, E-Mail-Bestätigung und Einführungen

- **Startseite** (`app/WelcomeScreen.kt`): ohne Login erst Erklärung, Stufen und „Wichtige Hinweise“ (18+, KCanG, keine Weitergabe, keine Rechts- oder Medizinberatung); weiter nur mit beiden Bestätigungen, dann Registrieren oder Anmelden. Version der Hinweise in `WelcomeContent.DISCLAIMER_VERSION`.
- **E-Mail-Bestätigung** (`app/VerifyEmailScreen.kt`): Nach der Registrierung geht eine Bestätigungs-Mail (Deutsch) raus; ohne bestätigte Adresse kommt niemand in die App. Der Bildschirm prüft alle 5 s, bietet „Ich habe bestätigt“, „Mail erneut senden“ (60 s Sperre) und Abmelden. Danach holt die App ein frisches ID-Token. Durchgesetzt auch im Backend: `requireUid` verlangt `email_verified` (Ausnahme: `deleteMyAccount`), die Firestore-Regeln ebenso (`signedIn()`).
- **App-Einführung** (`grow/ui/AppWizard.kt`): beim ersten Start; führt durch die ganze App außer dem Club: Box anlegen, Pflanzen, Licht, Sensoren, Zielwerte, Alarme (mit echten Einstellungen), dann Analyse, Coins und Konto. Nach dem Anlegen einer Box startet derselbe Assistent nur mit den Box-Schritten; in der Box zeigt „Einrichtung“, wie viele Schritte erledigt sind.
- **Club-Einführung** (`club/ui/ClubWizard.kt`): eigene Einführung je Rolle (ohne Club, Antrag offen, Mitglied, Team, Inhaber), beim ersten Öffnen des Club-Bereichs; in der Play-Fassung ohne Reservierung und Abgabe.
- Beide lassen sich unter Konto neu starten. **In den Einführungen sehen alle Stufen Banner**, auch Social Club und „werbefrei“ (`AdPolicy.showInWizard`, nur mit Werbe-Einwilligung).

### Boxen und Pflanzen löschen

- **Box löschen:** in der Übersicht über ⋮ an jeder Box (auch an gesperrten) oder in der Box-Ansicht über den Papierkorb oben. `deleteGrowBox` löscht die Box mit Verlauf, Statistik, Tagebuch und Lichtmessungen (`recursiveDelete`), ihre Automationen und für den Club geteilte Ansichten der Box. Die Sensoren bleiben im Konto; nach dem Löschen einer aktiven Box rückt eine gesperrte nach.
- **Pflanze löschen:** an der Pflanze in der Box-Ansicht; auf Wunsch mit ihren Tagebuch-Einträgen, und bei der letzten wachsenden Pflanze die Box-Phase zurück auf „Keimling / Steckling“. Nach der Ernte besser „Ernten“, dann bleibt die Pflanze mit Ertrag erhalten.

### Grow-Tagebuch

Je Box: Sorte und Starttag (daraus „Tag 30 · Woche 5, Tag 2“), dazu Einträge mit gegossen, Wassermenge, Dünger, von Hand gemessenen Werten (z. B. pH aus dem Tropfentest) und Notizen. Für alle Stufen. Gespeichert unter `users/{uid}/growBoxes/{boxId}/journal`; die App schreibt direkt, die Firestore-Regeln prüfen das Format. Die Einträge sind in „Meine Daten herunterladen“ enthalten und werden beim Löschen der Box oder des Kontos mitgelöscht.

### Lichtmodul (Licht & Spektrum)

Je Box unter „Licht & Spektrum“ in der Box-Ansicht (`grow/light/`, `grow/ui/LightScreen.kt`):

- **Lampen eintragen:** 28 Lampenarten in sechs Gruppen (Tageslicht, Glühlampen, LED, HID, Leuchtstoff, Sonstige), bis zu 6 je Box mit Leistung und Anzahl, dazu Fläche, Dimmer, Lichtstunden. Gespeichert im Box-Dokument unter `light` über die Function `saveLightSetup`.
- **Stufen:** Freemium wählt nur **Tageslicht** und **Glühbirne/Halogen**, Premium und Social Club alle Lampen (`TierFeatures.allLampTypes`). Die Function prüft das gegen RevenueCat; die Firestore-Regeln lassen Messungen nur mit freien Lampen, den gespeicherten Lampen der Box oder mit Premium-Stufe (`tierCache`) zu.
- **Messen:** Handy-Lichtsensor (Median, Warnung bei Sensor am Anschlag), Tuya-Helligkeitssensor (`bright_value`, Kategorie `ldcg`, neue Messgröße `light` in lx), Lux oder PPFD von Hand. Lux → PPFD mit dem Faktor der Lampe, bei Mischungen nach Photonen gewichtet (`LightMath.ppfdPerKlx`). Mehrere Messpunkte ergeben Mittel, Min/Max und Gleichmäßigkeit. Messungen unter `users/{uid}/growBoxes/{boxId}/lightReadings`.
- **Auswertung:** PPFD und DLI gegen die Phase (mit CO₂ ab 800 ppm höhere Obergrenze), Dimmer-Empfehlung, Photonen je Band (UV-A, Blau, Grün, Rot, Dunkelrot) in % und µmol/m²/s, Blauanteil, Rot : Dunkelrot, Hinweise; Lampenrechner (nötige Watt für das Ziel) und Handy-Kalibrierung gegen ein PAR-Meter.
- **Analyse und Verlauf:** Reiter „Licht“ (immer da) mit einem Bogen für die Tageslichtmenge (DLI) gegen das Ziel der Phase. Mit Tuya-Lichtsensor: Lux-Verlauf in PPFD umgerechnet, DLI der letzten 24 Stunden per Trapezregel (Lücken über 1 Stunde zählen nicht, Hinweis bei unvollständigem Tag); über 30 Tage und 1 Jahr DLI je Tag aus dem Tagesmittel. Ohne Sensor: die gespeicherten Einzelmessungen und deren DLI (`grow/light/LightHistory.kt`, `grow/ui/LightHistoryViews.kt`).
- **Richtwerte:** Faktoren nach Thimijan & Heins (1983) und Apogee (Sonne 18,5, NDL 12,2, DE-NDL 13,0, MH 14,1, CMH 4200 K 15,4, CMH 3100 K 17,0, Leuchtstoff 13,5, Glühlampe 20,0 µmol/m²/s je klx), weiße LED nach CRI (80: ≈ 14,3; 90: ≈ 15,9). Spektren und Effizienzen sind typische Werte, keine Messungen eurer Lampen. Schmalbandige LEDs (Rot/Blau, 660 nm, 450 nm) misst Lux nur grob, UV und Far-Red gar nicht; dafür PPFD von Hand eintragen.

### Server-Überwachung über die Tuya Cloud (empfohlen)

Die Cloud Function `serverMonitor` (`serverMonitor.ts`, Regeln in `rules/monitor.ts`) läuft alle 5 Minuten, ganz ohne Handy:

1. Sie liest über die **Tuya Cloud OpenAPI** die Sensoren aller aktiven Boxen; Box- und Sensor-Limits der Stufe gelten wie in der App.
2. Sie schreibt den **Verlauf** im selben Format wie die App.
3. Sie prüft die **Alarme** mit denselben Regeln wie das Handy (zweimal hintereinander, deutlich sofort, Erinnerung nach 6 Std., „Sensor offline“) und schickt sie als **Push** (Firebase Cloud Messaging) an alle angemeldeten Geräte des Nutzers. Die App meldet ihre Push-Token unter `users/{uid}/pushTokens`.
4. Sie schaltet die **Automationen** (Zeitplan und Messwert mit Ein-/Ausschaltpunkt). Die App nutzt dafür `ServerAutomationEngine`; die Tuya-Szenen (`TuyaAutomationSync`) bleiben als Alternative im Code.

Solange der Server läuft (letzter erfolgreicher Lauf unter 20 Minuten alt), meldet der Hintergrund-Job auf dem Handy keine Alarme. Fällt der Server aus, übernimmt das Handy automatisch wieder.

**Sicherheit:** Bevor der Server ein Gerät liest oder schaltet, prüft er bei Tuya, dass es dem Nutzer gehört. Dazu ermittelt er den Tuya-Besitzer des Geräts und vergleicht dessen Benutzernamen mit der Firebase-UID (UID-Login). Das Ergebnis wird 24 Std. in `tuyaDevices/` gespeichert. Ohne diese Prüfung könnte jemand fremde Geräte-IDs in seine Box eintragen.

**Einrichten:**
1. Tuya IoT Platform → **Cloud → Development → Create Cloud Project**, Rechenzentrum *Central Europe*. Bei den API-Diensten mindestens *IoT Core* und *Authorization* aktivieren.
2. Das Cloud-Projekt mit eurer App-SDK-App verknüpfen, damit es die Geräte der App-Nutzer sieht (im Cloud-Projekt unter *Devices* → *Link App* bzw. *Link Tuya App Account*; die Bezeichnung hängt von der Plattform-Version ab).
3. `firebase functions:secrets:set TUYA_CLIENT_ID` und `TUYA_CLIENT_SECRET` (Access ID und Secret des Cloud-Projekts), in `functions/.env` `TUYA_BASE_URL` (Standard `https://openapi.tuyaeu.com`).
4. Deployen. In den Logs von `serverMonitor` sollten nach 5 Minuten keine Warnungen „Gerät gehört nicht zum Nutzer“ stehen.

**Zu prüfen mit echten Geräten:**
- Dass `GET /v1.0/users/{uid}/infos` bei UID-Login-Nutzern im Feld `username` die Firebase-UID liefert. Liefert es etwas anderes, nur `ownedDevice()` in `serverMonitor.ts` anpassen.
- Das **API-Kontingent** eures Tuya-Cloud-Tarifs. Pro Lauf fällt etwa ein Aufruf je Gerät an, alle 5 Minuten. Die Testversion ist zeitlich und mengenmäßig begrenzt; für den Betrieb braucht ihr einen passenden Tarif.

Die Idee stammt aus eurer `tuya-functions.js`. Übernommen sind der signierte Tuya-Client und das Schalten; angepasst ist alles ans Datenmodell der App: Boxen, RevenueCat-Stufen, Skalierung aus den Datenpunkt-Definitionen, Besitzprüfung, Verlauf, Alarme per Push. Die Datei selbst wird nicht gebraucht.

### Alarme per Push aus der Tuya-Cloud (Alternative)

Zusätzlich zum Handy-Alarm legt die App für jede Box mit Alarmen und jede Messgröße, die die Tuya-Cloud prüfen kann (Temperatur, Luftfeuchte, CO₂, Boden- und Wassertemperatur, Bodenfeuchte), eine Szene an: „Wert unter Minimum ODER über Maximum → Push-Benachrichtigung“. Tuya prüft bei jeder Sensormeldung und schickt den Push selbst, auch bei ausgeschaltetem Handy. Ändern sich Zielwerte oder Sensoren, ersetzt der Hintergrund-Job die Szene; ohne Premium oder mit ausgeschalteten Alarmen entfernt er sie. Was die Cloud überwacht, meldet das Handy nicht doppelt; „Sensor offline“, VPD, pH und EC bleiben beim Handy-Alarm.

Einbinden: `GrowMonitor(…, alarmPush = TuyaAlarmPush(tuya, pushTaskFactory))`. Mit der Server-Überwachung oben ist das nicht nötig; sie übernimmt Push-Alarme ohne diese offene Stelle.

**Noch offen, bevor das funktioniert:**
1. In der Tuya IoT Platform den Push-Dienst (Firebase Cloud Messaging) für eure App einrichten.
2. Die Aktion „Benachrichtigung senden“ einer Szene bauen: `PushTaskFactory` in `TuyaAlarmPush.kt`. Die öffentliche SDK-Doku nennt die passende Methode nicht; ich habe keine API erfunden. Bis eine echte Implementierung übergeben wird, wirft `PushTaskFactory.Unconfigured` einen Fehler, es wird keine Szene angelegt und der Handy-Alarm bleibt vollständig zuständig.
3. Mit echten Geräten prüfen, ob Tuya bei Werten außerhalb des Bereichs bei jeder Meldung oder nur beim Überschreiten pusht. Gegebenenfalls Ein- und Ausschaltpunkt mit Abstand wie bei den Automationen nutzen.

### Verlauf und Alarme

`GrowMonitorWorker` läuft etwa alle 15 Minuten, auch bei geschlossener App. Er liest die zuletzt an die Tuya-Cloud gemeldeten Werte, speichert je aktiver Box einen Verlaufspunkt und prüft die Alarme. Gesperrte Boxen und Sensoren über dem Limit bleiben außen vor.

- **Verlauf:** je Box und Tag ein Firestore-Dokument `history/{yyyy-MM-dd}` (UTC, 5-Minuten-Raster). Die App darf nur Messwerte schreiben. Die Löschfrist setzt die Function `onHistoryDayCreated` nach der Stufe: Freemium 7 Tage, Premium 365 Tage, Social Club unbegrenzt. Jede Nacht um 3:30 Uhr schreibt `nightlyGrowStats` Tageswerte (Min., Mittel, Max.) in `stats/{Jahr}`, damit die 30-Tage- und Jahresansicht ein Dokument pro Jahr laden statt hunderter Tage. Ohne Netz puffert Firestore die Werte und überträgt sie später.
- **Alarme:** gemeldet wird ein Wert außerhalb des Zielbereichs oder ein Sensor offline, und zwar erst, wenn das bei zwei Prüfungen hintereinander so ist. So lösen kurze Ausreißer keinen Alarm aus. Deutliche Abweichungen (mehr als ein Viertel der Zielspanne) meldet die App sofort. Besteht ein Problem weiter, kommt nach 6 Stunden eine Erinnerung. Ein Wert gilt erst wieder als gut, wenn er etwas Abstand zur Grenze hat; damit springt der Alarm an der Grenze nicht ständig an und aus. Eine Benachrichtigung je Box mit allen aktuellen Problemen; ist alles wieder gut, verschwindet sie.
- **Manifest:** `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`. Für die Statusleiste ein einfarbiges Icon `res/drawable/ic_stat_grow.xml` (weißes Blatt, transparenter Hintergrund) anlegen; ohne nimmt die App ihr Launcher-Icon.
- Tippt man auf die Benachrichtigung, startet die App mit dem Extra `GrowNotifier.EXTRA_BOX_ID`; damit könnt ihr direkt die Box öffnen.

## 5. Club-Funktionen in der App

Alle Aktionen liefern `Result`. Bei Fehlern ist es eine `BackendException` mit deutscher `message` für die UI und einem `reason` wie `"club-full"` oder `"monthly-limit"`.

```kotlin
val clubs = app.clubs
val membership by clubs.membership().collectAsStateWithLifecycle(null)

// Inhaber (nach dem Kauf von Social Club)
val memberCode = clubs.createClub("Grüner Daumen e. V.").getOrThrow()   // Code für Mitgliedsanträge
val invite = clubs.createStaffInvite().getOrThrow()                       // Code für einen Team-Platz, 7 Tage gültig
clubs.removeStaff(uid)                                                    // Platz wieder freigeben

// Jede Person: Code einlösen (Team-Einladung oder Mitglieder-Code)
clubs.joinClub("ABCD-EFGH")

// Team: Mitglieder nach Ausweiskontrolle freigeben, auch das eigene Profil
clubs.members(clubId)                         // Flow, offene Anträge zuerst
clubs.approveMember(uid, "Anna", "Beispiel", "1998-04-12")

// Mitglied: Menge zur Abholung im Club vormerken
val remaining = clubs.requestReservation(grams = 10.0, pickupDate = "2026-11-03", strain = "Lemon Haze").getOrThrow()
clubs.myReservations(clubId)                  // Flow mit Status

// Team: bestätigen, ablehnen, Ausgabe dokumentieren
clubs.openReservations(clubId)
clubs.confirmReservation(reservationId)
clubs.recordHandout(grams = 10.0, thcPercent = 16.5, reservationId = reservationId)
clubs.recordHandout(grams = 5.0, thcPercent = 9.0, memberUid = uid)     // ohne Reservierung
val csv = clubs.exportDispensations(2026).getOrThrow()                  // für die Behörde
```

Wird jemandem ein Team-Platz gegeben oder entzogen, lädt die App den Abo-Status automatisch neu.

## 6. Club-Bildschirme

`ClubScreen` ist der Einstieg und zeigt je nach Rolle den passenden Bereich. Einfach in eure Navigation hängen, **erst nach dem Login**:

```kotlin
composable("club") {
    ClubScreen(
        clubs = app.clubs,
        subscriptions = app.subscriptions,
        onUpgradeClick = { navController.navigate("paywall") },
        clubBoxes = app.clubBoxes,                  // ClubBoxRepository()
        ownBoxes = { app.growBoxes.boxesOnce() },   // für „Eigene Box teilen“
        boxPrefs = SharedClubBoxPrefs(app),         // Anpinnen/Ausblenden, nur auf dem Gerät
        market = app.market,                        // MarketRepository()
    )
}
```

| Wer | Was er sieht |
|---|---|
| Ohne Club | Code einlösen (Mitglieder-Code oder Team-Einladung); mit Social-Club-Abo zusätzlich „Club gründen“, sonst Hinweis aufs Upgrade |
| Antrag offen | Hinweis auf die Freigabe nach Ausweiskontrolle, Antrag zurückziehen |
| Mitglied | Marktplatz, Club-Boxen (anpinnen, ausblenden), Reservierungsformular mit Abholtag (heute bis 30 Tage), eigene Reservierungen mit Status, ändern (solange unbestätigt) und stornieren, **Mein Bereich** (Anzeigename, Benachrichtigungen, alle eigenen Daten als JSON herunterladen) |
| Inhaber und Team | Reiter **Anfragen** (bestätigen, ablehnen, ausgeben, Ausgabe ohne Reservierung), **Mitglieder** (Code teilen oder erneuern, Anträge freigeben mit Ausweis-Häkchen, entfernen), **Doku** (Abgaben pro Monat, Jahres-CSV speichern), **Boxen** (eigene Box teilen, Teilen beenden), **Club** (Abo-Status, Team-Plätze vergeben und entziehen, eigene Reservierung); dazu der Marktplatz mit Anfragen nach Samen und Stecklingen und gemeldeten Inseraten |

**Farben:** Mitglieder, Antragsteller und Nutzer ohne Club sehen den Club-Bereich in Rot-Lila (`ClubMemberPalette`: Himbeer-Rot auf dunklem Aubergine), das Team im App-Grün. Umgeschaltet wird in `ClubAreaTheme`.

### Gemeinsame Club-Boxen

Ein Team-Mitglied teilt eine Box aus seinem Konto (`shareClubBox`, höchstens 10 je Club, nur mit aktivem Abo). Geteilt wird die Konfiguration (Name, Phase, Medium, Zielwerte); erneutes Teilen aktualisiert sie. Die Messwerte lädt der Hintergrund-Job auf dem Handy des Betreuers etwa alle 15 Minuten nach `clubs/{clubId}/growBoxes/{id}.latest` hoch; die Sicherheitsregeln erlauben dem Betreuer nur dieses Feld in festem Format. Jedes aktive Mitglied sieht Status, Temperatur, Luftfeuchte, VPD und wie aktuell die Werte sind; nach einer Stunde ohne Upload gilt die Box als offline. Sensoren, Automationen und Verlauf bleiben im Konto des Betreuers. Beenden darf der Betreuer oder der Inhaber (`unshareClubBox`).

Im Hintergrund-Job: `GrowMonitor(…, clubBoxes = app.clubBoxes)`.

### Mehr Kontrolle für Mitglieder

- **Anzeigename:** Andere Mitglieder (Marktplatz) sehen den gewählten Namen, sonst Vorname und Initiale. Der volle Name steht nur dort, wo das Gesetz ihn verlangt (Abgabe-Doku, Team).
- **Benachrichtigungen:** Reservierungen, Club-Boxen und Marktplatz einzeln (`members/{uid}.notify`). Die Einstellungen sind gespeichert; ausgewertet werden sie, sobald es Server-Pushes für diese Ereignisse gibt (siehe Offene Punkte).
- **Meine Daten:** `exportMyData` liefert alles über das Mitglied als JSON: Mitgliedschaft, Mitglieds-Eintrag, Reservierungen, dokumentierte Abgaben, Inserate, eigene Boxen und Automationen (Auskunft nach Art. 15 DSGVO). Der Messwert-Verlauf ist ausgenommen.
- **Reservierung ändern:** `updateReservation`, solange das Team noch nicht bestätigt hat; Tages- und Monatsgrenzen werden ohne die alte Fassung neu geprüft.
- **Club-Boxen:** anpinnen und ausblenden, nur auf dem eigenen Gerät.

### Marktplatz

| Was | Wer | Regeln |
|---|---|---|
| Zubehör (Biete, Suche, Tausch, Verschenken) | jedes aktive Mitglied, höchstens 10 offene Inserate | Kategorie, Zustand, Preis (nur bei Biete). Ein Wortfilter (`rules/market.ts`) lehnt Inserate mit Cannabis-, Samen- oder Stecklings-Begriffen ab. Interessenten melden sich mit kurzer Nachricht; der Anbieter sieht Anzeigename und Nachricht. Nach 60 Tagen löscht Firestore das Inserat. |
| Samen und Stecklinge | **nur das Team** als Club-Angebot, kostenlos | Nach § 20 KCanG gibt nur die Anbauvereinigung Vermehrungsmaterial weiter: persönlich im Club, nach Ausweiskontrolle, höchstens 7 Samen, 5 Stecklinge oder zusammen 5 je Person und Kalendermonat, kein Versand von Stecklingen. Mitglieder fragen an (`requestPropagation` prüft die Grenzen), das Team gibt vor Ort aus (`decidePropagation`), die Ausgabe wird 5 Jahre in `propagationLog` dokumentiert. |
| Melden | jedes Mitglied | Ab 3 Meldungen ist ein Inserat ausgeblendet, bis das Team es wieder freigibt oder entfernt. |

Das ist keine Rechtsberatung. Die Grenzen stehen gesammelt in `rules/market.ts` und `PropagationLimits` (App) und müssen zusammen geändert werden. Bitte vor dem Start mit eurer Aufsichtsbehörde abstimmen, insbesondere ob die Weitergabe kostenlos oder zum Selbstkostenpreis erfolgen soll.

Benötigt Compose Material 3 ab 1.2, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` und `activity-compose`; das ist in aktuellen Compose-Projekten meist schon dabei. Icons werden nicht gebraucht. Die Texte stehen auf Deutsch direkt im Code; für weitere Sprachen nach `strings.xml` verschieben.

Die CSV wird mit UTF-8-Kennung gespeichert, damit Excel Umlaute richtig anzeigt. Gespeichert wird über den Android-Dateidialog, eine Freigabe im Manifest ist nicht nötig.

## 7. KCanG-Regeln im Backend

Geprüft wird auf dem Server (`backend/functions/src/rules/kcang.ts`), die App kann sie nicht umgehen:

- Abgabe nur an freigegebene Mitglieder ab 18 mit eingetragenem Namen und Geburtsdatum.
- Höchstens **25 g pro Tag** und **50 g pro Kalendermonat**; für 18- bis 20-Jährige **30 g pro Monat** und höchstens **10 % THC**. Maßgeblich ist das Alter am Abgabetag.
- Eine **Reservierung** zählt bereits Ausgegebenes plus alle offenen Reservierungen des Monats. Die **Ausgabe** zählt nur tatsächlich Ausgegebenes.
- Reservierungen sind Vormerkungen für die Abholung im Club, bis zu 30 Tage im Voraus. Versand oder Lieferung gibt es nicht.
- Jede Ausgabe speichert die Pflichtangaben nach § 26: Name, Vorname, Geburtsjahr, Menge, THC-Gehalt, Datum. Aufbewahrung fünf Jahre ab Ende des Abgabejahres, danach löscht Firestore den Eintrag automatisch. Reservierungen werden 90 Tage nach dem Abholtag gelöscht.
- Der CSV-Export funktioniert auch bei pausiertem Abo, damit die Dokumentation erreichbar bleibt.

Nicht abgedeckt: die jährliche Meldung an die Behörde und weitere Vereinspflichten. Das ist eine technische Umsetzung, keine Rechtsberatung.

## 8. Firebase-Backend einrichten

1. Firebase-Projekt im **Blaze-Tarif** (für Functions und den nächtlichen Abgleich nötig). Firestore mit Standort **europe-west3 (Frankfurt)** anlegen, damit der Verlaufs-Trigger in derselben Region läuft wie die Functions (bei einem anderen Standort `region` in `history.ts` anpassen), unter *Authentication* die Anmeldearten aktivieren (z. B. E-Mail und Google).
2. In RevenueCat einen **Secret API Key (v2)** anlegen mit den Rechten `customer_information:customers:read_write` und `project_configuration:entitlements:read`.
3. Im Ordner `backend/functions`:

   ```bash
   npm install
   npm test
   cp .env.example .env          # REVENUECAT_PROJECT_ID eintragen
   firebase functions:secrets:set REVENUECAT_SECRET_KEY
   firebase functions:secrets:set REVENUECAT_WEBHOOK_SECRET
   ```

4. Im Ordner `backend`: `firebase deploy --only functions,firestore`
5. In RevenueCat unter *Integrations → Webhooks*: URL von `revenuecatWebhook` aus der Deploy-Ausgabe eintragen, **HMAC-Signierung** aktivieren und dessen Secret als `REVENUECAT_WEBHOOK_SECRET` hinterlegen (danach erneut deployen). Test-Event senden.
6. In der Firestore-Konsole prüfen, dass die Löschfristen (TTL) für `expireAt` in `dispensations`, `reservations`, `history`, `stats`, `listings`, `propagationRequests` und `propagationLog` aktiv sind. Firestore löscht abgelaufene Dokumente meist innerhalb von 24 Stunden.

Ablauf im Hintergrund: Kauft der Inhaber, verlängert oder kündigt, meldet RevenueCat das per Webhook. Das Backend gleicht dann den ganzen Club ab und gibt den 4 Team-Plätzen Social Club bis einen Tag nach dem Abo-Ende des Inhabers frei, oder entzieht es. Zusätzlich läuft jede Nacht um 4 Uhr ein Abgleich aller Clubs als Sicherheitsnetz.

## 9. Dashboard-Konfiguration (Play Console und RevenueCat)

Vorher die App mit dem SDK einmal in einen Test-Track hochladen, sonst lassen sich keine Abos anlegen. In der Play Console unter *Monetarisieren → Abos*:

| Abo-ID | Basispläne (automatisch verlängernd) | Stufe |
|---|---|---|
| `premium_farmer` | `monthly`, `yearly` | Premium Farmer |
| `social_club_farmer` | `monthly`, `yearly` | Social Club Farmer |

In RevenueCat:

1. Google-Play-App mit eurem Paketnamen anlegen, Service-Account-Zugangsdaten aus der Play Console hinterlegen, SDK-Key `goog_…` übernehmen.
2. Produkte importieren: `premium_farmer:monthly`, `premium_farmer:yearly`, `social_club_farmer:monthly`, `social_club_farmer:yearly`.
3. Entitlements: `premium_farmer` mit den beiden Premium-Produkten, `social_club_farmer` mit den beiden Social-Club-Produkten. Freemium bekommt keins.
4. Offerings: `premium_farmer` (als Default) und `social_club_farmer`, jeweils mit den Paketen `$rc_monthly` und `$rc_annual`.
5. Optional Test Store: Produkte für den `test_…`-Key anlegen und an dieselben Entitlements und Offerings hängen.

## 10. Testen

- **Debug mit `test_`-Key**: Kaufdialog des Test Stores, kein Play-Setup nötig.
- **Echte Play-Käufe**: Lizenztester in der Play Console eintragen, App aus dem internen Test-Track installieren. Testabos verlängern sich beschleunigt.
- **Koppeln**: Sensor zurücksetzen, „Gerät koppeln“ → passende Art wählen; nach dem Erfolg muss er ohne Neustart in der Übersicht auftauchen und sich einer Box zuordnen lassen.
- **Verlauf und Alarme**: Box mit Premium-Konto anlegen, Sensor zuordnen, App schließen. Nach 15 bis 30 Minuten stehen Punkte in `history`; die 24-Stunden-Ansicht zeigt sie. Dann die Zieltemperatur unter den aktuellen Wert setzen: Nach spätestens zwei Läufen kommt die Benachrichtigung. Zum schnellen Testen im Debug-Build einen Sofort-Lauf anstoßen: `WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<GrowMonitorWorker>().build())`. Den Verlauf des Jobs zeigt Android Studio unter *App Inspection → Background Task Inspector*.
- **Automationen**: Mit Premium eine Steckdose koppeln, Regel „Licht“ mit Zeitplan wenige Minuten in der Zukunft anlegen. In der Smart-Life-App (anderes Konto!) sind die Automationen nicht sichtbar, die Schaltbefehle siehst du im Gerätelog der Tuya IoT Platform. Messwert-Regel: Schwelle knapp unter den aktuellen Wert setzen, die Steckdose muss innerhalb einer Minute nach der nächsten Sensormeldung schalten. Dann Premium auslaufen lassen: Die Steckdose darf nicht mehr automatisch schalten.
- **Grow-Boxen**: Mit einem Freemium-Konto eine Box anlegen und 5 Sensoren zuordnen; die zweite Box und der sechste Sensor müssen abgelehnt werden. Mit Premium drei Boxen anlegen, dann das Abo auslaufen lassen: Zwei Boxen erscheinen gesperrt.
- **Club-Ablauf** mit drei Test-Konten: A kauft Social Club und gründet den Club, B löst die Team-Einladung ein (bekommt Social Club), C stellt mit dem Mitglieder-Code einen Antrag, B gibt C frei, C reserviert, B dokumentiert die Ausgabe. Danach A kündigen lassen und prüfen, dass B den Zugang verliert.

## 11. Werbung und Coins

**Wer sieht Werbung:** Freemium und Premium. Social Club ist werbefrei, ebenso wer mit Coins „werbefrei“ eingelöst hat. Ohne Werbe-Einwilligung (EU) erscheinen keine Anzeigen. Gesteuert in `AdPolicy` und `AdsManager`.

| Format | Wo | Regeln |
|---|---|---|
| Banner fest unten | App-Rahmen, über der Navigation | 1 je Seite, mit Abstand zu den Navigations-Buttons, als „Anzeige“ beschriftet |
| Banner im Inhalt | jede Inhaltsseite (Grow, Box, Analyse, Automationen, Club, Marktplatz, Coins) | 2 je Seite: nach dem ersten Inhaltsblock und am Ende, mit Abstand zu Bedienelementen |
| Start-Anzeige (App Open) | bei jedem App-Start ab dem zweiten | nur nach Einwilligung und mit echtem Abo-Status; wartet höchstens 4 s aufs Laden; Länge und Schließen bestimmt die Anzeige |
| Belohnungsvideo | Coins-Bereich | 10 Coins, bis 5 am Tag; Gutschrift nur nach Bestätigung durch AdMob an den Server (SSV) |

Eingabeformulare (Box-Assistent, Regel-Editor, Kopplung, Inserat anlegen) haben nur den festen Banner, damit beim Tippen niemand versehentlich auf Werbung klickt (AdMob-Richtlinie). Eine erzwungene Wartezeit (z. B. 20 Sekunden) um eine Anzeige herum ist nach den AdMob-Richtlinien nicht erlaubt und nicht eingebaut. Google-Ads-Werbekunden liefern bei Cannabis-Inhalten nicht aus; rechnet mit geringeren Einnahmen. Debug-Builds nutzen immer Googles Test-Anzeigen.

**Coins** (`functions/src/coins.ts`, `rules/coins.ts`; gebucht wird nur im Backend):

| Verdienen | Coins |
|---|---|
| Tägliche Belohnung, 7-Tage-Serie | 5, 6, 7, 8, 10, 12, 20, dann von vorn |
| Belohnungsvideo | 10, bis 5 am Tag |
| Erfolge (einmalig) | erste Box 20, erste Automation 25, erstes Inserat 10, Club-Mitglied 15, 7 Tage in Folge 30 |

| Einlösen | Coins |
|---|---|
| 1 Tag werbefrei | 40 |
| 7 Tage werbefrei | 200 |
| 3 Tage Alarme (nur Freemium) | 60 |
| Marktplatz-Inserat 3 Tage ganz oben | 20 |

Coins lassen sich nicht kaufen, auszahlen oder übertragen. So bleiben sie außerhalb der Google-Play-Abrechnungsregeln und des Glücksspielrechts. Firestore: `wallets/{uid}` (nur lesbar für den Nutzer), Buchungen in `ledger`.

## 12. App-Rahmen, Fassungen und Release

`MainActivity` fragt die Werbe-Einwilligung ab, zeigt ab dem zweiten Start die Start-Anzeige und dann die App. Ohne Login erscheint die Anmeldung (E-Mail/Passwort, Registrierung nur mit Bestätigung „ab 18“). Danach vier Bereiche: **Grow**, **Club**, **Coins**, **Konto**. Im Konto: Abo, Upgrade-Seite, Käufe wiederherstellen, Werbe-Einwilligung ändern, Rechtstexte, Abmelden und **Konto löschen** (`deleteMyAccount`, Pflicht für Google Play).

**Zwei Fassungen** (Gradle-Flavors), weil Google Play Apps verbietet, die die Abholung von Cannabis organisieren:
- `play`: ohne Reservierungen, Abgabe-Doku und Samen/Stecklinge
- `direct`: alles, für die Verteilung außerhalb von Google Play

Die Schritte bis zur Veröffentlichung stehen in **`docs/RELEASE.md`**. Bauen mit `scripts/build-release.sh`, Backend mit `backend/deploy.sh <projekt-id>`.

## Offene Punkte

- **Geräte koppeln** ist gebaut, aber nur gegen Tuyas Beispielprojekt geprüft. Bitte mit je einem Bluetooth-, Kombi-, WLAN- und Zigbee-Sensor testen. Nicht abgedeckt: das Koppeln eines neuen Zigbee-Gateways per Kabel (LAN-Suche) und Kopplung per QR-Code; ein WLAN-Gateway koppelt ihr wie jedes WLAN-Gerät. Geräte aus der Smart-Life-App erscheinen nicht, weil jeder Nutzer ein eigenes Tuya-Konto in eurer App hat.
- **Automationen sind nicht gegen echte Geräte getestet.** Zwei Annahmen müssen im echten Projekt geprüft werden: (1) Der Schlüssel der Messwert-Bedingung ist `"dp" + DP-Nummer` (`ValueRule.newInstance("dp2", ">", 60)`), wie bei `BoolRule` in Tuyas Doku; für Zahlenwerte steht das nicht ausdrücklich dort. Falls Tuya die Regel ablehnt oder nie auslöst, in `TuyaAutomationSync.createScene` den Schlüssel auf die bloße DP-Nummer ändern. (2) Die Szenen-Schnittstelle (`getSceneManagerInstance`) ist in thingsmart 7.x als veraltet markiert, aber enthalten; Tuya empfiehlt den Szenen-Baukasten im expansion-sdk. Läuft es mit eurer SDK-Version nicht, ist nur `TuyaAutomationSync` auszutauschen (Schnittstelle `AutomationEngine`).
- **Platzhalter vor dem Release:** Rechtstext-Links in `Legal.kt`, App-Icon (`ic_launcher_foreground.xml` ist ein Platzhalter-Blatt), Versionen in `gradle/libs.versions.toml` prüfen, Datenschutzerklärung juristisch prüfen lassen.
- **Server-Überwachung** (Push-Alarme, Automationen) braucht ein Tuya-Cloud-Projekt; Besitzprüfung und API-Kontingent mit echten Geräten prüfen (siehe „Server-Überwachung“). Ohne Cloud-Projekt kommen Alarme wie bisher vom Handy.
- **Benachrichtigungs-Einstellungen der Mitglieder** (Reservierungen, Club-Boxen, Marktplatz) sind gespeichert, aber es gibt noch keine Server-Pushes für diese Ereignisse. Dafür braucht es FCM-Tokens in Firestore und Functions, die bei Statuswechseln senden.
- **Club-Boxen** zeigen nur die letzten Werte, keinen Verlauf. Sie hängen am Handy des Betreuers: Ist es lange aus, erscheint die Box als offline.
- **Lichtmodul:** Handy-Lichtsensoren unterscheiden sich stark (Messbereich, Farbempfindlichkeit, Lage). Ohne Kalibrierung gegen ein PAR-Meter sind ±20–30 % üblich; viele Handys gehen bei 30 000–60 000 lx an den Anschlag, also unter starken Blütelampen. Die Lampenwerte sind Richtwerte; mit Herstellerdaten (µmol/J, Spektrum) lassen sie sich in `Lamps.kt` verfeinern.
- Der Verlauf hat Lücken, wenn das Handy lange aus oder ohne Netz ist. Mehrere Handys desselben Kontos schreiben in denselben Verlauf, das ist unschädlich.

- **Google Play**: Die Play-Richtlinien verbieten Apps, die die Abholung von Cannabis organisieren, ausdrücklich auch dort, wo es legal ist. Reservierungen und Abgabe-Doku liegen deshalb gebündelt im Paket `club`, damit sie sich bei Bedarf in ein eigenes Modul oder eine eigene App auslagern lassen.
- Vor dem Start **App Check** aktivieren (`enforceAppCheck: true` in den Function-Optionen), damit nur eure App die Functions aufrufen kann.
- Die Feature-Limits in `FarmerTier.kt` und `rules/grow.ts` sind Platzhalter und müssen gemeinsam geändert werden.
