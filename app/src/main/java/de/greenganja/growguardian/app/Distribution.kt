package de.greenganja.growguardian.app

import de.greenganja.growguardian.BuildConfig

/**
 * Welche Fassung der App das ist. Google Play verbietet Apps, die die Abholung von Cannabis organisieren
 * („Assisting users in arranging delivery or pick up of marijuana“), auch wo es legal ist. Deshalb gibt es zwei
 * Varianten (Gradle-Flavors):
 * - `play`: ohne Reservierungen, Abgabe-Doku und Weitergabe von Samen/Stecklingen
 * - `direct`: alles, zur Verteilung außerhalb von Google Play (z. B. direkt an Clubs)
 */
object Distribution {
    val dispensing: Boolean get() = BuildConfig.DISPENSING_ENABLED
}
