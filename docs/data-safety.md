# Google Play: Formular „Datensicherheit“ (Vorschlag für die Fassung `play`)

> Nach bestem Wissen aus dem Code abgeleitet. Die Angaben zu AdMob, Firebase und RevenueCat mit deren aktuellen
> Hinweisen zur Datensicherheit abgleichen; die Anbieter veröffentlichen eigene Vorlagen.

**Erhebt oder teilt die App Daten?** Ja. **Verschlüsselt bei der Übertragung?** Ja (HTTPS). **Löschung anfordern möglich?** Ja (in der App und per Web).

| Datentyp | Erhoben | Geteilt | Zweck | Optional |
|---|---|---|---|---|
| Persönliche Infos → E-Mail-Adresse | Ja | Nein | Kontoverwaltung | Nein |
| Persönliche Infos → Name (Anzeigename, Club) | Ja | Nein | App-Funktionen | Ja |
| Finanzinfos → Kaufverlauf | Ja | Nein | App-Funktionen (Abo-Status) | Nein |
| App-Aktivität → Interaktionen, sonstige Inhalte (Boxen, Inserate) | Ja | Nein | App-Funktionen | Nein |
| Geräte- oder andere IDs (Werbe-ID) | Ja | Ja (AdMob) | Werbung, Betrugsprävention | Nein* |
| App-Info und Leistung → Absturzprotokolle, Diagnose | Ja (durch SDKs) | Ja (AdMob) | Analyse, Betrugsprävention | Nein |
| Standort → ungefährer/genauer Standort | Nein** | Nein | – | – |

\* Personalisierung nur mit Einwilligung (UMP).
\** Die Standort-Berechtigung braucht Android nur für Bluetooth-/WLAN-Suche beim Koppeln; die App speichert keinen Standort. Mit dem Tuya-SDK abgleichen.
