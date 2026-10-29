package de.greenganja.growguardian.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.firebase.auth.FirebaseAuth
import de.greenganja.growguardian.ads.AnchoredAdBar
import de.greenganja.growguardian.club.SharedClubBoxPrefs
import de.greenganja.growguardian.club.ui.ClubScreen
import de.greenganja.growguardian.grow.ui.GrowScreen
import de.greenganja.growguardian.rewards.RewardsScreen

private enum class AppTab(val label: String, val icon: ImageVector) {
    GROW("Grow", Icons.Filled.Grass),
    CLUB("Club", Icons.Filled.Groups),
    COINS("Coins", Icons.Filled.Toll),
    ACCOUNT("Konto", Icons.Filled.Person),
}

/**
 * App-Rahmen: ohne Login die Anmeldung, sonst vier Bereiche unten. Über der Navigation sitzt der feste Banner
 * (mit Abstand, gegen versehentliche Klicks); die zwei Inhalts-Banner liegen in den Seiten selbst.
 */
@Composable
fun AppNavigation(app: GrowGuardianApp) {
    val auth = remember { FirebaseAuth.getInstance() }
    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.uid }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (uid == null) {
        SignInScreen(auth)
        return
    }

    var tab by rememberSaveable { mutableStateOf(AppTab.GROW) }
    var paywall by rememberSaveable { mutableStateOf(false) }
    val openPaywall = { paywall = true }

    if (paywall) {
        BackHandler { paywall = false }
        PaywallScreen(app.subscriptions, onClose = { paywall = false })
        return
    }

    Scaffold(
        bottomBar = {
            Column {
                AnchoredAdBar()
                NavigationBar {
                    AppTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(imageVector = entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            AppTab.GROW -> GrowScreen(
                repository = app.growBoxes,
                sensors = app.sensors,
                subscriptions = app.subscriptions,
                pairing = app.pairing,
                onUpgradeClick = openPaywall,
                history = app.growHistory,
                automations = app.growAutomations,
                wallet = app.wallets.wallet(),
                modifier = modifier,
            )
            AppTab.CLUB -> ClubScreen(
                clubs = app.clubs,
                subscriptions = app.subscriptions,
                onUpgradeClick = openPaywall,
                modifier = modifier,
                clubBoxes = app.clubBoxes,
                ownBoxes = { app.growBoxes.boxesOnce() },
                boxPrefs = remember { SharedClubBoxPrefs(app) },
                market = app.market,
                dispensing = Distribution.dispensing,
            )
            AppTab.COINS -> RewardsScreen(app.wallets, app.subscriptions, app.ads, modifier = modifier)
            AppTab.ACCOUNT -> AccountScreen(app, onUpgradeClick = openPaywall, modifier = modifier)
        }
    }
}
