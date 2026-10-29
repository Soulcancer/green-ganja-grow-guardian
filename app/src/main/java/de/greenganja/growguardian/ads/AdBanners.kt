package de.greenganja.growguardian.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** Was die Banner-Bausteine wissen müssen; ohne [AdsProvider] erscheinen keine Banner (z. B. in Tests). */
data class AdsUi(val enabled: Boolean, val units: AdUnits) {
    companion object {
        val Disabled = AdsUi(false, AdUnits())
    }
}

val LocalAds = staticCompositionLocalOf { AdsUi.Disabled }

/** Einmal um die App legen, z. B. `AdsProvider(app.ads) { AppNavigation() }`. */
@Composable
fun AdsProvider(manager: AdsManager?, content: @Composable () -> Unit) {
    if (manager == null) {
        content()
        return
    }
    val enabled by manager.showAds.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAds provides AdsUi(enabled, manager.units), content = content)
}

private enum class BannerKind { ANCHORED, INLINE }

@Composable
private fun Banner(unitId: String, kind: BannerKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val adView = remember(unitId, kind, widthDp) {
        AdView(context).apply {
            setAdSize(
                when (kind) {
                    BannerKind.ANCHORED -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
                    BannerKind.INLINE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp - 32)
                },
            )
            adUnitId = unitId
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    Column(modifier = modifier.fillMaxWidth()) {
        // Deutlich als Werbung erkennbar
        Text("Anzeige", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Fest unten angezeigter Banner, mit Abstand zur Navigation darunter. Gehört in den App-Rahmen, damit er
 * auf jeder Seite einmal erscheint.
 */
@Composable
fun AnchoredAdBar(modifier: Modifier = Modifier) {
    val ads = LocalAds.current
    if (!ads.enabled) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Spacer(Modifier.height(4.dp))
        Banner(ads.units.anchoredBanner, BannerKind.ANCHORED)
        Spacer(Modifier.height(AdPolicy.SPACING_DP.dp)) // Abstand zu Buttons der Navigation
    }
}

/** Banner im scrollbaren Inhalt, mit Abstand oben und unten. */
@Composable
fun InlineAd(modifier: Modifier = Modifier) {
    val ads = LocalAds.current
    if (!ads.enabled) return
    Column(modifier = modifier.fillMaxWidth().padding(vertical = (AdPolicy.SPACING_DP / 2).dp)) {
        Banner(ads.units.inlineBanner, BannerKind.INLINE)
    }
}

/**
 * Zwei Banner in einer Liste: einer nach dem Einstieg, einer am Ende. Zusammen mit [AnchoredAdBar] sind das drei
 * je Seite. Aufruf am Ende der LazyColumn: `inlineAdsEnd(key)`; nach dem Einstieg: `inlineAd("top-…")`.
 */
fun LazyListScope.inlineAd(key: String) {
    item(key = "ad-$key") { InlineAd() }
}
