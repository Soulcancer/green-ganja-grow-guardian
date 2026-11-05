package de.greenganja.growguardian.club.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.greenganja.growguardian.club.ClubRole
import de.greenganja.growguardian.club.Membership
import de.greenganja.growguardian.ui.wizard.WizardFlow
import de.greenganja.growguardian.ui.wizard.WizardPage
import de.greenganja.growguardian.ui.wizard.WizardScaffold

/** Für welche Rolle die Club-Einführung gedacht ist. */
enum class ClubWizardRole(val key: String, val title: String) {
    NONE("none", "Social Club"),
    PENDING("pending", "Dein Aufnahmeantrag"),
    MEMBER("member", "Club für Mitglieder"),
    MODERATOR("moderator", "Club-Moderation"),
    STAFF("staff", "Club-Team"),
    OWNER("owner", "Club-Inhaber"),
    ;

    companion object {
        fun of(membership: Membership?): ClubWizardRole = when {
            membership == null -> NONE
            membership.isPending -> PENDING
            membership.role == ClubRole.OWNER -> OWNER
            membership.role == ClubRole.STAFF -> STAFF
            membership.role == ClubRole.MODERATOR -> MODERATOR
            else -> MEMBER
        }
    }
}

/**
 * Eigene Einführung für den Club-Bereich (die App-Einführung lässt ihn aus). Inhalt je Rolle;
 * ohne Abgabe-Funktionen (Fassung für Google Play) fallen Reservierung und Abgabe-Doku weg.
 * Rechtsstand: KCanG §§ 16, 19, 20, 26 (Stand dieser App-Version), keine Rechtsberatung.
 */
object ClubWizardContent {
    const val VERSION = 1

