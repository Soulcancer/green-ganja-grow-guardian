package de.greenganja.growguardian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import de.greenganja.growguardian.ads.AdsProvider
import de.greenganja.growguardian.ui.theme.GrowGuardianTheme
import kotlinx.coroutines.launch

/**
 * Einziger Einstieg der App. Ablauf beim Start: Werbe-Einwilligung abfragen (nur wenn nötig), dann – ab dem
 * zweiten Start und nur ohne Social Club oder „werbefrei“ – die Start-Anzeige, danach die App.
 */
class MainActivity : ComponentActivity() {
    private var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GrowGuardianApp
        app.ads.gatherConsent(this)
        if (savedInstanceState == null) {
            val firstLaunch = app.consumeFirstLaunch()
            lifecycleScope.launch {
                app.ads.showAppOpenIfAllowed(this@MainActivity, firstLaunch)
                ready = true
            }
        } else {
            ready = true // nach Drehen des Bildschirms keine neue Start-Anzeige
        }
        setContent {
            GrowGuardianTheme {
                if (ready) AdsProvider(app.ads) { AppNavigation(app) } else Splash()
            }
        }
    }
}

@Composable
private fun Splash() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Green Ganja Grow Guardian", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            CircularProgressIndicator()
        }
    }
}
