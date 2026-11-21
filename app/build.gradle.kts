import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdmobNativeId = "ca-app-pub-3940256099942544/1044960115"
val testAdmobInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val testAdmobAppOpenId = "ca-app-pub-3940256099942544/9257395921"

fun gradleProp(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

// Release signing key, created by ./build-release.sh. Never commit this file or the keystore.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.mantra.player"
    compileSdk = 36

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.mantra.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.2.0"

        manifestPlaceholders["admobAppId"] = gradleProp("ADMOB_APP_ID", testAdmobAppId)
        buildConfigField("String", "ADMOB_NATIVE_ID", "\"${gradleProp("ADMOB_NATIVE_ID", testAdmobNativeId)}\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${gradleProp("ADMOB_INTERSTITIAL_ID", "")}\"")
        buildConfigField("String", "ADMOB_APP_OPEN_ID", "\"${gradleProp("ADMOB_APP_OPEN_ID", "")}\"")
        buildConfigField("String", "REMOTE_AD_CONFIG_URL", "\"${gradleProp("REMOTE_AD_CONFIG_URL", "")}\"")
        buildConfigField(
            "String",
            "REMOVE_ADS_PRODUCT_ID",
            "\"${gradleProp("REMOVE_ADS_PRODUCT_ID", "remove_ads")}\""
        )
    }

    buildTypes {
        debug {
            // Never request real ads from debug builds.
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "ADMOB_NATIVE_ID", "\"$testAdmobNativeId\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testAdmobInterstitialId\"")
            buildConfigField("String", "ADMOB_APP_OPEN_ID", "\"$testAdmobAppOpenId\"")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.google.android.gms:play-services-ads:23.0.0")
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
