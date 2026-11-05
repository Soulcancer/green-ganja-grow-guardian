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
import de.greenganja.growguardian.ui.wizard.OnboardingPrefs
import de.greenganja.growguardian.club.ui.ClubWizardRole

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
    // Bestätigt? AuthStateListener meldet das nicht, deshalb eigener Zustand je Konto
    var verified by remember(uid) { mutableStateOf(auth.currentUser?.isEmailVerified == true) }
    var mailSentAt by remember { mutableStateOf<String?>(null) }
    if (uid == null) {
        StartFlow(auth, onRegistered = { mailSentAt = auth.currentUser?.uid })
        return
    }
    if (!verified) {
        VerifyEmailScreen(auth, sentAtStart = mailSentAt == uid, onVerified = { verified = true })
        return
    }

    var tab by rememberSaveable { mutableStateOf(AppTab.GROW) }
    val onboarding = remember { OnboardingPrefs(app) }
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
                onboarding = onboarding,
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
                onboarding = onboarding,
            )
            AppTab.COINS -> RewardsScreen(app.wallets, app.subscriptions, app.ads, modifier = modifier)
            AppTab.ACCOUNT -> AccountScreen(
                app,
                onUpgradeClick = openPaywall,
                modifier = modifier,
                onAppTour = { onboarding.reset(OnboardingPrefs.APP); tab = AppTab.GROW },
                onClubTour = { ClubWizardRole.entries.forEach { onboarding.reset(OnboardingPrefs.club(it.key)) }; tab = AppTab.CLUB },
            )
        }
    }
}

private enum class StartPage { WELCOME, SIGN_IN, REGISTER }

/** Ohne Login: erst die erklärende Startseite mit Hinweisen, dann Anmelden oder Registrieren. */
@Composable
private fun StartFlow(auth: FirebaseAuth, onRegistered: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent = remember { WelcomeConsent(context) }
    var page by rememberSaveable { mutableStateOf(StartPage.WELCOME) }
    when (page) {
        StartPage.WELCOME -> WelcomeScreen(consent, onRegister = { page = StartPage.REGISTER }, onSignIn = { page = StartPage.SIGN_IN })
        StartPage.SIGN_IN, StartPage.REGISTER -> {
            BackHandler { page = StartPage.WELCOME }
            SignInScreen(auth, initialRegister = page == StartPage.REGISTER, onBack = { page = StartPage.WELCOME }, onRegistered = onRegistered)
        }
    }
}