    fun pages(role: ClubWizardRole, dispensing: Boolean): List<WizardPage> {
        val intro = WizardPage(
            "club-intro", "Der Club-Bereich",
            "Hier organisieren Anbauvereinigungen (Social Clubs nach dem KCanG) den gemeinsamen Anbau: Mitglieder, gemeinsame Grow-Boxen und Angebote untereinander.",
            listOf("Mitglied werden nur ab 18 und mit Wohnsitz oder gewöhnlichem Aufenthalt in Deutschland (§ 16 KCanG).", "Jede Person darf nur in einer Anbauvereinigung Mitglied sein; ein Club hat höchstens 500 Mitglieder."),
        )
        val pages = when (role) {
            ClubWizardRole.NONE -> listOf(
                intro,
                WizardPage(
                    "club-join", "Einem Club beitreten",
                    "Dein Club gibt dir einen Mitglieds-Code. Mit dem Code stellst du in der App den Aufnahmeantrag mit deinen Angaben.",
                    listOf("Das Team prüft deinen Ausweis persönlich und schaltet dich dann frei.", "Bis dahin siehst du „Antrag offen“."),
                ),
                WizardPage(
                    "club-found", "Einen Club gründen",
                    "Mit dem Abo Social Club Farmer legst du deinen Club an und vergibst bis zu 5 Team-Plätze, zum Beispiel für Vorstand und Ausgabe.",
                    listOf("Ein Code lädt Team-Mitglieder ein, ein zweiter Code ist für Mitglieder.", "Die Gründung als Verein und die behördliche Erlaubnis brauchst du zusätzlich außerhalb der App."),
                ),
            )
            ClubWizardRole.PENDING -> listOf(
                WizardPage(
                    "club-pending", "Antrag gestellt",
                    "Dein Aufnahmeantrag liegt beim Club. Das Team prüft deinen Ausweis persönlich und gibt dich dann frei.",
                    listOf("Danach öffnet sich der Mitgliederbereich mit Club-Boxen und Marktplatz.", "Du kannst den Antrag jederzeit zurückziehen."),
                ),
            )
            ClubWizardRole.MEMBER -> listOfNotNull(
                intro,
                WizardPage(
                    "club-reserve", "Reservieren",
                    "Reserviere deine Menge vorab; abholen kannst du nur persönlich im Club mit Mitgliedsausweis und Lichtbildausweis.",
                    listOf("Höchstens 25 g am Tag und 50 g im Monat (§ 19 KCanG).", "Unter 21 Jahren: höchstens 30 g im Monat und höchstens 10 % THC.", "Weitergabe an andere und Versand sind verboten."),
                ).takeIf { dispensing },
                WizardPage(
                    "club-boxes", "Club-Boxen",
                    "Das Team kann Grow-Boxen des Clubs teilen. Du siehst dann die aktuellen Werte, zum Beispiel Temperatur, Luftfeuchte und Phase.",
                ),
                WizardPage(
                    "club-market", "Marktplatz",
                    "Biete Zubehör an oder gib es kostenlos weiter. Samen und Stecklinge gibt es nur als Angebot des Clubs.",
                    listOf("Vermehrungsmaterial: höchstens 7 Samen oder 5 Stecklinge im Monat, persönlich im Club (§ 20 KCanG).", "Kein Cannabis im Marktplatz: Der Filter sperrt solche Angebote."),
                ),
                WizardPage(
                    "club-control", "Deine Einstellungen",
                    "Du bestimmst, unter welchem Namen dich andere sehen, welche Benachrichtigungen du bekommst, und kannst deine Daten jederzeit herunterladen.",
                ),
            )
            ClubWizardRole.MODERATOR -> listOf(
                WizardPage(
                    "club-mod-role", "Du moderierst den Club",
                    "Das Team hat dich zum Moderator ernannt. Du bleibst Mitglied und bekommst zusätzlich den Reiter „Moderation“.",
                    listOf("Du siehst nur Anzeigenamen, keine Ausweisdaten, Geburtsdaten oder Abgaben.", "Freigaben, Reservierungen und die Abgabe-Doku bleiben beim Team."),
                ),
                WizardPage(
                    "club-mod-reports", "Meldungen entscheiden",
                    "Gemeldete Inserate sind ab 3 Meldungen ausgeblendet. Du gibst sie wieder frei oder entfernst sie, gern mit Grund.",
                    listOf("Club-Angebote für Samen und Stecklinge entscheidet das Team.", "Cannabis selbst hat im Marktplatz nichts zu suchen."),
                ),
                WizardPage(
                    "club-mod-members", "Verwarnen und sperren",
                    "Bei Regelverstößen verwarnst du Mitglieder oder sperrst sie für 1, 3, 7 oder 30 Tage im Marktplatz.",
                    listOf("Ein Grund ist Pflicht; die Person sieht ihn unter „Hinweise vom Club“.", "Team-Mitglieder und andere Moderatoren regelt das Team."),
                ),
                WizardPage(
                    "club-mod-news", "Ankündigungen und Protokoll",
                    "Ankündigungen erreichen alle Mitglieder für 30 Tage, bis zu 3 lassen sich anheften. Jede Aktion steht im Protokoll.",
                    listOf("Nur intern für Mitglieder: keine Werbung nach außen (§ 6 KCanG)."),
                ),
            )
            ClubWizardRole.STAFF, ClubWizardRole.OWNER -> listOfNotNull(
                intro,
                WizardPage(
                    "club-requests", "Anfragen freigeben",
                    "Neue Mitglieder stellen mit dem Mitglieds-Code einen Antrag. Prüf Ausweis, Alter und Wohnsitz persönlich, dann gib sie frei.",
                    listOf("Anträge, die du nicht freigibst, und Mitglieder, die gehen, entfernst du in der Mitgliederliste."),
                ),
                WizardPage(
                    "club-dispense", "Abgabe dokumentieren",
                    "Jede Abgabe trägst du mit Menge, THC-Gehalt und Sorte ein; die App prüft die Grenzen pro Tag und Monat.",
                    listOf("Die Abgabe-Doku lässt sich je Jahr als CSV exportieren (§ 26 KCanG).", "Reservierungen der Mitglieder bestätigen oder ablehnen."),
                ).takeIf { dispensing },
                WizardPage(
                    "club-share", "Club-Boxen teilen",
                    "Teile eigene Grow-Boxen mit dem Club, dann sehen Mitglieder die Live-Werte. Bis zu 10 Boxen je Club.",
                ),
                WizardPage(
                    "club-offers", "Angebote und Vermehrungsmaterial",
                    "Im Marktplatz stellt das Team Samen und Stecklinge als Club-Angebot ein und entscheidet über Anfragen der Mitglieder.",
                ).takeIf { dispensing },
                WizardPage(
                    "club-moderators", "Moderatoren",
                    "Ernenne aktive Mitglieder zu Moderatoren. Sie entscheiden Meldungen, verwarnen, sperren im Marktplatz und schreiben Ankündigungen.",
                    listOf("Bis zu 10 je Club, ohne Team-Platz und ohne Zugriff auf Personendaten.", "Alles steht im Protokoll im Reiter „Moderation“."),
                ),
                WizardPage(
                    "club-seats", "Team und Einstellungen",
                    "Der Inhaber vergibt die 5 Team-Plätze über Einladungs-Codes und kann den Mitglieds-Code erneuern, wenn er weitergegeben wurde.",
                    listOf("Läuft das Social-Club-Abo aus, ist der Club bis zur Verlängerung gesperrt."),
                ).takeIf { role == ClubWizardRole.OWNER },
            )
        }
        return pages + WizardPage("club-done", "Fertig", "Diese Einführung findest du jederzeit wieder unter Konto → Club-Einführung.")
    }
}

/** Club-Einführung im Vollbild. Banner sehen hier alle Stufen. */
@Composable
internal fun ClubWizard(role: ClubWizardRole, dispensing: Boolean, onDone: () -> Unit) {
    val pages = ClubWizardContent.pages(role, dispensing)
    var index by rememberSaveable(role) { mutableStateOf(0) }
    WizardScaffold(
        title = "Club-Einführung · ${role.title}",
        pages = pages,
        index = index,
        onIndex = { index = WizardFlow.clamp(it, pages.size) },
        onClose = onDone,
        onFinish = onDone,
    )
}
