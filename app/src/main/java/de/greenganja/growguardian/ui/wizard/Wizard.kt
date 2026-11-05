package de.greenganja.growguardian.ui.wizard

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.ads.InlineAd

/** Eine Seite einer Einführung. [done]: null = reine Erklärung, sonst ob der Schritt erledigt ist. */
data class WizardPage(
    val key: String,
    val title: String,
    val intro: String,
    val bullets: List<String> = emptyList(),
    val done: Boolean? = null,
)

/** Blättern und Fortschritt, ohne Compose. */
object WizardFlow {
    fun clamp(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

    fun isLast(index: Int, size: Int): Boolean = index >= size - 1

    fun progress(index: Int, size: Int): Float = if (size <= 1) 1f else (clamp(index, size) + 1f) / size

    fun label(index: Int, size: Int): String = "Schritt ${clamp(index, size) + 1} von $size"

    /** Erste Seite mit offenem Schritt, sonst die erste. */
    fun firstOpen(pages: List<WizardPage>): Int = pages.indexOfFirst { it.done == false }.takeIf { it >= 0 } ?: 0

    fun doneCount(pages: List<WizardPage>): Int = pages.count { it.done == true }

    fun taskCount(pages: List<WizardPage>): Int = pages.count { it.done != null }
}

/** Merkt sich, welche Einführung gesehen wurde (je Version, damit neue Inhalte erneut erscheinen). */
class OnboardingPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun seen(key: String, version: Int = 1): Boolean = prefs.getInt(key, 0) >= version

    fun markSeen(key: String, version: Int = 1) {
        prefs.edit().putInt(key, version).apply()
    }

    fun reset(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val APP = "appWizard"
        fun club(role: String) = "clubWizard.$role"
    }
}

/**
 * Rahmen jeder Einführung: Schrittanzeige, Seite mit Erklärung und eigenem Inhalt, Banner für alle Stufen,
 * unten Zurück / Weiter. Überspringen jederzeit.
 */
@Composable
fun WizardScaffold(
    title: String,
    pages: List<WizardPage>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    finishLabel: String = "Fertig",
    content: @Composable (WizardPage) -> Unit = {},
) {
    if (pages.isEmpty()) return
    val i = WizardFlow.clamp(index, pages.size)
    val page = pages[i]
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(WizardFlow.label(i, pages.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("Überspringen") }
        }
        LinearProgressIndicator(progress = { WizardFlow.progress(i, pages.size) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title-${page.key}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    when (page.done) {
                        true -> Text("✓ erledigt", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        false -> Text("offen", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        null -> Unit
                    }
                }
            }
            item(key = "intro-${page.key}") { Text(page.intro, style = MaterialTheme.typography.bodyLarge) }
            if (page.bullets.isNotEmpty()) {
                item(key = "bullets-${page.key}") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        page.bullets.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            // In den Einführungen sehen alle Stufen Werbung
            item(key = "ad-top-${page.key}") { InlineAd(forAllTiers = true) }
            item(key = "content-${page.key}") { content(page) }
            item(key = "ad-end-${page.key}") { InlineAd(forAllTiers = true) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onIndex(i - 1) }, enabled = i > 0, modifier = Modifier.weight(1f)) { Text("Zurück") }
            if (WizardFlow.isLast(i, pages.size)) {
                Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text(finishLabel) }
            } else {
                Button(onClick = { onIndex(i + 1) }, modifier = Modifier.weight(1f)) { Text("Weiter") }
            }
        }
    }
}
