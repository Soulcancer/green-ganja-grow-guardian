import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Schlüssel und IDs aus local.properties (nicht ins Git) oder Umgebungsvariablen (CI)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String, fallback: String = ""): String =
    localProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')) ?: fallback
fun quoted(value: String) = "\"$value\""

// Offizielle Test-IDs von Google; im Release durch eure IDs aus AdMob ersetzen
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBanner = "ca-app-pub-3940256099942544/9214589741"
val testRewarded = "ca-app-pub-3940256099942544/5224354917"
val testAppOpen = "ca-app-pub-3940256099942544/9257395921"

android {
    namespace = "de.greenganja.growguardian"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.greenganja.growguardian"
        minSdk = 26
        targetSdk = 36
        // Alpha: Versionsnummer aus der CI (Lauf-Nummer), lokal 1
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.0-alpha"

        manifestPlaceholders["tuyaAppKey"] = secret("tuya.appKey")
        manifestPlaceholders["tuyaAppSecret"] = secret("tuya.appSecret")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // play: Google-Play-Fassung ohne Reservierungen/Abgabe (Play-Richtlinie „Marihuana“)
    // direct: vollständige Fassung zur Verteilung außerhalb von Google Play
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "DISPENSING_ENABLED", "false")
        }
        create("direct") {
            dimension = "store"
            versionNameSuffix = "-direct"
            buildConfigField("boolean", "DISPENSING_ENABLED", "true")
        }
    }

    signingConfigs {
        create("release") {
            // keystore.properties oder Umgebungsvariablen; ohne Keystore baut nur Debug
            val store = secret("release.storeFile")
            if (store.isNotEmpty()) {
                storeFile = rootProject.file(store)
                storePassword = secret("release.storePassword")
                keyAlias = secret("release.keyAlias")
                keyPassword = secret("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "REVENUECAT_API_KEY", quoted(secret("revenuecat.key.debug")))
            buildConfigField("String", "ADMOB_BANNER_ID", quoted(testBanner))
            buildConfigField("String", "ADMOB_REWARDED_ID", quoted(testRewarded))
            buildConfigField("String", "ADMOB_APP_OPEN_ID", quoted(testAppOpen))
            manifestPlaceholders["admobAppId"] = testAdmobAppId
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "REVENUECAT_API_KEY", quoted(secret("revenuecat.key.release")))
            buildConfigField("String", "ADMOB_BANNER_ID", quoted(secret("admob.banner", testBanner)))
            buildConfigField("String", "ADMOB_REWARDED_ID", quoted(secret("admob.rewarded", testRewarded)))
            buildConfigField("String", "ADMOB_APP_OPEN_ID", quoted(secret("admob.appOpen", testAppOpen)))
            manifestPlaceholders["admobAppId"] = secret("admob.appId", testAdmobAppId)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true // Tuya bringt native Bibliotheken mit
    }
}

kotlin { jvmToolchain(17) }

configurations.all {
    exclude(group = "com.thingclips.smart", module = "thingsmart-modularCampAnno")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.coroutines.play.services)

    implementation(libs.revenuecat)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.work.runtime)
    implementation(libs.tuya.sdk)
    implementation(libs.tuya.expansion)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar")))) // security-algorithm.aar von Tuya

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
