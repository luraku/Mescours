plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Sources de la passerelle NotebookLM (dossier bridge/ du dépôt) embarquées dans l'APK,
// pour que l'app puisse les déployer dans PocketDock.
val bridgeAssetsDir = layout.buildDirectory.dir("generated/bridgeAssets")
val syncBridgeAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../bridge")) {
        include("Dockerfile", "requirements.txt", "server.py", "prompts.py")
    }
    into(bridgeAssetsDir.map { it.dir("bridge") })
}
tasks.named("preBuild") { dependsOn(syncBridgeAssets) }

android {
    namespace = "fr.mescours.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.mescours.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Signature "release" optionnelle : fournie par des variables d'environnement
    // (secrets GitHub Actions). Sans elles, seule l'APK debug est produite.
    val keystorePath = System.getenv("MESCOURS_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MESCOURS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MESCOURS_KEY_ALIAS")
                keyPassword = System.getenv("MESCOURS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(bridgeAssetsDir.get().asFile)

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
